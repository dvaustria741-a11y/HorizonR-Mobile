# Third-party notices

## wgpu-hal

`third_party/wgpu-hal` is [wgpu-hal](https://github.com/gfx-rs/wgpu) 30.0.1 by the gfx-rs
developers, with upstream fixes applied
([gfx-rs/wgpu#10124](https://github.com/gfx-rs/wgpu/pull/10124),
[gfx-rs/wgpu#10714](https://github.com/gfx-rs/wgpu/pull/10714)). It is licensed under MIT or
Apache-2.0; both licenses are in that folder (`LICENSE.MIT`, `LICENSE.APACHE`).

## Pixelify Sans

`crates/horizon-theme/fonts/PixelifySans-*.ttf` is [Pixelify Sans](https://github.com/eifetx/Pixelify-Sans)
by The Pixelify Sans Project Authors, licensed under the SIL Open Font License 1.1
(`crates/horizon-theme/fonts/OFL-PixelifySans.txt`).

## Pixelarticons

`crates/horizon-theme/icons/pixel/*.svg` are icons from
[Pixelarticons](https://github.com/halfmage/pixelarticons) by Gerrit Halfmann, under the MIT
license (`crates/horizon-theme/icons/pixel/LICENSE-pixelarticons.txt`). `star.svg` is Horizon's own, drawn on
the same grid.

## winit

`third_party/winit` is [winit](https://github.com/rust-windowing/winit) 0.30.13,
licensed under Apache-2.0 (see `third_party/winit/LICENSE`). The Android backend
has local mouse and modifier-event fixes described in `third_party/winit/PATCHES.md`.

## AMD FidelityFX SDK (FSR 3.1 frame generation)

`crates/horizon-client-render/src/gfx/frame_gen/optical_flow.wgsl` is a port to WGSL of the
optical flow shaders of the [AMD FidelityFX SDK](https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK)
1.1.4 (`sdk/include/FidelityFX/gpu/opticalflow`, at commit
[c6efa6bf7f20](https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK/tree/c6efa6bf7f2027b3ec94f28578bb5965eabb9e55)),
and `optical_flow.rs` follows the SDK's `sdk/src/components/opticalflow/ffx_opticalflow.cpp`
(its passes, their order and their dispatch sizes). Copyright (C) 2024 Advanced Micro Devices,
Inc., under the MIT license, whose text heads both files. AMD and FidelityFX are AMD's
trademarks; Horizon is not affiliated with AMD.

## Mod loader logos

The launcher shows the logo of the mod loader an instance plays with.

- `launcher/assets/logos/quilt.png` is the Quilt mini icon (`brand/128png/quilt_mini_icon_transparent.png`)
  from [QuiltMC/art](https://github.com/QuiltMC/art), by the Quilt Project, dedicated to the public
  domain under [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/).
- `launcher/assets/logos/neoforge.png` is NeoForged's icon (`art/neoforged_icon_16x16.png`) from
  [neoforged/.github](https://github.com/neoforged/.github/tree/main/art), © the NeoForged team,
  created by Ridanisaurus, under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). It is
  unmodified (the launcher scales it up by whole pixels).
- Fabric's and Forge's icons are not shipped: neither publishes a license for its logo. The launcher
  fetches them from their own sites when an instance needs them
  ([fabric-loader's icon](https://github.com/FabricMC/fabric-loader/blob/master/src/main/resources/assets/fabricloader/icon.png),
  [files.minecraftforge.net's](https://files.minecraftforge.net/)).

Each logo belongs to its project. Showing it says which loader an instance runs; it does not mean
the project endorses Horizon.

## Sign in with Microsoft

The launcher's sign-in buttons follow Microsoft's published branding guidelines for apps that sign in
with Microsoft accounts
([Sign in with Microsoft branding](https://learn.microsoft.com/en-us/entra/identity-platform/howto-add-branding-in-apps)).
The four-square Microsoft symbol in `launcher/src/microsoft.rs` reproduces the squares, positions and
colours of Microsoft's official `ms-symbollockup_mssymbol_19.svg` from that page, unmodified. Microsoft
and its logo are trademarks of the Microsoft group of companies; Horizon is not endorsed by Microsoft.

## Horizon Translator (shader converter)

`crates/horizon-shaderport` is adapted from the shader converter in
[Horizon-Translator](https://github.com/merenotnoonereal/Horizon-Translator) by merenotnoonereal
(`shader-converter/`). The upstream repository has no LICENSE file; the author has said publicly
that the code is open source and free to use. It was turned into a library and given a Store
entry point (`port_pack`); the rewrite rules are otherwise unchanged. Credit for the rules belongs
to the upstream project.

## Statically bundled Android mods

Zoom, Minimap and Recipes are adapted from [horizon-rust/horizon-mods](https://github.com/horizon-rust/horizon-mods/tree/e3ee0a7c450b28c3ed58fa59a4881ec78c5ee2e0/native),
copyright 2026 Ludovic Marie and the Horizon contributors, MIT.
SmartF3 is adapted from [d3grando/smartf3](https://github.com/d3grando/smartf3),
copyright 2026 d3grando, MIT. Exact revisions and adaptation notes are recorded in
`crates/horizon-client/src/game/android_bundled/PROVENANCE.json`; each bundled
module retains its full MIT LICENSE. Changes provide static Android hook
adapters, opt-in configuration, profile-state reset and compatibility guards.
These are a fixed compiled set, not support for loading arbitrary native mods.

## Sodium Horizon (native mod)

`native-mods/sodium_horizon-0.4.0.hnmod` is [Sodium Horizon](https://github.com/badmasti2/horizon-sodium)
0.4.0 by badmasti2. The bundle's own code is MIT, but it carries material from Sodium, under the
[PolyForm Shield License 1.0.0](https://polyformproject.org/licenses/shield/1.0.0), and from Sodium
Extra, under the LGPL 3.0. The upstream license texts are in the bundle's repository
(`SODIUM-LICENSE.md`, `SODIUM-EXTRA-LICENSE.txt`, `THIRD_PARTY_NOTICES.md`) and the notices are
preserved in `mixins/sodium_horizon/third_party_notices.rs` inside the `.hnmod`. Horizon-derived
helpers in it are under Horizon's MIT license. The names identify the reference interface and do
not imply endorsement. Local changes are listed in `native-mods/PATCHES.md`.

## Iris (native mod)

`native-mods/iris_horizon-0.1.0.hnmod` is a Horizon port of the shader pack menu and configuration layer
of [Iris](https://github.com/IrisShaders/Iris) 26.3, MIT. Its page names, option wording and behavior
follow Iris's; it contains no Iris source. The license notice is kept inside the bundle
(`mixins/iris_horizon/LICENSE-Iris`). Shader rendering is Horizon's own.

