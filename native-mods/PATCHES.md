# Local changes to bundled native mods

`punchy-rust-preview-0_1_2.hnmod` is the upstream preview with these edits (`mixins/punchy/runtime.rs`):

- Trident use: `UseState.riptide` and `UseState.loyalty` are set from the held trident's enchantments.
  The preview left them false, so the riptide and loyalty throw clips could never be picked.

- Triggerable keys: the tuning's `triggerable` entries (sword inspect rework on G, classic on J) were ignored.
  `key()` now matches the pressed key against the entries of the held items (rebindable in the mod's keys),
  and `engine/actions.rs` plays the clip like an inspect (`Input.triggerable`).
- Default inspect: the fallback `new_inspect_*` clip is now chosen by the inspecting hand's side (it always used
  the right-hand clip).
- Animation sounds: the clips' `sound_effects` (inspect spins and hits, bow load, cake and gift) were never
  played. `runtime.rs` runs the engine's `SoundCursor` each tick and plays `punchy:animationssounds/<effect>`.
  The 20 .ogg files from Punchy's jar are bundled (`engine/data/sounds`), written once into the resource root
  and registered with Horizon's audio. Options → Mods → Punchy has an "Animation sounds" switch.
- Idle sway (now part of `engine/sway.rs`): the hands rest with Punchy's standing-still bob (up, forward and pitch; the jar's IDLE_INTENSITY
  2.6 and IDLE_SPEED 2.0 formulas), fading in when the player stands still and out on movement or any action.

Everything else is unchanged. Compare with the original `.hnmod` before updating it.

## sodium_horizon-0.4.0.hnmod

Packaged from [badmasti2/horizon-sodium](https://github.com/badmasti2/horizon-sodium) (Sodium Horizon
0.4.0) the way its `tools/package.py` does, with one change:

- The `ui_text` injection (target `game::control::Game::type_chars_into_screen`) and its helper
  `mixins/sodium_horizon/ui_text.rs` are removed. That method does not exist in this engine. Typed
  text still reaches Sodium's search box through `options_key` -> `ui_key` -> `exact_ui::key` ->
  `type_text`.

- `horizon_revision` in `horizon-mixin.toml` is `81ae4a7d8f27d38e5cabbdcda0a06b2c4b000045` (Punchy's pin) instead
  of upstream's `c9d474dd…`. `scripts/apply_native_mods.py` applies each distinct pin as its own pass, and
  horizon-mixins refuses a second pass over the first one's staged `native-crates/` (`E_INVALID_PATH`), which
  would drop Punchy too. With equal pins both bundles apply in one pass. The pin is only a label here: the
  script tells the tool it matches. `horizon-native.json` is unchanged.
- `extra_animations`: our `SpriteAnimator::collect` has no `active` layer mask, so that binding and its filter
  are removed.
- `extra_particles`: our `ParticleOptions` has no `mod_particle`, so the "named particle" flag is `false`.
- Video settings that don't exist in this engine are inert: Leaf Geometry (`leaf_culling`), Translucent
  Batching, Texture Budget (`ui.rs` reads 0 and ignores writes; no row in the exact UI) and the
  "Block broken" particles option (`exact_ui.rs` no longer exposes binding 188, so its row is unsupported).

Everything else is unchanged. Compare with upstream before updating it.
- Movement sway: `engine/sway.rs` ports Punchy's walk, sprint, jump/land and idle-breathing sway (constants from the
  original MovementStateMachine). `runtime.rs` feeds it the player's motion each tick and applies the result to the arm
  and item pose in `begin()`. Axis and sign conventions are approximations, unverified in-game.
- Climb, crawl and swim poses: `engine/locomotion.rs` picks the pack's looping `climb`, `crawl`, `swiming` and
  `swiming_above` clips with the original state machines' conditions (climb needs upward motion and forward/jump;
  crawl is the swimming pose out of water; swim needs water, and the `disable_swiming` item flag turns it off).
  The clip drives both hands, is held on its frame while the player stands still (climb, crawl), and gives way to
  any action (attack, use, inspect). Not ported: `falling_in_water` and the walk-on-water clips; the default pack
  has no fall or elytra clips.
