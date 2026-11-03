# Local changes to bundled native mods

`punchy-rust-preview-0_1_2.hnmod` is the upstream preview with these edits (`mixins/punchy/runtime.rs`):

- Trident use: `UseState.riptide` and `UseState.loyalty` are set from the held trident's enchantments.
  The preview left them false, so the riptide and loyalty throw clips could never be picked.

- Triggerable keys: the tuning's `triggerable` entries (sword inspect rework on G, classic on J) were ignored.
  `key()` now matches the pressed key against the entries of the held items (rebindable in the mod's keys),
  and `engine/actions.rs` plays the clip like an inspect (`Input.triggerable`).
- Default inspect: the fallback `new_inspect_*` clip is now chosen by the inspecting hand's side (it always used
  the right-hand clip).

Everything else is unchanged. Compare with the original `.hnmod` before updating it.
