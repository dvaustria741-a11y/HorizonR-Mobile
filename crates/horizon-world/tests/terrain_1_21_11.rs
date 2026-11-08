//! The engine's Overworld terrain on the 1.21.11 data against the real 1.21.11 game.
//!
//! `HORIZON_1_21_11_HEIGHTS` names the output of `Vanilla1_21_11HeightOracle` (the game's own `getBaseHeight`
//! for WORLD_SURFACE_WG on a grid), `HORIZON_1_21_11_WORLDGEN` the directory
//! `tools/assemble_worldgen_1_21_11.py` wrote and `HORIZON_1_21_11_REPORTS` the data generator's reports folder;
//! without them the test does nothing. It prints how many columns agree. Mojang's files are not part of this
//! repository.

use horizon_core::position::ChunkPos;
use horizon_world::{
    registry::BlockRegistry,
    worldgen::generator::{GeneratorData, OverworldGenerator},
};
use std::{collections::BTreeMap, fs, path::Path};

#[test]
fn how_close_the_engine_terrain_is_to_the_real_1_21_11_heights() {
    let (Some(dir), Some(reports), Some(heights)) = (
        std::env::var_os("HORIZON_1_21_11_WORLDGEN"),
        std::env::var_os("HORIZON_1_21_11_REPORTS"),
        std::env::var_os("HORIZON_1_21_11_HEIGHTS"),
    ) else {
        return;
    };
    let text = fs::read_to_string(heights).unwrap();
    let mut lines = text.lines();
    let seed: i64 = lines
        .next()
        .unwrap()
        .strip_prefix("seed ")
        .unwrap()
        .parse()
        .unwrap();
    let samples: Vec<(i32, i32, i32)> = lines
        .map(|l| {
            let mut p = l.split(' ').map(|v| v.parse::<i32>().unwrap());
            (p.next().unwrap(), p.next().unwrap(), p.next().unwrap())
        })
        .collect();
    let blocks = fs::File::open(Path::new(&reports).join("blocks.json")).unwrap();
    let (registry, _) = BlockRegistry::from_blocks_report(blocks).unwrap();
    let registry: &'static BlockRegistry = Box::leak(Box::new(registry));
    let data = GeneratorData::load(Path::new(&dir), registry).unwrap();
    let generator = OverworldGenerator::new(data, seed).unwrap();
    let mut worker = generator.worker();
    let mut chunks = BTreeMap::new();
    let mut same = 0;
    let mut off_by: BTreeMap<i32, usize> = BTreeMap::new();
    let mut first = Vec::new();
    for &(x, z, expected) in &samples {
        let pos = ChunkPos::new(x >> 4, z >> 4);
        if !chunks.contains_key(&(pos.x, pos.z)) {
            let proto = worker.generate_proto(pos).unwrap();
            chunks.insert((pos.x, pos.z), proto);
        }
        let got = chunks[&(pos.x, pos.z)].surface_height(x, z);
        if got == expected {
            same += 1;
        } else {
            *off_by.entry(got - expected).or_default() += 1;
            if first.len() < 8 {
                first.push((x, z, expected, got));
            }
        }
    }
    println!(
        "seed {seed}: {same}/{} columns have the game's WORLD_SURFACE_WG height; differences (engine - game): {off_by:?}; first: {first:?}",
        samples.len()
    );
}
