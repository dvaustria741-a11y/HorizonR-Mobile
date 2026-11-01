//! Combat modules: KillAura, AimAssist, Criticals, Reach, Velocity.

use super::{Module, ModuleCategory::Combat, ModuleManager};

pub fn register(mm: &mut ModuleManager) {
    mm.push(
        Module::new("killaura", Combat)
            .with_key("key.keyboard.r")
            .with_float("range", 4.5)
            .with_float("cps", 10.0)
            .with_bool("swing", true)
            .with_bool("rotate", true),
    );
    mm.push(
        Module::new("aimassist", Combat)
            .with_key("key.keyboard.z")
            .with_float("range", 8.0)
            .with_float("speed", 2.0)
            .with_float("fov", 90.0),
    );
    mm.push(
        Module::new("criticals", Combat)
            .with_key("key.keyboard.c"),
    );
    mm.push(
        Module::new("velocity", Combat)
            .with_float("horizontal", 0.0)
            .with_float("vertical", 0.0)
            .with_float("chance", 100.0),
    );
    mm.push(
        Module::new("reach", Combat)
            .with_float("min", 3.0)
            .with_float("max", 3.5),
    );
    mm.push(
        Module::new("autoclicker", Combat)
            .with_float("min_cps", 9.0)
            .with_float("max_cps", 14.0)
            .with_bool("right_click", false),
    );
    mm.push(
        Module::new("triggerbot", Combat)
            .with_float("delay_ms", 50.0),
    );
    mm.push(
        Module::new("keepalive", Combat),
    );
}
