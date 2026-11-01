//! command hook: intercepts chat commands before they go to the server.
//! All LiquidHorizon commands start with `.lb` or `lb`.

use liquid_horizon_engine as lh;

if let Some(reply) = lh::on_command(command) {
    game.native_chat(&reply);
    *ran = true;
}
