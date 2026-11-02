//! hurt_enemy hook — Criticals + hit tracking.

use liquid_horizon_engine as lh;

let out = lh::on_hurt_enemy(entity, kind);
let _ = stack;

// ── Criticals — force downward momentum so the server registers a crit ────────
if out.crit {
    if let Some(ref mut player) = game.player {
        if player.on_ground {
            player.on_ground = false;
            player.delta.y   = -0.1;
        }
    }
}
