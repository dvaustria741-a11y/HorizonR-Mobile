//! use_item hook: fires when the player right-clicks with an item.
//! Used by FastUse — instantly claims potion/bucket uses to bypass animation.

use liquid_horizon_engine as lh;

if lh::on_use_item(&stack.item) {
    *claimed = true;
}

let _ = game;
