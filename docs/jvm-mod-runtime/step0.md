# JVM mod runtime: Step 0 findings (2026-11-14)

Read from the repo at launcher-ui-desktop 149ec3fb0e. Nothing here was built or run.

## What exists
- Three mod kinds today: `.hmod` (wasmtime component, WIT in crates/horizon-mod-sdk/wit; content and sandboxed code,
  API 0.1/0.2, server mods 0.3 experimental), `.hnmod` (horizon-mixins bundle: Rust source patched into the game
  before compile; apply script scripts/apply_native_mods.py), and data carried over from jars (docs/modpacks.md).
- docs/mods.md states Fabric and NeoForge jars do not run: Horizon has no Mojang Java classes.
- Android: docs/android-0.7.6.md says `.hnmod` compilation on Android is unsupported and Java Fabric/Forge/NeoForge
  mods are not supported. `.hmod` runs.
- Existing JVM use is desktop dev-only: tools/neoforge_oracle.py installs NeoForge 21.1.259 (MC 1.21.1) server under
  .local/neoforge-oracle and runs it with the system `java`.
- Engine targets Minecraft 26.3, protocol 777 (crates/horizon-protocol/src/ids.rs). ATM10 inventory is 1.21.1.
- Dedicated server = the game run headless (`horizon-client --dedicated`, crates/horizon-client/src/game/dedicated.rs).
  Android docs mention no dedicated server.
- JNI: crates/horizon-android (cdylib, jni 0.21) is loaded into the ART process; no JavaVM creation or embedded JDK
  anywhere in the repo.
- docs/modpacks.md "All the Mods 10, measured" and docs/modpacks/atm10-mods.csv already inventory loaders and tiers.
  They do not inventory Mojang API calls per mod (Step 3 is still open).
