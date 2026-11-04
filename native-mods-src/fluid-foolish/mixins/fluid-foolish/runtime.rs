// Horizon glue for Fluid & Foolish's movement animations (engine/ has the clips and the logic).
use super::*;
use fluid_engine::{Clips, Input, Locomotion};
use std::cell::RefCell;

pub(super) const ID: &str = "fluid-foolish";
pub(super) const TITLE: &str = "Fluid & Foolish — movement";
thread_local! { static STATE: RefCell<State> = RefCell::new(State::default()); }
#[derive(Default)]
struct State {
    clips: Option<std::result::Result<Clips, String>>,
    loco: Locomotion,
}

fn enabled(game: &Game<'_>) -> bool {
    game.native_on(ID) && game.native_setting(ID, "enabled").as_deref() != Some("false")
}
/// How much of the animation shows: 25, 50, 75 or 100 percent.
fn intensity(game: &Game<'_>) -> f32 {
    game.native_setting(ID, "intensity")
        .and_then(|s| s.parse::<f32>().ok())
        .filter(|s| s.is_finite())
        .unwrap_or(1.0)
        .clamp(0.25, 1.0)
}
fn clips(s: &mut State) -> std::result::Result<&Clips, String> {
    s.clips.get_or_insert_with(fluid_engine::embedded).as_ref().map_err(Clone::clone)
}

pub(super) fn tick(game: &mut Game<'_>) {
    if !game.native_window() {
        return;
    }
    let on = enabled(game);
    STATE.with(|s| {
        let mut s = s.borrow_mut();
        let Some(p) = game.player.as_ref().filter(|_| on) else {
            s.loco = Locomotion::default();
            return;
        };
        let active = !(p.swimming
            || p.fall_flying
            || p.flying
            || p.is_passenger
            || p.health.dead_or_dying()
            || game.sleeping.is_some()
            || game.spectator()
            || game.screen == Screen::Dead);
        let input = Input {
            dx: p.velocity.x as f32,
            dz: p.velocity.z as f32,
            yaw: p.yaw,
            on_ground: p.ground.on_ground,
            vy: p.velocity.y as f32,
            fall_distance: p.fall_distance as f32,
            sprinting: p.sprinting,
            crouching: p.local_crouching,
            active,
        };
        let mut loco = std::mem::take(&mut s.loco);
        if let Ok(clips) = clips(&mut s) {
            loco.tick(clips, &input);
        }
        s.loco = loco;
    });
}

/// Poses the own player's body for the third-person view (`native_player_pose`).
pub(super) fn pose(game: &Game<'_>, partial: f32, pose: &mut horizon_client::mobs::native_pose::NativePose) {
    if !enabled(game) {
        return;
    }
    let strength = intensity(game);
    STATE.with(|s| {
        let mut s = s.borrow_mut();
        if s.loco.strength() <= 0.0 {
            return;
        }
        let loco = s.loco.clone();
        let Ok(clips) = clips(&mut s) else { return };
        let rig = loco.sample(clips, partial);
        for part in fluid_engine::parts(&rig) {
            let scale = |v: [f32; 3]| [v[0] * strength, v[1] * strength, v[2] * strength];
            pose.add(part.name, scale(part.position), scale(part.rotation));
        }
    });
}

pub(super) fn settings(game: &Game<'_>, buttons: &mut Vec<String>) {
    buttons.push(format!("Movement animations: {}", if enabled(game) { "ON" } else { "OFF" }));
    buttons.push(format!("Intensity: {}%", (intensity(game) * 100.0).round() as i32));
    STATE.with(|s| {
        let mut s = s.borrow_mut();
        buttons.push(match clips(&mut s) {
            Ok(c) => format!("{} animation clips loaded. Seen from the third-person view (F5).", c.len()),
            Err(e) => format!("Could not load the animation clips: {e}"),
        });
    });
}

pub(super) fn click(game: &Game<'_>, button: usize) {
    match button {
        0 => game.native_set_setting(ID, "enabled", if enabled(game) { "false" } else { "true" }),
        1 => {
            let next = if intensity(game) >= 0.99 { 0.25 } else { intensity(game) + 0.25 };
            game.native_set_setting(ID, "intensity", &format!("{next:.2}"));
        }
        _ => {}
    }
}
