// Luxium: Let there be light, by vinlanx (Luxium Free Modification License; see LICENSE in this directory).
// The mod's buttons in Options > Mods.
pub(super) fn run(_game: &mut crate::game::Game<'_>, mod_id: &str, buttons: &mut Vec<String>) {
    use crate::game::official_luxium as m;
    if mod_id != m::ID {
        return;
    }
    buttons.extend(m::labels(&crate::game::Game::shader_packs_dir()));
}
