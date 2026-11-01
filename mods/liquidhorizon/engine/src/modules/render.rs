//! Render modules: ESP, Tracers, Chams, FullBright, Xray, etc.

use super::{Module, ModuleCategory::Render, ModuleManager};

pub fn register(mm: &mut ModuleManager) {
    mm.push(
        Module::new("esp", Render)
            .with_key("key.keyboard.x")
            .with_float("line_width", 1.5)
            .with_bool("health_bar", true)
            .with_bool("name_tag", true)
            .with_bool("box", true),
    );
    mm.push(
        Module::new("tracers", Render)
            .with_bool("rainbow", false),
    );
    mm.push(
        Module::new("fullbright", Render)
            .with_key("key.keyboard.b")
            .with_float("gamma", 10.0),
    );
    mm.push(
        Module::new("iteminfo", Render),
    );
    mm.push(
        Module::new("nametags", Render),
    );
    mm.push(
        Module::new("chams", Render)
            .with_bool("player", true)
            .with_bool("mob", false),
    );
    mm.push(
        Module::new("breadcrumbs", Render),
    );
    mm.push(
        Module::new("holespy", Render),
    );
}
