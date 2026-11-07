//! Runtime block-state IDs imported from a version-pinned registry data export.
//! This supplies identities and default properties, not block behavior.
mod facts;
mod json;
pub(crate) mod snapshot;
use crate::{DATA_VERSION, chunk::BlockState, compression::invalid, nbt::Tag};
pub use json::BlockStateJsonCodec;
pub(crate) use json::java_integer;
use std::{
    collections::{BTreeMap, BTreeSet, HashMap},
    hash::{DefaultHasher, Hash, Hasher},
    io,
};

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct BlockStateId(pub u32);

pub struct BlockRegistry {
    states: Vec<BlockState>,
    /// Ids by a hash of their state, checked against `states`: 12 bytes per
    /// state instead of a second copy of every name and property map (about
    /// 125 MB for a registry of 190,000 states).
    ids: HashMap<u64, u32>,
    /// States whose hash an earlier state already took (none in vanilla).
    collided: BTreeMap<BlockState, BlockStateId>,
    defaults: BTreeMap<String, BlockStateId>,
}
/// The hash [`BlockRegistry`] indexes states by (fixed keys: the same in
/// every process).
fn state_key(state: &BlockState) -> u64 {
    let mut hasher = DefaultHasher::new();
    state.name.hash(&mut hasher);
    for (key, value) in &state.properties {
        key.hash(&mut hasher);
        value.hash(&mut hasher);
    }
    hasher.finish()
}
impl BlockRegistry {
    pub fn from_nbt(tag: &Tag) -> io::Result<Self> {
        if tag.get("DataVersion") != Some(&Tag::Int(DATA_VERSION)) {
            return Err(invalid("block registry DataVersion mismatch"));
        }
        let Some(Tag::List(entries)) = tag.get("states") else {
            return Err(invalid("missing registry states"));
        };
        let Some(Tag::Compound(defaults)) = tag.get("defaults") else {
            return Err(invalid("missing registry defaults"));
        };
        let states = entries
            .iter()
            .map(BlockState::from_nbt)
            .collect::<io::Result<_>>()?;
        let defaults = defaults
            .iter()
            .map(|(name, id)| {
                let name = name.to_string().map_err(|e| invalid(e.to_string()))?;
                let Tag::Int(id) = id else {
                    return Err(invalid("default state ID is not an int"));
                };
                Ok((name, i64::from(*id)))
            })
            .collect::<io::Result<_>>()?;
        Self::from_states(states, defaults)
    }

    /// The registry of a resources `facts.json` (`{"states": [[..., ..., ...,
    /// ..., state], ...], "defaults": {name: id}}`), read as it streams: no
    /// JSON or NBT tree of the whole file is built. For a pack of 190,000
    /// states the two trees were several hundred megabytes at startup.
    pub fn from_facts_json(reader: impl io::Read) -> io::Result<Self> {
        Ok(Self::from_facts_json_with_flags(reader)?.0)
    }

    /// [`Self::from_facts_json`], with each state's `(air, fluid)` columns.
    pub fn from_facts_json_with_flags(
        reader: impl io::Read,
    ) -> io::Result<(Self, Vec<(bool, bool)>)> {
        let facts: facts::Facts =
            facts::with_shared_strings(|| serde_json::from_reader(io::BufReader::new(reader)))
                .map_err(|e| invalid(e.to_string()))?;
        Self::from_facts(facts)
    }

    /// [`Self::from_facts_json_with_flags`] over bytes already in memory.
    ///
    /// `serde_json` cannot borrow out of a reader, so `from_reader` copies
    /// every string it parses; over one slice it borrows instead. The facts
    /// are about 10 MB and sit at the head of the start, before anything
    /// else in the core bundle can begin, so the difference is on the
    /// critical path.
    pub fn from_facts_json_bytes(bytes: &[u8]) -> io::Result<(Self, Vec<(bool, bool)>)> {
        let facts: facts::Facts = facts::with_shared_strings(|| serde_json::from_slice(bytes))
            .map_err(|e| invalid(e.to_string()))?;
        Self::from_facts(facts)
    }

    /// The registry of a data generator `blocks.json` report (`reports/blocks.json`: per block its
    /// `definition`, `properties` and `states` with each state's `id`, `default` and `properties`), with
    /// each state's `(air, fluid)` columns. The state ids must be the contiguous `0..n` the game numbers
    /// its states by. This needs no Java export: it is how a release whose exporters have not been
    /// ported (1.21.11) gets its block states.
    ///
    /// The columns are `BlockState.isAir()` and `!getFluidState().isEmpty()`, read off the block's
    /// definition: air is the `minecraft:air` definition; fluid is the `minecraft:liquid` definition,
    /// the blocks that hold water by themselves (bubble column, kelp, seagrass), and any state with
    /// `waterlogged=true`.
    pub fn from_blocks_report(reader: impl io::Read) -> io::Result<(Self, Vec<(bool, bool)>)> {
        let report: serde_json::Value = serde_json::from_reader(io::BufReader::new(reader))
            .map_err(|e| invalid(e.to_string()))?;
        let blocks = report
            .as_object()
            .ok_or_else(|| invalid("blocks report is not an object"))?;
        let mut rows: Vec<(u32, BlockState, bool, bool, bool)> = Vec::new();
        for (name, block) in blocks {
            let definition = block["definition"]["type"].as_str().unwrap_or_default();
            let air = definition == "minecraft:air";
            let holds_water = definition == "minecraft:liquid"
                || matches!(
                    name.as_str(),
                    "minecraft:bubble_column"
                        | "minecraft:kelp"
                        | "minecraft:kelp_plant"
                        | "minecraft:seagrass"
                        | "minecraft:tall_seagrass"
                );
            let states = block["states"]
                .as_array()
                .ok_or_else(|| invalid("block has no states"))?;
            for state in states {
                let id = state["id"]
                    .as_u64()
                    .and_then(|id| u32::try_from(id).ok())
                    .ok_or_else(|| invalid("state has no id"))?;
                let mut properties = crate::block_state_properties::BlockProperties::new();
                if let Some(map) = state["properties"].as_object() {
                    for (key, value) in map {
                        let value = value
                            .as_str()
                            .ok_or_else(|| invalid("state property is not a string"))?;
                        properties.insert(key.clone(), value.to_owned());
                    }
                }
                let waterlogged = properties.get("waterlogged").is_some_and(|v| v == "true");
                let default = state["default"].as_bool().unwrap_or(false);
                rows.push((
                    id,
                    BlockState {
                        name: name.clone(),
                        properties,
                    },
                    default,
                    air,
                    holds_water || waterlogged,
                ));
            }
        }
        rows.sort_by_key(|row| row.0);
        if rows.iter().enumerate().any(|(i, row)| row.0 as usize != i) {
            return Err(invalid("block state ids are not contiguous from 0"));
        }
        let mut defaults = BTreeMap::new();
        for (id, state, default, ..) in &rows {
            if *default
                && defaults
                    .insert(state.name.clone(), i64::from(*id))
                    .is_some()
            {
                return Err(invalid("block has two default states"));
            }
        }
        let flags = rows.iter().map(|row| (row.3, row.4)).collect();
        let states = rows.into_iter().map(|row| row.1).collect();
        Ok((Self::from_states(states, defaults)?, flags))
    }

    fn from_facts(facts: facts::Facts) -> io::Result<(Self, Vec<(bool, bool)>)> {
        let flags = facts.states.iter().map(|r| (r.air, r.fluid)).collect();
        let states = facts.states.into_iter().map(|r| r.state).collect();
        Ok((Self::from_states(states, facts.defaults)?, flags))
    }

    fn from_states(states: Vec<BlockState>, defaults: BTreeMap<String, i64>) -> io::Result<Self> {
        if states.is_empty() || states.len() > i32::MAX as usize {
            return Err(invalid("invalid registry size"));
        }
        let mut result = Self {
            states: Vec::with_capacity(states.len()),
            ids: HashMap::with_capacity(states.len()),
            collided: BTreeMap::new(),
            defaults: BTreeMap::new(),
        };
        // The states' property names and values: a few hundred strings, shared.
        let mut strings = crate::block_state_properties::SharedStrings::default();
        for (i, mut state) in states.into_iter().enumerate() {
            state.properties.share(&mut strings);
            if result.id(&state).is_some() {
                return Err(invalid("duplicate block registry state"));
            }
            match result.ids.entry(state_key(&state)) {
                std::collections::hash_map::Entry::Vacant(slot) => {
                    slot.insert(i as u32);
                }
                std::collections::hash_map::Entry::Occupied(_) => {
                    result
                        .collided
                        .insert(state.clone(), BlockStateId(i as u32));
                }
            }
            result.states.push(state);
        }
        for (name, id) in defaults {
            if id < 0
                || id > i64::from(i32::MAX)
                || result
                    .states
                    .get(id as usize)
                    .is_none_or(|s| s.name != name)
            {
                return Err(invalid("invalid default block state ID"));
            }
            result.defaults.insert(name, BlockStateId(id as u32));
        }
        let names: BTreeSet<_> = result.states.iter().map(|s| &s.name).collect();
        if names.len() != result.defaults.len()
            || names
                .iter()
                .any(|name| !result.defaults.contains_key(*name))
        {
            return Err(invalid("registry is missing block defaults"));
        }
        Ok(result)
    }
    pub fn len(&self) -> usize {
        self.states.len()
    }
    pub fn is_empty(&self) -> bool {
        self.states.is_empty()
    }
    pub fn state(&self, id: BlockStateId) -> Option<&BlockState> {
        self.states.get(id.0 as usize)
    }
    pub fn id(&self, state: &BlockState) -> Option<BlockStateId> {
        match self.ids.get(&state_key(state)) {
            Some(&id) if self.states[id as usize] == *state => Some(BlockStateId(id)),
            _ => self.collided.get(state).copied(),
        }
    }
    pub fn default_state(&self, name: &str) -> Option<BlockStateId> {
        self.defaults.get(name).copied()
    }

    /// The values `property` takes on `block`, in `Property.getPossibleValues`
    /// order. The state list is in `StateDefinition` order (properties sorted
    /// by name, values in their own order), so a value's first appearance is
    /// that order.
    pub fn property_values(&self, block: &str, property: &str) -> Vec<&str> {
        let mut values: Vec<&str> = Vec::new();
        for state in self
            .states
            .iter()
            .filter(|state| state.name.as_str() == block)
        {
            if let Some(value) = state.properties.get(property) {
                let value = value.as_str();
                if !values.contains(&value) {
                    values.push(value);
                }
            }
        }
        values
    }

    /// Resolve a saved descriptor, supplying omitted default properties. Unlike
    /// vanilla's lenient logging paths, invalid properties return a typed error.
    pub fn resolve(&self, descriptor: &BlockState) -> io::Result<BlockStateId> {
        let default = self
            .default_state(&descriptor.name)
            .ok_or_else(|| invalid("unknown block name"))?;
        let mut state = self.state(default).unwrap().clone();
        for (key, value) in &descriptor.properties {
            if !state.properties.contains_key(key) {
                return Err(invalid("unknown block property"));
            }
            state.properties.insert(key.clone(), value.clone());
        }
        self.id(&state)
            .ok_or_else(|| invalid("invalid block property value"))
    }
}

/// A per-state table (`occlusion.nbt`, `cached-collision.nbt`, ...) whose
/// `states` list, the identity of every state it describes in id order, has
/// been checked against `registry`.
#[derive(Clone, Copy)]
pub struct BlockTable<'t, 'r> {
    tag: &'t Tag,
    registry: &'r BlockRegistry,
    /// How many levels, from this one down, [`BlockTableFile::read`] checked
    /// while reading: their `states` lists are kept empty in the tree.
    read_checked: u8,
}
impl<'t, 'r> BlockTable<'t, 'r> {
    /// Checks that `tag`'s `states` names the registry's states in id order.
    pub fn check(tag: &'t Tag, registry: &'r BlockRegistry, what: &str) -> io::Result<Self> {
        Self {
            tag,
            registry,
            read_checked: 0,
        }
        .checked(what)
    }
    fn checked(self, what: &str) -> io::Result<Self> {
        let Some(Tag::List(states)) = self.tag.get("states") else {
            return Err(invalid(format!("missing {what} state identities")));
        };
        if self.read_checked == 0 {
            if states.len() != self.registry.len() {
                return Err(invalid(format!("{what} registry size mismatch")));
            }
            for (id, state) in states.iter().enumerate() {
                if self.registry.state(BlockStateId(id as u32))
                    != Some(&BlockState::from_nbt(state)?)
                {
                    return Err(invalid(format!("{what} state identities differ")));
                }
            }
        }
        Ok(self)
    }
    /// The table nested under `key` (`block-properties.nbt`'s `occlusion`),
    /// with its own `states`, checked the same way.
    pub fn nested(&self, key: &str, what: &str) -> io::Result<Self> {
        Self {
            tag: self
                .tag
                .get(key)
                .ok_or_else(|| invalid(format!("missing {what}")))?,
            registry: self.registry,
            read_checked: self.read_checked.saturating_sub(1),
        }
        .checked(what)
    }
    pub fn tag(&self) -> &'t Tag {
        self.tag
    }
    pub fn get(&self, key: &str) -> Option<&'t Tag> {
        self.tag.get(key)
    }
    pub fn registry(&self) -> &'r BlockRegistry {
        self.registry
    }
}

/// A per-state table read from its file with its state identities checked
/// while reading, so they never exist as a tree: for 190,000 states (a big
/// modpack) that tree took 300-600 MB per table.
pub struct BlockTableFile<'r> {
    tag: Tag,
    registry: &'r BlockRegistry,
}
impl<'r> BlockTableFile<'r> {
    /// [`Self::read`] of the file at `path`.
    pub fn open(path: &std::path::Path, registry: &'r BlockRegistry) -> io::Result<Self> {
        let bytes = std::fs::read(path)?;
        Self::read(&bytes, registry).map_err(|e| invalid(format!("{}: {e}", path.display())))
    }
    /// Reads `bytes`, checking every `states` list of the root compound and
    /// of the compounds directly under it against `registry`.
    pub fn read(bytes: &[u8], registry: &'r BlockRegistry) -> io::Result<Self> {
        let mut visit = |states: &mut crate::nbt::ListElements<'_, '_, '_>| {
            if states.len() != registry.len() {
                return Err(states.error("table registry size mismatch"));
            }
            let mut id = 0u32;
            while let Some(tag) = states.next_element()? {
                let state =
                    BlockState::from_nbt(&tag).map_err(|_| states.error("invalid table state"))?;
                if registry.state(BlockStateId(id)) != Some(&state) {
                    return Err(states.error("table state identities differ"));
                }
                id += 1;
            }
            Ok(())
        };
        let (named, _) = crate::nbt::read_named_streaming(
            bytes,
            crate::nbt::Limits::tables(),
            "states",
            &mut visit,
        )
        .map_err(|e| invalid(e.to_string()))?;
        Ok(Self {
            tag: named.tag,
            registry,
        })
    }
    /// The table, checked (`what` names it in errors).
    pub fn table(&self, what: &str) -> io::Result<BlockTable<'_, 'r>> {
        BlockTable {
            tag: &self.tag,
            registry: self.registry,
            read_checked: 2,
        }
        .checked(what)
    }
}

#[cfg(test)]
mod blocks_report_tests {
    use super::*;

    #[test]
    fn a_blocks_report_makes_a_registry_with_its_flags() {
        let report = r#"{
          "minecraft:air": {"definition": {"type": "minecraft:air"}, "properties": {},
            "states": [{"id": 0, "default": true}]},
          "minecraft:water": {"definition": {"type": "minecraft:liquid"}, "properties": {"level": ["0", "1"]},
            "states": [{"id": 1, "default": true, "properties": {"level": "0"}},
                       {"id": 2, "properties": {"level": "1"}}]},
          "minecraft:oak_slab": {"definition": {"type": "minecraft:slab"},
            "properties": {"waterlogged": ["true", "false"]},
            "states": [{"id": 4, "properties": {"waterlogged": "false"}},
                       {"id": 3, "default": true, "properties": {"waterlogged": "true"}}]}
        }"#;
        let (registry, flags) = BlockRegistry::from_blocks_report(report.as_bytes()).unwrap();
        assert_eq!(registry.len(), 5);
        assert_eq!(
            registry.default_state("minecraft:water"),
            Some(BlockStateId(1))
        );
        // Ids are the report's, whatever order the file lists them in.
        assert_eq!(
            registry.default_state("minecraft:oak_slab"),
            Some(BlockStateId(3))
        );
        assert_eq!(
            flags,
            vec![
                (true, false),
                (false, true),
                (false, true),
                (false, true),
                (false, false)
            ]
        );
        let gap = report.replace("\"id\": 4", "\"id\": 9");
        assert!(BlockRegistry::from_blocks_report(gap.as_bytes()).is_err());
    }

    /// The real 1.21.11 report, when `HORIZON_1_21_11_REPORTS` names the data generator's `reports` folder.
    #[test]
    fn the_1_21_11_blocks_report_loads_when_present() {
        let Some(dir) = std::env::var_os("HORIZON_1_21_11_REPORTS") else {
            return;
        };
        let file = std::fs::File::open(std::path::Path::new(&dir).join("blocks.json")).unwrap();
        let (registry, flags) = BlockRegistry::from_blocks_report(file).unwrap();
        assert_eq!(registry.len(), 29_671);
        let air = registry.default_state("minecraft:air").unwrap();
        assert_eq!(flags[air.0 as usize], (true, false));
        let water = registry.default_state("minecraft:water").unwrap();
        assert_eq!(flags[water.0 as usize], (false, true));
        println!(
            "1.21.11: {} states, {} air, {} fluid",
            registry.len(),
            flags.iter().filter(|f| f.0).count(),
            flags.iter().filter(|f| f.1).count()
        );
    }
}
