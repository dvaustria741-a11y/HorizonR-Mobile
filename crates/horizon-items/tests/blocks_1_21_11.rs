//! The engine's block-facts loader on the real 1.21.11 Java exports.
//!
//! Set `HORIZON_1_21_11_REPORTS` (the data generator's reports folder) and `HORIZON_1_21_11_EXPORTS` (a folder
//! holding the `VanillaGameplayFactsExporter` output as `facts.json` and the `VanillaBlockBehaviourExporter`
//! output as `block-behaviours.json`); without them the test does nothing. Mojang's files are not part of this
//! repository.

use horizon_items::blocks::BlockFacts;
use horizon_world::registry::BlockRegistry;
use std::{fs, path::Path};

#[test]
fn the_block_facts_loader_reads_the_1_21_11_exports() {
    let (Some(reports), Some(exports)) = (
        std::env::var_os("HORIZON_1_21_11_REPORTS"),
        std::env::var_os("HORIZON_1_21_11_EXPORTS"),
    ) else {
        return;
    };
    let (registry, _) = BlockRegistry::from_blocks_report(
        fs::File::open(Path::new(&reports).join("blocks.json")).unwrap(),
    )
    .unwrap();
    let root = std::env::temp_dir().join("horizon-1-21-11-exports");
    fs::create_dir_all(&root).unwrap();
    for (from, to) in [
        ("facts.json", "gameplay-facts.json"),
        ("block-behaviours.json", "block-behaviours.json"),
    ] {
        fs::copy(Path::new(&exports).join(from), root.join(to)).unwrap();
    }
    // `Behaviours` also reads the tag bundle and collision cache, which this test does not prepare.
    let facts = match BlockFacts::load(&root, &registry) {
        Ok(facts) => facts,
        Err(e) => panic!("1.21.11 exports do not load: {e}"),
    };
    println!(
        "{} state facts for {} registry states",
        facts.states.len(),
        registry.len()
    );
    assert_eq!(facts.states.len(), registry.len());
}
