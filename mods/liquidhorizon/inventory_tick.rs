//! inventory_tick hook: runs each tick per carried stack.
//! Used by AutoEat — checks food level and triggers use of food items.

use liquid_horizon_engine as lh;

// Read player health/food from game state.
let (health, food_level) = game.player.as_ref()
    .map(|p| (p.health, p.food_level))
    .unwrap_or((20.0, 20));

let result = lh::on_inventory_tick(slot, selected, health, food_level as i32);

if let Some(eat_slot) = result {
    // The engine says to eat; the helper triggers a UseItem packet for the
    // food item in this slot if it's a food item.
    if let Some(Some(ref stack)) = game.native_carried_stack(eat_slot).map(|s| s.as_ref()) {
        if game.res.items().get(&stack.item).is_some() {
            // Trigger a use-item action on this slot.
            game.send_use_item(eat_slot);
        }
    }
}
