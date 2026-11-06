# Handoff: merging upstream horizon-rust/horizon 4649ff39 into HorizonR-Mobile

Branch: `upstream-shader-handler` (never touch `main` until the build is green).
Upstream: https://github.com/horizon-rust/horizon commit 4649ff391b6cfecbdb237484687a76870451e6f5
Merge base: 1641b3ecc. Pre-merge mobile main: f6a33605f (`git show f6a33605f:<path>` = mobile's old file).

## What the branch is
- A real merge commit (ec4221f1f) with `-X theirs`: upstream wins every conflicting hunk. Mobile-only files and
  non-conflicting mobile edits survived. Upstream's other workflows were dropped; `build-apk.yml` is the only one.
- WebAssembly code mods restored by reverting upstream e7614d219 (the `mods` feature is back in horizon-client and
  `horizon-android` wires `mods = ["horizon-client/mods", "horizon-mods/runtime"]`).
- `Cargo.lock` regenerated from upstream's lock (workspace resolves).
- `crates/horizon-client-render/src/gfx.rs`: Android logic hand-re-applied (compat feature masks, `base_vertex` GLES draw
  fallback, culling guard, startup hook, `gl_backend`). Not compiled yet. Upstream's `layer_split` already clamps GL layers.
- A failing-build step in `build-apk.yml` writes cargo errors to the `ci-errors` branch (`errs/<abi>.txt`), colors stripped.

## What is NOT done
`docs/upstream-merge-displaced-hunks.md` lists ~1,436 conflict hunks (283 files) where mobile's side lost.
~70 of them add real Android/mobile logic; do these first (counts = hunks with new android/mobile code):
game.rs 14, native_hooks.rs 12, gfx.rs (rest of the 9), pad_menu.rs 6, pad.rs 4, graphics.rs 3, startup_menu.rs 3,
launcher/src/account.rs 3, graphics_ui.rs 2, plus single hunks in arena.rs, panorama.rs, options_ui.rs, settings.rs,
horizon-threads/src/lib.rs, horizon-world level/data/cache.rs, sulfur_cube.rs. Then the ~1,000 non-Android gameplay/UI hunks.
Find a file's displaced hunks with a 3-way: `git merge-file -p --diff3 <mobile> <base 1641b3ecc> <upstream 4649ff39>`.

## Lessons (do not repeat)
- DO NOT auto-union "both inserted" hunks: a script that appended mobile's side broke `horizon-world/src/living_fall.rs`
  (stray duplicate block) and 143 of 192 hunks looked duplicated or brace-unbalanced. It was backed out. Re-apply by hand,
  one file at a time, reading both sides.
- `-X theirs` leaves files half-applied: mobile's non-conflicting lines survive but the definitions they use may be gone
  (e.g. `compatibility`, `base_vertex` in gfx.rs). Grep for names the file uses but no longer defines.
- Upstream moved game code into `horizon-client-lib` (its lib path is `../horizon-client/src/client_lib.rs`); `horizon-client`
  re-exports it. Mobile's `android_main.rs` still lives in horizon-client/src.

## Batch 9 (host + forced-Android-cfg checked, not CI-compiled)
- Android structure: `horizon-android` no longer depends on `horizon-client`. `android_main.rs` mirrors upstream's
  `lib.rs` (`extern crate self as horizon_client; pub use horizon_client_lib::*; pub mod game; pub mod mod_api;`), and
  `build.rs` runs `horizon_mod_api_gen` (Target::Client). The game is compiled once, in horizon-android, because
  mobile's game tree uses `crate::platform`, `crate::display`, ... that only exist at its root. The launcher's
  `skins.rs` is included as `launcher_skins` (account.rs aliases it) so `horizon_client::skins` is the lib's.
  `i18n` and `native_mods` are tiny shims in android_main.rs. `zip` is 9.1.3 like the lib.
- The WASM restore (revert of e7614d219) had left stale pre-upstream code in ~18 game/ files. Reconciled by hand:
  levels, chat, control, mods (native helpers grafted in), native_recipes, lan_actions, extras, screens, game.rs tick.
  `close_mod_screen` (native) vs `close_wasm_mod_screen`; `mod_use` (item) vs `mod_mouse_use` (WASM mouse).
  `tell_native_now` (the "WASM mods stopped" notice) is intentionally not called.
- 3-way re-applied with conflict markers read by hand: game/pad.rs, pad_menu.rs, startup_menu.rs, launcher/src/account.rs.
- Panorama GLES downscale now lives in `Faces::cube_texels` (render crate), mobile's averaging kept.
- Check without Android tooling: `cargo check -p horizon-client --lib --no-default-features` (host) and
  `cargo rustc -p horizon-android --lib --profile check --no-default-features -- --cfg 'target_os="android"'
  -A explicit_builtin_cfgs_in_flags`. The second shows winit-android / chacha20poly1305 / oo7 noise; ignore those.

## Loop that works
1. Edit, commit, push to `upstream-shader-handler` (build-apk.yml runs on this branch; do not wait on it).
2. When the user says the run finished, `git clone -b ci-errors` and read `errs/*.txt` (or the user uploads the logs zip).
   Cargo stops at the first failing crate, so expect one crate's errors per run.
3. Fix that crate, repeat. First real error seen (fixed): living_fall.rs stray block. Next unknown: horizon-world onward.

## Sandbox limits
1 CPU core, no Actions-log access (blob host blocked), api.github.com + github.com + crates.io reachable.
A Rust 1.95 toolchain can be installed via micromamba from conda-forge, but a full `cargo check` is too slow here.
Use rustc only for single small crates if at all.

## User preferences
Zen writes casual Filipino-English. Wants commits pushed so GitHub Actions builds; do not babysit/poll the run.
