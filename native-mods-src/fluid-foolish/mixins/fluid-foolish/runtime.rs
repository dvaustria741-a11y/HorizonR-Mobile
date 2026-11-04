// Horizon glue for Fluid & Foolish's movement animations (engine/ has the clips and the logic).
use super::*;
use fluid_engine::{ActionInput, Clips, Compat, Input, Locomotion};
use std::cell::RefCell;

pub(super) const ID: &str = "fluid-foolish";
pub(super) const TITLE: &str = "Fluid & Foolish — movement";
thread_local! { static STATE: RefCell<State> = RefCell::new(State::default()); }
#[derive(Default)]
struct State {
    clips: Option<std::result::Result<Clips, String>>,
    compat: Option<std::result::Result<Compat, String>>,
    loco: Locomotion,
    last_hurt: i32,
}

/// The pack's emotes: id, name, clip and default key (`compat/develop.json` emotes).
pub(super) const EMOTES: [(&str, &str, &str, &str); 4] = [
    ("hello", "Emote: Hello", "hello_nleg", "key.keyboard.h"),
    ("hello_b", "Emote: Hello with both arms", "hello_nlegB", "key.keyboard.j"),
    ("cool_emote", "Emote: Static", "cool_emote", "key.keyboard.k"),
    ("shuuba_dance", "Emote: Shuuba dance", "shuuba_dance", "key.keyboard.l"),
];
/// The key bindings for the Mods screen: [id, name, default key].
pub(super) fn emote_keys() -> Vec<[String; 3]> {
    EMOTES.iter().map(|(id, name, _, key)| [(*id).into(), (*name).into(), (*key).into()]).collect()
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
fn compat(s: &mut State) -> std::result::Result<&Compat, String> {
    s.compat.get_or_insert_with(fluid_engine::embedded_compat).as_ref().map_err(Clone::clone)
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
        let active = !(p.health.dead_or_dying() || game.sleeping.is_some() || game.spectator() || game.screen == Screen::Dead);
        let seated = game.riding.is_some_and(|id| {
            game.boats.iter().any(|b| b.id == id) || game.carts.iter().any(|c| c.id == id)
        });
        // The hurt animation timer rises when a hit lands; `hurt_dir` is 90 for a hit from the front.
        let hurt_time = p.health.hurt_animation_time;
        let hurt_from = (hurt_time > s.last_hurt).then(|| p.health.hurt_dir - 90.0);
        s.last_hurt = hurt_time;
        let velocity = p.velocity;
        let input = Input {
            dx: velocity.x as f32,
            dz: velocity.z as f32,
            yaw: p.yaw,
            on_ground: p.ground.on_ground,
            vy: velocity.y as f32,
            fall_distance: p.fall_distance as f32,
            sprinting: p.sprinting,
            crouching: p.local_crouching,
            swimming: p.swimming,
            underwater: p.air < 300,
            crawling: p.pose == horizon_client::pose::Pose::Swimming && !p.swimming,
            flying: p.flying && !p.ground.on_ground,
            gliding: p.fall_flying,
            seated,
            speed: (velocity.x * velocity.x + velocity.y * velocity.y + velocity.z * velocity.z).sqrt() as f32,
            hurt_from,
            active,
        };
        let id_of = |stack: Option<&ItemStack>| stack.map(|s| s.item.to_string()).unwrap_or_default();
        let actions = ActionInput {
            held: id_of(game.stack_in(game.selected)),
            using: game.using.map(|(slot, ticks)| (id_of(game.stack_in(slot)), ticks)),
            swings: game.swings_started,
            mining: game.mining.is_some(),
            use_held: game.right,
            offhand: game.offhand.is_some(),
            armor: std::array::from_fn(|i| id_of(game.armor[i].as_ref())),
        };
        let mut loco = std::mem::take(&mut s.loco);
        let compat = compat(&mut s).ok().cloned();
        if let (Ok(clips), Some(compat)) = (clips(&mut s), compat) {
            loco.tick(clips, &input);
            loco.tick_actions(clips, &compat, &actions);
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
        let compat = compat(&mut s).ok().cloned().unwrap_or_default();
        let Ok(clips) = clips(&mut s) else { return };
        let rig = loco.sample_with(clips, &compat, partial);
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

/// An emote key went down.
pub(super) fn key(game: &mut Game<'_>, key: &str, pressed: bool) {
    if !pressed || !game.native_playing() || !enabled(game) {
        return;
    }
    for (id, _, clip, default) in EMOTES {
        if key == game.native_mod_key(ID, id, default) {
            STATE.with(|s| s.borrow_mut().loco.play_emote(clip));
        }
    }
}
