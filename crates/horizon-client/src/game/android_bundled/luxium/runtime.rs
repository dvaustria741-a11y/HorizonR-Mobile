// Luxium: Let there be light, by vinlanx (Luxium Free Modification License; see LICENSE in this directory).
// Ported from Luxium 2.8.4-pre-alpha for HorizonR's renderer. Built in, off until switched on in Mods.
// Stage 1: filmic tonemap, sun light shafts and rain-wet terrain, run through the engine's own
// shader stages (`game/native_shaders.rs`). The bundled WGSL is written to `shaderpacks/luxium`
// when the mod is on, so the engine reads it like any pack.

use std::path::Path;

/// The mod's id, name and version.
pub const ID: &str = "luxium";
pub const NAME: &str = "Luxium";
pub const VERSION: &str = "0.1.0";
pub const DESCRIPTION: &str = "Port of Luxium: water, metal, polished stone and glass sheen, plant wave, sky, lens flare, air distortion (/luxium warp), filmic tonemap, light shafts and wet surfaces.";
/// The folder the bundled stages are written to inside `shaderpacks/`.
pub const PACK_DIR: &str = "luxium";

const FILES: [(&str, &str); 7] = [
    (
        "horizon-shaders.toml",
        include_str!("pack/horizon-shaders.toml"),
    ),
    ("sky.wgsl", include_str!("pack/sky.wgsl")),
    ("post/distort.wgsl", include_str!("pack/post/distort.wgsl")),
    ("post/flare.wgsl", include_str!("pack/post/flare.wgsl")),
    ("post/shafts.wgsl", include_str!("pack/post/shafts.wgsl")),
    ("post/tonemap.wgsl", include_str!("pack/post/tonemap.wgsl")),
    ("material/material.wgsl", include_str!("pack/material/material.wgsl")),
];

/// Writes the bundled stages into `<shader packs dir>/luxium` when a file is missing or differs
/// (the player's option values live beside the folder, so they are kept).
pub fn ensure_pack(packs_dir: &Path) {
    // A warp from an earlier session must not play again at its old place.
    let mut chosen = horizon_shaders::read_overrides(packs_dir, PACK_DIR);
    if WARP_KEYS.iter().any(|k| chosen.remove(*k).is_some()) {
        let _ = horizon_shaders::write_overrides(packs_dir, PACK_DIR, &chosen);
    }
    let root = packs_dir.join(PACK_DIR);
    for (relative, text) in FILES {
        let path = root.join(relative);
        if std::fs::read_to_string(&path).ok().as_deref() == Some(text) {
            continue;
        }
        if let Some(parent) = path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        let _ = std::fs::write(&path, text);
    }
}

/// The options the mod's settings page cycles: pack option id, label, values, default.
const SETTINGS: [(&str, &str, &[&str], &str); 15] = [
    ("water_on", "Water surface", &["true", "false"], "true"),
    ("metal_on", "Metal reflections", &["true", "false"], "true"),
    (
        "metal_strength",
        "Metal strength",
        &["0.5", "1", "1.5"],
        "1",
    ),
    ("polish_on", "Polished stone", &["true", "false"], "true"),
    ("glass_on", "Glass sheen", &["true", "false"], "true"),
    ("wave_on", "Plant wave", &["true", "false"], "true"),
    (
        "wave_strength",
        "Wave strength",
        &["0.5", "1", "1.5"],
        "1",
    ),
    ("sky_on", "Sky", &["true", "false"], "true"),
    ("flare_on", "Lens flare", &["true", "false"], "true"),
    (
        "flare_intensity",
        "Flare intensity",
        &["0.5", "1", "1.5"],
        "1",
    ),
    ("shafts_on", "Light shafts", &["true", "false"], "true"),
    (
        "shafts_strength",
        "Shaft strength",
        &["0.15", "0.35", "0.6", "0.9"],
        "0.35",
    ),
    ("tm_strength", "Tonemap", &["0", "0.5", "1"], "1"),
    ("tm_exposure", "Exposure", &["-0.5", "0", "0.5"], "0"),
    ("wet_on", "Wet surfaces", &["true", "false"], "true"),
];

/// The options `/luxium warp` sets; cleared when a session starts.
const WARP_KEYS: [&str; 5] = ["warp_on", "warp_cx", "warp_cy", "warp_cz", "warp_r"];

/// Starts the air distortion sphere `radius` blocks wide, centred ahead of `eye` along `dir`,
/// and saves it where the engine reads pack options. Returns whether it was saved.
pub fn warp(packs_dir: &Path, eye: [f64; 3], dir: [f64; 3], radius: f64) -> bool {
    let radius = radius.clamp(2.0, 64.0);
    let ahead = radius * 1.6;
    let mut chosen = horizon_shaders::read_overrides(packs_dir, PACK_DIR);
    chosen.insert("warp_on".into(), "true".into());
    chosen.insert("warp_cx".into(), format!("{:.2}", eye[0] + dir[0] * ahead));
    chosen.insert("warp_cy".into(), format!("{:.2}", eye[1] + dir[1] * ahead));
    chosen.insert("warp_cz".into(), format!("{:.2}", eye[2] + dir[2] * ahead));
    chosen.insert("warp_r".into(), format!("{radius:.1}"));
    horizon_shaders::write_overrides(packs_dir, PACK_DIR, &chosen).is_ok()
}

fn shown(value: &str) -> &str {
    match value {
        "true" => "On",
        "false" => "Off",
        other => other,
    }
}

/// One label per option, with the value in use.
pub fn labels(packs_dir: &Path) -> Vec<String> {
    let chosen = horizon_shaders::read_overrides(packs_dir, PACK_DIR);
    SETTINGS
        .iter()
        .map(|(id, label, _, default)| {
            let value = chosen.get(*id).map_or(*default, String::as_str);
            format!("{label}: {}", shown(value))
        })
        .collect()
}

/// Moves option `button` to its next value and saves it where the engine reads pack options.
/// Returns whether anything changed (the pack is then rebuilt).
pub fn click(packs_dir: &Path, button: usize) -> bool {
    let Some((id, _, values, default)) = SETTINGS.get(button) else {
        return false;
    };
    let mut chosen = horizon_shaders::read_overrides(packs_dir, PACK_DIR);
    let current = chosen.get(*id).map_or(*default, String::as_str);
    let at = values.iter().position(|v| *v == current).unwrap_or(0);
    let next = values[(at + 1) % values.len()];
    chosen.insert((*id).to_owned(), next.to_owned());
    horizon_shaders::write_overrides(packs_dir, PACK_DIR, &chosen).is_ok()
}
