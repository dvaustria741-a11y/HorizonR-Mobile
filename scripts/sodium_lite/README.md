# Sodium Lite

A Sodium-style performance profile for HorizonR, written in Rust (`sodium_lite.rs`, std only).

**What it is not:** a port of Sodium. Sodium is a Java mod that rewrites Minecraft's Java chunk
renderer; Horizon has no Java classes to patch, and already has Sodium's structural wins (shared
vertex arena, one indirect multi-draw per pass, graph-based cave culling).

**What it does:** while `mod.sodium_lite:true` is set in `horizon-mods.txt`, each time the game is
started it writes these keys into `horizon-graphics.txt` (every other line is kept):

| Key | Value | Why |
| --- | --- | --- |
| `entityCulling` | `true` | skip entities hidden behind opaque terrain (off by default) |
| `caveCulling` | `true` | keep the section-visibility graph on |
| `chunkUpdates` | `4` | at most 4 meshed sections uploaded per frame, so bursts don't stall a frame |
| `uploadBudgetKiB` | `2048` | at most 2 MiB of section mesh uploaded per frame |
| `preset` | `custom` | the values are set one by one |

Switching the mod off puts the player's own values back (kept in `horizon-graphics.sodium_lite.bak`).
While it is on, it owns those five keys: changes made to them in the game's settings are
overwritten at the next start.

**Honest limits:** the numbers are a starting point, reasoned for Adreno-619-class GPUs, not measured.
Use the engine's performance overlay (`overlay:graph`) with the mod on and off to compare.

## How it gets into the build

`scripts/patch_sodium_lite.py` runs in CI after the upstream engine is synced (the sync replaces
`crates/`, so nothing there can be kept). It checks that the engine still parses each key, copies
`sodium_lite.rs` into `crates/horizon-client/src/android/`, declares the module and calls
`sodium_lite::apply(game_dir)` at the start of `Launcher::action`. If an anchor in the upstream
code moved, it prints a warning and changes nothing.

Tests: `rustc --edition 2021 --test scripts/sodium_lite/sodium_lite.rs -o /tmp/t && /tmp/t`
