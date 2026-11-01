//! Movement modules: Speed, Flight, Scaffold, Sprint, NoFall, etc.

use super::{Module, ModuleCategory::Movement, ModuleManager};

pub fn register(mm: &mut ModuleManager) {
    mm.push(
        Module::new("speed", Movement)
            .with_key("key.keyboard.v")
            .with_float("multiplier", 1.3)
            .with_bool("bhop", true),
    );
    mm.push(
        Module::new("flight", Movement)
            .with_key("key.keyboard.f")
            .with_float("speed", 1.0)
            .with_bool("creative_bypass", false),
    );
    mm.push(
        Module::new("scaffold", Movement)
            .with_key("key.keyboard.g")
            .with_bool("tower", false)
            .with_bool("safe_walk", true),
    );
    mm.push(
        Module::new("autosprint", Movement)
            .with_bool("omnidirectional", false),
    );
    mm.push(
        Module::new("nofall", Movement)
            .with_key("key.keyboard.n"),
    );
    mm.push(
        Module::new("noslow", Movement),
    );
    mm.push(
        Module::new("longjump", Movement)
            .with_float("boost", 1.5),
    );
    mm.push(
        Module::new("fastclimb", Movement),
    );
    mm.push(
        Module::new("antilevitation", Movement),
    );
    mm.push(
        Module::new("stepplus", Movement)
            .with_float("height", 1.0),
    );
}
