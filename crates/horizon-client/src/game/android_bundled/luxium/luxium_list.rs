// Luxium: Let there be light, by vinlanx (Luxium Free Modification License; see LICENSE in this directory).
// The mod's row in Options > Mods.
pub(super) fn run(mods: &mut Vec<crate::game::native_hooks::NativeMod>) {
    use crate::game::official_luxium as m;
    mods.push(crate::game::native_hooks::NativeMod {
        id: m::ID.into(),
        title: format!("{} {}", m::NAME, m::VERSION),
        description: m::DESCRIPTION.into(),
        keys: Vec::new(),
    });
}
