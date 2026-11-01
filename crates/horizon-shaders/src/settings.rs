//! The options a player can change in a classic pack (spec 8.1) and the values they chose.
//!
//! A pack declares its options as `#define` lines in its sources: `#define NAME` / `//#define NAME`
//! is a switch (on / off) and `#define NAME 2 //[1 2 3]` takes one of the listed values. The values
//! a player chose live beside the pack as `shaderpacks/<pack file>.txt`, one `NAME=value` per line
//! (`true` / `false` for a switch). [`apply_overrides`] rewrites the `#define` lines of a source
//! with those values, which is how a pack that is read with overrides sees them.

use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::io;
use std::path::{Path, PathBuf};

use crate::{Lang, Macros, Pack, Properties};

const SOURCES: [&str; 8] = ["vsh", "fsh", "gsh", "csh", "tcs", "tes", "glsl", "inc"];
/// Files that are never sources, whatever they contain.
const NOT_SOURCES: [&str; 5] = ["properties", "lang", "txt", "json", "md"];
const MAX_OPTIONS: usize = 400;

/// One `#define` line, taken apart.
struct Define<'a> {
    indent: &'a str,
    commented: bool,
    name: &'a str,
    /// What follows the name before any `//` comment.
    value: Option<&'a str>,
    /// The `//` comment at the end, with its slashes.
    comment: &'a str,
}

fn parse_define(line: &str) -> Option<Define<'_>> {
    let rest = line.trim_start();
    let indent = &line[..line.len() - rest.len()];
    let (commented, rest) = match rest.strip_prefix("//") {
        Some(r) => (true, r.trim_start()),
        None => (false, rest),
    };
    let rest = rest
        .strip_prefix('#')?
        .trim_start()
        .strip_prefix("define")?;
    if !rest.starts_with(char::is_whitespace) {
        return None;
    }
    let rest = rest.trim_start();
    let end = rest
        .find(|c: char| !(c.is_ascii_alphanumeric() || c == '_'))
        .unwrap_or(rest.len());
    let name = &rest[..end];
    if name.is_empty() || name.starts_with(|c: char| c.is_ascii_digit()) {
        return None;
    }
    let tail = &rest[end..];
    // `#define F(x)` is a function-like macro, never an option.
    if tail.starts_with('(') {
        return None;
    }
    let (body, comment) = match tail.find("//") {
        Some(i) => (&tail[..i], &tail[i..]),
        None => (tail, ""),
    };
    let body = body.trim();
    Some(Define {
        indent,
        commented,
        name,
        value: (!body.is_empty()).then_some(body),
        comment: comment.trim_end(),
    })
}

/// The values of a `//[a b c]` comment.
fn value_list(comment: &str) -> Vec<String> {
    let Some(start) = comment.find('[') else {
        return Vec::new();
    };
    let Some(len) = comment[start..].find(']') else {
        return Vec::new();
    };
    comment[start + 1..start + len]
        .split_whitespace()
        .map(str::to_owned)
        .collect()
}

pub(crate) fn is_source_name(path: &str) -> bool {
    let ext = path.rsplit_once('.').map_or("", |(_, e)| e);
    !NOT_SOURCES.contains(&ext)
}

/// `text` with the `#define` lines of the overridden names set to the chosen values.
pub fn apply_overrides(text: &str, overrides: &BTreeMap<String, String>) -> String {
    let mut out = String::with_capacity(text.len());
    for raw in text.split_inclusive('\n') {
        let content = raw.trim_end_matches(['\r', '\n']);
        let eol = &raw[content.len()..];
        let chosen = parse_define(content).and_then(|d| overrides.get(d.name).map(|v| (d, v)));
        let Some((d, value)) = chosen else {
            out.push_str(raw);
            continue;
        };
        let switch = match value.trim().to_ascii_lowercase().as_str() {
            "true" => Some(true),
            "false" => Some(false),
            _ => None,
        };
        out.push_str(d.indent);
        match switch {
            Some(on) => {
                if !on {
                    out.push_str("//");
                }
                out.push_str("#define ");
                out.push_str(d.name);
                if let Some(v) = d.value {
                    out.push(' ');
                    out.push_str(v);
                }
            }
            None => {
                out.push_str("#define ");
                out.push_str(d.name);
                out.push(' ');
                out.push_str(value.trim());
            }
        }
        if !d.comment.is_empty() {
            out.push(' ');
            out.push_str(d.comment);
        }
        out.push_str(eol);
    }
    out
}

/// One entry of a screen in the pack's `shaders.properties` (`screen=`, `screen.NAME=`).
#[derive(Clone, Debug, PartialEq)]
pub enum LayoutEntry {
    /// An option by name.
    Option(String),
    /// `[NAME]`: a button to the screen `screen.NAME`.
    Screen(String),
    /// `<empty>`: a gap.
    Space,
    /// `*`: every option no screen lists.
    Rest,
}

/// A screen the pack lays out: the main one (`id` empty) or one `[NAME]` links to.
#[derive(Clone, Debug, PartialEq)]
pub struct ScreenDef {
    pub id: String,
    /// The pack's `screen.NAME` name for it, else the id.
    pub title: String,
    pub entries: Vec<LayoutEntry>,
}

/// How a pack arranges its options: the screens it defines and the options it wants as sliders.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Layout {
    pub screens: Vec<ScreenDef>,
    pub sliders: BTreeSet<String>,
}

impl Layout {
    /// Every option name the layout mentions.
    fn names(&self) -> impl Iterator<Item = &str> {
        let listed = self
            .screens
            .iter()
            .flat_map(|s| &s.entries)
            .filter_map(|e| match e {
                LayoutEntry::Option(n) => Some(n.as_str()),
                _ => None,
            });
        listed.chain(self.sliders.iter().map(String::as_str))
    }
}

/// The screens and sliders of `shaders.properties`. A pack without `screen=` has no screens: its
/// options are shown in the order its files declare them.
pub fn layout(pack: &Pack, locale: &str) -> Layout {
    let Some(text) = pack.read_text("shaders.properties") else {
        return Layout::default();
    };
    let properties = Properties::parse(&text, &Macros::standard());
    let lang = Lang::load(pack, locale);
    let entries = |value: &str| -> Vec<LayoutEntry> {
        value
            .split_whitespace()
            .map(|t| match t {
                "<empty>" => LayoutEntry::Space,
                "*" => LayoutEntry::Rest,
                t if t.starts_with('[') && t.ends_with(']') && t.len() > 2 => {
                    LayoutEntry::Screen(t[1..t.len() - 1].to_owned())
                }
                t => LayoutEntry::Option(t.to_owned()),
            })
            // `<profile>` and `<comment>` lines are not options.
            .filter(|e| !matches!(e, LayoutEntry::Option(n) if n.starts_with('<')))
            .collect()
    };
    let mut out = Layout::default();
    if let Some(main) = properties.get("screen") {
        out.screens.push(ScreenDef {
            id: String::new(),
            title: String::new(),
            entries: entries(main),
        });
    }
    for entry in &properties.entries {
        let Some(id) = entry.key.strip_prefix("screen.") else {
            continue;
        };
        if id.is_empty() || id.ends_with(".columns") {
            continue;
        }
        let title = lang
            .get(&format!("screen.{id}"))
            .map(strip_formatting)
            .filter(|t| !t.trim().is_empty())
            .unwrap_or_else(|| id.to_owned());
        out.screens.push(ScreenDef {
            id: id.to_owned(),
            title,
            entries: entries(&entry.value),
        });
    }
    if let Some(sliders) = properties.get("sliders") {
        out.sliders = sliders.split_whitespace().map(str::to_owned).collect();
    }
    out
}

/// One option of a pack the player can change.
#[derive(Clone, Debug, PartialEq)]
pub struct ClassicOption {
    pub name: String,
    /// The pack's `option.NAME` name for it, else the name itself.
    pub label: String,
    /// What it can be, as written to the overrides: `false`, `true` for a switch.
    pub values: Vec<String>,
    /// Index into `values` of what the pack ships with.
    pub default: usize,
}

fn strip_formatting(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut chars = s.chars();
    while let Some(c) = chars.next() {
        if c == '§' {
            chars.next();
        } else {
            out.push(c);
        }
    }
    out
}

/// The options of `pack` in the order its files declare them, named in `locale` when the pack
/// translates them. Reads the pack as it is shipped: open it without overrides.
pub fn options(pack: &Pack, locale: &str) -> Vec<ClassicOption> {
    let lang = Lang::load(pack, locale);
    let files = pack.all_files();
    let sources: Vec<(&String, String)> = files
        .iter()
        .filter(|p| {
            let ext = p.rsplit_once('.').map_or("", |(_, e)| e);
            SOURCES.contains(&ext)
        })
        .filter_map(|p| Some((p, pack.read_text(p)?)))
        .collect();
    let included: BTreeSet<&str> = sources
        .iter()
        .flat_map(|(_, t)| t.lines())
        .filter_map(|l| l.trim().strip_prefix("#include"))
        .map(|r| r.trim().trim_matches(|c| c == '"' || c == '<' || c == '>'))
        .map(|r| r.rsplit('/').next().unwrap_or(r))
        .collect();
    let extra: Vec<(&String, String)> = files
        .iter()
        .filter(|p| {
            let ext = p.rsplit_once('.').map_or("", |(_, e)| e);
            !SOURCES.contains(&ext)
                && is_source_name(p)
                && included.contains(p.rsplit('/').next().unwrap_or(p))
        })
        .filter_map(|p| Some((p, pack.read_text(p)?)))
        .collect();
    let texts: Vec<&(&String, String)> = sources.iter().chain(&extra).collect();
    // Names a condition looks at: a switch nothing tests changes nothing.
    let mut tested = BTreeSet::new();
    for (_, text) in &texts {
        for line in text.lines().map(str::trim) {
            let Some(cond) = line.strip_prefix('#').map(str::trim_start) else {
                continue;
            };
            if cond.starts_with("if") || cond.starts_with("elif") {
                for word in cond.split(|c: char| !(c.is_ascii_alphanumeric() || c == '_')) {
                    if !word.is_empty() {
                        tested.insert(word.to_owned());
                    }
                }
            }
        }
    }
    // An option the pack lists on a screen or as a slider is one even if no condition tests it.
    let layout = layout(pack, locale);
    tested.extend(layout.names().map(str::to_owned));
    let mut seen = BTreeSet::new();
    let mut found = Vec::new();
    for (_, text) in &texts {
        let mut previous = "";
        for line in text.lines() {
            let trimmed = line.trim();
            let guard = |name: &str| {
                previous
                    .strip_prefix('#')
                    .map(str::trim_start)
                    .and_then(|p| p.strip_prefix("ifndef"))
                    .is_some_and(|n| n.trim() == name)
            };
            let Some(d) = parse_define(line) else {
                if !trimmed.is_empty() {
                    previous = trimmed;
                }
                continue;
            };
            let is_guard = guard(d.name);
            if !trimmed.is_empty() && !d.commented {
                previous = trimmed;
            }
            if is_guard || seen.contains(d.name) {
                continue;
            }
            let list = value_list(d.comment);
            let option = match d.value {
                None if tested.contains(d.name) => Some((
                    vec!["false".to_owned(), "true".to_owned()],
                    usize::from(!d.commented),
                )),
                Some(value) if !d.commented && !list.is_empty() => {
                    let mut values = list;
                    let at = values.iter().position(|v| v == value).unwrap_or_else(|| {
                        values.insert(0, value.to_owned());
                        0
                    });
                    Some((values, at))
                }
                _ => None,
            };
            if let Some((values, default)) = option {
                seen.insert(d.name.to_owned());
                let label = lang
                    .get(&format!("option.{}", d.name))
                    .map(strip_formatting)
                    .filter(|l| !l.trim().is_empty())
                    .unwrap_or_else(|| d.name.to_owned());
                found.push(ClassicOption {
                    name: d.name.to_owned(),
                    label,
                    values,
                    default,
                });
                if found.len() >= MAX_OPTIONS {
                    return found;
                }
            }
        }
    }
    found
}

/// `shaderpacks/<pack file>.txt`.
pub fn overrides_path(dir: &Path, pack_file: &str) -> PathBuf {
    dir.join(format!("{pack_file}.txt"))
}

/// The values the player chose for the pack, by option name. Missing file: none.
pub fn read_overrides(dir: &Path, pack_file: &str) -> BTreeMap<String, String> {
    let text = fs::read_to_string(overrides_path(dir, pack_file)).unwrap_or_default();
    text.lines()
        .map(str::trim)
        .filter(|l| !l.is_empty() && !l.starts_with('#'))
        .filter_map(|l| l.split_once('='))
        .map(|(k, v)| (k.trim().to_owned(), v.trim().to_owned()))
        .filter(|(k, v)| !k.is_empty() && !v.is_empty())
        .collect()
}

/// Saves the chosen values; with none, the file is removed.
pub fn write_overrides(
    dir: &Path,
    pack_file: &str,
    overrides: &BTreeMap<String, String>,
) -> io::Result<()> {
    let path = overrides_path(dir, pack_file);
    if overrides.is_empty() {
        return match fs::remove_file(&path) {
            Err(e) if e.kind() != io::ErrorKind::NotFound => Err(e),
            _ => Ok(()),
        };
    }
    let mut text = String::new();
    for (k, v) in overrides {
        text.push_str(k);
        text.push('=');
        text.push_str(v);
        text.push('\n');
    }
    fs::create_dir_all(dir)?;
    fs::write(path, text)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::pack::tests::zip;

    fn map(pairs: &[(&str, &str)]) -> BTreeMap<String, String> {
        pairs
            .iter()
            .map(|(k, v)| ((*k).to_owned(), (*v).to_owned()))
            .collect()
    }

    #[test]
    fn overrides_rewrite_switches_and_values_and_keep_comments() {
        let text = "#define AO // ambient\r\n//#define BLOOM\n  #define LEVEL 2 //[1 2 3]\n#define OTHER 5\n";
        let out = apply_overrides(
            text,
            &map(&[("AO", "false"), ("BLOOM", "true"), ("LEVEL", "3")]),
        );
        assert_eq!(
            out,
            "//#define AO // ambient\r\n#define BLOOM\n  #define LEVEL 3 //[1 2 3]\n#define OTHER 5\n"
        );
    }

    #[test]
    fn finds_switches_that_are_tested_and_values_with_a_list() {
        let pack = Pack::from_zip_bytes(
            "P.zip",
            zip(&[
                (
                    "shaders/lib/settings.glsl",
                    "#ifndef SETTINGS_H\n#define SETTINGS_H\n#define AO\n//#define BLOOM\n\
                     #define LEVEL 2 //[1 2 3]\n#define NOLIST 4\n#define UNTESTED\n\
                     #define F(x) x\n",
                ),
                (
                    "shaders/final.fsh",
                    "#ifdef AO\n#endif\n#ifndef BLOOM\n#endif\n#if LEVEL > 1\n#endif\n",
                ),
                ("shaders/lang/en_US.lang", "option.AO=§bAmbient Occlusion\n"),
            ]),
        )
        .unwrap();
        let found = options(&pack, "en_us");
        let names: Vec<&str> = found.iter().map(|o| o.name.as_str()).collect();
        assert_eq!(names, ["AO", "BLOOM", "LEVEL"]);
        assert_eq!(found[0].label, "Ambient Occlusion");
        assert_eq!((found[0].default, found[1].default), (1, 0));
        assert_eq!(found[2].values, ["1", "2", "3"]);
        assert_eq!(found[2].default, 1);
    }

    #[test]
    fn reads_screens_sliders_and_titles() {
        let pack = Pack::from_zip_bytes(
            "P.zip",
            zip(&[
                (
                    "shaders/shaders.properties",
                    "screen=AO <empty> [LIGHT] <profile> *\nscreen.LIGHT=BRIGHT LEVEL\n\
                     screen.LIGHT.columns=1\nsliders=LEVEL\n",
                ),
                ("shaders/lang/en_us.lang", "screen.LIGHT=Lighting\n"),
            ]),
        )
        .unwrap();
        let l = layout(&pack, "en_us");
        assert_eq!(l.screens.len(), 2);
        assert_eq!(
            l.screens[0].entries,
            [
                LayoutEntry::Option("AO".into()),
                LayoutEntry::Space,
                LayoutEntry::Screen("LIGHT".into()),
                LayoutEntry::Rest
            ]
        );
        assert_eq!(
            (l.screens[1].id.as_str(), l.screens[1].title.as_str()),
            ("LIGHT", "Lighting")
        );
        assert!(l.sliders.contains("LEVEL"));
    }

    #[test]
    fn overrides_file_round_trips() {
        let dir =
            std::env::temp_dir().join(format!("horizon-shader-settings-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        write_overrides(&dir, "BSL.zip", &map(&[("AO", "false"), ("LEVEL", "3")])).unwrap();
        assert_eq!(
            read_overrides(&dir, "BSL.zip"),
            map(&[("AO", "false"), ("LEVEL", "3")])
        );
        write_overrides(&dir, "BSL.zip", &BTreeMap::new()).unwrap();
        assert!(read_overrides(&dir, "BSL.zip").is_empty());
        let _ = fs::remove_dir_all(&dir);
    }
}
