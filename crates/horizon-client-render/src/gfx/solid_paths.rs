//! Solid terrain through cheaper shaders on Adreno's Vulkan, in the frames whose instances prove
//! they draw the same pixels (ported from Starling, MIT, which took it from Vasysik's Android
//! fork, measured on an Adreno 710). The frames of shader packs, of HDR output and of occlusion
//! culling keep the usual pipelines, and so does a device that refuses these.
use super::adreno::TerrainModule;

/// One solid pass's pipeline for both kinds of section draw.
pub(super) struct Pass {
    sections: wgpu::RenderPipeline,
    /// None without flat draws.
    flat: Option<wgpu::RenderPipeline>,
}

impl Pass {
    fn new(
        terrain: &TerrainModule<'_>,
        (layout, buffers): (&wgpu::PipelineLayout, &[Option<wgpu::VertexBufferLayout<'_>>]),
        flat_layout: Option<&wgpu::PipelineLayout>,
        (label, flat_label): (&str, &str),
        (vertex, flat_vertex): (&str, &str),
        fragment: &str,
    ) -> Option<Self> {
        let back = Some(wgpu::Face::Back);
        let sections =
            terrain.pipeline(layout, label, vertex, fragment, buffers, back, true, None)?;
        let flat = match flat_layout {
            Some(layout) => Some(terrain.pipeline(
                layout,
                flat_label,
                flat_vertex,
                fragment,
                // The flat draws pull everything from the page and the index list.
                &[],
                back,
                true,
                None,
            )?),
            None => None,
        };
        Some(Self { sections, flat })
    }
    fn get(&self, flat: bool) -> Option<&wgpu::RenderPipeline> {
        if flat {
            self.flat.as_ref()
        } else {
            Some(&self.sections)
        }
    }
}

/// What a frame's solid passes draw through in place of the usual pipelines.
#[derive(Clone, Copy, Default)]
pub(super) struct FramePasses<'a> {
    opaque: Option<&'a Pass>,
    cutout: Option<&'a Pass>,
}

impl FramePasses<'_> {
    pub fn opaque(&self, flat: bool) -> Option<&wgpu::RenderPipeline> {
        self.opaque.and_then(|pass| pass.get(flat))
    }
    pub fn cutout(&self, flat: bool) -> Option<&wgpu::RenderPipeline> {
        self.cutout.and_then(|pass| pass.get(flat))
    }
}

pub(super) struct SolidPaths {
    /// The cutout pass without `faded` (terrain.wgsl's `fs_cutout_static`).
    static_cutout: Pass,
    pub static_cutout_on: bool,
    /// Both passes through terrain.wgsl's `SETTLED_TERRAIN`, the opaque one then the cutout one.
    settled: Option<[Pass; 2]>,
    pub settled_on: bool,
}

impl SolidPaths {
    /// `flat_bindings`: the flat draws' bind group 1, where they exist. `buffers`: the terrain's
    /// vertex buffers (`Renderer::new`'s `terrain_buffers`).
    pub fn new(
        terrain: &TerrainModule<'_>,
        bindings: &wgpu::BindGroupLayout,
        layout: &wgpu::PipelineLayout,
        flat_bindings: Option<&wgpu::BindGroupLayout>,
        buffers: &[Option<wgpu::VertexBufferLayout<'_>>],
    ) -> Option<Self> {
        // With flat draws' page groups the per-section solid draws read their vertices from the
        // page too: only the origins stay a vertex buffer, at slot 1 (as `Renderer::new`).
        let pulled = flat_bindings.map(|flat| {
            terrain
                .kept
                .create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
                    label: Some("Horizon terrain layout (pulled)"),
                    bind_group_layouts: &[Some(bindings), Some(flat)],
                    ..Default::default()
                })
        });
        let origins = [None, buffers.get(1).cloned().flatten()];
        let (sections, pulled_vertices): ((&wgpu::PipelineLayout, &[_]), bool) = match &pulled {
            Some(pulled) => ((pulled, &origins[..]), true),
            None => ((layout, buffers), false),
        };
        let vertex = |pulled_name, plain_name| if pulled_vertices { pulled_name } else { plain_name };
        let constants: Vec<_> = terrain
            .constants
            .iter()
            .copied()
            .chain([("SETTLED_TERRAIN", 1.0)])
            .collect();
        let settled_module = TerrainModule {
            constants: &constants,
            vertex_constants: &[("SETTLED_TERRAIN", 1.0)],
            ..*terrain
        };
        let settled_opaque = Pass::new(
            &settled_module,
            sections,
            pulled.as_ref(),
            ("Settled terrain opaque", "Settled terrain opaque (flat)"),
            (
                vertex("vs_section_opaque", "vs_terrain_opaque"),
                "vs_section_flat_opaque",
            ),
            "fs_opaque",
        );
        let settled_cutout = Pass::new(
            &settled_module,
            sections,
            pulled.as_ref(),
            ("Settled terrain cutout", "Settled terrain cutout (flat)"),
            (
                vertex("vs_section_cutout", "vs_terrain_cutout"),
                "vs_section_flat_cutout",
            ),
            "fs_cutout_static",
        );
        Some(Self {
            settled: settled_opaque.zip(settled_cutout).map(<[Pass; 2]>::from),
            settled_on: true,
            static_cutout: Pass::new(
                terrain,
                sections,
                pulled.as_ref(),
                (
                    "Terrain cutout without fades",
                    "Terrain cutout without fades (flat)",
                ),
                (
                    vertex("vs_section_cutout", "vs_terrain_cutout"),
                    "vs_section_flat_cutout",
                ),
                "fs_cutout_static",
            )?,
            static_cutout_on: true,
        })
    }

    /// The passes `origins`, the frame's instances, and `filtering`,
    /// `Options.textureFiltering`, allow.
    pub fn for_frame(&self, origins: &[[f32; 4]], filtering: u32) -> FramePasses<'_> {
        if !self.static_cutout_on || !origins.iter().all(|origin| unfaded(origin[3])) {
            return FramePasses::default();
        }
        let settled = self
            .settled
            .as_ref()
            .filter(|_| self.settled_on && filtering != RGSS)
            .filter(|_| origins.iter().all(|origin| settled(origin[3])));
        match settled {
            Some([opaque, cutout]) => FramePasses {
                opaque: Some(opaque),
                cutout: Some(cutout),
            },
            None => FramePasses {
                opaque: None,
                cutout: Some(&self.static_cutout),
            },
        }
    }
}

/// `Options.textureFiltering`'s RGSS, which settled terrain doesn't take.
const RGSS: u32 = 1;

/// A fade's code is what a negative `w` holds (terrain.wgsl's `fade_code`).
fn unfaded(w: f32) -> bool {
    w >= 0.0
}

/// A whole section is the bare UV step: no fade-in in its mantissa, and not a distant tile's step
/// (`section_instance`, `lod::instance`).
fn settled(w: f32) -> bool {
    w == 1.0 / crate::mesher::UV_STEPS
}

#[cfg(test)]
mod tests {
    use super::{settled, unfaded};

    /// Every entry point the paths name exists in terrain.wgsl and the whole shader validates.
    #[test]
    fn the_terrain_shader_has_every_entry_point_these_paths_name() {
        let source = super::super::terrain_shader();
        let module = naga::front::wgsl::parse_str(&source).unwrap();
        naga::valid::Validator::new(
            naga::valid::ValidationFlags::all(),
            naga::valid::Capabilities::all(),
        )
        .validate(&module)
        .unwrap();
        for name in [
            "vs_terrain_cutout",
            "vs_section_cutout",
            "vs_section_flat_cutout",
            "fs_cutout_static",
            "vs_section_opaque",
            "vs_terrain_opaque",
            "vs_section_flat_opaque",
            "fs_opaque",
        ] {
            assert!(
                module.entry_points.iter().any(|e| e.name == name),
                "{name} is missing"
            );
        }
    }

    /// Only a section's bare step is settled: a fade-in in its mantissa, a distant tile's step
    /// and a fade code are not.
    #[test]
    fn only_a_whole_section_is_settled() {
        let step = 1.0 / crate::mesher::UV_STEPS;
        assert!(settled(step));
        assert!(!settled(f32::from_bits(step.to_bits() | 1)));
        assert!(!settled(1.0 / crate::mesher::TILED_UV_STEPS));
        assert!(!settled(-(3.0 + step)));
    }

    #[test]
    fn only_a_negative_w_is_a_fade() {
        assert!(unfaded(1.0 / crate::mesher::UV_STEPS));
        assert!(unfaded(0.0));
        assert!(!unfaded(-(7.0 + 1.0 / crate::mesher::UV_STEPS)));
    }
}
