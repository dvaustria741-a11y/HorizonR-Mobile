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
