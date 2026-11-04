// Luxium: Let there be light, by vinlanx (Luxium Free Modification License; see LICENSE in this directory).
// `/luxium warp [radius]`: the air distortion sphere, ahead of the player for a few seconds.
pub(super) fn run(game: &mut crate::game::Game<'_>, command: &str, ran: &mut bool) {
    use crate::game::official_luxium as m;
    if *ran || !game.native_on(m::ID) {
        return;
    }
    let mut words = command.split_whitespace();
    if words.next() != Some("luxium") {
        return;
    }
    *ran = true;
    let message = match (words.next(), game.native_player()) {
        (Some("warp"), Some(p)) => {
            let radius = words
                .next()
                .and_then(|w| w.parse::<f64>().ok())
                .unwrap_or(8.0);
            let (yaw, pitch) = (f64::from(p.yaw).to_radians(), f64::from(p.pitch).to_radians());
            let dir = [-yaw.sin() * pitch.cos(), -pitch.sin(), yaw.cos() * pitch.cos()];
            let eye = [p.x, p.y + 1.62, p.z];
            if m::warp(&crate::game::Game::shader_packs_dir(), eye, dir, radius) {
                game.native_shaders.invalidate();
                "Luxium: air warp started".to_owned()
            } else {
                "Luxium: could not save the warp".to_owned()
            }
        }
        _ => "Usage: /luxium warp [radius]".to_owned(),
    };
    game.system_message(horizon_client::text::Text::literal(message));
}
