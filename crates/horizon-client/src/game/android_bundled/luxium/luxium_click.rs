// Luxium: Let there be light, by vinlanx (Luxium Free Modification License; see LICENSE in this directory).
// A press on one of the mod's buttons: the next value, then the stages are rebuilt with it.
pub(super) fn run(game: &mut crate::game::Game<'_>, mod_id: &str, button: usize) {
    use crate::game::official_luxium as m;
    if mod_id != m::ID {
        return;
    }
    if m::click(&crate::game::Game::shader_packs_dir(), button) {
        game.native_shaders.invalidate();
    }
}
