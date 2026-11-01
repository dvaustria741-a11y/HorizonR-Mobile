//! The options of the picked shader pack, as the Shader Pack Settings page lists them: a classic
//! pack's `#define` switches and value lists (`horizon_shaders::classic_options`) and a Horizon
//! pack's manifest options. Every option is a list of values to step through. What the player
//! chose is saved beside the packs as `shaderpacks/<pack file>.txt`, `NAME=value` per line, and
//! the game reads it back when it builds the pack.

use std::collections::BTreeMap;
use std::path::Path;

use crate::shader_pack_list::{Entry, Kind};

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
}

impl Item {
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

fn classic(dir: &Path, entry: &Entry, locale: &str) -> Vec<Item> {
    let Ok(pack) = horizon_shaders::Pack::open(&dir.join(&entry.file)) else {
        return Vec::new();
    };
    let chosen = horizon_shaders::read_overrides(dir, &entry.file);
    horizon_shaders::classic_options(&pack, locale)
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
            Item {
                id: o.name,
                label: o.label,
                values: o.values,
                shown,
                default: o.default,
                current,
            }
        })
        .collect()
}

fn horizon(dir: &Path, entry: &Entry) -> Vec<Item> {
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
        return Vec::new();
    };
    let chosen = horizon_shaders::read_overrides(dir, &entry.file);
    manifest
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
                values,
                shown,
                default,
                current,
            }
        })
        .collect()
}

/// The options of `entry`, with the values saved for it.
pub fn load(dir: &Path, entry: &Entry, locale: &str) -> Vec<Item> {
    match entry.kind {
        Kind::Classic => classic(dir, entry, locale),
        Kind::Horizon => horizon(dir, entry),
    }
}

/// Saves the values that differ from the pack's own; all at their defaults removes the file.
pub fn save(dir: &Path, entry: &Entry, items: &[Item]) {
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
}
