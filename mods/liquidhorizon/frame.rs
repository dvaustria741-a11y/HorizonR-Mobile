//! frame hook — client-side per-frame logic:
//!   FullBright: override gamma each frame
//!   Scaffold:   send block-placement packets when airborne + moving
//!   Triggerbot: auto-attack entity under crosshair

use liquid_horizon_engine as lh;

// ── Scaffold prerequisite: find a block in hotbar ────────────────────────────
// Check first 9 slots for any solid block item.
let block_slot: Option<i32> = (0..9i32).find(|&s| {
    game.inventory.get(s as usize)
        .and_then(|opt| opt.as_ref())
        .map(|stack| {
            // Heuristic: item id contains "block", "stone", "dirt", "plank", etc.
            let id = &stack.item;
            id.contains("block")   || id.contains("stone") || id.contains("dirt")
            || id.contains("plank") || id.contains("brick") || id.contains("cobblestone")
            || id.contains("sand")  || id.contains("gravel") || id.contains("concrete")
            || id.contains("wool")  || id.contains("log")    || id.contains("wood")
        })
        .unwrap_or(false)
});

// ── Check whether block directly below player is air ─────────────────────────
let block_below_air = if let Some(p) = game.native_player() {
    let bx = p.x.floor() as i32;
    let by = (p.y - 1.0).floor() as i32;
    let bz = p.z.floor() as i32;
    game.native_block([bx, by, bz]) == "minecraft:air"
} else {
    false
};

// ── Crosshair entity (for Triggerbot) ────────────────────────────────────────
// Horizon exposes the targeted entity id via game.target_entity_id or similar.
// Use a proxy: the last entity in the mob list whose bounding box contains the look ray.
// For simplicity, use game.target if it exists.
let crosshair_entity: Option<u32> = game.targeted_mob_id();

let out = lh::on_frame(elapsed.as_millis() as u64, block_below_air, block_slot, crosshair_entity);

// ── Apply FullBright ──────────────────────────────────────────────────────────
if let Some(gamma) = out.fullbright_gamma {
    // game.options.gamma is the field Horizon reads for brightness.
    // Overwrite each frame so it persists through settings reloads.
    game.options.gamma = gamma;
}

// ── Apply Scaffold block placement ───────────────────────────────────────────
if let Some(([dx, dy, dz], slot)) = out.scaffold_place {
    if let Some(p) = game.native_player() {
        let bx = (p.x.floor() as i32) + dx;
        let by = (p.y.floor() as i32) + dy;
        let bz = (p.z.floor() as i32) + dz;
        // Switch to the scaffold slot if needed.
        if game.selected != slot as usize {
            game.selected = slot as usize;
        }
        // Send ServerboundUseItemOn packet: place on top face of the block below.
        game.send_place_on([bx, by, bz], [0, 1, 0]);
    }
}
