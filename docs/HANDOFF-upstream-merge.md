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

## Batches 12-15 (displaced mobile hunks, host-checked only)
Host check that works here: `cargo check --locked -p horizon-client --lib --no-default-features --features mods`
(default features need ALSA; `--no-default-features` alone hides the WASM-mod code). Not aarch64.
- game.rs: device-runtime frame hook, touch HUD state/draw (+ `touch_mining_progress` in remote.rs), custom mouse hold,
  gamepad/input-mode in `device_event`, display-scale/keyboard/GPU-timing prelude in `about_to_wait`,
  `rendering_api()` in reload/finish paths, `exit_app` takes `&dyn EventLoopCtl`.
- native_hooks.rs: `android_bundled::native_*` calls run before each `mod_api::dispatch_*`; Zenkai installed filter in
  `list_native_mods`. pad.rs: `touch_aim` reset. graphics.rs: Android defaults (entity culling Synchronous, worker headroom 2).
  graphics_ui.rs: Graphics API row hidden / not stepped on Android.
- gfx.rs: `Renderer::new_with_context` (+ `ReplacementContext.device_lost/reuse`); `Renderer::new` wraps it.
  game.rs `Loading::prepare_renderer` builds the world renderer on a worker (Android Vulkan only), `finish_renderer`
  holds the post-creation steps shared with `build_renderer`; handoff waits while `renderer_job` runs.
- Superseded by upstream (no action): GLES 256-layer sprite split (`layer_split` uses the adapter limit, `resplit`),
  MemoryUsage device hint everywhere, shader-pack option UI (Option A), `legacy` login flag already in account.rs.
- Not verified on a device: background renderer handoff, scaled presentation screenshot exclusion, GLES panorama.

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

## Batch 16 (host + forced-Android-cfg checked only; not CI-compiled, not run on a device)
- Android `graphics_api()` returns `Auto` outside OpenGL mode (Vulkan, then the GLES fallback), as starling 7fc390041.
- Menu background cache: `Frame::bg_cache` (a key from `Game::background_cache_key`: pause menu of a paused
  single-player world, and the inventory, 250 ms after opening; inventory re-keys every 500 ms). The first frame with a key
  draws the world and keeps the result (blurred when the menu blurs) in `MenuBlur`'s cache; frames with the same key
  and size run `Renderer::replay_background` (kept image + HUD from `blur_at` on). Dropped on key `None`, resize,
  `install_built_shader_pack` (`Renderer::invalidate_menu_blur`, new). Skipped with HDR, a pending screenshot, scaled
  presentation or a non-opaque Android surface. Frame generation is off while a key is set.
- Frozen while cached: world animation behind the menu (water, clouds, shader-pack time effects), GPU/pass timing.

## Batch 17 (host + forced-Android-cfg checked only; not CI-compiled, not run on a device)
- 735b97488 built green on CI.
- Pack pipelines skipped on a 4-bind-group device (Adreno 619 log, MakeUp-UltraFast: every program "Bind group layout count 6 exceeds device bind group limit 4"): `pack_post.rs` built its pipeline layouts as a fixed 6-slot array (table, draws, ids, buffers, indices) and wgpu counts the slice length, empty slots included. New `used_groups` drops the trailing `None`s in `build_pass` and `build_compute`, so programs that only use groups 0-3 fit. Groups 4 (buffers) and 5 (indices) are still bound only by passes that use them. Packs that really need SSBO buffers (group 4) or a geometry stage reading indices (group 5) still cannot build on a 4-group device: the shaders' `@group` numbers are fixed, so that needs renumbering, not done.
- In-game diagnostics heartbeat: `Game` RedrawRequested calls `diagnostics::heartbeat` on Android (`in_world` while on the Playing screen, `in_game_menu` otherwise, with the GPU name). The heartbeat dedupes per phase for 5 s and skips if the diagnostic lock is busy.

## Batch 18 (host + forced-Android-cfg checked only; not CI-compiled, not run on a device)
- 34348f2d: INFO line `menu background cache: N replayed, M full frames ... blocked by [...]` (once a second while a key is set).
- Pause menu follows starling 68a2c4d (`skip_world_for_blur`): when the game is paused (`self.pause`) and `Renderer::can_replay_background_now(key)` holds, `world_cached` skips the CPU world build (entities, bolts, dragon, celestial, hand, screen effects, end, weather, border, dynamic lights, outline, occlusion search). Shader pack inputs still run (they poll the pack workers). The inventory keeps the CPU build (its world runs) and only replays on the GPU side.
- `replay_background` now ends like a full frame: `readback.present`, `frame_scale.present`, `opaque_output.present` and `configure_surface` on reconfigure, so scaled presentation and a non-opaque surface no longer block the replay (they did before: `surface_frame.texture == target_texture` and the Opaque check). The game and the renderer ask the same `can_replay_background`, so a frame with the world skipped always replays.

## Batch 19 (host-checked only; platform.rs is Android-only code, not compiled on host; not CI-compiled, not run on a device)
- New graphics settings (Performance > Culling group, saved as `menuBackgroundCache` and `packWorkers`, both default on): Menu Background Cache gates `Game::background_cache_key` (off: no key, so no replay and no CPU world skip); Pack Worker Threads gates the Android worker-thread pack load in `shader_pack_inputs` (off: the synchronous path non-Android uses).
- Pack notes: `log_pack_notes` in shader_packs.rs logs a note at WARN only when it says a program was skipped or refused; routine notes (which pass draws what, uniforms left at defaults, shadow map line) go to INFO.
- `KeptRef` in android/platform.rs wraps the thread-local vibrator GlobalRefs and attaches the thread before dropping them, which removes the jni "Dropping a GlobalRef in a detached thread" WARN.
- Still WARN, a real skip: LIGHT-Shaders-1.9.0 `final` needs a vertex output at location 28, the device allows 27 (max_inter_stage_shader_variables). The device already asks for the adapter's limits, so the fix would be pruning unused varyings in the translator; not done.
- Menu background cache now also covers the screens opened from the pause menu (Options, Advancements, Statistics, World Options, Friends, Social, Server Links, Debug Options): `Game::pause_screen_over_world`. They share one key (no screen discriminant), so moving between them over the paused world reuses the kept image; the CPU world skip applies to all of them while paused. Not verified on a device: whether each of those screens blurs the world the same way the pause menu does.

## Batch 20 (host-checked; forced-Android-cfg check gives the same 44 known errors, none in the files touched; not CI-compiled, not run on a device)
- `pack_post/unused_outputs.rs` (`fit`, called in `build_pass` before the shader modules are made): when a vertex stage writes a location at or above the device's `max_inter_stage_shader_variables` (LIGHT-Shaders-1.9.0 `final`: location 28, limit 27), it drops the vertex outputs the fragment stage never reads and renumbers the rest from 0 in both stages. A program within the limit, or one the fragment stage itself needs more than the limit for, is left untouched; so is an entry point that does not end in `return`s of the composed result struct. Unit tests (`unused_outputs_tests.rs`) passed in a scratch crate against `third_party/naga`; the `horizon-client-render` lib tests do not compile at this branch (`has_settings` missing in `options_ui.rs` and `pvp.rs` test fixtures), so they were not run in place.
- GlobalRef detached-thread WARN, remaining sources: the "Android exit history" thread (exit_reports.rs), the "Content import" thread (documents.rs) and the "Screenshot gallery" thread (gallery.rs) now drop their GlobalRefs while attached. Android-only code: not type-checked here (the forced-cfg check stops at the 44 known name-resolution errors before it).
- Dynamic Resolution moved from Quality > Image to Performance > Resolution (a setting may appear on one page only, `rows` test). New `Resolution` group key in en_us.json.
- Worker Threads slider: its top is `max_workers()`, which on Android is at least 8 (`available_parallelism` reported 2 on the Redmi Note 11 Pro 5G, which has 8 cores). Other platforms keep the core count. The saved value was never capped by it (`workers` parses up to 64).
