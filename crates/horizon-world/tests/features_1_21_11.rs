//! How much of 1.21.11's feature data the engine's feature decoders accept as they are.
//!
//! Set `HORIZON_1_21_11_REPORTS` to the reports folder of a 1.21.11 data generator run (its `data` folder is
//! beside it); without it the test does nothing. It prints the decode failures grouped by cause, which is the
//! list of format differences left to port. Mojang's files are not part of this repository.

use horizon_world::worldgen::feature_graph::{FeatureDefinition, PlacedFeatureDefinition};
use std::{collections::BTreeMap, fs, path::Path};

fn files(dir: &Path) -> Vec<(String, String)> {
    let mut out = Vec::new();
    let Ok(read) = fs::read_dir(dir) else {
        return out;
    };
    for entry in read.flatten() {
        let path = entry.path();
        if path.extension().is_some_and(|e| e == "json") {
            let id = path.file_stem().unwrap().to_string_lossy().into_owned();
            out.push((id, fs::read_to_string(&path).unwrap()));
        }
    }
    out.sort();
    out
}

#[test]
fn how_many_1_21_11_features_decode() {
    let Some(reports) = std::env::var_os("HORIZON_1_21_11_REPORTS") else {
        return;
    };
    let data = Path::new(&reports).join("../data/minecraft/worldgen");
    let mut causes: BTreeMap<String, Vec<String>> = BTreeMap::new();
    let mut ok = (0, 0);
    let configured = files(&data.join("configured_feature"));
    let placed = files(&data.join("placed_feature"));
    for (id, json) in &configured {
        match FeatureDefinition::from_json(json) {
            Ok(_) => ok.0 += 1,
            Err(e) => causes
                .entry(format!("feature: {e:?}"))
                .or_default()
                .push(id.clone()),
        }
    }
    for (id, json) in &placed {
        match PlacedFeatureDefinition::from_json(json) {
            Ok(_) => ok.1 += 1,
            Err(e) => causes
                .entry(format!("placed: {e:?}"))
                .or_default()
                .push(id.clone()),
        }
    }
    println!(
        "configured features {}/{} decode, placed features {}/{}",
        ok.0,
        configured.len(),
        ok.1,
        placed.len()
    );
    for (cause, ids) in &causes {
        println!(
            "  {} x {cause} e.g. {:?}",
            ids.len(),
            &ids[..ids.len().min(3)]
        );
    }
    assert!(
        !configured.is_empty() && !placed.is_empty(),
        "no 1.21.11 data found"
    );
}
