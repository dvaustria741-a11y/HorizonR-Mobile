//! settings_click hook: a button on the settings page was clicked.

use liquid_horizon_engine as lh;

if mod_id != "liquid_horizon" {
    return;
}
let _ = game;
lh::on_settings_click(button);
