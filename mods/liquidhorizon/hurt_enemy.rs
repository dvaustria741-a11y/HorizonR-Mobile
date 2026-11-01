//! hurt_enemy hook: called when our hit lands on an entity.
//! Used for Criticals (force crit phase) and stat tracking.

use liquid_horizon_engine as lh;

let out = lh::on_hurt_enemy(entity, kind);

// ── Criticals: force the player into crit phase ───────────────────────────────
// A crit happens when the player is falling (velocity.y < 0) and not on ground.
// We inject a slight downward velocity spike just before the hit lands so the
// server registers a critical hit.
if out.crit {
    if let Some(ref mut player) = game.player {
        // Ensure we're not on ground and have downward momentum.
        if player.on_ground {
            player.on_ground     = false;
            player.delta.y       = -0.1;
        }
    }
}

let _ = stack;
