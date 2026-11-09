# Local changes to bundled native mods

`punchy-rust-preview-0_1_2.hnmod` is the upstream preview with these edits (`mixins/punchy/runtime.rs`):

- Trident use: `UseState.riptide` and `UseState.loyalty` are set from the held trident's enchantments.
  The preview left them false, so the riptide and loyalty throw clips could never be picked.

- Triggerable keys: the tuning's `triggerable` entries (sword inspect rework on G, classic on J) were ignored.
  `key()` now matches the pressed key against the entries of the held items (rebindable in the mod's keys),
  and `engine/actions.rs` plays the clip like an inspect (`Input.triggerable`).
- Inspect is per item, as in the original: only items with an `inspect` tuning entry play one (swords, pickaxes,
  golden dandelion, the baby-wolf carry; the sword's G/J triggerables). The earlier fallback that played the sword's
  `new_inspect_*` on every other item was removed. Handed variants are still chosen by the inspecting hand's side.
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

- The sidebar header is two lines like Sodium 0.9's ("Sodium" over "0.9.2", the icon spanning both).
- `exact_ui.rs` gains a small extension point (`external()` / `set_external()`): while another bundle
  owns the content area (Iris), Sodium draws no tab highlight and no rows. Its flag is cleared when the
  screen closes.

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
- Camera-look sway: `engine/look.rs` ports the original's camera tilt (view up/forward/right axes), pitch shift and
  the arm's yaw-lag spring (stiffness 64, damping 15, 1/120 s steps), stepped each rendered frame from the view's
  yaw and pitch. `runtime.rs::apply_look` applies it to both hands. Axis signs are unverified in-game.
- Air poses: `engine/air.rs` ports the fall orbit (hands circle while falling fast or far), the elytra flight pose
  (hands pushed forward, ease-out-back) and the underwater sink pose (idle below the surface, slow blend in), with the
  original's blend times and constants, stepped each rendered frame. They add to the hands' walk/sprint swing in
  `begin()`. Simplified: no per-item fall-arm disable, no root-space variant while an action plays, the sink's
  solid-support check uses `on_ground`. Unverified in-game.
- Crouch spring: `engine/sway.rs` also ports the original's sneak spring (stiffness 22, damping 14, depth -0.12): the
  hands dip as crouching starts and spring back on release, through the same root shift as the jump spring.
- Horse riding: `engine/locomotion.rs` plays `newhorse_riding_idle` or `newhorse_riding_loop` (moving above the original's
  4e-4 speed squared) on horses, donkeys, mules, camels and llamas. Not ported: the one-shot `newhorse_riding_mount` camera dip.
- Fix: the crawl check named `crate::pose::Pose`, which the game crate doesn't have; it is `horizon_client::pose::Pose`.

## iris_horizon-0.1.0.hnmod

Iris's shader pack screens, opened from the Sodium video settings. It requires `sodium_horizon` (it uses
Sodium's layout and extension point), and its hooks check `native_on("iris_horizon")`, so it stays inert
until installed.

Like Iris in Sodium 0.9, the sidebar has an "Iris" group (Iris's own logo, `iris-logo.png`, reduced to 20 x 20 pixels, over "Iris" and "1.11.7") below "Sodium Extra": "Shader Packs" (a link that
opens the Shader Packs screen) and "Settings" (the groups "Iris" and "Settings" with the "Open >" rows).

- Shader Packs screen ("Select"): the "Shaders: Enabled/Disabled" switch, the pack list (the selected pack
  in yellow), Open Shader Pack Folder..., Shader Pack Settings..., Cancel, Apply and Done. The choice is
  pending until Apply or Done; Cancel and Escape drop it.
- Shader Pack Settings screen ("Configure"): the pack's name, Reset, the pack's own screens laid out in its
  one or two columns, drawn like Iris's widgets (the label on the left and the value in an inset box on the right:
  On green and Off red when changed from the default, other values blue, a preview slider in the box and a full
  slider while hovered or dragged, a modified option's label in gold until Apply; button colours are Iris's
  `widgets.png` ones; "< Back" inside a sub screen), tooltips from the
  pack's comments and non-default values in yellow. Option changes apply at once (the renderer reloads the
  pack); Cancel and Escape restore the values the screen opened with.

Horizon already runs OptiFine/Iris shader packs itself (`horizon-shaders`), so this is the menu and
config layer Iris adds, not a second renderer. It drives the engine's own state: the pack list
(`settings.shader_packs`), the picked pack, per-pack options, profiles, reset, refresh and the folder.
Enable Shaders remembers the last pack in `iris.properties` beside `options.txt`.
Not ported because the engine has no equivalent: Max Shadow Distance, Color Space (shown as sRGB, read
only), drag and drop of packs, import and export of option files.

Hooks: RETURN of `draw_options` (draws after Sodium, the screens cover it), and HEAD of `options_mouse`,
`options_drag`, `options_scroll`, `options_key` (priority 200, before Sodium; it swallows keys while a
screen is open so Escape closes it) and `native_mods`.

## Ported to Horizon 2.58

- `punchy`: the `hand_geometry` target is now `horizon_client::game::frame_geometry::Game<'_>::hand_geometry` (the method moved to `game/frame_geometry.rs`).
- `sodium_horizon`: the `section_visibility` injection, its helper `visibility.rs` and the `visibility_runtime` module (`runtime.rs`) are removed. 2.58 dropped `mesher::visibility_reusing` and has its own bit-row `visibility_rows`, so the replacement is no longer needed.
