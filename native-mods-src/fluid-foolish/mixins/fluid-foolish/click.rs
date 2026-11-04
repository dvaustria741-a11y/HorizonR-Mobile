fn click_fluid_foolish(game:&crate::game::Game<'_>,mod_id:&str,button:usize) {
    if mod_id=="fluid-foolish" {crate::game::fluid_foolish::click(game,button);}
}
