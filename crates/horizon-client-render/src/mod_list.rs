//! Which of the installed Horizon mods are on (`docs/mods.md`), kept in
//! `horizon-mods.txt` beside `options.txt` as `mod.<id>:true` lines. Every
//! mod is off until the player turns it on in Options > Mods. A native mod is
//! built in, so on until turned off (`mod.<id>:false`); the launcher builds
//! only the native mods whose line is `true`.
use std::{
    collections::{BTreeMap, BTreeSet},
    fs,
    path::Path,
};

pub const FILE: &str = "horizon-mods.txt";
/// The Store source the Android modules are installed from: made by the repo owner.
pub const ZENKAI_SOURCE: &str = "Zenkai";
/// Native modules compiled into Android APKs. They are not on until the player installs them from
/// the Store's Zenkai source (`zenkai.<id>:true` in `horizon-mods.txt`).
pub const ANDROID_BUNDLED: [(&str, &str, &str); 7] = [
    (
        "zoom",
        "Zoom",
        "Hold Z to zoom; configurable in Options > Mods",
    ),
    (
        "minimap",
        "Minimap",
        "A configurable map in the screen corner",
    ),
    (
        "recipes",
        "Recipes",
        "Item recipes and uses beside the inventory",
    ),
    (
        "smartf3",
        "SmartF3",
        "Configurable modules for the F3 debug screen",
    ),
    (
        "punchy",
        "Punchy",
        "First-person hand and item animations (preview)",
    ),
    (
        "sodium_horizon",
        "Sodium",
        "Sodium-style Video Settings and non-lighting optimizations",
    ),
    (
        "iris_horizon",
        "Iris",
        "Iris-style Shader Packs and Shader Pack Settings screens (needs Sodium)",
    ),
];

/// The folder holding `.hmod` files, beside `options.txt`.
pub const DIR: &str = "horizon-mods";
/// Where the launcher installs native mods (`<id>.hnmod`), beside [`DIR`]. They run only in a
/// game the launcher built with them.
pub const NATIVE_DIR: &str = "horizon-native-mods";
/// Why a native mod installed in [`NATIVE_DIR`] doesn't run in this game.
pub const NOT_BUILT: &str = "Installed, not built into this game: build the game with it in the launcher (Settings > Native mods), then play the instance that build is in.";

/// Native mods that start off: they are in the Mods list from the first run, and the player turns
/// them on there (an unfinished preview, say). A mod's line in `horizon-mods.txt` overrides this.
/// Add a native mod's id here to ship it off by default.
pub const DEFAULT_OFF: &[&str] = &["punchy"];

/// Whether `id` ships off: a [`DEFAULT_OFF`] native mod, or on Android one of the
/// [`ANDROID_BUNDLED`] modules. Holds even before `horizon-mods.txt` exists, so a new player
/// starts with them off; installing one from the Zenkai source turns it on.
pub fn ships_off(id: &str) -> bool {
    if DEFAULT_OFF.contains(&id) {
        return true;
    }
    #[cfg(target_os = "android")]
    if ANDROID_BUNDLED.iter().any(|(b, _, _)| *b == id) {
        return true;
    }
    false
}

/// Whether `id` is one of the modules the Store's Zenkai source offers.
pub fn is_zenkai(id: &str) -> bool {
    ANDROID_BUNDLED.iter().any(|(b, _, _)| *b == id)
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct ModList {
    /// Ids of the mods the player turned on.
    pub enabled: BTreeSet<String>,
    /// Ids of the native mods the player turned off: they rest
    /// (`native_on`) until the launcher's next build leaves them out.
    pub disabled: BTreeSet<String>,
    /// Ids of the [`ANDROID_BUNDLED`] modules the player installed from the Store's Zenkai source
    /// (`zenkai.<id>:true`). One that is not installed does not run and is not listed in the game.
    pub zenkai: BTreeSet<String>,
    /// The keys the player chose for mods' keys, mod id → key name → key
    /// (`key.<id>.<name>:key.keyboard.x` lines).
    pub keys: BTreeMap<String, BTreeMap<String, String>>,
    /// What the Mods page shows, filled by the game from the mods it found.
    pub installed: Vec<Installed>,
}

/// One installed mod as the Mods page shows it.
#[derive(Clone, Debug, PartialEq)]
pub struct Installed {
    pub id: String,
    /// `Name 1.0` (the file name when the manifest is unreadable).
    pub title: String,
    pub description: String,
    /// Why it is not running, when it failed.
    pub problem: Option<String>,
    /// Loaded with `--dev-mod`: always on, reloaded when rebuilt.
    pub dev: bool,
    /// The running mod set a page of widgets (capability `ui`): its row
    /// gains a Settings button.
    pub has_settings: bool,
    /// A native mod (`game/native_hooks.rs`): built into the game, on until
    /// the player turns it off, and its settings page.
    pub native: Option<NativePage>,
}

/// A native mod's settings page: its keys as Key Binds rows, then its
/// buttons. The keys the player chooses are kept in `ModList::keys`.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct NativePage {
    /// Each key's name, label and default key (`key.keyboard.z`).
    pub keys: Vec<[String; 3]>,
    pub buttons: Vec<String>,
}

impl ModList {
    pub fn load(dir: &Path) -> Self {
        fs::read_to_string(dir.join(FILE))
            .map(|t| Self::parse(&t))
            .unwrap_or_default()
    }
    pub fn parse(text: &str) -> Self {
        let ids = |value: &str| -> BTreeSet<String> {
            text.lines()
                .filter_map(|l| l.split_once(':'))
                .filter(|(_, v)| v.trim() == value)
                .filter_map(|(k, _)| k.trim().strip_prefix("mod."))
                .map(str::to_owned)
                .collect()
        };
        let (enabled, disabled) = (ids("true"), ids("false"));
        #[allow(unused_mut)]
        let mut zenkai: BTreeSet<String> = text
            .lines()
            .filter_map(|l| l.split_once(':'))
            .filter(|(_, v)| v.trim() == "true")
            .filter_map(|(k, _)| k.trim().strip_prefix("zenkai."))
            .map(str::to_owned)
            .collect();
        // A module the player turned on before it became installable stays installed.
        #[cfg(target_os = "android")]
        for (id, _, _) in ANDROID_BUNDLED {
            if enabled.contains(id) {
                zenkai.insert(id.to_owned());
            }
        }
        let mut keys: BTreeMap<String, BTreeMap<String, String>> = BTreeMap::new();
        for (k, v) in text.lines().filter_map(|l| l.split_once(':')) {
            // Mod ids have no dots; a key's name may.
            if let Some((id, name)) = k
                .trim()
                .strip_prefix("key.")
                .and_then(|r| r.split_once('.'))
            {
                let entry = keys.entry(id.to_owned()).or_default();
                entry.insert(name.to_owned(), v.trim().to_owned());
            }
        }
        Self {
            enabled,
            disabled,
            zenkai,
            keys,
            installed: Vec::new(),
        }
    }
    pub fn serialize(&self) -> String {
        let on = self.enabled.iter().map(|id| format!("mod.{id}:true\n"));
        let off = self.disabled.iter().map(|id| format!("mod.{id}:false\n"));
        let installed = self.zenkai.iter().map(|id| format!("zenkai.{id}:true\n"));
        let keys = self.keys.iter().flat_map(|(id, keys)| {
            keys.iter()
                .map(move |(name, key)| format!("key.{id}.{name}:{key}\n"))
        });
        on.chain(off).chain(installed).chain(keys).collect()
    }
    /// Mod `id`'s chosen keys, for `Mods::set_bindings`.
    pub fn keys_of(&self, id: &str) -> Vec<(&str, &str)> {
        self.keys.get(id).map_or_else(Vec::new, |k| {
            k.iter().map(|(n, k)| (n.as_str(), k.as_str())).collect()
        })
    }
    pub fn save(&self, dir: &Path) {
        let path = dir.join(FILE);
        if self.enabled.is_empty()
            && self.disabled.is_empty()
            && self.zenkai.is_empty()
            && self.keys.is_empty()
            && !path.exists()
        {
            return;
        }
        let _ = fs::write(path, self.serialize());
    }
    /// Whether native mod `id` runs: on until the player turns it off, unless it ships in
    /// [`DEFAULT_OFF`] and the player has not turned it on.
    pub fn native_on(&self, id: &str) -> bool {
        #[cfg(target_os = "android")]
        if is_zenkai(id) && !self.zenkai.contains(id) {
            return false;
        }
        if self.enabled.contains(id) {
            return true;
        }
        !self.disabled.contains(id) && !ships_off(id)
    }
    /// Installs Zenkai module `id` and turns it on.
    pub fn install_zenkai(&mut self, id: &str) {
        self.zenkai.insert(id.to_owned());
        self.disabled.remove(id);
        self.enabled.insert(id.to_owned());
    }
    /// Uninstalls Zenkai module `id`: it stops running and leaves the game's Mods list.
    pub fn uninstall_zenkai(&mut self, id: &str) {
        self.zenkai.remove(id);
        self.enabled.remove(id);
        self.disabled.remove(id);
    }
    pub fn is_on(&self, i: usize) -> bool {
        self.installed.get(i).is_some_and(|m| {
            m.dev
                || if m.native.is_some() {
                    self.native_on(&m.id)
                } else {
                    self.enabled.contains(&m.id)
                }
        })
    }
    /// Turns installed mod `i` on or off (a dev mod stays on). A native mod's
    /// line says `false` when off, so that the launcher's next build leaves
    /// it out, and `true` when on again.
    pub fn toggle(&mut self, i: usize) {
        let Some(m) = self.installed.get(i).filter(|m| !m.dev) else {
            return;
        };
        if m.native.is_some() {
            if self.native_on(&m.id) {
                self.enabled.remove(&m.id);
                self.disabled.insert(m.id.clone());
            } else {
                self.disabled.remove(&m.id);
                self.enabled.insert(m.id.clone());
            }
        } else if !self.enabled.remove(&m.id) {
            self.enabled.insert(m.id.clone());
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_default_off_native_mod_starts_off_until_turned_on() {
        let mut list = ModList::default();
        assert!(!list.native_on("punchy"));
        assert!(list.native_on("other"));
        // On Android Punchy is a Zenkai mod: it is listed only once installed.
        list.zenkai.insert("punchy".into());
        list.installed.push(Installed {
            id: "punchy".into(),
            title: "Punchy".into(),
            description: String::new(),
            problem: None,
            dev: false,
            has_settings: false,
            native: Some(NativePage::default()),
        });
        list.toggle(0);
        assert!(list.is_on(0));
        list.toggle(0);
        assert!(!list.is_on(0));
    }
    #[cfg(target_os = "android")]
    #[test]
    fn zenkai_android_mods_run_only_once_installed() {
        let fresh = ModList::default();
        for (id, _, _) in ANDROID_BUNDLED {
            assert!(!fresh.native_on(id));
        }
        let mut list = ModList::default();
        list.install_zenkai("zoom");
        let back = ModList::parse(&list.serialize());
        assert!(back.native_on("zoom"));
        assert!(!back.native_on("minimap"));
        // Turned off in the game: still installed, not running.
        let mut off = back;
        off.enabled.remove("zoom");
        off.disabled.insert("zoom".into());
        let off = ModList::parse(&off.serialize());
        assert!(off.zenkai.contains("zoom") && !off.native_on("zoom"));
        // A module turned on before it was installable counts as installed.
        assert!(
            ModList::parse("mod.recipes:true\n")
                .zenkai
                .contains("recipes")
        );
    }
    #[test]
    fn zenkai_install_round_trips_and_uninstall_forgets() {
        let mut list = ModList::default();
        list.install_zenkai("zoom");
        assert!(list.zenkai.contains("zoom") && list.enabled.contains("zoom"));
        let back = ModList::parse(&list.serialize());
        assert!(back.zenkai.contains("zoom"));
        let mut gone = back;
        gone.uninstall_zenkai("zoom");
        assert!(!gone.zenkai.contains("zoom"));
        assert!(!gone.enabled.contains("zoom") && !gone.disabled.contains("zoom"));
        assert!(!gone.serialize().contains("zenkai.zoom"));
    }
    #[test]
    fn round_trips_and_ignores_the_rest() {
        let list = ModList::parse("mod.coords_hud:true\nmod.other:false\nzoom:true\n");
        assert_eq!(list.enabled.iter().collect::<Vec<_>>(), ["coords_hud"]);
        assert_eq!(ModList::parse(&list.serialize()), list);
        assert_eq!(ModList::default().serialize(), "");
    }

    #[test]
    fn keeps_the_keys_players_chose() {
        let text = "mod.zoom:true\nkey.zoom.zoom.in:key.mouse.4\nkey.zoom.out:key.keyboard.x\n";
        let list = ModList::parse(text);
        assert_eq!(
            list.keys_of("zoom"),
            [("out", "key.keyboard.x"), ("zoom.in", "key.mouse.4")]
        );
        assert!(list.keys_of("other").is_empty());
        assert_eq!(ModList::parse(&list.serialize()), list);
    }

    #[test]
    fn toggles_installed_and_native_mods_but_not_dev_ones() {
        let installed = |id: &str, dev| Installed {
            id: id.into(),
            title: id.into(),
            description: String::new(),
            problem: None,
            dev,
            has_settings: false,
            native: None,
        };
        let mut list = ModList {
            installed: vec![installed("a", false), installed("b", true)],
            ..Default::default()
        };
        assert!(!list.is_on(0) && list.is_on(1));
        list.toggle(0);
        list.toggle(1);
        assert!(list.is_on(0) && list.is_on(1));
        assert_eq!(list.serialize(), "mod.a:true\n");
        list.toggle(0);
        assert!(!list.is_on(0));
        list.installed.push(Installed {
            native: Some(NativePage::default()),
            ..installed("c", false)
        });
        // On until turned off; off, the launcher leaves it out.
        assert!(list.is_on(2));
        list.toggle(2);
        assert!(!list.is_on(2));
        assert_eq!(list.serialize(), "mod.c:false\n");
        assert_eq!(ModList::parse(&list.serialize()).disabled, list.disabled);
        list.toggle(2);
        assert!(list.is_on(2));
        assert_eq!(list.serialize(), "mod.c:true\n");
    }
}
