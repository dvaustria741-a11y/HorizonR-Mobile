//! What the 26.2 -> 26.3 worldgen upgrade makes of the real 1.21.11 data.
//!
//! Set `HORIZON_1_21_11_REPORTS` to the reports folder of a 1.21.11 data generator run (its `data` folder is
//! beside it); without it the test does nothing. Mojang's files are not part of this repository.

use std::{collections::BTreeMap, fs, path::Path};

fn read_pack(dir: &Path, prefix: &str, into: &mut BTreeMap<String, Vec<u8>>) {
    let Ok(entries) = fs::read_dir(dir) else {
        return;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        let name = format!("{prefix}/{}", entry.file_name().to_string_lossy());
        if path.is_dir() {
            read_pack(&path, &name, into);
        } else {
            into.insert(name, fs::read(&path).unwrap());
        }
    }
}

#[test]
fn what_the_upgrade_makes_of_the_1_21_11_data() {
    let Some(reports) = std::env::var_os("HORIZON_1_21_11_REPORTS") else {
        return;
    };
    let mut files = BTreeMap::new();
    read_pack(&Path::new(&reports).join("../data"), "data", &mut files);
    files.insert(
        "pack.mcmeta".into(),
        br#"{"pack":{"pack_format":94,"description":"1.21.11"}}"#.to_vec(),
    );
    let (out, report) = horizon_port_pack::upgrade::upgrade_pack(&files);
    println!("files in {}, out {}", files.len(), out.len());
    println!("upgraded: {:?}", report.upgraded);
    println!("added: {}", report.added.len());
    println!("lossy: {:#?}", report.lossy);
    println!("unknown: {:#?}", report.unknown);
    if let Some(dir) = std::env::var_os("HORIZON_UPGRADE_OUT") {
        for (name, bytes) in &out {
            let path = Path::new(&dir).join(name);
            fs::create_dir_all(path.parent().unwrap()).unwrap();
            fs::write(path, bytes).unwrap();
        }
    }
    let kinds = |m: &BTreeMap<String, Vec<u8>>, k: &str| m.keys().filter(|f| f.contains(k)).count();
    for k in [
        "worldgen/feature/",
        "worldgen/configured_feature/",
        "worldgen/carver/",
        "worldgen/configured_carver/",
    ] {
        println!("{k}: in {} out {}", kinds(&files, k), kinds(&out, k));
    }
}
