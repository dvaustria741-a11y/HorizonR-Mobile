//! Pass `sky-vanilla`. A pack that turns `gbuffers_skybasic` off (`program.gbuffers_skybasic.enabled=false`)
//! means "draw the vanilla sky". Horizon has no such mode: with the program off it falls back to
//! `gbuffers_basic` (spec 2.3) and draws the sky disc and stars with that, which for most packs is
//! an outline or textured-lines shader, so the sky comes out the wrong colour (TrueLightFX Lite).
//!
//! The pack gets a `gbuffers_skybasic` that is the vanilla look (sky colour fading to fog colour at
//! the horizon, stars as they come), and the line that turned it off is dropped. Kept only when the
//! game then loads `gbuffers_skybasic` for the sky in every dimension folder it was added to.
//!
//! ### Why #version 330 core instead of #version 120
//! Horizon's naga-based GLSL loader targets GLSL 330 core (OpenGL ES 3.0 / Vulkan-compatible).
//! The compatibility-profile builtins `ftransform()`, `gl_FragData[]`, and legacy `varying` are
//! not available on that path and produce a silent black sky or a loader skip.  The vanilla sky
//! shaders here therefore use:
//!   - explicit `in`/`out` varyings
//!   - `layout(location = 0) out vec4 fragColor`
//!   - an explicit full-screen projection (position already in NDC from the game's vertex buffer)
//! This matches what the terrain-test sample pack does and what every real Iris/OF pack uses.

use horizon_shaders::{Macros, Properties, dimension_folder, load_post};

use crate::files::Files;
use crate::fix::Change;

const MARK: &str = "// horizon-shaderport sky-vanilla: the pack turned gbuffers_skybasic off; this is the vanilla sky";

/// Vanilla sky vertex shader — GLSL 330 core, no compatibility builtins.
///
/// The game sends a full-screen quad whose vertices already carry NDC XY in `gl_Vertex.xy`
/// (range 0..1, same as mc_Entity in the terrain path).  We scale to [-1,1] ourselves.
/// Star vertices have equal RGB and non-zero intensity; sky disc vertices have differing channels.
fn vsh() -> String {
    format!(
        "#version 330 core\n\
         {MARK}\n\
         in vec3 Position;\n\
         in vec4 Color;\n\
         out vec4 starData;\n\
         void main() {{\n\
         \x20   // Position.xy is already 0..1 from the game's fullscreen-quad buffer.\n\
         \x20   gl_Position = vec4(Position.xy * 2.0 - 1.0, 0.0, 1.0);\n\
         \x20   float isStar = float(\n\
         \x20       Color.r == Color.g && Color.g == Color.b && Color.r > 0.0);\n\
         \x20   starData = vec4(Color.rgb, isStar);\n\
         }}\n"
    )
}

/// Vanilla sky fragment shader — GLSL 330 core, no gl_FragData[], no compatibility fog.
///
/// Sky colour blends toward fog colour based on the upward angle of the fragment, reproducing
/// the gradient vanilla draws.  Stars keep their raw colour unchanged.
///
/// `gbufferProjectionInverse` turns the NDC fragment position back into a view-space direction
/// so we can dot with the view-space up vector (gbufferModelView's Y column).
fn fsh() -> String {
    format!(
        "#version 330 core\n\
         {MARK}\n\
         uniform float viewHeight;\n\
         uniform float viewWidth;\n\
         uniform mat4 gbufferModelView;\n\
         uniform mat4 gbufferProjectionInverse;\n\
         uniform vec3 fogColor;\n\
         uniform vec3 skyColor;\n\
         in  vec4 starData;\n\
         layout(location = 0) out vec4 fragColor;\n\
         \n\
         // Soft horizon blend — same curve vanilla uses.\n\
         float fogify(float x, float w) {{\n\
         \x20   return w / (x * x + w);\n\
         }}\n\
         \n\
         void main() {{\n\
         \x20   if (starData.a > 0.5) {{\n\
         \x20       // Star: use raw colour, full opacity.\n\
         \x20       fragColor = vec4(starData.rgb, 1.0);\n\
         \x20       return;\n\
         \x20   }}\n\
         \x20   // Reconstruct view direction from NDC.\n\
         \x20   vec2 ndc = gl_FragCoord.xy / vec2(viewWidth, viewHeight) * 2.0 - 1.0;\n\
         \x20   vec4 viewPos = gbufferProjectionInverse * vec4(ndc, 1.0, 1.0);\n\
         \x20   vec3 viewDir = normalize(viewPos.xyz / viewPos.w);\n\
         \x20   // Up in view space is the Y column of gbufferModelView.\n\
         \x20   float upDot = dot(viewDir, gbufferModelView[1].xyz);\n\
         \x20   vec3 color = mix(skyColor, fogColor, fogify(max(upDot, 0.0), 0.25));\n\
         \x20   fragColor = vec4(color, 1.0);\n\
         }}\n"
    )
}

/// The `program.gbuffers_skybasic.enabled` line (plain or per folder) with a constant `false`.
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
        // Only when the sky draws via the fallback (not via a real gbuffers_skybasic).
        if post.sky.as_ref().is_some_and(|s| s.name != "gbuffers_skybasic")
            && !folders.contains(&folder)
        {
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
    // Drop the `enabled=false` lines so Horizon will actually pick up the new program.
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
        let props =
            Properties::parse(&files.text("shaders.properties").unwrap_or_default(), &macros);
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
    println!(
        "  sky-vanilla: vanilla gbuffers_skybasic added in {} folder(s)",
        folders.len()
    );
    log.push(Change {
        rule: "sky-vanilla",
        files: added.into_iter().map(|(p, _)| p).collect(),
        outcome: "the pack turns `gbuffers_skybasic` off to get the vanilla sky; \
                  Horizon then draws the sky with `gbuffers_basic` instead, so a \
                  vanilla-look sky program was added (GLSL 330 core, no compat builtins) \
                  and the off switch removed"
            .to_owned(),
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

    #[test]
    fn generated_shaders_contain_no_compat_builtins() {
        for src in [vsh(), fsh()] {
            assert!(!src.contains("ftransform"), "ftransform in generated shader");
            assert!(!src.contains("gl_FragData"), "gl_FragData in generated shader");
            assert!(!src.contains("#version 120"), "#version 120 in generated shader");
        }
        // Fragment must declare a proper output.
        assert!(fsh().contains("layout(location = 0) out vec4"));
        // Vertex must NOT use the compat attribute path.
        assert!(vsh().contains("in vec3 Position"));
    }
}
