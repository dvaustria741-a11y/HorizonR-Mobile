# Local changes to bundled native mods

`punchy-rust-preview-0_1_2.hnmod` is the upstream preview with these edits (`mixins/punchy/runtime.rs`):

- Trident use: `UseState.riptide` and `UseState.loyalty` are set from the held trident's enchantments.
  The preview left them false, so the riptide and loyalty throw clips could never be picked.

Everything else is unchanged. Compare with the original `.hnmod` before updating it.
