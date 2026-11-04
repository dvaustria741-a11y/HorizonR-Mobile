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
pub const DESCRIPTION: &str = "Port of Luxium: filmic tonemap, light shafts and wet surfaces in rain.";
/// The folder the bundled stages are written to inside `shaderpacks/`.
pub const PACK_DIR: &str = "luxium";

const FILES: [(&str, &str); 4] = [
    (
        "horizon-shaders.toml",
        include_str!("pack/horizon-shaders.toml"),
    ),
    ("post/shafts.wgsl", include_str!("pack/post/shafts.wgsl")),
    ("post/tonemap.wgsl", include_str!("pack/post/tonemap.wgsl")),
    ("material/wet.wgsl", include_str!("pack/material/wet.wgsl")),
];

/// Writes the bundled stages into `<shader packs dir>/luxium` when a file is missing or differs
/// (the player's option values live beside the folder, so they are kept).
pub fn ensure_pack(packs_dir: &Path) {
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
const SETTINGS: [(&str, &str, &[&str], &str); 7] = [
    ("shafts_on", "Light shafts", &["true", "false"], "true"),
    (
        "shafts_strength",
        "Shaft strength",
        &["0.15", "0.35", "0.6", "0.9"],
        "0.35",
    ),
    ("tm_strength", "Tonemap", &["0", "0.5", "1"], "1"),
    ("tm_exposure", "Exposure", &["-0.5", "0", "0.5"], "0"),
    ("tm_contrast", "Contrast", &["0.15", "0.35", "0.6"], "0.35"),
    (
        "tm_saturation",
        "Saturation",
        &["0.95", "1.05", "1.2"],
        "1.05",
    ),
    ("wet_on", "Wet surfaces", &["true", "false"], "true"),
];

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
