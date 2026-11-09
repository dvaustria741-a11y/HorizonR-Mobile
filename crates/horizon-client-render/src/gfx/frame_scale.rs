//! Scale the complete Android frame with the existing post-process copy pass.
use super::world_post;

pub(super) struct FrameScale {
    post: world_post::Post,
    /// The swapchain's format: what the scaled frame is drawn onto.
    format: wgpu::TextureFormat,
    /// The frame's own format: what every pass of the frame draws in.
    frame_format: wgpu::TextureFormat,
    target: Option<(wgpu::Texture, wgpu::BindGroup)>,
}

impl FrameScale {
    pub(super) fn new(
        device: &wgpu::Device,
        format: wgpu::TextureFormat,
        frame_format: wgpu::TextureFormat,
    ) -> Self {
        Self {
            post: world_post::Post::new(device, format),
            format,
            frame_format,
            target: None,
        }
    }

    /// The swapchain format this scaler was built for (sRGB suffix removed).
    pub(super) fn format(&self) -> wgpu::TextureFormat {
        self.format
    }

    pub(super) fn frame(&mut self, device: &wgpu::Device, size: [u32; 2]) -> wgpu::Texture {
        if self
            .target
            .as_ref()
            .is_none_or(|(texture, _)| [texture.width(), texture.height()] != size)
        {
            let texture = device.create_texture(&wgpu::TextureDescriptor {
                label: Some("Android client frame"),
                size: wgpu::Extent3d {
                    width: size[0],
                    height: size[1],
                    depth_or_array_layers: 1,
                },
                mip_level_count: 1,
                sample_count: 1,
                dimension: wgpu::TextureDimension::D2,
                format: self.frame_format,
                usage: wgpu::TextureUsages::RENDER_ATTACHMENT
                    | wgpu::TextureUsages::TEXTURE_BINDING
                    | wgpu::TextureUsages::COPY_SRC,
                view_formats: &[],
            });
            let group = self
                .post
                .bind(device, &texture.create_view(&Default::default()));
            self.target = Some((texture, group));
        }
        self.target.as_ref().expect("frame target").0.clone()
    }

    pub(super) fn present(&self, encoder: &mut wgpu::CommandEncoder, output: &wgpu::Texture) {
        if let Some((_, group)) = &self.target {
            // Only ask for a view format the swapchain texture can give: its own format, or the
            // sRGB twin the surface was configured with. A mismatched format (Bgra8Unorm view of
            // an Rgba8Unorm texture) is a fatal wgpu validation error.
            let view_format = (output.format().remove_srgb_suffix()
                == self.format.remove_srgb_suffix())
            .then_some(self.format);
            let view = output.create_view(&wgpu::TextureViewDescriptor {
                format: view_format,
                ..Default::default()
            });
            self.post.finish_in_rect(
                encoder,
                &view,
                group,
                Some(winit::platform::android::native_content_rect([
                    output.width(),
                    output.height(),
                ])),
                None,
            );
        }
    }
}
