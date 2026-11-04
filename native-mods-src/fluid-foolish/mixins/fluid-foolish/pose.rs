fn pose_fluid_foolish(game:&crate::game::Game<'_>,partial:f32,pose:&mut horizon_client::mobs::native_pose::NativePose) {
    crate::game::fluid_foolish::pose(game,partial,pose);
}
