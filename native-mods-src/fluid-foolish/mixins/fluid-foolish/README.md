# Fluid & Foolish — movement (Horizon native mod)

A native port of the **Fluid & Foolish** animation pack's movement clips, for the player's body in
the third-person view (F5): idle, walking, sprinting, sidestepping, crouching, jumping, falling and
landing. Starts **off**; turn it on in Options → Mods.

The animation clips are the pack's (`engine/data/clips.json`, merged from its `*.animation.json`
files by `tools/build_fluid_foolish_clips.py`). The pack is free to use, port and redistribute; see
`LICENSE-FluidFoolish.txt`. The logic (`engine/`) is new Rust: a clip sampler, a movement state
machine that follows the pack's `compat/develop.json` defaults (stride length, cycle-rate caps,
amplitude, fall distances), and a mapping from the pack's rig (root > waist > body/head/arms, legs
under root) to Horizon's player model parts.

Not done yet: swimming, diving, elytra, crawling, climbing, riding, hurt, turn-in-place, idle
events, cameras and emotes. The Bedrock-to-Java axis conversion is read from the clips themselves
and is unverified in game.

Needs the `native_player_pose` hook (`Game::native_player_pose`) of this engine.
