//! player_tick hook: runs each server-side player tick.
//! Used for KillAura (attack dispatch) and AimAssist (yaw/pitch injection).
//!
//! This hook is server-side, so in singleplayer it runs on the integrated
//! server's thread; entity data comes from `game.mobs`.

use liquid_horizon_engine::{self as lh, EntitySnapshot, PlayerSnapshot};

// ── collect current game state ────────────────────────────────────────────────
let Some(p) = game.native_player() else { return; };

let player_snap = PlayerSnapshot {
    x: p.x, y: p.y, z: p.z,
    yaw: p.yaw, pitch: p.pitch,
    // health/food not available directly here; use 20.0 defaults
    health: 20.0,
    food:   20,
    on_ground: false,
};

// Build entity list from game.mobs (accessible because this helper is
// compiled into horizon-client and has access to private fields).
let entities: Vec<EntitySnapshot> = game.mobs.iter().map(|mob| {
    EntitySnapshot {
        id:     mob.id,
        kind:   format!("minecraft:{}", mob.kind.id.trim_start_matches("minecraft:")),
        x:      mob.position.x,
        y:      mob.position.y,
        z:      mob.position.z,
        health: mob.health,
    }
}).collect();

let out = lh::on_player_tick(player_snap, entities);

// ── AimAssist: override yaw/pitch ────────────────────────────────────────────
if let (Some(ny), Some(np)) = (out.aim_yaw, out.aim_pitch) {
    game.yaw   = ny;
    game.pitch = np;
}

// ── KillAura: attack nearest target ──────────────────────────────────────────
if let Some(target_id) = out.killaura_target {
    // Iterate mobs to find the target and send an attack.
    if let Some(mob) = game.mobs.iter().find(|m| m.id == target_id) {
        // Rotate to face the target before attacking.
        let dx = mob.position.x - p.x;
        let dy = (mob.position.y + 0.85) - (p.y + 1.62);
        let dz = mob.position.z - p.z;
        let target_yaw = -(dx.atan2(dz) as f32).to_degrees();
        let horiz      = (dx * dx + dz * dz).sqrt() as f32;
        let target_pitch = -(dy.atan2(horiz as f64) as f32).to_degrees();
        game.yaw   = target_yaw;
        game.pitch = target_pitch;

        // Send the attack packet (ServerboundInteractPacket → attack).
        // Horizon exposes this via the attack helper used by left-click.
        game.send_attack(mob.id);
    }
}

// ── AutoSprint ────────────────────────────────────────────────────────────────
if out.force_sprint {
    game.sprinting = true;
}

// ── NoFall ────────────────────────────────────────────────────────────────────
// Flag the player's fall distance as 0 so no damage is taken.
if out.no_fall {
    if let Some(ref mut player) = game.player {
        player.fall_distance = 0.0;
    }
}
