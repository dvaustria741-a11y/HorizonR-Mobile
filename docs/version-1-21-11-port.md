# Porting the engine to Minecraft 1.21.11 (singleplayer)

Status: scoping. What exists, what the gap is, what is needed. Numbers come from
`python3 tools/mcmeta_diff.py 1.21.11 26.3` (the public misode/mcmeta mirror of the generated reports).

## What already works for 1.21.11

Joining 1.21.11 **servers** from the 26.3 engine: `horizon-protocol/src/adapter` (`ServerVersion::V1_21_11`,
protocol 774), enabled with `HORIZON_OLDER_SERVERS=1` (the launcher's 1.21.11 profile sets it). It translates
packets; the game, the world and the assets stay 26.3's.

## Why singleplayer is a different job

Singleplayer runs vanilla's rules inside the engine, so the engine has to be 1.21.11's rules, not a translation.
`horizon-core/src/version.rs` pins one version at compile time (protocol, data version 5023 vs 4671, pack formats
97.1/121.0 vs 75.0/94.1, Java 25 vs 21), and the worldgen, datafix, item, block and mob code is written against the
pinned jar.

### Data gap, 1.21.11 -> 26.3

- Blocks 1166 -> 1286: +120 (cinnabar and sulfur families, wool/concrete slabs and stairs...), none removed; 41
  shared blocks changed properties or states.
- 26 registries exist only in 26.3, among them `worldgen/feature_type`, `worldgen/block_state_provider`,
  `worldgen/carver_type`, `worldgen/material_*`, `world_clock`, `villager_trade`, `trade_set`, `predicate`,
  `context_*`, `block_transformer` and the cat/chicken/cow/pig sound variants.
- 5 registries exist only in 1.21.11: `worldgen/configured_feature`, `worldgen/configured_carver`, `block_type`,
  `loot_number_provider_type`, `experiment/trade_rebalance/enchantment_provider`. Worldgen features were
  restructured (63 `worldgen/feature` entries in 1.21.11, 240 in 26.3), so the engine's feature graph reads a
  format 1.21.11 does not have.
- Also: items +153, sounds +556, recipes +585, loot tables +123, structures +309, advancements +293.

### What a real port needs

1. 1.21.11's data from its own jars: `tools/lab.py prepare` and `horizon-prepare` against the 1.21.11 client and
   server jars (`blocks.json`, `registries.json`, `packets.json`, datapack reports, the Java exporters).
2. Decompiled 1.21.11 sources. The jar is obfuscated (1.21.11 is the last obfuscated release), so the mappings
   (`client_mappings`/`server_mappings` in the version JSON) are required.
3. A per-version engine build: a second set of version facts and generated tables (`ids.rs`, tracking, command
   tables, datafix state data) selected at build time, with two native libraries in the APK, one per version.
4. Code changes where behaviour differs: the worldgen feature format above, block states, mobs, trades and loot,
   each checked with the parity harnesses (`tools/parity.py`) against a 1.21.11 oracle.

Steps 1 and 2 need `piston-meta.mojang.com`, `piston-data.mojang.com`, `libraries.minecraft.net` and
`resources.download.minecraft.net`.

## Verified so far (real 1.21.11 data: the official server jar's datagen reports and datapack)

Reproduce with the jar: `java -DbundlerMainClass=net.minecraft.data.Main -jar server.jar --reports --server --output out`,
then `HORIZON_1_21_11_REPORTS=out/reports cargo test -p <crate> --test <name> -- --nocapture`. The tests do nothing
without the variable; Mojang's files are not in the repository.

- **Block states:** `BlockRegistry::from_blocks_report` loads the real `blocks.json`: 1166 blocks, 29,671 states
  (ids contiguous), air and fluid columns from the block definitions, with no Java export.
- **Packet ids:** `horizon-protocol/tests/adapter_1_21_11_ids.rs` checks the adapter's 774 tables against the real
  `packets.json`: all 251 packets map to the pinned packet of the same name, 0 differ.
- **Pack layout:** `PackLayout::V1_21_11` reads 1.21.11's `configured_feature` / `configured_carver` folders; the default
  layout is 26.3's and is tested as unchanged.
- **Worldgen data:** `horizon-port-pack`'s existing 26.2 -> 26.3 upgrader converts the real 1.21.11 worldgen data without
  error (224 features, 4 carvers, 65 biomes, 35 density functions, 60 noises, 7 noise settings; nothing lossy; unconverted:
  27 `random_patch` and about 25 other feature types that 26.3 writes as data, 3 `weird_scaled_sampler`). Compared with
  vanilla 26.3's own files, the terrain-defining data comes out identical: noises 60/60, carvers 4/4, structure sets
  20/20, template pools 188/188, world presets 6/6. Differences are mostly representation (shared vs inlined density
  functions, block states as strings, biome mob-spawn categories); the rest are 26.x content changes. Whether the result
  generates what 1.21.11 generates is not established: that needs a 1.21.11 oracle.
- **Singleplayer:** `HORIZON_TARGET_VERSION` (set by the launcher from the selected version) makes the engine refuse
  singleplayer for a release it is not pinned to, with a message; it never opens a 26.3 world under another name.

## Java exporters on 1.21.11 (mappings supplied)

With Mojang's `client_mappings` file: `tools/RemapJar.java` remaps the obfuscated client jar to Mojang names (the JDK's
built-in ASM; all 10,291 mapped classes remapped; names checked by reflection), and `tools/export_1_21_11.py` compiles
and runs the exporters against it. The exporters need almost no change: with a `mods/horizon/src/test/java-1.21.11`
overlay (`getLightBlock`, `ItemStack.getTags`, `BlockState.CODEC`; searched before the 26.3 sources, which stay as
they are) the registry facts (`VanillaHeightmapOracle export`), gameplay-facts, entity-types, block-behaviour and structure-block exporters compile and run. Results:
29,671 state facts, and the engine's own `BlockFacts` loader reads them
(`horizon-items/tests/blocks_1_21_11.rs`). The Java registry export and the datagen-report loader agree on all 29,671 states with no air/fluid flag
difference, which checks the flag rules of `BlockRegistry::from_blocks_report`.
Format difference found: 1.21.11 writes a block state as `{Name, Properties}`, 26.3 as `{id, properties}`. The chunk
codec now has both (`horizon_world::chunk`): `StateKeys::{Modern, Classic}`, `ChunkFormat::{CURRENT, V1_21_11}` (data
version 4671, `Name`/`Properties`), `BlockState::{to_nbt_as, from_nbt_as}`, `BlockSection::{to_nbt_as, from_nbt_as}` and
`ChunkData::new_as`, whose `block` and `set_block_data` read and write the chunk's own keys. Each format is read strictly
as itself (a 1.21.11 section is an error to the 26.3 reader and the other way round) and `new`, `to_nbt` and `from_nbt`
stay 26.3's. Tested (`chunk_format_1_21_11.rs`): keys written and read per format, data-version gate, editing a 1.21.11
chunk, and all 29,671 real 1.21.11 states through a Classic section round trip. Not done: block entities, entities and
other NBT that embed block states, and nothing yet selects `V1_21_11` for a save: singleplayer stays unavailable. Exporters added since: `ParityData` has a 1.21.11 overlay (layers STATIC/WORLDGEN/DIMENSIONS/RELOADABLE, synchronous
registry loader, no component initializers), so `VanillaCreativeTabsExporter` (overlay: `ItemStack.getTags`) and
`VanillaCommandSuggestionsExporter` run on the remapped jar and are registered in `tools/export_1_21_11.py`: 14 creative
tabs with 3,914 item entries (`creative-tabs.json`) and 122 command suggestions (`command-suggestions.tsv`). They ran;
nothing compares them with the game's own client or with what the engine reads, and the engine's loaders for them
(`horizon-client`) were not built here (ALSA).

**Oracles: 182 of 316 `Vanilla*Oracle` classes do not compile against 1.21.11** (one javac pass over all of them with the
overlay, 1,415 errors; the other 134 compile, none was run). Sorted by the errors' symbols (`oracle_buckets` kind, from
that pass; not committed):

- 67 use APIs 1.21.11 does not have at all (the new renderer, `Hud`/`GuiGraphicsExtractor`, the density-function
  compiler and `DensitySampler`, `world.clock`, `ItemStackTemplate`, sprite/cuboid model packages). They test 26.x-only
  code, so there is nothing to port: they have no 1.21.11 counterpart.
- 72 fail with 1 to 3 errors. The commonest cause is the `ServerLevel`/`LocalPlayer`/`ServerChunkCache`/`ServerPlayer`
  constructors the oracles subclass with 26.3's signatures (about 80 errors over 27+ oracles), then `bindComponents`,
  `BlockState.FULL_CODEC`, `EntityTypes` (renamed `EntityType` fields), `Registries.BLOCK_TRANSFORMER` and `Feature.DIRECT_CODEC`
  (26.3-only). Each is a per-oracle edit; none is done.
- 43 fail with more (for example `VanillaChunkGenerationOracle` 33, `VanillaCommandExecOracle` 22, `VanillaDataFixOracle` 21).

The client exporters, the tag bundle and the collision cache are not yet produced.

## The generator loads 1.21.11's data

`tools/assemble_worldgen_1_21_11.py` assembles the directory `GeneratorData::load` reads from the datagen output, the
upgraded worldgen data, the Java exports and the jar's 1,202 structure templates, and
`horizon-world/tests/generator_1_21_11.rs` loads it: `GeneratorData::load: ok` (65 biomes, 224 features, 258 placed
features, 60 noises, 81 density functions, 7 noise settings, 34 structures, 20 structure sets, 188 template pools,
tags 204/2/68). Differences between the versions that the loader found on the way:

- **Heightmap tags.** 26.3 reads the block tags `blocks_motion_in_heightmap(_no_leaves)`; 1.21.11 asks
  `BlockState.blocksMotion()`. `Vanilla1_21_11MotionTagsExporter` writes the tags from the game's own method:
  912 blocks (901 without leaves). Against 26.3's real tag, every block of both versions is in it except 24 sign
  blocks that 1.21.11 counts as blocking motion and 26.3 does not: a real behaviour change, not an export error.
  `resin_brick_wall` has 2 of 324 states that differ and cannot be one tag entry.
- **Carvers.** 26.3 has one `uncarvable` tag (bedrock); 1.21.11 gives each carver a `replaceable` block list (the
  upgrade drops it). The assembler writes `minecraft:uncarvable` for the Overworld (1,114 blocks, from
  `overworld_carver_replaceables`) and `horizon:uncarvable/nether` (1,141 blocks, from `nether_carver_replaceables`).
  `GeneratorData` keeps a table per noise settings id: a `horizon:uncarvable/<path>` block tag is the table of
  settings `minecraft:<path>`, and settings without one use `minecraft:uncarvable`, so 26.3's data (one tag) loads and
  generates as before. The End has no carvers. Checked by `generator_1_21_11.rs` (netherrack, soul sand, nylium carvable
  only in the Nether; bedrock in neither); not checked against the game's carved blocks, only against the tag data.
- **Material conditions and block-state providers** are inline in 1.21.11 (no `conditions` or `state_providers`
  bundle): the loader takes an empty `conditions` bundle.

- **weird_scaled_sampler.** 1.21.11 has this density type (`r * |noise(x/r, y/r, z/r)|`, `r` from a two-table mapper);
  26.3 writes it as `abs(interval_select(...))` of scaled noises. The upgrader now converts it (checked against the
  real bytecode and 26.3's own `entrances` and `spaghetti_2d` data).
- **Block-state keys in worldgen settings:** the generator's state reader accepts `Name`/`Properties` and `id`/`properties`.

Loading is not generating. `horizon-world/tests/terrain_1_21_11.rs` compares the engine's generated Overworld heights
with the real game's (`Vanilla1_21_11HeightOracle`, vanilla `getBaseHeight` for WORLD_SURFACE_WG). Seed 12345, a 64x64 grid
(every 8 blocks, x and z from -256 to 256): **4207 of 4225 columns (99.6%) match**. The 18 that differ are all lower in the
engine, by 1 to 8 blocks (-8: 2, -6: 3, -4: 1, -3: 4, -2: 2, -1: 6). The engine's height is read after carving and the
oracle's is not, so carving is the likely cause; that has not been checked (one seed, one area, Overworld only).

## Still blocked

- The rest of the exporters and bundles listed above.
- **`horizon-prepare` for 1.21.11: started, not done.** Done: it reads another version's lock file
  (`--lock research-lock-1.21.11.json` or `HORIZON_PREPARE_LOCK`; only a plain `research-lock*.json` name is accepted, and
  the default stays 26.3's `research-lock.json`), `research-lock-1.21.11.json` pins the version JSON, client and server
  jars and client mappings by sha1 (Java 21, data version 4671), and the import of an obfuscated release no longer stops
  with "targets unobfuscated releases": it needs the lock to pin the client mappings, checks the file the player names with
  `--client-mappings` against that pin (or an `accepted_client_mappings_sha1` entry of the lock; none is committed) and keeps
  it as `jars/client_mappings.txt`. Tested: the lock-name rules and both locks' pins (unit tests); a real run on the
  1.21.11 files got through the version JSON, the mappings and the client jar. Not done: it stops at the client libraries
  (Mojang's `libraries.minecraft.net` files, which the sandbox does not have), so the later stages never ran for 1.21.11:
  the remap of the client jar (`tools/RemapJar.java`) is not part of the preparer, the exporters still compile against an
  unobfuscated jar with `--release 25` and no `java-1.21.11` overlay, the pack formats and the asset index are 26.3's.
  The first `client.txt` supplied for this work was not Mojang's published file (its sha1 differs from the version JSON's, it is
  270 bytes longer and its header is an MIT-licence text, not Mojang's), so the repository lock correctly refuses it.
  The file supplied afterwards (`client__1_.txt`) matches the pin (sha1 031a68be...): `tools/export_1_21_11.py` on it
  gives byte-identical `facts.json` and `motion-tags.json`, so the earlier exports stand.
- Gameplay logic differences 1.21.11 -> 26.3 (mobs, items, trades, loot): partly measured, see below.

## Gameplay differences: what is measured

Decompiled: Vineflower 1.12.0 (the lock's pin) on the remapped jar's `world/entity`, `world/item`, `world/inventory` and
`world/level/storage/loot` packages: 1,856 classes into 1,196 source files, no failure markers. A decompile of the whole
jar was started first and died without output, so only these packages exist. Not read yet: no behaviour was compared
with 26.3's code, which is not available here.

Loot tables, 1.21.11 against 26.3 (`tools/loot_diff_1_21_11.py`, 26.3 from misode/mcmeta): 134 entity and gameplay tables
in both versions; one entity table is new in 26.3 (`sulfur_cube`). After mapping 26.3's restructured JSON onto 1.21.11's
shape, 104 of 134 are equal and 30 differ (26 entities, 4 gameplay). For the four gameplay ones (fishing, fishing junk,
fishing treasure, piglin bartering) the item names and weights are equal, so what differs is conditions or
representation. The 26 entity tables were not read one by one. This is the data only: mob AI, damage, item use and trade
logic are code and are unmeasured.
