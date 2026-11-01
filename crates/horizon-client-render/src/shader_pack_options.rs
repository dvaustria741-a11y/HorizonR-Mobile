//! The options of the picked shader pack, as the Shader Pack Settings page lists them: a classic
//! pack's `#define` switches and value lists (`horizon_shaders::classic_options`) and a Horizon
//! pack's manifest options. Every option is a list of values to step through. What the player
//! chose is saved beside the packs as `shaderpacks/<pack file>.txt`, `NAME=value` per line, and
//! the game reads it back when it builds the pack.

use std::collections::BTreeMap;
use std::path::Path;

use crate::shader_pack_list::{Entry as PackEntry, Kind};

/// Most values a number option steps through.
const MAX_STEPS: usize = 33;

#[derive(Clone, Debug, PartialEq)]
pub struct Item {
    /// The name the options file keeps it under.
    pub id: String,
    pub label: String,
    /// The values, as the options file writes them.
    pub values: Vec<String>,
    /// What the button shows for each value.
    pub shown: Vec<String>,
    pub default: usize,
    pub current: usize,
    /// Shown as a slider over `values` instead of a button that steps through them.
    pub slider: bool,
}

/// What a screen of the settings page holds.
#[derive(Clone, Debug, PartialEq)]
pub enum Entry {
    /// `Options.items[i]`.
    Option(usize),
    /// A button to `Options.screens[i]`.
    Screen(usize),
    Space,
    /// The button that picks one of the pack's profiles.
    Profile,
}

/// A preset of the pack: option values to set, as (item, value) indexes.
#[derive(Clone, Debug, PartialEq)]
pub struct Profile {
    pub title: String,
    pub settings: Vec<(usize, usize)>,
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct Screen {
    /// Empty for the main screen, which the page titles itself.
    pub title: String,
    pub entries: Vec<Entry>,
}

/// The picked pack's options and how its screens arrange them; screen 0 is the main one.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Options {
    pub items: Vec<Item>,
    pub screens: Vec<Screen>,
    pub profiles: Vec<Profile>,
}

impl Options {
    /// The profile the values match, `None` for custom ones.
    pub fn current_profile(&self) -> Option<usize> {
        self.profiles.iter().position(|p| {
            !p.settings.is_empty()
                && p.settings
                    .iter()
                    .all(|&(i, v)| self.items.get(i).is_some_and(|item| item.current == v))
        })
    }

    /// Sets the values of the next profile (the first when the values are custom).
    pub fn next_profile(&mut self) {
        let n = self.profiles.len();
        if n == 0 {
            return;
        }
        let next = self.current_profile().map_or(0, |i| (i + 1) % n);
        for &(i, v) in &self.profiles[next].settings {
            if let Some(item) = self.items.get_mut(i) {
                item.current = v;
            }
        }
    }
}

impl Item {
    /// The slider's position, 0 to 1.
    pub fn position(&self) -> f32 {
        let last = self.values.len().saturating_sub(1);
        if last == 0 {
            0.0
        } else {
            self.current as f32 / last as f32
        }
    }

    /// Moves to the value nearest a slider position, 0 to 1.
    pub fn set_position(&mut self, v: f32) {
        let last = self.values.len().saturating_sub(1);
        self.current = (v.clamp(0.0, 1.0) * last as f32).round() as usize;
    }

    /// The next value (or the one before, going back), wrapping around.
    pub fn cycle(&mut self, back: bool) {
        let n = self.values.len();
        if n > 1 {
            self.current = if back {
                (self.current + n - 1) % n
            } else {
                (self.current + 1) % n
            };
        }
    }

    pub fn value_text(&self) -> &str {
        self.shown.get(self.current).map_or("", String::as_str)
    }
}

fn number(v: f64) -> String {
    let rounded = (v * 10_000.0).round() / 10_000.0;
    format!("{rounded}")
}

/// `min..=max` in at most [`MAX_STEPS`] values that include `default`.
fn numbers(min: f64, max: f64, step: f64, default: f64) -> Vec<f64> {
    let count = if step > 0.0 {
        ((max - min) / step).round() as usize + 1
    } else {
        MAX_STEPS
    };
    let mut values: Vec<f64> = if count <= MAX_STEPS {
        (0..count).map(|i| min + step * i as f64).collect()
    } else {
        (0..MAX_STEPS)
            .map(|i| min + (max - min) * i as f64 / (MAX_STEPS - 1) as f64)
            .collect()
    };
    if !values.iter().any(|v| (v - default).abs() < 1e-4) {
        values.push(default);
        values.sort_by(f64::total_cmp);
    }
    values
}

fn classic(dir: &Path, entry: &PackEntry, locale: &str) -> Options {
    let Ok(pack) = horizon_shaders::Pack::open(&dir.join(&entry.file)) else {
        return Options::default();
    };
    let chosen = horizon_shaders::read_overrides(dir, &entry.file);
    let layout = horizon_shaders::classic_layout(&pack, locale);
    let items: Vec<Item> = horizon_shaders::classic_options(&pack, locale)
        .into_iter()
        .map(|o| {
            let switch = o.values.len() == 2 && o.values[0] == "false" && o.values[1] == "true";
            let shown = if switch {
                vec!["OFF".to_owned(), "ON".to_owned()]
            } else {
                o.values.clone()
            };
            let current = chosen
                .get(&o.name)
                .and_then(|v| o.values.iter().position(|x| x == v))
                .unwrap_or(o.default);
            let slider = !switch && o.values.len() > 2 && layout.sliders.contains(&o.name);
            Item {
                id: o.name,
                label: o.label,
                values: o.values,
                shown,
                default: o.default,
                current,
                slider,
            }
        })
        .collect();
    let screens = screens(&layout, &items);
    let profiles = profiles(&layout, &items);
    Options {
        items,
        screens,
        profiles,
    }
}

/// The pack's profiles, over the options that exist.
fn profiles(layout: &horizon_shaders::Layout, items: &[Item]) -> Vec<Profile> {
    layout
        .profiles
        .iter()
        .map(|p| Profile {
            title: p.title.clone(),
            settings: p
                .settings
                .iter()
                .filter_map(|(name, value)| {
                    let i = items.iter().position(|item| &item.id == name)?;
                    let v = items[i].values.iter().position(|x| x == value)?;
                    Some((i, v))
                })
                .collect(),
        })
        .collect()
}

/// The screens of the page: the pack's own layout, or one screen with every option.
fn screens(layout: &horizon_shaders::Layout, items: &[Item]) -> Vec<Screen> {
    use horizon_shaders::LayoutEntry;
    let by_name: BTreeMap<&str, usize> = items
        .iter()
        .enumerate()
        .map(|(i, item)| (item.id.as_str(), i))
        .collect();
    let all = || (0..items.len()).map(Entry::Option).collect::<Vec<_>>();
    let def = |id: &str| layout.screens.iter().find(|s| s.id == id);
    if def("").is_none() {
        return vec![Screen {
            title: String::new(),
            entries: all(),
        }];
    }
    // Options no screen lists, which a `*` shows.
    let listed: std::collections::BTreeSet<&str> = layout
        .screens
        .iter()
        .flat_map(|s| &s.entries)
        .filter_map(|e| match e {
            LayoutEntry::Option(n) => Some(n.as_str()),
            _ => None,
        })
        .collect();
    let rest: Vec<usize> = items
        .iter()
        .enumerate()
        .filter(|(_, item)| !listed.contains(item.id.as_str()))
        .map(|(i, _)| i)
        .collect();
    let mut ids = vec![String::new()];
    let mut screens = Vec::new();
    let mut k = 0;
    while k < ids.len() {
        let id = ids[k].clone();
        let mut entries = Vec::new();
        if let Some(d) = def(&id) {
            for e in &d.entries {
                match e {
                    LayoutEntry::Option(n) => {
                        if let Some(&i) = by_name.get(n.as_str()) {
                            entries.push(Entry::Option(i));
                        }
                    }
                    LayoutEntry::Screen(n) => {
                        let at = ids.iter().position(|x| x == n).unwrap_or_else(|| {
                            ids.push(n.clone());
                            ids.len() - 1
                        });
                        entries.push(Entry::Screen(at));
                    }
                    LayoutEntry::Space => entries.push(Entry::Space),
                    LayoutEntry::Profile if !layout.profiles.is_empty() => {
                        entries.push(Entry::Profile);
                    }
                    LayoutEntry::Profile => {}
                    LayoutEntry::Rest => entries.extend(rest.iter().map(|&i| Entry::Option(i))),
                }
            }
        }
        screens.push(Screen {
            title: def(&id).map_or_else(|| id.clone(), |d| d.title.clone()),
            entries,
        });
        k += 1;
    }
    screens
}

fn horizon(dir: &Path, entry: &PackEntry) -> Options {
    use horizon_shader_api::OptionKind;
    let path = dir.join(&entry.file);
    let manifest = if path.is_dir() {
        std::fs::read_to_string(path.join(horizon_shader_api::MANIFEST))
            .ok()
            .and_then(|t| horizon_shader_api::Manifest::parse(&t).ok())
    } else {
        horizon_shader_api::Pack::read(&path)
            .ok()
            .map(|p| p.manifest)
    };
    let Some(manifest) = manifest else {
        return Options::default();
    };
    let chosen = horizon_shaders::read_overrides(dir, &entry.file);
    let items: Vec<Item> = manifest
        .options
        .iter()
        .map(|o| {
            let (values, shown, default): (Vec<String>, Vec<String>, usize) = match &o.kind {
                OptionKind::Bool { default } => (
                    vec!["false".into(), "true".into()],
                    vec!["OFF".into(), "ON".into()],
                    usize::from(*default),
                ),
                OptionKind::Choice { values, default } => {
                    (values.clone(), values.clone(), *default as usize)
                }
                OptionKind::Int { default, min, max } => {
                    let list = numbers(f64::from(*min), f64::from(*max), 1.0, f64::from(*default));
                    let values: Vec<String> = list.iter().map(|v| number(*v)).collect();
                    let at = list
                        .iter()
                        .position(|v| (v - f64::from(*default)).abs() < 1e-4)
                        .unwrap_or(0);
                    (values.clone(), values, at)
                }
                OptionKind::Float {
                    default,
                    min,
                    max,
                    step,
                } => {
                    let list = numbers(
                        f64::from(*min),
                        f64::from(*max),
                        f64::from(*step),
                        f64::from(*default),
                    );
                    let values: Vec<String> = list.iter().map(|v| number(*v)).collect();
                    let at = list
                        .iter()
                        .position(|v| (v - f64::from(*default)).abs() < 1e-4)
                        .unwrap_or(0);
                    (values.clone(), values, at)
                }
            };
            let current = chosen
                .get(&o.id)
                .and_then(|text| {
                    let want = o.kind.constant_from(text)?;
                    values.iter().position(|v| {
                        o.kind
                            .constant_from(v)
                            .is_some_and(|c| (c - want).abs() < 1e-4)
                    })
                })
                .unwrap_or(default);
            Item {
                id: o.id.clone(),
                label: if o.name.trim().is_empty() {
                    o.id.clone()
                } else {
                    o.name.clone()
                },
                slider: matches!(o.kind, OptionKind::Int { .. } | OptionKind::Float { .. })
                    && values.len() > 2,
                values,
                shown,
                default,
                current,
            }
        })
        .collect();
    let screens = vec![Screen {
        title: String::new(),
        entries: (0..items.len()).map(Entry::Option).collect(),
    }];
    Options {
        items,
        screens,
        profiles: Vec::new(),
    }
}

/// The options of `entry`, with the values saved for it.
pub fn load(dir: &Path, entry: &PackEntry, locale: &str) -> Options {
    match entry.kind {
        Kind::Classic => classic(dir, entry, locale),
        Kind::Horizon => horizon(dir, entry),
    }
}

/// Saves the values that differ from the pack's own; all at their defaults removes the file.
pub fn save(dir: &Path, entry: &PackEntry, items: &[Item]) {
    let chosen: BTreeMap<String, String> = items
        .iter()
        .filter(|i| i.current != i.default)
        .filter_map(|i| Some((i.id.clone(), i.values.get(i.current)?.clone())))
        .collect();
    if let Err(e) = horizon_shaders::write_overrides(dir, &entry.file, &chosen) {
        log::warn!("shader pack {}: options not saved: {e}", entry.file);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cycling_wraps_both_ways() {
        let mut i = Item {
            id: "A".into(),
            label: "A".into(),
            values: vec!["false".into(), "true".into(), "x".into()],
            shown: vec!["OFF".into(), "ON".into(), "x".into()],
            default: 0,
            current: 0,
            slider: false,
        };
        i.cycle(true);
        assert_eq!(i.value_text(), "x");
        i.cycle(false);
        assert_eq!(i.value_text(), "OFF");
    }

    #[test]
    fn numbers_keep_the_default_and_stay_short() {
        let v = numbers(0.0, 1.0, 0.25, 0.3);
        assert!(v.iter().any(|x| (x - 0.3).abs() < 1e-9));
        assert!(numbers(0.0, 1000.0, 1.0, 500.0).len() <= MAX_STEPS + 1);
    }

    fn item(id: &str) -> Item {
        Item {
            id: id.into(),
            label: id.into(),
            values: vec!["a".into(), "b".into(), "c".into()],
            shown: vec!["a".into(), "b".into(), "c".into()],
            default: 0,
            current: 0,
            slider: false,
        }
    }

    #[test]
    fn screens_follow_the_pack_layout_and_star_takes_the_rest() {
        use horizon_shaders::{Layout, LayoutEntry, ScreenDef};
        let items = [item("A"), item("B"), item("C"), item("D")];
        let layout = Layout {
            screens: vec![
                ScreenDef {
                    id: String::new(),
                    title: String::new(),
                    entries: vec![
                        LayoutEntry::Option("B".into()),
                        LayoutEntry::Space,
                        LayoutEntry::Screen("MORE".into()),
                        LayoutEntry::Rest,
                    ],
                },
                ScreenDef {
                    id: "MORE".into(),
                    title: "More".into(),
                    entries: vec![LayoutEntry::Option("A".into())],
                },
            ],
            ..Layout::default()
        };
        let screens = screens(&layout, &items);
        assert_eq!(screens.len(), 2);
        assert_eq!(
            screens[0].entries,
            [
                Entry::Option(1),
                Entry::Space,
                Entry::Screen(1),
                Entry::Option(2),
                Entry::Option(3)
            ]
        );
        assert_eq!(screens[1].title, "More");
        assert_eq!(screens[1].entries, [Entry::Option(0)]);
        // No layout: one screen with everything.
        assert_eq!(
            super::screens(&Layout::default(), &items)[0].entries.len(),
            4
        );
    }

    #[test]
    fn a_slider_maps_positions_to_values() {
        let mut i = item("A");
        i.slider = true;
        i.set_position(1.0);
        assert_eq!(i.current, 2);
        i.set_position(0.4);
        assert_eq!(i.current, 1);
        assert!((i.position() - 0.5).abs() < 1e-6);
    }

    #[test]
    fn profiles_cycle_and_custom_values_match_none() {
        let mut o = Options {
            items: vec![item("A"), item("B")],
            screens: Vec::new(),
            profiles: vec![
                Profile {
                    title: "LOW".into(),
                    settings: vec![(0, 0), (1, 0)],
                },
                Profile {
                    title: "HIGH".into(),
                    settings: vec![(0, 2), (1, 2)],
                },
            ],
        };
        assert_eq!(o.current_profile(), Some(0));
        o.next_profile();
        assert_eq!(o.current_profile(), Some(1));
        o.next_profile();
        assert_eq!(o.current_profile(), Some(0));
        o.items[1].current = 1;
        assert_eq!(o.current_profile(), None);
        o.next_profile();
        assert_eq!(o.current_profile(), Some(0));
    }
}
