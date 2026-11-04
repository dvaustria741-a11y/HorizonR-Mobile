# Fixed Android mod sources

These seven modules (Zoom, Minimap, Recipes and SmartF3, compiled in here, plus Punchy, Sodium and Iris,
which the CI build adds from `native-mods/`) are installed from the Store's **Zenkai**
source (Mods page, third source button), not toggled on a built-in list. Install writes
`zenkai.<id>:true` and `mod.<id>:true` to `horizon-mods.txt`; Uninstall removes both. A module that
is not installed does not run and is not listed in Options > Mods. The code still ships inside the
APK (there is no native-mod loader on Android), so install only switches it on.

The parent `android_bundled.rs` wires a fixed, opt-in set of MIT native modules
into existing game hook points. It is not a native-mod loader or compiler.
`PROVENANCE.json` pins source revisions; every module retains its MIT LICENSE.

The runtime and adapter sources come from the corresponding upstream mod.
Helper files are renamed and expose `run` for static calls. Original
`horizon-mixin.toml` files are retained for reference, not used for building:
this directory is not a directly installable `.hnmod` bundle.

Android changes reset profile-scoped state on launcher transitions, retain the
Android debug text scale and preserve the code-of-conduct screen. Update the
portable runtime tests and Android checks when changing these adapters.

Punchy, Sodium (`sodium_horizon`) and Iris (`iris_horizon`, which needs Sodium) are not in this directory: they are `.hnmod` bundles in
`native-mods/` that `scripts/apply_native_mods.py` applies to the engine in CI, together and all or
nothing. They are listed in `ANDROID_BUNDLED` so the Zenkai source installs them, and their hooks
check `native_on(<id>)`, so they stay inert until installed. See `native-mods/PATCHES.md`.
