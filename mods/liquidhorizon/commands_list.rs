//! commands hook: register our client-side commands for chat autocomplete.

use liquid_horizon_engine as lh;

for &cmd in lh::command_names() {
    commands.push(cmd);
}

let _ = game;
