//! tooltip hook: appends extra lines to item tooltips (ItemInfo module).

use liquid_horizon_engine as lh;
use crate::text::Text;

let _ = game;
for line in lh::on_tooltip(&stack.item) {
    lines.push((Text::literal(line), u32::MAX));
}
