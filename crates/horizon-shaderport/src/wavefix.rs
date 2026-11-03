//! Pass `waving-fix`. LIGHT Shaders' `gbuffers_terrain.vsh` moves plants and leaves, then ends with
//! `gl_Position = ftransform();`, which overwrites the moved position: nothing ever waves, in
//! Horizon or anywhere else. The pack also tests `#ifdef WAVING_PLANTS` but never defines it, so
//! the option (listed in its settings screen) cannot be turned on.
//!
//! In `gbuffers_terrain.vsh` files:
//! - a last `gl_Position = ftransform();` that follows an earlier `gl_Position = ...;` computed from
//!   the pack's own position is removed;
//! - a `WAVING_*` option that the file tests with `#ifdef`, that `shaders.properties` lists in a
//!   settings screen, and that no file defines (not even commented out) gets `#define` at the top.

use crate::files::Files;
use crate::fix::{Change, Edits, sources};
use crate::glsl::{after_version, comment_ranges, find_word, in_comment};

const MARK: &str = "// horizon-shaderport waving-fix";

fn is_terrain_vsh(path: &str) -> bool {
    path.rsplit('/').next() == Some("gbuffers_terrain.vsh")
}

/// Byte ranges of the `gl_Position = <expr>;` statements outside comments: (start, end, is_ftransform).
fn assignments(text: &str) -> Vec<(usize, usize, bool)> {
    let comments = comment_ranges(text);
    let mut out = Vec::new();
    for at in find_word(text, "gl_Position") {
        if in_comment(&comments, at) {
            continue;
        }
        let rest = &text[at + "gl_Position".len()..];
        let trimmed = rest.trim_start();
        if !trimmed.starts_with('=') || trimmed.starts_with("==") {
            continue;
        }
        let Some(semi) = text[at..].find(';').map(|i| at + i) else { continue };
        let expr = text[at + "gl_Position".len()..semi].trim_start_matches([' ', '\t', '=']).trim();
        out.push((at, semi + 1, expr.replace(' ', "") == "ftransform()"));
    }
    out
}

fn remove_override(text: &str) -> Option<String> {
    let a = assignments(text);
    let &(start, end, is_ft) = a.last()?;
    if !is_ft || !a[..a.len() - 1].iter().any(|x| !x.2) {
        return None;
    }
    let mut out = text.to_owned();
    out.replace_range(start..end, &format!("{MARK}: removed the late ftransform() that undid the waving"));
    Some(out)
}

pub fn fix(files: &mut Files, log: &mut Vec<Change>) {
    let all = sources(files);
    let props = files.text("shaders.properties").unwrap_or_default();
    let defined = |name: &str| {
        all.iter().any(|(_, t)| {
            t.lines().any(|l| {
                let l = l.trim_start().trim_start_matches('/').trim_start();
                l.strip_prefix("#define").is_some_and(|r| r.split_whitespace().next() == Some(name))
            })
        })
    };
    let mut edits = Edits::new();
    for (path, text) in &all {
        if !is_terrain_vsh(path) || text.contains(MARK) {
            continue;
        }
        let mut out = text.clone();
        let mut notes = Vec::new();
        if let Some(o) = remove_override(&out) {
            out = o;
            notes.push("late ftransform() removed".to_owned());
        }
        let comments = comment_ranges(&out);
        let mut wanted = Vec::new();
        for at in find_word(&out, "WAVING_PLANTS") {
            if in_comment(&comments, at) {
                continue;
            }
            let before = out[..at].trim_end();
            if before.ends_with("#ifdef") && props.contains("WAVING_PLANTS") && !defined("WAVING_PLANTS") && !wanted.contains(&"WAVING_PLANTS") {
                wanted.push("WAVING_PLANTS");
            }
        }
        if !wanted.is_empty()
            && let Some(v) = after_version(&out)
        {
            let defs: String = wanted.iter().map(|w| format!("\n#define {w}")).collect();
            out.insert_str(v, &format!("\n{MARK}{defs}\n"));
            notes.push(format!("{} defined", wanted.join(" ")));
        }
        if out != *text {
            edits.insert(path.clone(), (out, notes.join(", ")));
        }
    }
    if edits.is_empty() {
        return;
    }
    let Ok(best) = crate::load::evaluate(files) else { return };
    let kept = crate::pbr::keep(files, &best, edits, 0);
    println!("  waving-fix: {} file(s)", kept.len());
    log.push(Change {
        rule: "waving-fix",
        files: kept.into_iter().collect(),
        outcome: "terrain vertex shaders that moved plants and then reset `gl_Position` with `ftransform()` lose that last line, and an undefined `WAVING_PLANTS` option the pack lists in its settings gets defined, so plants and leaves wave".into(),
    });
}
