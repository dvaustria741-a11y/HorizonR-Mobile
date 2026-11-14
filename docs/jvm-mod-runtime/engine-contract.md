# Engine contract: what a JVM mod bridge can use today (read 2026-11-14)

Read from the repo at `jvm-mod-runtime` (5f1f7f6c22): `crates/horizon-mod-sdk/wit/horizon.wit` (840 lines, package
`horizon:mods@0.1.0`), `crates/horizon-mods/src/runtime.rs` (1,971 lines), `crates/horizon-client/src/game/mods.rs`
(2,327 lines). Nothing was built or run. "Not found" means a grep and a read did not show it.

## What the mod host does today
- One host type, `Mods` (runtime.rs). Per game tick the client builds a `Snapshot`, calls `set_snapshot`, then
  `tick(ticks)`, then shows the mods' `Output` (`tick_mods` in client/game/mods.rs). Per frame: `frame(seconds)`.
  Other events: chat, command; server side: server tick, player joined/left, chat, command, block broken/placed.
- `Snapshot` (what mods can see): player (x, y, z, yaw, pitch, health, food, dimension), clock, GUI size, held item,
  frame stats, key mappings, clicks per second, font widths, server players, the block the player looks at, recipes.
- `Output` (what mods can ask for): chat, command, set blocks, open/close screen, camera shake, place recipe, give item.
- Drawing: `render.set-hud(shapes)`, `set-corner`, `text-width`, `gui-size`, `set-minimap`, `set-zoom`. Shapes are
  fill, quad, sprite, item and text.
- `WorldView` trait: block, biome and dimension lookups; `set-blocks` writes.
- Mod kinds: `.hmod` wasm components (capabilities declared in the manifest) and `.hnmod` native Rust patches.
  No Java host and no JVM code anywhere in the crates.

## What this means for a Java bridge
A JVM backend can sit inside `Mods` as another way to run a mod: fed the same `Snapshot`, answering with the same
`Output` and shapes. The client-tier facade classes would turn their calls into those shapes and events.

## Gaps against Fabric mods (AppleSkin as the first yardstick, see contracts/appleskin-3.0.10.md)
- Food saturation and exhaustion exist in `horizon-world` (food_properties.rs, living_damage.rs) but `Snapshot.player`
  carries only health and food.
- No hook found for item tooltips, the inventory, entities or the loaded client level in the mod host.
- Mixins into Minecraft classes have no Rust counterpart: the engine has no object model with those class names.
  Every facade class is new code, and each new fact it needs must be added to the snapshot or to a new event.
- Networking (Fabric API's client/server payloads) has no mod-facing API.
- The JNI path for Android that I expected under `crates/horizon-android` is not at that path in this tree; where the
  Rust side loads from the Kotlin app was not read.

## Consequence for the plan
Facade classes need new engine capabilities added one by one, driven by the measured demand list (api-demand.md), most
used first. The existing `render`, `events` and `%world` interfaces cover a HUD overlay, ticks and block access today.
