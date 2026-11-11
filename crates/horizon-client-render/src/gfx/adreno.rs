//! What only Adreno on Android's Vulkan takes (ported from Starling, MIT). Every other GPU keeps
//! the usual pipelines and draw order.

/// Adreno on Android's Vulkan.
pub(super) fn adreno_vulkan(info: &wgpu::AdapterInfo) -> bool {
    cfg!(target_os = "android")
        && info.backend == wgpu::Backend::Vulkan
        && info.name.contains("Adreno")
}

/// Whether solid terrain draws in two passes (opaque quads without a discard, then cutout
/// ones): where a discard costs the GPU its hidden surface removal. Apple's tiled GPUs behind
/// Metal, and Adreno on Android's Vulkan (Starling measured 18.8 -> 13.6 ms GPU on an
/// Adreno 710). Elsewhere the second pass would double the solid draw calls for nothing.
pub(super) fn splits_solid(info: &wgpu::AdapterInfo) -> bool {
    info.backend == wgpu::Backend::Metal || adreno_vulkan(info)
}

/// What these paths' pipelines of terrain.wgsl share. Unlike `Renderer::new`'s own, they may fail:
/// the frame then keeps the usual pipeline.
pub(super) struct TerrainModule<'a> {
    pub kept: &'a super::reuse::On<'a>,
    pub shader: &'a wgpu::ShaderModule,
    pub format: wgpu::TextureFormat,
    /// For the fragment stage, as `Renderer::new`'s own pipelines give it.
    pub constants: &'a [(&'a str, f64)],
    /// For the vertex stage: what `Renderer::new`'s own pipelines give it is nothing.
    pub vertex_constants: &'a [(&'a str, f64)],
}

impl TerrainModule<'_> {
    /// A pipeline testing depth, as `pipeline_in` in `Renderer::new` builds one.
    #[allow(clippy::too_many_arguments)]
    pub fn pipeline(
        &self,
        layout: &wgpu::PipelineLayout,
        label: &str,
        vertex: &str,
        fragment: &str,
        buffers: &[Option<wgpu::VertexBufferLayout<'_>>],
        cull: Option<wgpu::Face>,
        depth_write: bool,
        blend: Option<wgpu::BlendState>,
    ) -> Option<wgpu::RenderPipeline> {
        self.kept
            .try_create_render_pipeline(&wgpu::RenderPipelineDescriptor {
                label: Some(label),
                layout: Some(layout),
                vertex: wgpu::VertexState {
                    module: self.shader,
                    entry_point: Some(vertex),
                    compilation_options: wgpu::PipelineCompilationOptions {
                        constants: self.vertex_constants,
                        ..Default::default()
                    },
                    buffers,
                },
                primitive: wgpu::PrimitiveState {
                    topology: wgpu::PrimitiveTopology::TriangleList,
                    cull_mode: cull,
                    ..Default::default()
                },
                depth_stencil: Some(wgpu::DepthStencilState {
                    format: wgpu::TextureFormat::Depth32Float,
                    depth_write_enabled: Some(depth_write),
                    depth_compare: Some(wgpu::CompareFunction::GreaterEqual),
                    stencil: Default::default(),
                    bias: Default::default(),
                }),
                multisample: Default::default(),
                fragment: Some(wgpu::FragmentState {
                    module: self.shader,
                    entry_point: Some(fragment),
                    compilation_options: wgpu::PipelineCompilationOptions {
                        constants: self.constants,
                        ..Default::default()
                    },
                    targets: &[Some(wgpu::ColorTargetState {
                        format: self.format,
                        blend,
                        write_mask: wgpu::ColorWrites::ALL,
                    })],
                }),
                multiview_mask: None,
                cache: None,
            })
    }
}
