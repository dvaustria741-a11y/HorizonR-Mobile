pub mod combat;
pub mod misc;
pub mod movement;
pub mod render;

use std::collections::HashMap;

#[derive(Clone, Debug)]
pub struct Module {
    pub name:     String,
    pub category: ModuleCategory,
    pub enabled:  bool,
    pub keybind:  Option<String>,
    pub floats:   HashMap<String, f32>,
    pub bools:    HashMap<String, bool>,
}

impl Module {
    pub fn new(name: &str, cat: ModuleCategory) -> Self {
        Self {
            name: name.into(),
            category: cat,
            enabled: false,
            keybind: None,
            floats: HashMap::new(),
            bools:  HashMap::new(),
        }
    }
    pub fn with_key(mut self, k: &str)         -> Self { self.keybind = Some(k.into()); self }
    pub fn with_float(mut self, k: &str, v: f32) -> Self { self.floats.insert(k.into(), v); self }
    pub fn with_bool(mut self, k: &str, v: bool) -> Self { self.bools.insert(k.into(), v); self }

    pub fn tag(&self) -> String {
        match self.name.as_str() {
            "killaura"  => format!("{}r {}cps",
                self.floats.get("range").copied().unwrap_or(4.5),
                self.floats.get("cps").copied().unwrap_or(10.0) as u32),
            "speed"     => format!("x{:.1}", self.floats.get("multiplier").copied().unwrap_or(1.3)),
            "aimassist" => format!("{}r", self.floats.get("range").copied().unwrap_or(8.0)),
            "reach"     => format!("{:.1}", self.floats.get("max").copied().unwrap_or(3.5)),
            "velocity"  => format!("{}%h", (self.floats.get("horizontal").copied().unwrap_or(0.0) * 100.0) as i32),
            "timer"     => format!("x{:.1}", self.floats.get("speed").copied().unwrap_or(1.0)),
            "scaffold"  => if self.bools.get("tower").copied().unwrap_or(false) { "tower".into() } else { String::new() },
            _           => String::new(),
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ModuleCategory {
    Combat,
    Movement,
    Render,
    Misc,
}

// pub so lib.rs can do index access (fixes E0505 / E0597)
pub struct ModuleManager {
    pub modules: Vec<Module>,
}

impl ModuleManager {
    pub fn new() -> Self {
        let mut mm = Self { modules: Vec::new() };
        combat::register(&mut mm);
        movement::register(&mut mm);
        render::register(&mut mm);
        misc::register(&mut mm);
        mm
    }
    pub fn push(&mut self, m: Module) { self.modules.push(m); }

    pub fn is_on(&self, name: &str) -> bool {
        self.modules.iter()
            .find(|m| m.name.eq_ignore_ascii_case(name))
            .map_or(false, |m| m.enabled)
    }
    pub fn float_setting(&self, module: &str, key: &str) -> Option<f32> {
        self.modules.iter()
            .find(|m| m.name.eq_ignore_ascii_case(module))?
            .floats.get(key).copied()
    }
}
