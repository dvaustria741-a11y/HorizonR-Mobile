//! Module registry and manager.

pub mod combat;
pub mod misc;
pub mod movement;
pub mod render;

use std::collections::HashMap;

// ── Module descriptor ─────────────────────────────────────────────────────────

#[derive(Clone, Debug)]
pub struct Module {
    pub name:     String,
    pub category: ModuleCategory,
    pub enabled:  bool,
    pub keybind:  Option<String>,
    /// float settings: name → value
    pub floats:   HashMap<String, f32>,
    /// bool settings: name → value
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

    pub fn with_key(mut self, key: &str) -> Self {
        self.keybind = Some(key.into()); self
    }

    pub fn with_float(mut self, k: &str, v: f32) -> Self {
        self.floats.insert(k.into(), v); self
    }

    pub fn with_bool(mut self, k: &str, v: bool) -> Self {
        self.bools.insert(k.into(), v); self
    }

    /// One-line tag shown in the HUD (e.g. "4.5 | 10cps").
    pub fn tag(&self) -> String {
        match self.name.as_str() {
            "killaura"  => format!(
                "{}bps | {}cps",
                self.floats.get("range").copied().unwrap_or(4.5),
                self.floats.get("cps").copied().unwrap_or(10.0) as u32,
            ),
            "speed"     => format!("x{}", self.floats.get("multiplier").copied().unwrap_or(1.3)),
            "aimassist" => format!("{}r", self.floats.get("range").copied().unwrap_or(8.0)),
            "scaffold"  => "tower".into(),
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

// ── Module manager ────────────────────────────────────────────────────────────

pub struct ModuleManager {
    modules: Vec<Module>,
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

    pub fn all(&self) -> impl Iterator<Item = &Module> {
        self.modules.iter()
    }

    pub fn all_mut(&mut self) -> impl Iterator<Item = &mut Module> {
        self.modules.iter_mut()
    }

    pub fn find_mut(&mut self, name: &str) -> Option<&mut Module> {
        self.modules.iter_mut().find(|m| m.name.eq_ignore_ascii_case(name))
    }

    pub fn is_on(&self, name: &str) -> bool {
        self.modules.iter()
            .find(|m| m.name.eq_ignore_ascii_case(name))
            .map(|m| m.enabled)
            .unwrap_or(false)
    }

    pub fn float_setting(&self, module: &str, key: &str) -> Option<f32> {
        self.modules.iter()
            .find(|m| m.name.eq_ignore_ascii_case(module))?
            .floats.get(key).copied()
    }

    pub fn set_float(&mut self, module: &str, key: &str, val: f32) -> bool {
        if let Some(m) = self.find_mut(module) {
            if m.floats.contains_key(key) {
                m.floats.insert(key.into(), val);
                return true;
            }
        }
        false
    }
}
