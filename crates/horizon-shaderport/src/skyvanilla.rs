//! Pass `sky-vanilla`. A pack that turns `gbuffers_skybasic` off (`program.gbuffers_skybasic.enabled=false`)
//! means "draw the vanilla sky". Horizon has no such mode: with the program off it falls back to
//! `gbuffers_basic` (spec 2.3) and draws the sky disc and stars with that, which for most packs is
//! an outline or textured-lines shader, so the sky comes out the wrong colour (TrueLightFX Lite).
//!
//! The pack gets a `gbuffers_skybasic` that is the vanilla look (sky colour fading to fog colour at
//! the horizon, stars as they come), and the line that turned it off is dropped. Kept only when the
//! game then loads `gbuffers_skybasic` for the sky in every dimension folder it was added to.

use horizon_shaders::{Macros, Properties, dimension_folder, load_post};

use crate::files::Files;
use crate::fix::Change;

const MARK: &str = "// horizon-shaderport sky-vanilla: the pack turned gbuffers_skybasic off; this is the vanilla sky";

fn vsh() -> String {
    format!(
        "#version 120\n{MARK}\nvarying vec4 starData;\nvoid main() {{\n    gl_Position = ftransform();\n    starData = vec4(gl_Color.rgb, float(gl_Color.r == gl_Color.g && gl_Color.g == gl_Color.b && gl_Color.r > 0.0));\n}}\n"
    )
}

fn fsh() -> String {
    format!(
        "#version 120\n{MARK}\nuniform float viewHeight;\nuniform float viewWidth;\nuniform mat4 gbufferModelView;\nuniform mat4 gbufferProjectionInverse;\nuniform vec3 fogColor;\nuniform vec3 skyColor;\nvarying vec4 starData;\n\nfloat fogify(float x, float w) {{\n    return w / (x * x + w);\n}}\n\nvoid main() {{\n    vec3 color;\n    if (starData.a > 0.5) {{\n        color = starData.rgb;\n    }} else {{\n        vec4 pos = vec4(gl_FragCoord.xy / vec2(viewWidth, viewHeight) * 2.0 - 1.0, 1.0, 1.0);\n        pos = gbufferProjectionInverse * pos;\n        float upDot = dot(normalize(pos.xyz), gbufferModelView[1].xyz);\n        color = mix(skyColor, fogColor, fogify(max(upDot, 0.0), 0.25));\n    }}\n    gl_FragData[0] = vec4(color, 1.0);\n}}\n"
    )
}

/// The `program.gbuffers_skybasic.enabled` line (plain or per folder) and whether it is a constant `false`.
fn is_off_line(line: &str) -> bool {
    let Some((key, value)) = line.split_once('=') else { return false };
    let key = key.trim();
    let is_sky = key == "program.gbuffers_skybasic.enabled"
        || (key.starts_with("program.") && key.ends_with("/gbuffers_skybasic.enabled"));
    is_sky && value.trim() == "false"
}

pub fn fix(files: &mut Files, log: &mut Vec<Change>) {
    let ptext = files.text("shaders.properties").unwrap_or_default();
    if !ptext.lines().any(is_off_line) {
        return;
    }
    let Ok(pack) = files.pack() else { return };
    let macros = Macros::standard();
    let before_props = Properties::parse(&ptext, &macros);
    let inputs = crate::load::inputs();
    let mut folders = Vec::new();
    for id in [0, -1, 1] {
        let folder = dimension_folder(&pack, id);
        let Ok(post) = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            load_post(&pack, &folder, &before_props, &macros, &inputs)
        })) else {
            continue;
        };
        // Only a sky drawn by the fallback needs this; a pack whose sky program loads is left alone.
        if post.sky.as_ref().is_some_and(|s| s.name != "gbuffers_skybasic") && !folders.contains(&folder) {
            folders.push(folder);
        }
    }
    if folders.is_empty() {
        return;
    }
    let saved_props = files.entries.get("shaders/shaders.properties").cloned();
    let mut added: Vec<(String, Option<Vec<u8>>)> = Vec::new();
    for folder in &folders {
        let prefix = if folder.is_empty() { String::new() } else { format!("{folder}/") };
        for (ext, text) in [("vsh", vsh()), ("fsh", fsh())] {
            let path = format!("{prefix}gbuffers_skybasic.{ext}");
            added.push((path.clone(), files.remove(&path)));
            files.set_text(&path, text);
        }
    }
    let kept: Vec<&str> = ptext.lines().filter(|l| !is_off_line(l)).collect();
    files.set_text("shaders.properties", kept.join("\n") + "\n");
    let undo = |files: &mut Files, added: Vec<(String, Option<Vec<u8>>)>, props: Option<Vec<u8>>| {
        for (path, old) in added {
            files.remove(&path);
            files.restore(&path, old);
        }
        files.restore("shaders.properties", props);
    };
    let ok = files.pack().is_ok_and(|pack| {
        let props = Properties::parse(&files.text("shaders.properties").unwrap_or_default(), &macros);
        folders.iter().all(|folder| {
            std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                load_post(&pack, folder, &props, &macros, &inputs)
            }))
            .is_ok_and(|p| p.sky.is_some_and(|s| s.name == "gbuffers_skybasic"))
        })
    });
    if !ok {
        undo(files, added, saved_props);
        println!("  sky-vanilla: not kept (the added sky program did not load)");
        return;
    }
    println!("  sky-vanilla: vanilla gbuffers_skybasic added in {} folder(s)", folders.len());
    log.push(Change {
        rule: "sky-vanilla",
        files: added.into_iter().map(|(p, _)| p).collect(),
        outcome: "the pack turns `gbuffers_skybasic` off to get the vanilla sky; Horizon then draws the sky with `gbuffers_basic` instead, so a vanilla-look sky program was added and the off switch removed".to_owned(),
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_a_constant_off_switch_for_the_sky_program_matches() {
        assert!(is_off_line("program.gbuffers_skybasic.enabled=false"));
        assert!(is_off_line("program.world1/gbuffers_skybasic.enabled = false"));
        assert!(!is_off_line("program.gbuffers_skybasic.enabled=true"));
        assert!(!is_off_line("program.gbuffers_skybasic.enabled=!LOW"));
        assert!(!is_off_line("program.gbuffers_clouds.enabled=false"));
    }
}
