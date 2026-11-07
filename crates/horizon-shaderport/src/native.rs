//! Translator V2's native output: a Horizon `.hshader` (WGSL stages + `horizon-shaders.toml`).
//!
//! The GLSL pack has already been rewritten so Horizon's own loader reads it. That loader turns each
//! program into a naga module; here the fragment modules are written back out as WGSL and fitted to
//! Horizon's native stage API (docs/api/shaders.md):
//!
//! - `composite*`/`deferred*`/`final` programs that write `colortex0` become `[[post]]` passes, with
//!   `colortex0` read as `previous`, `depthtex*` as `depth_at`, and the OptiFine uniforms rebuilt from
//!   the native `frame`.
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
    /// G-buffer data the native post stage has no equivalent of.
    GBuffer,
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
    let mut post_files: Vec<(String, String, String)> = Vec::new(); // (file, label, text)
    let mut material: Option<String> = None;
    let mut sky: Option<String> = None;

    for p in &post.programs {
        if !matches!(p.group, Group::Deferred | Group::Composite | Group::Final) {
            continue;
        }
        let file = format!("post/{}.wgsl", p.name);
        match adapt(Kind::Post, p) {
            Ok(text) => match verify(StageKind::Post, &file, &text) {
                Ok(()) => post_files.push((file, p.name.clone(), text)),
                Err(e) => notes.push(format!("{}: not converted ({e})", p.name)),
            },
            Err(e) => notes.push(format!("{}: not converted ({e})", p.name)),
        }
    }
    if post_files.len() > horizon_shader_api::manifest::MAX_POST_PASSES {
        let extra = post_files.split_off(horizon_shader_api::manifest::MAX_POST_PASSES);
        for (_, label, _) in extra {
            notes.push(format!("{label}: not converted (more than {} passes)", horizon_shader_api::manifest::MAX_POST_PASSES));
        }
    }
    if let Some(p) = &post.terrain {
        match adapt(Kind::Material, p).and_then(|t| verify(StageKind::Material, "material.wgsl", &t).map(|_| t)) {
            Ok(t) => material = Some(t),
            Err(e) => notes.push(format!("gbuffers_terrain: left to Horizon's own lighting ({e})")),
        }
    }
    if let Some(p) = &post.sky {
        match adapt(Kind::Sky, p).and_then(|t| verify(StageKind::Sky, "sky.wgsl", &t).map(|_| t)) {
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
    for (file, label, text) in post_files {
        toml.push_str(&format!("\n[[post]]\nfile = \"{file}\"\nname = \"{}\"\n", toml_escape(&label)));
        out.insert(file, text.into_bytes());
    }
    out.insert(horizon_shader_api::MANIFEST.into(), toml.into_bytes());
    for extra in ["LICENSE", "LICENSE.txt", "LICENSE.md", "README.md"] {
        if let Some(b) = files.entries.get(extra) {
            out.insert(extra.into(), b.clone());
        }
    }
    // The whole pack must pass as a pack, not only stage by stage.
    NativePack::from_files(out.clone()).map_err(|e| format!("the native pack was refused: {e}"))?;
    Ok(Native { files: out, notes, stages })
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

/// Checks one stage on its own in a minimal pack.
fn verify(kind: StageKind, file: &str, text: &str) -> Result<(), String> {
    let stage = match kind {
        StageKind::Post => format!("[[post]]\nfile = \"{file}\"\n"),
        StageKind::Material => format!("[material]\nfile = \"{file}\"\n"),
        StageKind::Sky => format!("[sky]\nfile = \"{file}\"\n"),
        _ => return Err("unsupported stage".into()),
    };
    let manifest = format!("schema = 1\nid = \"probe\"\nname = \"probe\"\nversion = \"1.0.0\"\napi = \"0.1\"\n\n{stage}");
    let mut files = BTreeMap::new();
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

fn adapt(kind: Kind, p: &PostProgram) -> Result<String, String> {
    let program = p.program.as_ref().ok_or("no fragment stage")?;
    let info = naga::valid::Validator::new(naga::valid::ValidationFlags::all(), naga::valid::Capabilities::all())
        .validate(&program.fragment)
        .map_err(|e| format!("invalid module: {e}"))?;
    let wgsl = naga::back::wgsl::write_string(&program.fragment, &info, naga::back::wgsl::WriterFlags::empty())
        .map_err(|e| format!("WGSL writer: {e}"))?;
    let out_member = |outs: &[(String, u32)]| -> Result<String, String> {
        outs.iter()
            .find(|(_, loc)| p.buffers.get(*loc as usize) == Some(&0))
            .map(|(n, _)| n.clone())
            .ok_or_else(|| "does not write colortex0".to_owned())
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
    for name in PRELUDE {
        if !glsl::find_word(&text, name).is_empty() {
            text = rename_word(&text, name, &format!("hzp_{name}"));
        }
    }

    // --- texture reads.
    let mut roles: BTreeMap<String, Role> = BTreeMap::new();
    for (name, ty) in &textures {
        if ty.contains("<u32>") || ty.contains("<i32>") {
            return Err(format!("reads an integer texture ({})", name.trim_end_matches('_')));
        }
        roles.insert(name.clone(), role_of(kind, name.trim_end_matches('_')));
    }
    let (rewritten, used) = rewrite_calls(&text, &roles, kind);
    text = rewritten;
    if kind == Kind::Post {
        if let Some(n) = used.iter().find(|n| roles.get(*n) == Some(&Role::GBuffer)) {
            return Err(format!("reads G-buffer data ({})", n.trim_end_matches('_')));
        }
    }
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
    Ok(format!("{header}{text}\n{wrapper}"))
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
    fn texture_reads_are_replaced_by_the_native_resources() {
        let mut roles = BTreeMap::new();
        roles.insert("colortex0_".to_owned(), Role::Previous);
        roles.insert("depthtex0_".to_owned(), Role::Depth);
        let src = "let a = textureSample(colortex0_, hz_s_colortex0_, _e1);\nlet b = textureSample(depthtex0_, hz_s_depthtex0_, _e2);";
        let (out, used) = rewrite_calls(src, &roles, Kind::Post);
        assert_eq!(
            out,
            "let a = textureSampleLevel(previous, linear_sampler, hzn_uv(_e1), 0.0);\nlet b = vec4<f32>(1.0 - depth_at(hzn_uv(_e2)));"
        );
        assert_eq!(used.len(), 2);
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
