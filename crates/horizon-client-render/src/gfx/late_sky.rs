//! The vanilla sky after solid terrain, in the same pass and before entities and translucent
//! surfaces (ported from Starling, MIT, which took it from Vasysik's Android fork). At the far
//! plane it reads the depth solids left, without writing any, so the sky's fragment shaders run
//! only where no terrain covers them. Its colours, fog and rotation are the usual ones. Shader
//! packs, the End sky, custom skies, the water mirror and occlusion culling keep the usual order.
use super::adreno::TerrainModule;

pub(super) struct LateSky {
    pub sky: wgpu::RenderPipeline,
    pub sunrise: wgpu::RenderPipeline,
    pub celestial: wgpu::RenderPipeline,
    pub stars: wgpu::RenderPipeline,
    pub dark_disc: wgpu::RenderPipeline,
}

impl LateSky {
    /// `celestial`: the usual pipeline's descriptor. None where the device refuses one of them:
    /// the sky keeps its usual order.
    pub fn new(
        terrain: &TerrainModule<'_>,
        layout: &wgpu::PipelineLayout,
        sunrise: (&[Option<wgpu::VertexBufferLayout<'_>>], wgpu::BlendState),
        dark_disc_blend: wgpu::BlendState,
        celestial: &wgpu::RenderPipelineDescriptor<'_>,
        stars: &super::stars::Stars,
    ) -> Option<Self> {
        let sky = |label, vertex, fragment, buffers, blend| {
            terrain.pipeline(layout, label, vertex, fragment, buffers, None, false, blend)
        };
        Some(Self {
            sky: sky("Late sky", "vs_sky", "fs_sky_base", &[], None)?,
            sunrise: sky(
                "Late sunrise fan",
                "vs_sunrise_late",
                "fs_sunrise",
                sunrise.0,
                Some(sunrise.1),
            )?,
            celestial: terrain.kept.try_create_render_pipeline(
                &wgpu::RenderPipelineDescriptor {
                    label: Some("Late celestial bodies"),
                    vertex: wgpu::VertexState {
                        entry_point: Some("vs_entity_sky"),
                        ..celestial.vertex.clone()
                    },
                    depth_stencil: Some(wgpu::DepthStencilState {
                        depth_compare: Some(wgpu::CompareFunction::GreaterEqual),
                        ..celestial.depth_stencil.clone()?
                    }),
                    ..celestial.clone()
                },
            )?,
            stars: stars.late_pipeline(terrain.kept, terrain.format)?,
            dark_disc: sky(
                "Late dark disc",
                "vs_sky",
                "fs_dark_disc",
                &[],
                Some(dark_disc_blend),
            )?,
        })
    }
}

#[cfg(test)]
mod tests {
    /// Every far-plane entry point the late sky names exists, and the shaders still validate.
    #[test]
    fn the_shaders_have_every_late_entry_point() {
        for (source, names) in [
            (
                super::super::terrain_shader(),
                &["vs_sky", "vs_sunrise_late", "fs_sunrise", "fs_sky_base", "fs_dark_disc"][..],
            ),
            (super::super::entity_shader(), &["vs_entity_sky", "fs_sky_celestial"][..]),
            (
                include_str!("../hdr_light.wgsl").to_owned() + include_str!("stars.wgsl"),
                &["vs_stars_late", "fs_stars"][..],
            ),
        ] {
            let module = naga::front::wgsl::parse_str(&source).unwrap();
            naga::valid::Validator::new(
                naga::valid::ValidationFlags::all(),
                naga::valid::Capabilities::all(),
            )
            .validate(&module)
            .unwrap();
            for name in names {
                assert!(
                    module.entry_points.iter().any(|e| e.name == *name),
                    "{name} is missing"
                );
            }
        }
    }
}
