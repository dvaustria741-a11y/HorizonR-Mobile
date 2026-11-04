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
- Idle sway: the hands rest with Punchy's standing-still bob (up, forward and pitch; the jar's IDLE_INTENSITY
  2.6 and IDLE_SPEED 2.0 formulas), fading in when the player stands still and out on movement or any action.

Everything else is unchanged. Compare with the original `.hnmod` before updating it.

- Movement sway: new `engine/sway.rs` ports Punchy's walk, sprint, jump/land and idle-breathing sway (spring/smoothing constants from the original MovementStateMachine). `runtime.rs` feeds it player motion each tick and applies the result to the arm and item pose in `begin()`. Idle sway now lives here. Unverified in-game: axis/sign conventions are approximations.
