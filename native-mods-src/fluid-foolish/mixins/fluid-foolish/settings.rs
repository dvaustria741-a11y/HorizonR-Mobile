fn settings_fluid_foolish(game:&crate::game::Game<'_>,mod_id:&str,buttons:&mut Vec<String>) {
    if mod_id=="fluid-foolish" {crate::game::fluid_foolish::settings(game,buttons);}
}
