# Fluid & Foolish — movement (Horizon native mod)

A native port of the **Fluid & Foolish** animation pack's movement clips, for the player's body in
the third-person view (F5): idle, walking, sprinting, sidestepping, crouching, jumping, falling and
landing, plus swimming, crawling, climbing, creative flight, elytra gliding (with banking), sitting in boats and minecarts, getting hurt, turning on the spot, random idle events and hand actions (attack, mining, item switch, armour on, eating, drinking, shield, bow, spyglass, held tool poses) and the four emotes (keys H, J, K, L, changeable in the Mods screen). Starts **off**; turn it on in Options → Mods.

The animation clips are the pack's (`engine/data/clips.json`, merged from its `*.animation.json`
files by `tools/build_fluid_foolish_clips.py`). The pack is free to use, port and redistribute; see
`LICENSE-FluidFoolish.txt`. The logic (`engine/`) is new Rust: a clip sampler, a movement state
machine that follows the pack's `compat/develop.json` defaults (stride length, cycle-rate caps,
amplitude, fall distances), and a mapping from the pack's rig (root > waist > body/head/arms, legs
under root) to Horizon's player model parts.

Not done yet: diving into water or lava, riding a nautilus, walking on fences or water, nausea,
low hunger, cold biomes, listening to a jukebox, greetings, the item actions of crossbows, tridents, spears, maces, fishing rods, buckets, throwables and
the other groups, and the third-person camera moves. The Bedrock-to-Java axis conversion is read from the clips themselves
and is unverified in game.

Needs the `native_player_pose` hook (`Game::native_player_pose`) of this engine.
