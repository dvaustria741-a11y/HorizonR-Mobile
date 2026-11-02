//! `horizon-shaderport <pack.zip|folder> [--out DIR]`: converts one pack (development tool).
use std::path::{Path, PathBuf};

fn main() {
    horizon_shaderport::silence_panics();
    let args: Vec<String> = std::env::args().skip(1).collect();
    let out = args.iter().position(|a| a == "--out").and_then(|i| args.get(i + 1)).map(PathBuf::from).unwrap_or_else(|| PathBuf::from("out"));
    let Some(pack) = args.iter().find(|a| !a.starts_with("--") && Some(a.as_str()) != args.iter().position(|x| x == "--out").and_then(|i| args.get(i + 1)).map(String::as_str)) else {
        eprintln!("usage: horizon-shaderport <pack.zip|folder> [--out DIR]");
        std::process::exit(2);
    };
    match horizon_shaderport::port_pack(Path::new(pack), &out) {
        Ok(o) => println!("{}: before {} load / {} skipped, after {} load / {} skipped, {} rules, wrote {}", o.name, o.before_loaded, o.before_skipped, o.after_loaded, o.after_skipped, o.rules_kept, o.zip.display()),
        Err(e) => { eprintln!("error: {e}"); std::process::exit(1); }
    }
}
