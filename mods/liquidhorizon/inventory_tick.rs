//! inventory_tick hook — AutoEat.

use liquid_horizon_engine as lh;

let health     = game.player.as_ref().map(|p| p.health).unwrap_or(20.0);
let food_level = game.player.as_ref().map(|p| p.food_level as i32).unwrap_or(20);

if let Some(_eat_slot) = lh::on_inventory_tick(slot, selected, health, food_level) {
    // Item is already in `slot` (selected slot) — send a use-item packet.
    if selected {
        game.send_use_item_in_hand();
    }
}
