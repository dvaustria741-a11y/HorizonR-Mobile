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
