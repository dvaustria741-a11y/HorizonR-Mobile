//! The 1.21.11 packet-id adapter against the real 1.21.11 packets report.
//!
//! Set `HORIZON_1_21_11_REPORTS` to the folder of a 1.21.11 data generator run's reports
//! (`java -DbundlerMainClass=net.minecraft.data.Main -jar server.jar --reports --server --output out`, then
//! `out/reports`); without it the test does nothing. Mojang's files are not part of this repository.
//!
//! For every packet 1.21.11 has, the adapter must send it to the pinned packet of the same name, or to
//! nothing when the pinned version has no packet of that name.

use horizon_protocol::{Direction, State, adapter::ServerVersion, ids};
use serde_json::Value;

const STATES: [(&str, State); 5] = [
    ("handshake", State::Handshake),
    ("status", State::Status),
    ("login", State::Login),
    ("configuration", State::Configuration),
    ("play", State::Play),
];

#[test]
fn adapter_id_tables_match_the_real_1_21_11_packets_report() {
    let Some(dir) = std::env::var_os("HORIZON_1_21_11_REPORTS") else {
        return;
    };
    let text = std::fs::read_to_string(std::path::Path::new(&dir).join("packets.json")).unwrap();
    let report: Value = serde_json::from_str(&text).unwrap();
    let version = ServerVersion::V1_21_11;
    let mut checked = 0;
    let mut problems = Vec::new();
    for (state_name, state) in STATES {
        for (direction_name, direction) in [
            ("clientbound", Direction::Clientbound),
            ("serverbound", Direction::Serverbound),
        ] {
            let Some(packets) = report[state_name][direction_name].as_object() else {
                continue;
            };
            for (full_name, entry) in packets {
                let name = full_name
                    .split_once(':')
                    .map_or(full_name.as_str(), |(_, n)| n);
                let old = entry["protocol_id"].as_i64().unwrap() as i32;
                // The pinned id of the same name, if the pinned version has one.
                let pinned = (0..)
                    .map_while(|id| Some((id, ids::name(state, direction, id)?)))
                    .find_map(|(id, n)| (n == name).then_some(id));
                let expected = pinned;
                let got = match direction {
                    // A 1.21.11 server's id -> the pinned id.
                    Direction::Clientbound => version.clientbound_id(state, old),
                    // The pinned id -> what a 1.21.11 server knows it by.
                    Direction::Serverbound => {
                        pinned.and_then(|p| version.serverbound_id(state, p).filter(|&o| o == old))
                    }
                };
                let ok = match direction {
                    Direction::Clientbound => got == expected,
                    Direction::Serverbound => got == pinned.map(|_| old),
                };
                checked += 1;
                if !ok {
                    problems.push(format!(
                        "{state_name} {direction_name} {name}: 1.21.11 id {old}, pinned {pinned:?}, adapter {got:?}"
                    ));
                }
            }
        }
    }
    println!("checked {checked} packets, {} differ", problems.len());
    for p in &problems {
        println!("  {p}");
    }
    assert!(checked > 200, "the report looks empty: {checked} packets");
    assert!(
        problems.is_empty(),
        "{} adapter id mismatches",
        problems.len()
    );
}
