//! settings hook: provides buttons for the Options > Mods settings page.

use liquid_horizon_engine as lh;

if mod_id != "liquid_horizon" {
    return;
}
let _ = game;
for btn in lh::settings_buttons() {
    buttons.push(btn);
}
