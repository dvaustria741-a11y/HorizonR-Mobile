//! Translator V2's native output: a Horizon `.hshader` (WGSL stages + `horizon-shaders.toml`).
//!
//! The GLSL pack has already been rewritten so Horizon's own loader reads it. That loader turns each
//! program into a naga module; here the fragment modules are written back out as WGSL and fitted to
//! Horizon's native stage API (docs/api/shaders.md):
//!
//! - `composite*`/`deferred*`/`final` programs become `[[post]]` passes. OptiFine's colour buffers are
//!   followed through the chain: the buffer the terrain draws the scene into (and the one `final`
//!   reads) travels as the native `previous`, every other buffer a later pass reads becomes a pack
//!   target (`[[post]] output`, read by name as `ctexN`), and a program that writes several buffers is
//!   emitted once per buffer that is used. `depthtex*` is read as `depth_at` and the OptiFine uniforms
//!   are rebuilt from the native `frame`.
//! - the terrain program becomes the `material` stage when it lights itself (reads the lightmap).
//! - the sky program becomes the `sky` stage.
//!
//! Every stage is kept only when `horizon_shader_api::check_stage` accepts it, so the result always
//! loads; whatever cannot be expressed (programs that read G-buffer data other than `colortex0`, shadow
//! maps, options) is left out and listed in the report.

use std::collections::{BTreeMap, BTreeSet};

use horizon_shader_api::{Pack as NativePack, StageKind, check::check_stage};
use horizon_shaders::{Group, Macros, PostProgram, Properties, dimension_folder, load_post};

use crate::files::Files;
use crate::glsl;

/// The converted pack and what happened to each program.
pub struct Native {
    pub files: BTreeMap<String, Vec<u8>>,
    pub notes: Vec<String>,
    pub stages: usize,
}

#[derive(Clone, Copy, PartialEq, Debug)]
enum Kind {
    Post,
    Material,
    Sky,
}

#[derive(Clone, Debug, PartialEq)]
enum Role {
    Previous,
    Depth,
    Albedo,
    Lightmap,
    Const(&'static str),
    /// A pack target holding colour buffer N (`ctexN`).
    Named(u32),
    /// G-buffer data the native post stage has no equivalent of.
    GBuffer,
}

/// Where each OptiFine colour buffer lives while one post pass is adapted.
#[derive(Clone, Debug, Default)]
struct Plan {
    /// The buffer the native `previous` holds.
    chain: Option<u32>,
    /// Buffers kept in pack targets.
    named: BTreeSet<u32>,
    /// Probing which buffers a program reads: every buffer is a target.
    optimistic: bool,
}

impl Plan {
    fn optimistic() -> Self {
        Self { optimistic: true, ..Self::default() }
    }

    fn role_for(&self, buffer: u32) -> Role {
        if self.optimistic {
            Role::Named(buffer)
        } else if self.chain == Some(buffer) {
            Role::Previous
        } else if self.named.contains(&buffer) {
            Role::Named(buffer)
        } else {
            // Nothing converted writes it (a gbuffers program does, or nothing): OptiFine clears such
            // a buffer to black each frame.
            Role::Const("0.0")
        }
    }
}

/// The colour buffer a sampler name refers to (`gcolor` is colortex0, `gaux1` colortex4, ...).
fn buffer_index(name: &str) -> Option<u32> {
    if let Some(n) = name.strip_prefix("colortex") {
        return n.parse::<u32>().ok().filter(|n| *n < 16);
    }
    match name {
        "gcolor" => Some(0),
        "gdepth" => Some(1),
        "gnormal" => Some(2),
        "composite" => Some(3),
        "gaux1" => Some(4),
        "gaux2" => Some(5),
        "gaux3" => Some(6),
        "gaux4" => Some(7),
        _ => None,
    }
}

fn target_name(buffer: u32) -> String {
    format!("ctex{buffer}")
}

/// What `adapt` made of one program.
struct Adapted {
    text: String,
    /// The colour buffers it reads.
    reads: BTreeSet<u32>,
}

/// Names the native prelude defines; a pack's own function or variable of one is renamed.
const PRELUDE: &[&str] = &[
    "frame", "Frame", "scene_color", "scene_depth", "previous", "linear_sampler", "nearest_sampler",
    "shadow_map", "shadow_sampler", "history", "shadow_map_translucent", "shadow_color", "scene_surface",
    "depth_at", "view_position", "surface_at", "shadow_position", "shadow_distort", "PostInput",
    "SkyInput", "MaterialInput", "Surface", "SurfaceData", "VertexInput", "WeatherInput",
    "DisplacementInput", "post", "sky", "material", "vertex", "weather", "displacement", "reflection",
    "reflection_sampler", "opaque_depth", "opaque_color", "block_light_colors", "block_light_grid",
    "block_light_color", "block_texture", "block_texture_level", "block_texture_size",
    "block_texture_lod", "block_pbr_maps", "block_normal_map", "block_specular_map",
    "block_normal_map_level", "block_specular_map_level", "block_pbr_size", "set_surface", "noise",
    "noise_sampler",
];

pub fn convert(files: &Files, display_name: &str) -> Result<Native, String> {
    let pack = files.pack()?;
    let macros = Macros::standard();
    let props = Properties::parse(&pack.read_text("shaders.properties").unwrap_or_default(), &macros);
    let folder = dimension_folder(&pack, 0);
    let post = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        load_post(&pack, &folder, &props, &macros, &crate::load::inputs())
    }))
    .map_err(|_| "the shader loader crashed on this pack".to_owned())?;

    let mut notes = Vec::new();
    let mut material: Option<String> = None;
    let mut sky: Option<String> = None;

    // The buffer the terrain (or the sky) draws the scene into: where the post chain starts.
    let first_buffer = |p: &PostProgram| p.buffers.first().copied().unwrap_or(0);
    let scene_buffer = post.terrain.as_ref().or(post.sky.as_ref()).map_or(0, first_buffer);

    let post_files = plan_post(&post.programs, scene_buffer, &mut notes);
    if let Some(p) = &post.terrain {
        match adapt(Kind::Material, p, first_buffer(p), &Plan::default())
            .and_then(|a| verify(StageKind::Material, "material.wgsl", &a.text, &[], None).map(|_| a.text))
        {
            Ok(t) => material = Some(t),
            Err(e) => notes.push(format!("gbuffers_terrain: left to Horizon's own lighting ({e})")),
        }
    }
    if let Some(p) = &post.sky {
        match adapt(Kind::Sky, p, first_buffer(p), &Plan::default())
            .and_then(|a| verify(StageKind::Sky, "sky.wgsl", &a.text, &[], None).map(|_| a.text))
        {
            Ok(t) => sky = Some(t),
            Err(e) => notes.push(format!("gbuffers_skybasic: left to Horizon's own sky ({e})")),
        }
    }

    let stages = post_files.len() + material.is_some() as usize + sky.is_some() as usize;
    if stages == 0 {
        return Err(format!("no program of this pack could be written as a native stage ({})", notes.join("; ")));
    }

    let stem = display_name
        .trim_end_matches(".zip")
        .trim_end_matches(".hshader")
        .to_owned();
    let id = pack_id(&stem);
    let mut toml = String::new();
    toml.push_str("schema = 1\n");
    toml.push_str(&format!("id = \"{id}\"\n"));
    toml.push_str(&format!("name = \"{}\"\n", toml_escape(&stem)));
    toml.push_str(&format!("version = \"{}\"\n", version_of(&stem)));
    toml.push_str("api = \"0.1\"\n");
    toml.push_str(&format!(
        "description = \"{} converted from GLSL to WGSL by HorizonR Translator V2.\"\n",
        toml_escape(&stem)
    ));
    let mut out: BTreeMap<String, Vec<u8>> = BTreeMap::new();
    if let Some(t) = sky {
        toml.push_str("\n[sky]\nfile = \"sky.wgsl\"\nname = \"Sky\"\n");
        out.insert("sky.wgsl".into(), t.into_bytes());
    }
    if let Some(t) = material {
        toml.push_str("\n[material]\nfile = \"material.wgsl\"\nname = \"Lighting\"\n");
        out.insert("material.wgsl".into(), t.into_bytes());
    }
    for v in post_files {
        toml.push_str(&format!("\n[[post]]\nfile = \"{}\"\nname = \"{}\"\n", v.file, toml_escape(&v.label)));
        if let Some(t) = v.target {
            toml.push_str(&format!("output = \"{}\"\n", target_name(t)));
        }
        out.insert(v.file, v.text.into_bytes());
    }
    out.insert(horizon_shader_api::MANIFEST.into(), toml.into_bytes());
    for extra in ["LICENSE", "LICENSE.txt", "LICENSE.md", "README.md"] {
        if let Some(b) = files.entries.get(extra) {
            out.insert(extra.into(), b.clone());
        }
    }
    // The whole pack must pass as a pack, not only stage by stage.
    let pack = NativePack::from_files(out.clone()).map_err(|e| format!("the native pack was refused: {e}"))?;
    if let Err(problems) = horizon_shader_api::check(&pack) {
        let p = &problems[0];
        return Err(format!("the native pack failed its check, {}:{}: {}", p.file, p.line, p.message));
    }
    Ok(Native { files: out, notes, stages })
}

/// One native `[[post]]` pass.
struct Variant {
    file: String,
    label: String,
    text: String,
    /// The colour buffer whose pack target this pass writes; `None` writes the chain's picture.
    target: Option<u32>,
}

/// A program of the chain, as the planner sees it.
struct Cand<'a> {
    prog: &'a PostProgram,
    fin: bool,
    /// The buffers it writes (none for `final`, which draws the picture).
    writes: Vec<u32>,
    reads: BTreeSet<u32>,
    on: bool,
}

/// Passes the output of a trailing pack-target pass to the picture.
const PRESENT: &str = "fn post(input: PostInput) -> vec4<f32> {\n    return input.color;\n}\n";

/// Follows OptiFine's colour buffers through the `deferred*`, `composite*` and `final` programs and
/// writes them as native passes. `scene` is the buffer the terrain draws into. The picture travels
/// as the chain's `previous`; every other buffer that is both written and read becomes a pack target.
/// A program whose reads cannot be met (a buffer nothing converted writes, or its own history) is
/// left out and noted.
fn plan_post(programs: &[PostProgram], scene: u32, notes: &mut Vec<String>) -> Vec<Variant> {
    let mut cands: Vec<Cand> = Vec::new();
    for p in programs {
        if !matches!(p.group, Group::Deferred | Group::Composite | Group::Final) {
            continue;
        }
        let fin = p.group == Group::Final;
        let out0 = p.buffers.first().copied().unwrap_or(0);
        match adapt(Kind::Post, p, out0, &Plan::optimistic()) {
            Ok(a) => {
                let mut writes: Vec<u32> = Vec::new();
                if !fin {
                    for w in if p.buffers.is_empty() { vec![0] } else { p.buffers.clone() } {
                        if !writes.contains(&w) {
                            writes.push(w);
                        }
                    }
                }
                cands.push(Cand { prog: p, fin, writes, reads: a.reads, on: true });
            }
            Err(e) => notes.push(format!("{}: not converted ({e})", p.name)),
        }
    }

    // The buffer `final` reads as the picture.
    let screen = match cands.iter().find(|c| c.fin) {
        Some(f) if !f.reads.is_empty() && !f.reads.contains(&scene) => cands
            .iter()
            .flat_map(|c| c.writes.iter().copied())
            .find(|w| f.reads.contains(w))
            .unwrap_or(scene),
        _ => scene,
    };

    'again: loop {
        // Buffers written and read by programs still in: they live in pack targets.
        let mut named: Vec<u32> = Vec::new();
        for c in cands.iter().filter(|c| c.on) {
            for &w in &c.writes {
                if w != screen && !named.contains(&w) && cands.iter().any(|d| d.on && d.reads.contains(&w)) {
                    named.push(w);
                }
            }
        }
        named.truncate(horizon_shader_api::manifest::MAX_POST_TARGETS);
        let named_set: BTreeSet<u32> = named.iter().copied().collect();

        let mut chain = Some(scene);
        let mut variants: Vec<Variant> = Vec::new();
        for i in 0..cands.len() {
            if !cands[i].on {
                continue;
            }
            let prog = cands[i].prog;
            let fin = cands[i].fin;
            let writes = cands[i].writes.clone();
            let name = prog.name.clone();

            let feedback = cands[i].reads.iter().find(|b| named_set.contains(b) && writes.contains(b)).copied();
            if let Some(b) = feedback {
                cands[i].on = false;
                notes.push(format!(
                    "{name}: not converted (reads colortex{b} from the last frame while writing it; history buffers are not supported)"
                ));
                continue 'again;
            }

            let mut outs: Vec<(Option<u32>, u32)> = Vec::new();
            for &w in &writes {
                if named_set.contains(&w) {
                    outs.push((Some(w), w));
                }
            }
            if fin {
                outs.push((None, prog.buffers.first().copied().unwrap_or(0)));
            } else if writes.contains(&screen) {
                outs.push((None, screen));
            }
            if outs.is_empty() {
                cands[i].on = false;
                notes.push(format!("{name}: skipped (nothing it writes is read later)"));
                continue 'again;
            }

            let plan = Plan { chain, named: named_set.clone(), optimistic: false };
            let mut made = Vec::new();
            for (target, buffer) in outs {
                let file = match target {
                    Some(t) => format!("post/{name}_{}.wgsl", target_name(t)),
                    None => format!("post/{name}.wgsl"),
                };
                match adapt(Kind::Post, prog, buffer, &plan)
                    .and_then(|a| verify(StageKind::Post, &file, &a.text, &named, target).map(|_| a.text))
                {
                    Ok(text) => {
                        let label = match target {
                            Some(t) => format!("{name} (colortex{t})"),
                            None => name.clone(),
                        };
                        made.push(Variant { file, label, text, target });
                    }
                    Err(e) => {
                        cands[i].on = false;
                        notes.push(format!("{name}: not converted ({e})"));
                        continue 'again;
                    }
                }
            }
            let black: Vec<String> = cands[i]
                .reads
                .iter()
                .filter(|b| chain != Some(**b) && !named_set.contains(b))
                .map(|b| format!("colortex{b}"))
                .collect();
            if !black.is_empty() {
                notes.push(format!("{name}: reads {}, which no converted program writes, as black", black.join(", ")));
            }
            variants.extend(made);
            if fin || writes.contains(&screen) {
                chain = Some(screen);
            }
        }

        let max = horizon_shader_api::manifest::MAX_POST_PASSES;
        if variants.len() + 1 > max {
            // Drop the program that costs the most passes (the last of equals), never `final`.
            let heavy = (0..cands.len())
                .filter(|&i| cands[i].on && !cands[i].fin)
                .max_by_key(|&i| (variants.iter().filter(|v| v.file.starts_with(&format!("post/{}.", cands[i].prog.name)) || v.file.starts_with(&format!("post/{}_ctex", cands[i].prog.name))).count(), i));
            match heavy {
                Some(i) => {
                    cands[i].on = false;
                    notes.push(format!("{}: not converted (more than {max} passes in total)", cands[i].prog.name));
                    continue 'again;
                }
                None => variants.truncate(max - 1),
            }
        }
        if variants.last().is_some_and(|v| v.target.is_some()) {
            variants.push(Variant { file: "post/present.wgsl".into(), label: "Present".into(), text: PRESENT.into(), target: None });
        }
        if variants.iter().any(|v| v.target.is_some()) {
            let list = named.iter().map(|b| format!("colortex{b}")).collect::<Vec<_>>().join(", ");
            notes.push(format!(
                "post chain: the scene travels as colortex{screen}; {list} are pack targets (mipmapped reads of them see level 0)"
            ));
        }
        return variants;
    }
}

fn toml_escape(s: &str) -> String {
    s.replace('\\', "\\\\").replace('"', "\\\"")
}

fn pack_id(stem: &str) -> String {
    let mut id: String = stem
        .to_lowercase()
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() { c } else { '_' })
        .collect();
    while id.contains("__") {
        id = id.replace("__", "_");
    }
    let id = id.trim_matches('_').to_owned();
    let id = if id.is_empty() { "converted_pack".to_owned() } else { id };
    id.chars().take(64).collect()
}

fn version_of(stem: &str) -> String {
    // The first run of `digits.digits...` in the name, else 1.0.0.
    let b = stem.as_bytes();
    let mut i = 0;
    while i < b.len() {
        if b[i].is_ascii_digit() {
            let start = i;
            let mut dots = 0;
            while i < b.len() && (b[i].is_ascii_digit() || (b[i] == b'.' && i + 1 < b.len() && b[i + 1].is_ascii_digit())) {
                if b[i] == b'.' {
                    dots += 1;
                }
                i += 1;
            }
            if dots >= 1 {
                return stem[start..i].to_owned();
            }
        }
        i += 1;
    }
    "1.0.0".to_owned()
}

/// Checks one stage on its own in a minimal pack. `targets` are the pack targets the real pack
/// declares (colour buffers), `output` the one this pass writes.
fn verify(kind: StageKind, file: &str, text: &str, targets: &[u32], output: Option<u32>) -> Result<(), String> {
    let mut files = BTreeMap::new();
    let stage = match kind {
        StageKind::Post => {
            let dummy = "fn post(input: PostInput) -> vec4<f32> {\n    return input.color;\n}\n";
            let mut s = String::new();
            for t in targets.iter().filter(|t| Some(**t) != output) {
                s.push_str(&format!("[[post]]\nfile = \"probe/t{t}.wgsl\"\noutput = \"{}\"\n\n", target_name(*t)));
                files.insert(format!("probe/t{t}.wgsl"), dummy.as_bytes().to_vec());
            }
            s.push_str(&format!("[[post]]\nfile = \"{file}\"\n"));
            if let Some(t) = output {
                s.push_str(&format!("output = \"{}\"\n\n[[post]]\nfile = \"probe/end.wgsl\"\n", target_name(t)));
                files.insert("probe/end.wgsl".to_owned(), dummy.as_bytes().to_vec());
            }
            s
        }
        StageKind::Material => format!("[material]\nfile = \"{file}\"\n"),
        StageKind::Sky => format!("[sky]\nfile = \"{file}\"\n"),
        _ => return Err("unsupported stage".into()),
    };
    let manifest = format!("schema = 1\nid = \"probe\"\nname = \"probe\"\nversion = \"1.0.0\"\napi = \"0.1\"\n\n{stage}");
    files.insert(horizon_shader_api::MANIFEST.to_owned(), manifest.into_bytes());
    files.insert(file.to_owned(), text.as_bytes().to_vec());
    let pack = NativePack::from_files(files)?;
    match check_stage(&pack, kind, file) {
        Ok(_) => Ok(()),
        Err(problems) => {
            let p = &problems[0];
            Err(if p.line > 0 { format!("{}:{}: {}", p.file, p.line, p.message) } else { p.message.clone() })
        }
    }
}

// ---------------------------------------------------------------------------------------------
// One program: naga module -> WGSL -> native stage.

fn adapt(kind: Kind, p: &PostProgram, out_buf: u32, plan: &Plan) -> Result<Adapted, String> {
    let program = p.program.as_ref().ok_or("no fragment stage")?;
    let info = naga::valid::Validator::new(naga::valid::ValidationFlags::all(), naga::valid::Capabilities::all())
        .validate(&program.fragment)
        .map_err(|e| format!("invalid module: {e}"))?;
    let wgsl = naga::back::wgsl::write_string(&program.fragment, &info, naga::back::wgsl::WriterFlags::empty())
        .map_err(|e| format!("WGSL writer: {e}"))?;
    let out_member = |outs: &[(String, u32)]| -> Result<String, String> {
        outs.iter()
            .find(|(_, loc)| p.buffers.get(*loc as usize) == Some(&out_buf))
            .or_else(|| if p.group == Group::Final { outs.first() } else { None })
            .map(|(n, _)| n.clone())
            .ok_or_else(|| format!("does not write colortex{out_buf}"))
    };
    let mut text = wgsl;

    // --- resource declarations: textures and samplers go, the uniform block becomes a private copy.
    let mut textures: BTreeMap<String, String> = BTreeMap::new(); // var name -> type
    let mut kept = Vec::new();
    let lines: Vec<&str> = text.lines().collect();
    let mut i = 0;
    while i < lines.len() {
        let l = lines[i];
        if l.trim_start().starts_with("@group(") {
            let decl = lines.get(i + 1).copied().unwrap_or("").trim();
            if let Some(rest) = decl.strip_prefix("var<uniform> ") {
                kept.push(format!("var<private> {rest}"));
            } else if decl.starts_with("var<storage") || decl.contains("texture_storage") {
                return Err("uses storage buffers or images".into());
            } else if let Some(rest) = decl.strip_prefix("var ") {
                let (name, ty) = rest.trim_end_matches(';').split_once(": ").ok_or("unreadable binding")?;
                if ty.starts_with("texture") {
                    textures.insert(name.to_owned(), ty.to_owned());
                } else if !ty.starts_with("sampler") {
                    return Err(format!("unsupported binding {ty}"));
                }
            } else {
                return Err("unsupported binding".into());
            }
            i += 2;
            continue;
        }
        kept.push(l.to_owned());
        i += 1;
    }
    text = kept.join("\n");

    // --- the pack's own names that the prelude owns.
    let targets: Vec<String> = (0..16).map(target_name).collect();
    for name in PRELUDE.iter().copied().chain(targets.iter().map(String::as_str)) {
        if !glsl::find_word(&text, name).is_empty() {
            text = rename_word(&text, name, &format!("hzp_{name}"));
        }
    }

    // --- helper functions that take a texture: one copy per texture they are called with.
    text = specialise_texture_params(&text, &textures)?;

    // --- texture reads.
    let mut roles: BTreeMap<String, Role> = BTreeMap::new();
    for (name, ty) in &textures {
        if ty.contains("<u32>") || ty.contains("<i32>") {
            return Err(format!("reads an integer texture ({})", name.trim_end_matches('_')));
        }
        let base = name.trim_end_matches('_');
        let role = match (kind, role_of(kind, base), buffer_index(base)) {
            (Kind::Post, Role::Previous | Role::GBuffer, Some(i)) => plan.role_for(i),
            (_, role, _) => role,
        };
        roles.insert(name.clone(), role);
    }
    let (rewritten, used) = rewrite_calls(&text, &roles, kind);
    text = rewritten;
    if kind == Kind::Post {
        if let Some(n) = used.iter().find(|n| roles.get(*n) == Some(&Role::GBuffer)) {
            return Err(format!("reads G-buffer data ({})", n.trim_end_matches('_')));
        }
    }
    let reads: BTreeSet<u32> = used
        .iter()
        .filter(|n| matches!(roles.get(*n), Some(Role::Previous | Role::Named(_) | Role::GBuffer)))
        .filter_map(|n| buffer_index(n.trim_end_matches('_')))
        .collect();
    if kind == Kind::Material && !used.iter().any(|n| roles.get(n) == Some(&Role::Lightmap)) {
        return Err("it does not light itself (deferred lighting needs G-buffers)".into());
    }
    // A leftover mention of a removed variable would fail the check; say so early.
    for (name, _) in &textures {
        if !glsl::find_word(&text, name).is_empty() {
            return Err(format!("passes texture {} around", name.trim_end_matches('_')));
        }
    }

    // --- the entry point and its output struct.
    let entry_at = text.find("@fragment").ok_or("no fragment entry point")?;
    text.replace_range(entry_at..entry_at + "@fragment".len(), "");
    let fn_at = text.find("fn main(").ok_or("no main")?;
    let open = fn_at + "fn main".len();
    let close = glsl::matching(&text, open).ok_or("unreadable entry point")?;
    let params = split_params(&text[open + 1..close]);
    let mut parsed: Vec<Param> = Vec::new();
    for raw in &params {
        parsed.push(Param::parse(raw)?);
    }
    let plain = parsed.iter().map(|p| format!("{}: {}", p.name, p.ty)).collect::<Vec<_>>().join(", ");
    text.replace_range(fn_at..close + 1, &format!("fn hzn_entry({plain})"));
    let mut outs: Vec<(String, u32)> = Vec::new();
    if let Some(s) = text.find("struct FragmentOutput {") {
        let end = text[s..].find('}').map(|e| s + e).ok_or("unreadable output struct")?;
        let body = text[s..end].to_owned();
        let mut new_body = String::new();
        for line in body.lines() {
            let t = line.trim();
            if let Some(rest) = t.strip_prefix("@location(") {
                let (n, rest) = rest.split_once(") ").ok_or("unreadable output")?;
                let name = rest.split(':').next().unwrap_or("").trim().to_owned();
                outs.push((name, n.parse().map_err(|_| "unreadable output location")?));
                new_body.push_str(&format!("    {rest}\n"));
            } else {
                new_body.push_str(line);
                new_body.push('\n');
            }
        }
        text.replace_range(s..end, new_body.trim_end_matches('\n'));
    }
    let out_name = out_member(&outs)?;

    // --- uniforms: the private block is filled from the frame.
    let members = uniform_members(&text);
    let mut fill = String::new();
    for (name, ty) in &members {
        if let Some(e) = uniform_value(name, ty) {
            fill.push_str(&format!("    global.{name} = {e};\n"));
        }
    }

    let flipped_frag = p.group != Group::Final;
    let wrapper = match kind {
        Kind::Post => {
            let args = parsed
                .iter()
                .map(|a| post_arg(a, flipped_frag))
                .collect::<Vec<_>>()
                .join(", ");
            format!(
                "{HELPERS}\nfn post(input: PostInput) -> vec4<f32> {{\n    let hzn_res = frame.resolution;\n{fill}    let hzn_o = hzn_entry({args});\n    return hzn_o.{out_name};\n}}\n"
            )
        }
        Kind::Sky => {
            let args = parsed.iter().map(|a| sky_arg(a)).collect::<Vec<_>>().join(", ");
            format!(
                "{HELPERS}\nfn sky(input: SkyInput) -> vec4<f32> {{\n    let hzn_res = frame.resolution;\n    let hzn_clip = frame.projection * (frame.view * vec4<f32>(input.direction, 0.0));\n    let hzn_ndc = hzn_clip.xy / max(abs(hzn_clip.w), 0.0001);\n    let hzn_fc = vec4<f32>((hzn_ndc.x * 0.5 + 0.5) * hzn_res.x, (hzn_ndc.y * 0.5 + 0.5) * hzn_res.y, 1.0, 1.0);\n{fill}    let hzn_o = hzn_entry({args});\n    return vec4<f32>(hzn_o.{out_name}.rgb, 1.0);\n}}\n"
            )
        }
        Kind::Material => {
            let args = parsed.iter().map(|a| material_arg(a)).collect::<Vec<_>>().join(", ");
            format!(
                "{HELPERS}\nvar<private> hzn_albedo: vec4<f32>;\nvar<private> hzn_lightmap: vec3<f32>;\nfn material(input: MaterialInput) -> Surface {{\n    let hzn_res = frame.resolution;\n    let hzn_fc = vec4<f32>(input.screen.x * hzn_res.x, (1.0 - input.screen.y) * hzn_res.y, 1.0, 1.0);\n    hzn_albedo = input.albedo;\n    hzn_lightmap = input.lightmap;\n{fill}    let hzn_o = hzn_entry({args});\n    var hzn_s: Surface;\n    hzn_s.color = hzn_o.{out_name};\n    return hzn_s;\n}}\n"
            )
        }
    };
    let header = format!(
        "// Generated by HorizonR Translator V2 from the pack's `{}` program (GLSL -> WGSL).\n",
        p.name
    );
    Ok(Adapted { text: format!("{header}{text}\n{wrapper}"), reads })
}

/// Helper functions every adapted stage gets (names start `hzn_`, which no translated code uses).
const HELPERS: &str = "\
const HZN_T: mat4x4<f32> = mat4x4<f32>(vec4<f32>(1.0, 0.0, 0.0, 0.0), vec4<f32>(0.0, 1.0, 0.0, 0.0), vec4<f32>(0.0, 0.0, -0.5, 0.0), vec4<f32>(0.0, 0.0, 0.5, 1.0));
fn hzn_uv(v: vec2<f32>) -> vec2<f32> {
    return vec2<f32>(v.x, 1.0 - v.y);
}
fn hzn_px(c: vec2<i32>) -> vec2<i32> {
    return vec2<i32>(c.x, i32(frame.resolution.y) - 1 - c.y);
}";

fn role_of(kind: Kind, base: &str) -> Role {
    if base.starts_with("shadowtex") {
        return Role::Const("1.0");
    }
    if base.starts_with("shadowcolor") {
        return Role::Const("0.0");
    }
    if base == "noisetex" {
        return Role::Const("0.5");
    }
    match kind {
        Kind::Post => match base {
            "colortex0" | "gcolor" => Role::Previous,
            "depthtex0" | "depthtex1" | "depthtex2" | "gdepthtex" => Role::Depth,
            "gnormal" | "gdepth" | "composite" | "gaux1" | "gaux2" | "gaux3" | "gaux4" => Role::GBuffer,
            b if b.starts_with("colortex") => Role::GBuffer,
            _ => Role::Const("0.5"),
        },
        Kind::Material => match base {
            "gtexture" | "texture" | "tex" | "colortex0" | "gcolor" => Role::Albedo,
            "lightmap" => Role::Lightmap,
            "normals" => Role::Const("0.5, 0.5, 1.0, 1.0"),
            "specular" => Role::Const("0.0"),
            b if b.starts_with("depthtex") || b == "gdepthtex" => Role::Const("1.0"),
            _ => Role::Const("0.5"),
        },
        Kind::Sky => match base {
            b if b.starts_with("depthtex") || b == "gdepthtex" => Role::Const("1.0"),
            _ => Role::Const("0.0"),
        },
    }
}

const TEXTURE_FNS: &[&str] = &[
    "textureSampleCompareLevel",
    "textureSampleCompare",
    "textureSampleLevel",
    "textureSampleBias",
    "textureSampleGrad",
    "textureSample",
    "textureLoad",
    "textureDimensions",
    "textureNumLevels",
    "textureGather",
];

/// A function of the module that takes a texture (and usually its sampler) as parameters.
struct TexFn {
    name: String,
    /// Start of `fn` and end of the closing brace.
    start: usize,
    end: usize,
    /// Index of the `{`.
    body: usize,
    /// Index of the `)` closing the parameters.
    close: usize,
    params: Vec<Param>,
    /// (texture parameter, its sampler parameter).
    tex: Vec<(usize, Option<usize>)>,
}

fn texture_fns(text: &str) -> Vec<TexFn> {
    let mut out = Vec::new();
    let mut from = 0;
    while let Some(n) = text[from..].find("fn ") {
        let start = from + n;
        from = start + 3;
        if start > 0 && glsl::is_ident_byte(text.as_bytes()[start - 1]) {
            continue;
        }
        let rest = &text[start + 3..];
        let Some(paren) = rest.find('(') else { break };
        let name = rest[..paren].trim().to_owned();
        if name.is_empty() || !name.bytes().all(glsl::is_ident_byte) {
            continue;
        }
        let open = start + 3 + paren;
        let Some(close) = glsl::matching(text, open) else { continue };
        let Some(brace) = text[close..].find('{').map(|b| close + b) else { continue };
        let Some(end) = glsl::matching(text, brace) else { continue };
        let Ok(params) = split_params(&text[open + 1..close]).iter().map(|p| Param::parse(p)).collect::<Result<Vec<_>, _>>() else {
            continue;
        };
        let mut tex = Vec::new();
        for (i, p) in params.iter().enumerate() {
            if p.ty.starts_with("texture") {
                let sampler = params
                    .iter()
                    .position(|q| q.ty.starts_with("sampler") && q.name == format!("hz_s_{}", p.name))
                    .or_else(|| params.get(i + 1).filter(|q| q.ty.starts_with("sampler")).map(|_| i + 1));
                tex.push((i, sampler));
            }
        }
        if !tex.is_empty() {
            out.push(TexFn { name, start, end: end + 1, body: brace, close, params, tex });
        }
        from = end + 1;
    }
    out
}

/// Gives every helper function that takes a texture one copy per global texture it is called with
/// (the texture and sampler parameters replaced by the globals), then drops the originals: a native
/// stage has no texture parameters, only the stage's own resources.
fn specialise_texture_params(text: &str, textures: &BTreeMap<String, String>) -> Result<String, String> {
    let mut text = text.to_owned();
    if texture_fns(&text).is_empty() {
        return Ok(text);
    }
    for _ in 0..32 {
        let defs = texture_fns(&text);
        let mut clones: BTreeMap<String, String> = BTreeMap::new();
        let mut edits: Vec<(usize, usize, String)> = Vec::new();
        for f in &defs {
            for at in glsl::find_word(&text, &f.name) {
                if text[..at].ends_with("fn ") || text.as_bytes().get(at + f.name.len()) != Some(&b'(') {
                    continue;
                }
                let open = at + f.name.len();
                let Some(close) = glsl::matching(&text, open) else { continue };
                let args = glsl::split_args(&text, open, close);
                if args.len() != f.params.len() || !f.tex.iter().all(|(t, _)| textures.contains_key(args[*t].as_str())) {
                    continue;
                }
                let mut removed: Vec<usize> = Vec::new();
                let mut key = Vec::new();
                for (t, sm) in &f.tex {
                    removed.push(*t);
                    removed.extend(sm.iter().copied());
                    key.push(args[*t].trim_end_matches('_').to_owned());
                }
                let clone = format!("{}__{}", f.name, key.join("_"));
                let kept: Vec<&str> =
                    args.iter().enumerate().filter(|(i, _)| !removed.contains(i)).map(|(_, a)| a.as_str()).collect();
                edits.push((at, close + 1, format!("{clone}({})", kept.join(", "))));
                clones.entry(clone.clone()).or_insert_with(|| {
                    let kept_params: Vec<String> = f
                        .params
                        .iter()
                        .enumerate()
                        .filter(|(i, _)| !removed.contains(i))
                        .map(|(_, p)| format!("{}: {}", p.name, p.ty))
                        .collect();
                    let mut body = text[f.body..f.end].to_owned();
                    for (t, sm) in &f.tex {
                        body = rename_word(&body, &f.params[*t].name, &args[*t]);
                        if let Some(sm) = sm {
                            body = rename_word(&body, &f.params[*sm].name, &args[*sm]);
                        }
                    }
                    let ret = text[f.close + 1..f.body].trim_end();
                    format!("fn {clone}({}){} {body}\n", kept_params.join(", "), if ret.is_empty() { String::new() } else { format!(" {ret}") })
                });
            }
        }
        if edits.is_empty() {
            break;
        }
        edits.sort_by_key(|e| std::cmp::Reverse(e.0));
        let mut last_start = usize::MAX;
        for (a, b, new) in edits {
            if b <= last_start {
                text.replace_range(a..b, &new);
                last_start = a;
            }
        }
        for c in clones.values() {
            text.push('\n');
            text.push_str(c);
        }
    }
    let mut defs = texture_fns(&text);
    defs.sort_by_key(|d| std::cmp::Reverse(d.start));
    let names: Vec<String> = defs.iter().map(|d| d.name.clone()).collect();
    for d in &defs {
        text.replace_range(d.start..d.end, "");
    }
    for name in names {
        if text.match_indices(&format!("{name}(")).any(|(at, _)| at == 0 || !glsl::is_ident_byte(text.as_bytes()[at - 1])) {
            return Err(format!("passes a texture around (function {name})"));
        }
    }
    Ok(text)
}

/// Rewrites reads of the removed textures; returns the text and the textures that were read.
fn rewrite_calls(text: &str, roles: &BTreeMap<String, Role>, _kind: Kind) -> (String, BTreeSet<String>) {
    let mut out = String::new();
    let mut used = BTreeSet::new();
    let mut i = 0;
    loop {
        let mut best: Option<(usize, &str)> = None;
        for f in TEXTURE_FNS {
            let pat = format!("{f}(");
            let mut from = i;
            while let Some(n) = text[from..].find(&pat) {
                let at = from + n;
                let ok = at == 0 || !glsl::is_ident_byte(text.as_bytes()[at - 1]);
                if ok {
                    if best.is_none_or(|(b, _)| at < b) {
                        best = Some((at, f));
                    }
                    break;
                }
                from = at + 1;
            }
        }
        let Some((pos, f)) = best else {
            out.push_str(&text[i..]);
            break;
        };
        let open = pos + f.len();
        let Some(close) = glsl::matching(text, open) else {
            out.push_str(&text[i..]);
            break;
        };
        let args = glsl::split_args(text, open, close);
        match args.first().and_then(|a| roles.get(a.as_str()).map(|r| (a.clone(), r))) {
            Some((name, role)) => {
                out.push_str(&text[i..pos]);
                out.push_str(&replacement(f, &args, role));
                used.insert(name);
                i = close + 1;
            }
            None => {
                out.push_str(&text[i..open]);
                i = open;
            }
        }
    }
    (out, used)
}

fn replacement(f: &str, args: &[String], role: &Role) -> String {
    let arg = |n: usize| args.get(n).map(String::as_str).unwrap_or("0.0");
    match f {
        "textureNumLevels" => return "1u".into(),
        "textureSampleCompare" | "textureSampleCompareLevel" => return "1.0".into(),
        "textureDimensions" => {
            return match role {
                Role::Previous => "textureDimensions(previous)".into(),
                Role::Depth => "textureDimensions(scene_depth)".into(),
                Role::Named(n) => format!("textureDimensions({})", target_name(*n)),
                _ => "vec2<u32>(frame.resolution)".into(),
            };
        }
        _ => {}
    }
    let load = f == "textureLoad";
    // (texture, sampler, coords, ...) for the sampling forms; (texture, coords, level) for a load.
    let level = if f == "textureSampleLevel" { arg(3).to_owned() } else { "0.0".to_owned() };
    match role {
        Role::Previous if load => format!("textureLoad(previous, hzn_px({}), {})", arg(1), arg(2)),
        Role::Previous => format!("textureSampleLevel(previous, linear_sampler, hzn_uv({}), {level})", arg(2)),
        Role::Named(n) if load => format!("textureLoad({}, hzn_px({}), {})", target_name(*n), arg(1), arg(2)),
        Role::Named(n) => format!("textureSampleLevel({}, linear_sampler, hzn_uv({}), {level})", target_name(*n), arg(2)),
        Role::Depth if load => format!("vec4<f32>(1.0 - textureLoad(scene_depth, hzn_px({}), 0))", arg(1)),
        Role::Depth => format!("vec4<f32>(1.0 - depth_at(hzn_uv({})))", arg(2)),
        Role::Albedo => "hzn_albedo".into(),
        Role::Lightmap => "vec4<f32>(hzn_lightmap, 1.0)".into(),
        Role::Const(v) if v.contains(',') => format!("vec4<f32>({v})"),
        Role::Const(v) => format!("vec4<f32>({v})"),
        Role::GBuffer => "vec4<f32>(0.0)".into(),
    }
}

fn rename_word(text: &str, from: &str, to: &str) -> String {
    let mut out = String::new();
    let mut last = 0;
    for at in glsl::find_word(text, from) {
        out.push_str(&text[last..at]);
        out.push_str(to);
        last = at + from.len();
    }
    out.push_str(&text[last..]);
    out
}

// ---------------------------------------------------------------------------------------------
// Entry point parameters.

struct Param {
    name: String,
    ty: String,
    builtin: Option<String>,
}

impl Param {
    fn parse(raw: &str) -> Result<Self, String> {
        let mut s = raw.trim();
        let mut builtin = None;
        while s.starts_with('@') {
            let end = s.find(')').map_or_else(|| s.find(' ').unwrap_or(s.len()), |e| e + 1);
            let attr = &s[..end];
            if let Some(b) = attr.strip_prefix("@builtin(") {
                builtin = Some(b.trim_end_matches(')').to_owned());
            }
            s = s[end..].trim_start();
            // `@invariant` and friends have no parentheses.
        }
        let (name, ty) = s.split_once(':').ok_or("unreadable entry parameter")?;
        Ok(Self { name: name.trim().to_owned(), ty: ty.trim().to_owned(), builtin })
    }
}

/// Splits a parameter list at top-level commas, `<...>` and `(...)` aware.
fn split_params(s: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut depth = 0i32;
    let mut cur = String::new();
    for ch in s.chars() {
        match ch {
            '<' | '(' | '[' => depth += 1,
            '>' | ')' | ']' => depth -= 1,
            ',' if depth == 0 => {
                out.push(std::mem::take(&mut cur));
                continue;
            }
            _ => {}
        }
        cur.push(ch);
    }
    if !cur.trim().is_empty() {
        out.push(cur);
    }
    out
}

fn zero(ty: &str) -> String {
    format!("{ty}()")
}

fn name_has(name: &str, words: &[&str]) -> bool {
    let n = name.to_lowercase();
    words.iter().any(|w| n.contains(w))
}

fn builtin_arg(a: &Param, frag: &str) -> Option<String> {
    match a.builtin.as_deref() {
        Some("position") => Some(frag.to_owned()),
        Some("front_facing") => Some("true".into()),
        Some(_) => Some(zero(&a.ty)),
        None => None,
    }
}

fn post_arg(a: &Param, flipped: bool) -> String {
    let frag = if flipped {
        "vec4<f32>(input.pixel.x, hzn_res.y - input.pixel.y, 1.0 - input.depth, 1.0)"
    } else {
        "vec4<f32>(input.pixel.x, input.pixel.y, 1.0 - input.depth, 1.0)"
    };
    if let Some(b) = builtin_arg(a, frag) {
        return b;
    }
    if a.ty == "vec2<f32>" && name_has(&a.name, &["coord", "uv", "tc"]) {
        return "hzn_uv(input.uv)".into();
    }
    zero(&a.ty)
}

fn sky_arg(a: &Param) -> String {
    if let Some(b) = builtin_arg(a, "hzn_fc") {
        return b;
    }
    if a.ty == "vec4<f32>" && name_has(&a.name, &["color"]) {
        return "input.color".into();
    }
    zero(&a.ty)
}

fn material_arg(a: &Param) -> String {
    if let Some(b) = builtin_arg(a, "hzn_fc") {
        return b;
    }
    match a.ty.as_str() {
        "vec2<f32>" if name_has(&a.name, &["lmcoord", "lightmap", "lm"]) => "input.light".into(),
        "vec2<f32>" if name_has(&a.name, &["coord", "uv", "tc"]) => "input.uv".into(),
        "vec4<f32>" if name_has(&a.name, &["color", "tint"]) => "input.tint".into(),
        "vec3<f32>" if name_has(&a.name, &["normal"]) => "(frame.view * vec4<f32>(input.normal, 0.0)).xyz".into(),
        "vec3<f32>" if name_has(&a.name, &["viewpos", "position", "fragpos"]) => {
            "(frame.view * vec4<f32>(input.position, 1.0)).xyz".into()
        }
        _ => zero(&a.ty),
    }
}

// ---------------------------------------------------------------------------------------------
// The uniform block.

/// `(name, type)` of the members of `struct HzUniforms`.
fn uniform_members(text: &str) -> Vec<(String, String)> {
    let Some(s) = text.find("struct HzUniforms {") else { return Vec::new() };
    let Some(e) = text[s..].find('}') else { return Vec::new() };
    text[s + "struct HzUniforms {".len()..s + e]
        .lines()
        .filter_map(|l| {
            let mut t = l.trim().trim_end_matches(',');
            while t.starts_with('@') {
                t = t[t.find(')')? + 1..].trim_start();
            }
            let (n, ty) = t.split_once(": ")?;
            Some((n.to_owned(), ty.to_owned()))
        })
        .collect()
}

/// The WGSL value of OptiFine uniform `name`, converted to the member's type; `None` leaves it zero.
fn uniform_value(name: &str, ty: &str) -> Option<String> {
    // (value, its natural type)
    let (v, nat): (String, &str) = match name.strip_prefix("hz_bool_").unwrap_or(name) {
        "hz_target" => ("vec4<f32>(hzn_res, 1.0 / hzn_res)".into(), "vec4<f32>"),
        "hz_fog_color" => ("vec4<f32>(frame.fog_color, 1.0)".into(), "vec4<f32>"),
        "hz_fog" => (
            "vec4<f32>(0.0, frame.fog_start, frame.fog_end, 1.0 / max(frame.fog_end - frame.fog_start, 0.001))".into(),
            "vec4<f32>",
        ),
        "far" => ("frame.far".into(), "f32"),
        "near" => ("0.05".into(), "f32"),
        "viewWidth" => ("hzn_res.x".into(), "f32"),
        "viewHeight" => ("hzn_res.y".into(), "f32"),
        "aspectRatio" => ("hzn_res.x / hzn_res.y".into(), "f32"),
        "frameTimeCounter" => ("frame.time".into(), "f32"),
        "frameTime" => ("frame.delta_time".into(), "f32"),
        "frameCounter" => ("i32(frame.frame_index)".into(), "i32"),
        "worldTime" => ("i32(frame.game_time * 24000.0)".into(), "i32"),
        "sunAngle" => ("frame.day_time".into(), "f32"),
        "rainStrength" => ("frame.rain".into(), "f32"),
        "wetness" => ("frame.wetness".into(), "f32"),
        "thunderStrength" => ("frame.thunder".into(), "f32"),
        "screenBrightness" => ("frame.brightness".into(), "f32"),
        "nightVision" => ("frame.night_vision".into(), "f32"),
        "blindness" => ("frame.blindness".into(), "f32"),
        "darknessFactor" | "darknessLightFactor" => ("frame.darkness".into(), "f32"),
        "eyeAltitude" => ("frame.camera_position.y".into(), "f32"),
        "isEyeInWater" => ("i32(frame.eye_in)".into(), "i32"),
        "hideGUI" => ("i32(frame.hide_gui)".into(), "i32"),
        "cameraPosition" => ("frame.camera_position".into(), "vec3<f32>"),
        "previousCameraPosition" => ("frame.camera_position - frame.camera_motion".into(), "vec3<f32>"),
        "fogColor" => ("frame.fog_color".into(), "vec3<f32>"),
        "skyColor" => ("frame.sky_color".into(), "vec3<f32>"),
        "sunPosition" => ("(frame.view * vec4<f32>(frame.sun_direction * 100.0, 0.0)).xyz".into(), "vec3<f32>"),
        "moonPosition" => ("(frame.view * vec4<f32>(frame.moon_direction * 100.0, 0.0)).xyz".into(), "vec3<f32>"),
        "shadowLightPosition" => ("(frame.view * vec4<f32>(frame.light_direction * 100.0, 0.0)).xyz".into(), "vec3<f32>"),
        "upPosition" => ("(frame.view * vec4<f32>(0.0, 100.0, 0.0, 0.0)).xyz".into(), "vec3<f32>"),
        "eyeBrightness" => ("vec2<i32>(frame.eye_light * 240.0)".into(), "vec2<i32>"),
        "eyeBrightnessSmooth" => ("vec2<i32>(frame.eye_light_smooth * 240.0)".into(), "vec2<i32>"),
        "gbufferModelView" | "gbufferPreviousModelView" => ("frame.view".into(), "mat4x4<f32>"),
        "gbufferModelViewInverse" => ("frame.view_inverse".into(), "mat4x4<f32>"),
        "gbufferProjection" | "gbufferPreviousProjection" => {
            // OptiFine's clip space (z -1..1, depth 0 near) from the native one (z 0..1, reversed).
            ("mat4x4<f32>(vec4<f32>(1.0, 0.0, 0.0, 0.0), vec4<f32>(0.0, 1.0, 0.0, 0.0), vec4<f32>(0.0, 0.0, -2.0, 0.0), vec4<f32>(0.0, 0.0, 1.0, 1.0)) * frame.projection".into(), "mat4x4<f32>")
        }
        "gbufferProjectionInverse" => ("frame.projection_inverse * HZN_T".into(), "mat4x4<f32>"),
        "shadowModelView" | "shadowProjection" | "shadowModelViewInverse" | "shadowProjectionInverse" => {
            ("mat4x4<f32>(vec4<f32>(1.0, 0.0, 0.0, 0.0), vec4<f32>(0.0, 1.0, 0.0, 0.0), vec4<f32>(0.0, 0.0, 1.0, 0.0), vec4<f32>(0.0, 0.0, 0.0, 1.0))".into(), "mat4x4<f32>")
        }
        _ => return None,
    };
    if nat == ty {
        return Some(v);
    }
    // `bool` uniforms travel as `i32`.
    if ty == "i32" && nat == "f32" {
        return Some(format!("i32({v})"));
    }
    if ty == "f32" && nat == "i32" {
        return Some(format!("f32({v})"));
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ids_and_versions_come_from_the_file_name() {
        assert_eq!(pack_id("Wisdom Shaders V3 (Horizon port)"), "wisdom_shaders_v3_horizon_port");
        assert_eq!(version_of("complementary_v4.7.1"), "4.7.1");
        assert_eq!(version_of("plainpack"), "1.0.0");
    }

    #[test]
    fn sampler_names_map_to_colour_buffers() {
        assert_eq!(buffer_index("colortex0"), Some(0));
        assert_eq!(buffer_index("colortex12"), Some(12));
        assert_eq!(buffer_index("colortex16"), None);
        assert_eq!(buffer_index("gaux3"), Some(6));
        assert_eq!(buffer_index("gcolor"), Some(0));
        assert_eq!(buffer_index("depthtex0"), None);
    }

    #[test]
    fn a_plan_sends_the_picture_to_previous_and_the_rest_to_targets() {
        let plan = Plan { chain: Some(1), named: [0, 6].into_iter().collect(), optimistic: false };
        assert_eq!(plan.role_for(1), Role::Previous);
        assert_eq!(plan.role_for(0), Role::Named(0));
        assert_eq!(plan.role_for(6), Role::Named(6));
        assert_eq!(plan.role_for(3), Role::Const("0.0"));
        assert_eq!(Plan::optimistic().role_for(3), Role::Named(3));
    }

    #[test]
    fn texture_reads_are_replaced_by_the_native_resources() {
        let mut roles = BTreeMap::new();
        roles.insert("colortex0_".to_owned(), Role::Previous);
        roles.insert("depthtex0_".to_owned(), Role::Depth);
        let src = "let a = textureSample(colortex0_, hz_s_colortex0_, _e1);\nlet b = textureSample(depthtex0_, hz_s_depthtex0_, _e2);";
        roles.insert("colortex1_".to_owned(), Role::Named(1));
        let src = format!("{src}\nlet c = textureSample(colortex1_, hz_s_colortex1_, _e3);");
        let (out, used) = rewrite_calls(&src, &roles, Kind::Post);
        assert_eq!(
            out,
            "let a = textureSampleLevel(previous, linear_sampler, hzn_uv(_e1), 0.0);\nlet b = vec4<f32>(1.0 - depth_at(hzn_uv(_e2)));\nlet c = textureSampleLevel(ctex1, linear_sampler, hzn_uv(_e3), 0.0);"
        );
        assert_eq!(used.len(), 3);
    }

    #[test]
    fn a_composite_pack_becomes_native_post_passes() {
        let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../horizon-shaders/tests/packs/post-test");
        let files = Files::read(&root).unwrap();
        let n = convert(&files, "post-test.zip").unwrap();
        assert!(n.stages >= 1, "{:?}", n.notes);
        let pack = NativePack::from_files(n.files).unwrap();
        horizon_shader_api::check(&pack).unwrap_or_else(|p| panic!("{p:?}"));
    }
}
