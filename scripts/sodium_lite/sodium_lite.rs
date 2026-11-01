//! Sodium Lite: a Sodium-style performance profile for HorizonR (Android).
//!
//! Sodium itself is a Java mod that rewrites Minecraft's Java chunk renderer, so it cannot be
//! ported to Horizon's Rust engine. Horizon already has Sodium's structural wins (one shared
//! vertex arena, one indirect multi-draw per pass, graph-based cave culling). What it leaves at
//! vanilla defaults are the *knobs* that matter on a mid-range GPU: entity culling and the
//! per-frame upload budgets that stop chunk meshing from causing frame hitches.
//!
//! This module turns those knobs when the mod `sodium_lite` is switched on in
//! `horizon-mods.txt` (`mod.sodium_lite:true`) and puts the player's own values back when it is
//! switched off. It only touches keys of `horizon-graphics.txt` that the engine itself parses
//! (`horizon-client-render/src/graphics.rs`), keeps every other line as it is, and uses only `std`.
//!
//! The values are a starting point, not measured results: they are tuned by reasoning about the
//! Adreno 619 class of GPU and should be checked with the engine's own performance overlay.
use std::{fs, io, path::Path};

pub const ID: &str = "sodium_lite";
const GRAPHICS_FILE: &str = "horizon-graphics.txt";
const MODS_FILE: &str = "horizon-mods.txt";
/// The player's own values of the keys below, kept while the profile is on.
const BACKUP_FILE: &str = "horizon-graphics.sodium_lite.bak";
const ABSENT: &str = "<absent>";

/// Keys of `horizon-graphics.txt` this profile owns while it is on.
pub const PROFILE: [(&str, &str); 5] = [
    // Skip entities hidden behind opaque terrain (off in vanilla; Sodium does this too).
    ("entityCulling", "true"),
    // Keep the section-visibility graph on.
    ("caveCulling", "true"),
    // Upload at most 4 meshed sections per frame, so a burst of finished chunks cannot stall one frame.
    ("chunkUpdates", "4"),
    // And at most 2 MiB of section mesh per frame, for the same reason on slow memory.
    ("uploadBudgetKiB", "2048"),
    // The profile sets individual values, so the preset is Custom (the engine does the same on a change).
    ("preset", "custom"),
];

#[derive(Debug, PartialEq, Eq)]
pub enum Outcome {
    /// The profile was written into the graphics file.
    Applied,
    /// The profile was already in place.
    AlreadyApplied,
    /// The player's own values were put back.
    Restored,
    /// Off, and nothing of ours to undo.
    Nothing,
}

/// Is `mod.sodium_lite:true` the last word on it in `horizon-mods.txt`?
pub fn enabled_in(mods_txt: &str) -> bool {
    let want = format!("mod.{ID}");
    let mut on = false;
    for line in mods_txt.lines() {
        if let Some((k, v)) = line.split_once(':') {
            if k.trim() == want {
                on = v.trim() == "true";
            }
        }
    }
    on
}

fn value_of(text: &str, key: &str) -> Option<String> {
    let mut found = None;
    for line in text.lines() {
        if let Some((k, v)) = line.split_once(':') {
            if k.trim() == key {
                found = Some(v.trim().to_owned());
            }
        }
    }
    found
}

/// `text` with `key` set to `value` (every old line of the key replaced by one), or the key removed when `value` is `None`.
fn with_key(text: &str, key: &str, value: Option<&str>) -> String {
    let mut out: Vec<String> = Vec::new();
    let mut placed = false;
    for line in text.lines() {
        let is_key = line.split_once(':').is_some_and(|(k, _)| k.trim() == key);
        if !is_key {
            out.push(line.to_owned());
        } else if !placed {
            placed = true;
            if let Some(v) = value {
                out.push(format!("{key}:{v}"));
            }
        }
    }
    if !placed {
        if let Some(v) = value {
            out.push(format!("{key}:{v}"));
        }
    }
    let mut s = out.join("\n");
    if !s.is_empty() {
        s.push('\n');
    }
    s
}

fn write_atomic(path: &Path, text: &str) -> io::Result<()> {
    let tmp = path.with_extension("tmp");
    fs::write(&tmp, text)?;
    fs::rename(&tmp, path)
}

/// Brings `game_dir` (the folder holding `options.txt`) in line with the mod's switch.
pub fn apply(game_dir: &Path) -> io::Result<Outcome> {
    let on = enabled_in(&fs::read_to_string(game_dir.join(MODS_FILE)).unwrap_or_default());
    let graphics_path = game_dir.join(GRAPHICS_FILE);
    let backup_path = game_dir.join(BACKUP_FILE);
    let graphics = fs::read_to_string(&graphics_path).unwrap_or_default();
    let had_backup = backup_path.is_file();

    if on {
        if !had_backup {
            // First time: remember what the player had, for every key we own.
            let mut backup = String::new();
            for (key, _) in PROFILE {
                let old = value_of(&graphics, key).unwrap_or_else(|| ABSENT.to_owned());
                backup.push_str(&format!("{key}:{old}\n"));
            }
            write_atomic(&backup_path, &backup)?;
        }
        let mut next = graphics.clone();
        for (key, value) in PROFILE {
            next = with_key(&next, key, Some(value));
        }
        if next == graphics && had_backup {
            return Ok(Outcome::AlreadyApplied);
        }
        write_atomic(&graphics_path, &next)?;
        return Ok(Outcome::Applied);
    }

    if !had_backup {
        return Ok(Outcome::Nothing);
    }
    let backup = fs::read_to_string(&backup_path).unwrap_or_default();
    let mut next = graphics;
    for (key, _) in PROFILE {
        match value_of(&backup, key) {
            Some(v) if v == ABSENT => next = with_key(&next, key, None),
            Some(v) => next = with_key(&next, key, Some(&v)),
            None => {}
        }
    }
    write_atomic(&graphics_path, &next)?;
    fs::remove_file(&backup_path)?;
    Ok(Outcome::Restored)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn dir(name: &str) -> std::path::PathBuf {
        let d = std::env::temp_dir().join(format!("sodium_lite_{name}_{}", std::process::id()));
        let _ = fs::remove_dir_all(&d);
        fs::create_dir_all(&d).unwrap();
        d
    }

    #[test]
    fn switch_is_read_from_the_last_line() {
        assert!(enabled_in("mod.sodium_lite:true\n"));
        assert!(!enabled_in("mod.sodium_lite:true\nmod.sodium_lite:false\n"));
        assert!(!enabled_in("mod.other:true\n"));
        assert!(!enabled_in(""));
    }

    #[test]
    fn on_merges_and_keeps_other_lines() {
        let d = dir("merge");
        fs::write(d.join(MODS_FILE), "mod.sodium_lite:true\n").unwrap();
        fs::write(d.join(GRAPHICS_FILE), "sky:false\nchunkUpdates:0\nworkers:3\n").unwrap();
        assert_eq!(apply(&d).unwrap(), Outcome::Applied);
        let g = fs::read_to_string(d.join(GRAPHICS_FILE)).unwrap();
        assert!(g.contains("sky:false\n") && g.contains("workers:3\n"));
        assert_eq!(value_of(&g, "chunkUpdates").as_deref(), Some("4"));
        assert_eq!(value_of(&g, "entityCulling").as_deref(), Some("true"));
        assert_eq!(g.matches("chunkUpdates:").count(), 1);
    }

    #[test]
    fn on_with_no_graphics_file_creates_it() {
        let d = dir("create");
        fs::write(d.join(MODS_FILE), "mod.sodium_lite:true\n").unwrap();
        assert_eq!(apply(&d).unwrap(), Outcome::Applied);
        let g = fs::read_to_string(d.join(GRAPHICS_FILE)).unwrap();
        assert_eq!(value_of(&g, "uploadBudgetKiB").as_deref(), Some("2048"));
    }

    #[test]
    fn second_start_changes_nothing() {
        let d = dir("twice");
        fs::write(d.join(MODS_FILE), "mod.sodium_lite:true\n").unwrap();
        assert_eq!(apply(&d).unwrap(), Outcome::Applied);
        let first = fs::read_to_string(d.join(GRAPHICS_FILE)).unwrap();
        assert_eq!(apply(&d).unwrap(), Outcome::AlreadyApplied);
        assert_eq!(first, fs::read_to_string(d.join(GRAPHICS_FILE)).unwrap());
    }

    #[test]
    fn off_puts_the_players_values_back() {
        let d = dir("restore");
        fs::write(d.join(GRAPHICS_FILE), "chunkUpdates:7\nsky:false\npreset:quality\n").unwrap();
        fs::write(d.join(MODS_FILE), "mod.sodium_lite:true\n").unwrap();
        apply(&d).unwrap();
        fs::write(d.join(MODS_FILE), "mod.sodium_lite:false\n").unwrap();
        assert_eq!(apply(&d).unwrap(), Outcome::Restored);
        let g = fs::read_to_string(d.join(GRAPHICS_FILE)).unwrap();
        assert_eq!(value_of(&g, "chunkUpdates").as_deref(), Some("7"));
        assert_eq!(value_of(&g, "preset").as_deref(), Some("quality"));
        assert_eq!(value_of(&g, "entityCulling"), None, "a key the player never had is removed again");
        assert!(g.contains("sky:false\n"));
        assert!(!d.join(BACKUP_FILE).exists());
        assert_eq!(apply(&d).unwrap(), Outcome::Nothing);
    }

    #[test]
    fn off_without_ever_being_on_does_nothing() {
        let d = dir("never");
        fs::write(d.join(GRAPHICS_FILE), "sky:false\n").unwrap();
        assert_eq!(apply(&d).unwrap(), Outcome::Nothing);
        assert_eq!(fs::read_to_string(d.join(GRAPHICS_FILE)).unwrap(), "sky:false\n");
    }
}
