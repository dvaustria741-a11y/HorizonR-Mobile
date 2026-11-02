//! player_tick hook — KillAura, AimAssist, AutoSprint, NoFall, Velocity.

use liquid_horizon_engine::{self as lh, EntitySnapshot, PlayerSnapshot};

let Some(p) = game.native_player() else { return; };

let player_snap = PlayerSnapshot {
    x: p.x, y: p.y, z: p.z,
    yaw: p.yaw, pitch: p.pitch,
    health: game.player.as_ref().map(|pl| pl.health).unwrap_or(20.0),
    food:   game.player.as_ref().map(|pl| pl.food_level as i32).unwrap_or(20),
    on_ground: game.player.as_ref().map(|pl| pl.on_ground).unwrap_or(false),
    delta_y: game.player.as_ref().map(|pl| pl.delta.y).unwrap_or(0.0),
};

let entities: Vec<EntitySnapshot> = game.mobs.iter().map(|mob| {
    let kind = format!("minecraft:{}", mob.kind.id.trim_start_matches("minecraft:"));
    EntitySnapshot {
        id:        mob.id,
        is_player: mob.kind.id.contains("player"),
        kind,
        x: mob.position.x,
        y: mob.position.y,
        z: mob.position.z,
        health: mob.health,
    }
}).collect();

let out = lh::on_player_tick(player_snap, entities);

// ── AimAssist ─────────────────────────────────────────────────────────────────
if let (Some(ny), Some(np)) = (out.aim_yaw, out.aim_pitch) {
    game.yaw   = ny;
    game.pitch = np;
}

// ── KillAura ──────────────────────────────────────────────────────────────────
if let Some(target_id) = out.killaura_target {
    // Reach module clamp
    let max_reach = lh::reach_range().unwrap_or(4.5);
    if let Some(mob) = game.mobs.iter().find(|m| m.id == target_id) {
        let dx = mob.position.x - p.x;
        let dy = mob.position.y - (p.y + 1.62);
        let dz = mob.position.z - p.z;
        let dist = (dx*dx + dy*dy + dz*dz).sqrt() as f32;
        if dist <= max_reach {
            let yaw_r = -(dx.atan2(dz) as f32).to_degrees();
            let horiz = (dx*dx + dz*dz).sqrt() as f32;
            let pitch_r = -(dy.atan2(horiz as f64) as f32).to_degrees();
            game.yaw   = yaw_r;
            game.pitch = pitch_r;
            game.send_attack(mob.id);
        }
    }
}

// ── AutoSprint ────────────────────────────────────────────────────────────────
if out.force_sprint {
    game.sprinting = true;
}

// ── NoFall ────────────────────────────────────────────────────────────────────
if out.no_fall {
    if let Some(ref mut player) = game.player {
        player.fall_distance = 0.0;
    }
}

// ── Velocity (AntiKnockback) — zero horizontal delta on next tick ─────────────
if out.zero_velocity {
    if let Some(ref mut player) = game.player {
        player.delta.x = 0.0;
        player.delta.z = 0.0;
    }
}
