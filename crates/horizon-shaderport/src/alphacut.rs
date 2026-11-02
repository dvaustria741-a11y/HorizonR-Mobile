//! Pass `terrain-alpha`. OptiFine and Iris cut see-through texels (alpha <= 0.1) from the terrain
//! program by themselves, so a pack's `gbuffers_terrain` never discards. Horizon keeps every
//! fragment of terrain (its loader tests alpha only for entities, particles and weather), so
//! flowers, tall grass, saplings and the like draw their transparent texels as solid sheets, in
//! the colour the atlas bleeds into them (yellow dandelions, green grass).
//!
//! A terrain fragment shader that never discards, writes `gl_FragData[0]` or `gl_FragColor`, and has
//! no `alphaTest.gbuffers_terrain` line gets the default test after its `main`:
//! `if (gl_FragData[0].a <= 0.1) discard;` (the test applies to output 0, as in GL).

use horizon_shaders::{Macros, Properties};

use crate::files::Files;
use crate::fix::{Change, Edits, sources};
use crate::glsl::{after_version, comment_ranges, find_word, in_comment};

const MARK: &str = "// horizon-shaderport terrain-alpha";

fn is_terrain(path: &str) -> bool {
    let name = path.rsplit('/').next().unwrap_or(path);
    name == "gbuffers_terrain.fsh"
}

fn rewrite(text: &str) -> Option<String> {
    if text.contains(MARK) {
        return None;
    }
    let comments = comment_ranges(text);
    let live = |word: &str| find_word(text, word).into_iter().any(|a| !in_comment(&comments, a));
    if live("discard") || !live("main") {
        return None;
    }
    let out = if live("gl_FragData") {
        "gl_FragData[0].a"
    } else if live("gl_FragColor") {
        "gl_FragColor.a"
    } else {
        return None;
    };
    let at = after_version(text)?;
    let mut s = String::with_capacity(text.len() + 400);
    s.push_str(&text[..at]);
    s.push_str(&format!("\n{MARK}\n#define main hz_pack_main\n"));
    s.push_str(&text[at..]);
    s.push_str(&format!(
        "\n\n{MARK}\n#undef main\nvoid main() {{\n    hz_pack_main();\n    if ({out} <= 0.1) discard;\n}}\n"
    ));
    Some(s)
}

pub fn fix(files: &mut Files, log: &mut Vec<Change>) {
    let ptext = files.text("shaders.properties").unwrap_or_default();
    let properties = Properties::parse(&ptext, &Macros::standard());
    if properties.get("alphaTest.gbuffers_terrain").is_some() {
        return;
    }
    let mut edits = Edits::new();
    for (path, text) in sources(files) {
        if !is_terrain(&path) {
            continue;
        }
        if let Some(new) = rewrite(&text) {
            edits.insert(path, (new, "alpha cut at 0.1".to_owned()));
        }
    }
    if edits.is_empty() {
        return;
    }
    let Ok(best) = crate::load::evaluate(files) else { return };
    let kept = crate::pbr::keep(files, &best, edits, 0);
    println!("  terrain-alpha: {} file(s)", kept.len());
    log.push(Change {
        rule: "terrain-alpha",
        files: kept.into_iter().collect(),
        outcome: "terrain fragment shaders that never discard get OptiFine's default alpha test (`discard` at alpha <= 0.1); Horizon keeps every terrain fragment, so flowers and grass drew their see-through texels as solid yellow/green sheets".into(),
    });
}
