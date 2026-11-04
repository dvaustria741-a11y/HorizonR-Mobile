fn list_fluid_foolish(mods: &mut Vec<crate::game::native_hooks::NativeMod>) {
    mods.push(crate::game::native_hooks::NativeMod {
        id: "fluid-foolish".into(),
        title: crate::game::fluid_foolish::TITLE.into(),
        description: "Fluid & Foolish body animations: idle, walking, sprinting, sidestepping, crouching, jumping and landing, in the third-person view.".into(),
        keys: Vec::new(),
    });
}
