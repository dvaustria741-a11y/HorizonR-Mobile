//! The engine's chunk generator loading a worldgen directory assembled from the 1.21.11 data.
//!
//! Set `HORIZON_1_21_11_WORLDGEN` to the directory `tools/assemble_worldgen_1_21_11.py` wrote and
//! `HORIZON_1_21_11_REPORTS` to the data generator's reports folder; without them the test does nothing. It prints
//! what the loader says, which is the list of what is left to port. Mojang's files are not part of this repository.

use horizon_world::{registry::BlockRegistry, worldgen::generator::GeneratorData};
use std::{fs, path::Path};

#[test]
fn what_the_generator_makes_of_the_1_21_11_directory() {
    let (Some(dir), Some(reports)) = (
        std::env::var_os("HORIZON_1_21_11_WORLDGEN"),
        std::env::var_os("HORIZON_1_21_11_REPORTS"),
    ) else {
        return;
    };
    let blocks = fs::File::open(Path::new(&reports).join("blocks.json")).unwrap();
    let (registry, _) = BlockRegistry::from_blocks_report(blocks).unwrap();
    let registry: &'static BlockRegistry = Box::leak(Box::new(registry));
    match GeneratorData::load(Path::new(&dir), registry) {
        Ok(_) => println!("GeneratorData::load: ok"),
        Err(e) => println!("GeneratorData::load: {e}"),
    }
}
