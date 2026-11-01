//! Misc modules: AutoEat, FastUse, Timer, Freecam, etc.

use super::{Module, ModuleCategory::Misc, ModuleManager};

pub fn register(mm: &mut ModuleManager) {
    mm.push(
        Module::new("autoeat", Misc)
            .with_float("food_threshold", 16.0)
            .with_bool("eat_offhand", false),
    );
    mm.push(
        Module::new("fastuse", Misc),
    );
    mm.push(
        Module::new("timer", Misc)
            .with_float("speed", 1.0),
    );
    mm.push(
        Module::new("freecam", Misc)
            .with_float("speed", 1.0),
    );
    mm.push(
        Module::new("fastplace", Misc)
            .with_float("cps", 20.0),
    );
    mm.push(
        Module::new("antiafk", Misc)
            .with_bool("swing", true)
            .with_bool("sneak", false),
    );
    mm.push(
        Module::new("nointeract", Misc),
    );
    mm.push(
        Module::new("autopot", Misc)
            .with_float("health_threshold", 15.0),
    );
    mm.push(
        Module::new("autotool", Misc),
    );
}
