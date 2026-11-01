//! ClickGUI state — rendered by the frame.rs helper using HudBatch.

#[derive(Debug, Clone, Default)]
pub struct ClickGui {
    pub open: bool,
    /// Which category panel is expanded (0=Combat,1=Movement,2=Render,3=Misc).
    pub active_panel: usize,
    /// Scroll offsets per panel.
    pub scroll: [f32; 4],
}

impl ClickGui {
    pub fn new() -> Self { Self::default() }
}
