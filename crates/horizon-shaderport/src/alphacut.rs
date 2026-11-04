//! Pass `terrain-alpha`. OptiFine and Iris cut see-through texels (alpha <= 0.1) from the terrain
//! program by themselves, so a pack's terrain program never discards. Horizon keeps every
//! fragment of terrain (its loader tests alpha only for entities, particles and weather), so
//! flowers, tall grass, saplings and the like draw their transparent texels as solid sheets, in
//! the colour the atlas bleeds into them (yellow dandelions, green grass).
//!
//! The terrain program is the first of `gbuffers_terrain`, `gbuffers_textured_lit`,
//! `gbuffers_textured`, `gbuffers_basic` the pack has (OptiFine's fallback chain, looked up in the
//! dimension's folder, then in the root `shaders/`): many small packs have no `gbuffers_terrain`
//! and draw terrain with `gbuffers_textured_lit`. A stage file is looked at after its includes
//! are resolved, since the entry file is often only `#version` and an `#include` of the real
//! program.
//!
//! A terrain fragment shader that never discards, writes `gl_FragData[0]` or `gl_FragColor`, and has
//! no `alphaTest.<program>` line gets the default test after its `main`:
//! `if (gl_FragData[0].a <= 0.1) discard;` (the test applies to output 0, as in GL).

use horizon_shaders::{Macros, Properties, preprocess};

use crate::files::Files;
use crate::fix::{Change, Edits, sources};
use crate::glsl::{after_version, comment_ranges, find_word, in_comment};

const MARK: &str = "// horizon-shaderport terrain-alpha";

/// OptiFine's fallback order for a program that draws terrain.
const CHAIN: [&str; 4] = [
    "gbuffers_terrain",
    "gbuffers_textured_lit",
    "gbuffers_textured",
    "gbuffers_basic",
];

/// The folder of a stage file (`world1/`, or empty for `shaders/` itself) and its program name.
fn split(path: &str) -> Option<(&str, &str)> {
    let (dir, file) = path.rsplit_once('/').map_or(("", path), |(d, f)| (d, f));
    let name = file.strip_suffix(".fsh")?;
    Some((dir, name))
}

/// The terrain program for the stage files of folder `dir`: the first of [`CHAIN`] with a `.fsh`
/// in `dir` or in the root.
fn terrain_program(paths: &[&str], dir: &str) -> Option<&'static str> {
    CHAIN.into_iter().find(|name| {
        let file = format!("{name}.fsh");
        paths.iter().any(|p| {
            *p == file || (!dir.is_empty() && *p == format!("{dir}/{file}"))
        })
    })
}

fn rewrite(text: &str, code: &str) -> Option<String> {
    if text.contains(MARK) {
        return None;
    }
    let comments = comment_ranges(code);
    let live = |word: &str| find_word(code, word).into_iter().any(|a| !in_comment(&comments, a));
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
    // The entry file: `#version`, then (often) an `#include` of the real program. The pack's
    // `main` becomes `hz_pack_main` wherever it is included from, ours calls it.
    let at = after_version(text).unwrap_or(0);
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
    let Ok(pack) = files.pack() else { return };
    let macros = Macros::standard();
    // Entry files only: a stage file starts with `#version`; the files it includes do not.
    let srcs: Vec<(String, String)> = sources(files)
        .into_iter()
        .filter(|(p, t)| p.ends_with(".fsh") && after_version(t).is_some())
        .collect();
    let paths: Vec<&str> = srcs.iter().map(|(p, _)| p.as_str()).collect();
    let mut edits = Edits::new();
    for (path, text) in &srcs {
        let Some((dir, name)) = split(path) else { continue };
        let Some(name) = CHAIN.into_iter().find(|c| *c == name) else { continue };
        if terrain_program(&paths, dir) != Some(name) {
            continue;
        }
        if properties.get(&format!("alphaTest.{name}")).is_some() {
            continue;
        }
        let code = preprocess(&pack, path, &macros).code;
        if let Some(new) = rewrite(text, &code) {
            edits.insert(path.clone(), (new, "alpha cut at 0.1".to_owned()));
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn follows_the_fallback_chain() {
        let only_lit = ["gbuffers_textured_lit.fsh", "gbuffers_basic.fsh", "world1/gbuffers_textured_lit.fsh"];
        assert_eq!(terrain_program(&only_lit, ""), Some("gbuffers_textured_lit"));
        assert_eq!(terrain_program(&only_lit, "world1"), Some("gbuffers_textured_lit"));
        let with_terrain = ["gbuffers_terrain.fsh", "gbuffers_textured_lit.fsh"];
        assert_eq!(terrain_program(&with_terrain, ""), Some("gbuffers_terrain"));
        let basic = ["gbuffers_basic.fsh"];
        assert_eq!(terrain_program(&basic, ""), Some("gbuffers_basic"));
        assert_eq!(terrain_program(&["composite.fsh"], ""), None);
    }

    #[test]
    fn wraps_the_entry_file_around_an_included_main() {
        let entry = "#version 120\n\n#define OVERWORLD\n\n#include \"/program/lit.fsh\"";
        let code = "void main() {\n  gl_FragData[0] = vec4(1.0);\n}\n";
        let out = rewrite(entry, code).unwrap();
        assert!(out.contains("#define main hz_pack_main"));
        assert!(out.contains("if (gl_FragData[0].a <= 0.1) discard;"));
        assert!(out.find("#define main").unwrap() < out.find("#include").unwrap());
        assert_eq!(rewrite(&out, code), None);
        assert_eq!(rewrite(entry, "void main() { if (a < 0.1) discard; gl_FragData[0] = c; }"), None);
    }
}
