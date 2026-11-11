//! Shader pack programs without wgpu's runtime safety checks, for a player who asks for it
//! (the launcher's "Trust shader packs" in Experimental Stuff sets `HORIZON_PACK_TRUSTED_SHADERS`).
//!
//! wgpu normally guards every array and texture access of a shader with a bounds check and every
//! loop with an iteration counter. A pack that stays in bounds and terminates draws the same
//! pixels without them, in less GPU time. A broken pack can no longer be caught: out-of-range
//! reads give whatever the driver returns, and an endless loop stops the GPU instead of ending.
use std::sync::OnceLock;

fn trusted() -> bool {
    static ON: OnceLock<bool> = OnceLock::new();
    *ON.get_or_init(|| std::env::var("HORIZON_PACK_TRUSTED_SHADERS").is_ok_and(|v| v == "1"))
}

/// `Device::create_shader_module` for a pack's program: unchecked where the player trusts packs.
pub(super) fn module(
    device: &wgpu::Device,
    descriptor: wgpu::ShaderModuleDescriptor<'_>,
) -> wgpu::ShaderModule {
    if !trusted() {
        return device.create_shader_module(descriptor);
    }
    horizon_hdr::create_unchecked_shader_module(device, descriptor)
}
