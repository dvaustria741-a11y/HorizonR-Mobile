//! Equivalent GLSL ES lowering for legacy entity-program linkers.

/// Adreno 3xx's linker may call exit(1) for the dynamic white-overlay table lookup in
/// `vs_cloud`. All sixteen exact OverlayTexture texels are kept, read through constant indices.
/// The caller applies this only to GLES; Vulkan keeps the original WGSL.
pub(super) fn legacy_overlay(source: &str) -> String {
    let mut helper = String::from("fn legacy_white_overlay(u: u32) -> f32 {\n");
    for i in 0..15 {
        helper.push_str(&format!(
            "    if u == {i}u {{ return WHITE_OVERLAY_ALPHA[{i}]; }}\n"
        ));
    }
    helper.push_str("    return WHITE_OVERLAY_ALPHA[15];\n}\n");
    source
        .replacen("fn overlay_texel(", &(helper + "fn overlay_texel("), 1)
        .replace(
            "WHITE_OVERLAY_ALPHA[overlay - 16u]",
            "legacy_white_overlay(overlay - 16u)",
        )
}
