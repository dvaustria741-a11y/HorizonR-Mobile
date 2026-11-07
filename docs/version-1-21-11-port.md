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
Format difference found: 1.21.11 writes a block state as `{Name, Properties}`, 26.3 as `{id, properties}`; the registry
loader now reads both, but chunk palettes in saves need the matching codec both ways (reading is not enough:
singleplayer has to write 1.21.11 saves in 1.21.11's format, data version 4671). Left: `ParityData` (registry layering and data-component initializers
changed) blocks the creative-tabs and command-suggestion exporters; the oracles (`VanillaSimpleBlockOracle`,
`VanillaCommandParseOracle`, `VanillaBlockLandingOracle`, `VanillaPlacementOracle`, ...) have 60-odd API-difference
errors; the client exporters, the tag bundle and the collision cache are not yet produced.

## Still blocked

- The rest of the exporters and bundles listed above, and `horizon-prepare` itself, which is still pinned to 26.3's
  jars, pack formats and Java 25: it does not yet prepare 1.21.11.
- Gameplay logic differences 1.21.11 -> 26.3 (mobs, items, trades, loot) are unmeasured without decompiled sources.
