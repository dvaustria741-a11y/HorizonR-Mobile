//! Saved block states in 1.21.11's format (`{Name, Properties}`, data version 4671) next to 26.3's
//! (`{id, properties}`, `DATA_VERSION`): each format is read and written as its own, never as the other.
//!
//! The last test runs on the real 1.21.11 block registry when `HORIZON_1_21_11_REPORTS` names the data
//! generator's reports folder; without it that test does nothing.

use horizon_core::position::{BlockPos, ChunkPos};
use horizon_world::{
    DATA_VERSION,
    chunk::{BlockSection, BlockState, ChunkData, ChunkFormat, PalettedContainer, StateKeys},
    nbt::{JavaString, NamedTag, Tag},
    registry::{BlockRegistry, BlockStateId},
};
use std::{fs, path::Path};

fn log(axis: &str) -> BlockState {
    BlockState {
        name: "minecraft:oak_log".to_owned(),
        properties: [("axis".to_owned(), axis.to_owned())].into(),
    }
}

fn air_chunk(version: i32) -> NamedTag {
    let palette =
        PalettedContainer::uniform(Tag::String(JavaString::from("minecraft:air")), 4096, 4)
            .unwrap();
    NamedTag {
        name: "".into(),
        tag: Tag::compound([
            ("DataVersion", Tag::Int(version)),
            ("xPos", Tag::Int(3)),
            ("zPos", Tag::Int(-2)),
            (
                "sections",
                Tag::List(vec![Tag::compound([
                    ("Y", Tag::Byte(0)),
                    ("block_states", palette.to_nbt()),
                ])]),
            ),
        ]),
    }
}

#[test]
fn a_state_is_written_and_read_with_its_versions_keys_only() {
    let state = log("x");
    let modern = state.to_nbt();
    assert_eq!(modern, state.to_nbt_as(StateKeys::Modern));
    assert!(modern.get("id").is_some() && modern.get("properties").is_some());
    assert!(modern.get("Name").is_none() && modern.get("Properties").is_none());

    let classic = state.to_nbt_as(StateKeys::Classic);
    assert!(classic.get("Name").is_some() && classic.get("Properties").is_some());
    assert!(classic.get("id").is_none() && classic.get("properties").is_none());

    assert_eq!(BlockState::from_nbt(&modern).unwrap(), state);
    assert_eq!(
        BlockState::from_nbt_as(&classic, StateKeys::Classic).unwrap(),
        state
    );
    // Neither format is read as the other: no silent mix of the two versions' saves.
    assert!(BlockState::from_nbt(&classic).is_err());
    assert!(BlockState::from_nbt_as(&modern, StateKeys::Classic).is_err());
    // A state without properties writes no properties key; a bare name is a state in both.
    let air = BlockState {
        name: "minecraft:air".to_owned(),
        properties: Default::default(),
    };
    assert!(
        air.to_nbt_as(StateKeys::Classic)
            .get("Properties")
            .is_none()
    );
    let bare = Tag::String(JavaString::from("minecraft:air"));
    assert_eq!(
        BlockState::from_nbt_as(&bare, StateKeys::Classic).unwrap(),
        air
    );
}

#[test]
fn a_chunk_is_accepted_only_at_its_formats_data_version() {
    assert_eq!(ChunkFormat::CURRENT.data_version, DATA_VERSION);
    assert_eq!(ChunkFormat::CURRENT.keys, StateKeys::Modern);
    assert_eq!(ChunkFormat::V1_21_11.data_version, 4671);
    assert_eq!(ChunkFormat::V1_21_11.keys, StateKeys::Classic);

    assert!(ChunkData::new(air_chunk(DATA_VERSION)).is_ok());
    assert!(ChunkData::new(air_chunk(4671)).is_err());
    assert!(ChunkData::new_as(air_chunk(4671), ChunkFormat::V1_21_11).is_ok());
    assert!(ChunkData::new_as(air_chunk(DATA_VERSION), ChunkFormat::V1_21_11).is_err());
    assert!(ChunkData::new_as(air_chunk(4670), ChunkFormat::V1_21_11).is_err());
    assert_eq!(
        ChunkData::new(air_chunk(DATA_VERSION)).unwrap().format(),
        ChunkFormat::CURRENT
    );
}

#[test]
fn editing_a_1_21_11_chunk_writes_name_and_properties() {
    let mut chunk = ChunkData::new_as(air_chunk(4671), ChunkFormat::V1_21_11).unwrap();
    assert_eq!(chunk.position(), ChunkPos::new(3, -2));
    let at = BlockPos::new(3 * 16 + 5, 7, -2 * 16 + 9);
    let old = chunk.set_block_data(at, log("z")).unwrap();
    assert_eq!(old.name, "minecraft:air");
    assert_eq!(chunk.block(at).unwrap(), Some(log("z")));
    chunk.set_block_data(at.offset(1, 0, 0), log("x")).unwrap();

    let root = chunk.into_nbt();
    let Some(Tag::List(sections)) = root.tag.get("sections") else {
        panic!("sections");
    };
    let container =
        PalettedContainer::from_nbt(sections[0].get("block_states").unwrap(), 4096, 4).unwrap();
    assert_eq!(container.palette_len(), 3);
    // The test chunk starts with a bare-name `air` entry, which stays one; the two logs are compounds.
    let mut named = 0;
    for i in 0..4096 {
        let entry = container.get(i).unwrap();
        assert!(
            entry.get("id").is_none() && entry.get("properties").is_none(),
            "{entry:?}"
        );
        if entry.get("Name").is_some() {
            assert!(entry.get("Properties").is_some());
            named += 1;
        } else {
            assert_eq!(entry, &Tag::String(JavaString::from("minecraft:air")));
        }
    }
    assert_eq!(named, 2);
    // The saved chunk reads back the same.
    let again = ChunkData::new_as(root, ChunkFormat::V1_21_11).unwrap();
    assert_eq!(again.block(at).unwrap(), Some(log("z")));
    assert_eq!(again.block(at.offset(1, 0, 0)).unwrap(), Some(log("x")));
}

#[test]
fn a_26_3_chunk_still_writes_id_and_properties() {
    let mut chunk = ChunkData::new(air_chunk(DATA_VERSION)).unwrap();
    let at = BlockPos::new(3 * 16, 0, -2 * 16);
    chunk.set_block_data(at, log("y")).unwrap();
    let root = chunk.into_nbt();
    let Some(Tag::List(sections)) = root.tag.get("sections") else {
        panic!("sections");
    };
    let container =
        PalettedContainer::from_nbt(sections[0].get("block_states").unwrap(), 4096, 4).unwrap();
    let entry = (0..4096)
        .map(|i| container.get(i).unwrap())
        .find(|t| t.get("properties").is_some())
        .expect("the log's entry");
    assert!(entry.get("id").is_some() && entry.get("Name").is_none());
    assert!(entry.get("Properties").is_none());
}

#[test]
fn every_real_1_21_11_state_survives_a_classic_section_round_trip() {
    let Some(reports) = std::env::var_os("HORIZON_1_21_11_REPORTS") else {
        return;
    };
    let file = fs::File::open(Path::new(&reports).join("blocks.json")).unwrap();
    let (registry, _) = BlockRegistry::from_blocks_report(file).unwrap();
    let total = registry.len();
    assert_eq!(total, 29_671);
    // 4,096 cells per section: walk every state id through enough sections to hold them all.
    let mut next = 0usize;
    let mut checked = 0usize;
    while next < total {
        let mut section = BlockSection::uniform(BlockStateId(0), &registry).unwrap();
        let mut expected = Vec::with_capacity(4096);
        for i in 0..4096 {
            let id = BlockStateId(((next + i) % total) as u32);
            section.set(i, id).unwrap();
            expected.push(id);
        }
        next += 4096;
        let saved = section.to_nbt_as(StateKeys::Classic).unwrap();
        let loaded = BlockSection::from_nbt_as(&saved, &registry, StateKeys::Classic).unwrap();
        for (i, id) in expected.iter().enumerate() {
            assert_eq!(loaded.get(i).unwrap(), *id, "cell {i}");
            checked += 1;
        }
        // The same section is not readable as 26.3's.
        assert!(BlockSection::from_nbt(&saved, &registry).is_err());
    }
    assert!(checked >= total);
    println!(
        "{total} states round-tripped through {} sections of 4096 cells",
        next / 4096
    );
}
