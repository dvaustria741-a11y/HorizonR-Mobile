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

/// 1.21.11 gives each carver its own replaceable blocks: the Overworld's carvers cannot cut netherrack, the
/// Nether's `nether_cave` can (and still cannot cut bedrock). The generator picks the table by noise settings.
#[test]
fn carving_tables_differ_per_dimension_in_the_1_21_11_data() {
    let (Some(dir), Some(reports)) = (
        std::env::var_os("HORIZON_1_21_11_WORLDGEN"),
        std::env::var_os("HORIZON_1_21_11_REPORTS"),
    ) else {
        return;
    };
    let blocks = fs::File::open(Path::new(&reports).join("blocks.json")).unwrap();
    let (registry, _) = BlockRegistry::from_blocks_report(blocks).unwrap();
    let registry: &'static BlockRegistry = Box::leak(Box::new(registry));
    let data = GeneratorData::load(Path::new(&dir), registry).unwrap();
    let state = |name: &str| registry.default_state(name).unwrap();
    let overworld = data.carving_for(Some("minecraft:overworld"));
    let nether = data.carving_for(Some("minecraft:nether"));
    for (name, in_overworld, in_nether) in [
        ("minecraft:stone", false, false),
        ("minecraft:netherrack", true, false),
        ("minecraft:soul_sand", true, false),
        ("minecraft:crimson_nylium", true, false),
        ("minecraft:dirt", false, false),
        ("minecraft:bedrock", true, true),
        ("minecraft:diamond_block", true, true),
    ] {
        assert_eq!(
            overworld.is_uncarvable(state(name)),
            in_overworld,
            "overworld {name}"
        );
        assert_eq!(
            nether.is_uncarvable(state(name)),
            in_nether,
            "nether {name}"
        );
    }
    // Settings without a table of their own use the default one.
    let end = data.carving_for(Some("minecraft:end"));
    assert_eq!(
        end.is_uncarvable(state("minecraft:netherrack")),
        overworld.is_uncarvable(state("minecraft:netherrack"))
    );
}
