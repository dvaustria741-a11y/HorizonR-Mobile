fn list_fluid_foolish(mods: &mut Vec<crate::game::native_hooks::NativeMod>) {
    mods.push(crate::game::native_hooks::NativeMod {
        id: "fluid-foolish".into(),
        title: crate::game::fluid_foolish::TITLE.into(),
        description: "Fluid & Foolish body animations: idle, walking, sprinting, sidestepping, crouching, jumping, landing, swimming, crawling, climbing, flying, gliding, sitting, getting hurt, turning on the spot and emotes, in the third-person view.".into(),
        keys: crate::game::fluid_foolish::emote_keys(),
    });
}
