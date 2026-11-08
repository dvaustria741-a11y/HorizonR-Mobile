//! Legacy GLES lowering. Vulkan retains the original WGSL byte for byte.
use std::borrow::Cow;

/// The terrain shader as the GPU's API takes it: Vulkan keeps upstream's `terrain_shader` byte
/// for byte, GLES gets the lowering below.
pub(super) fn terrain_shader_for(gl: bool) -> Cow<'static, str> {
    let source = super::terrain_shader();
    if gl {
        Cow::Owned(gles_source(&source))
    } else {
        Cow::Owned(source)
    }
}

fn gles_source(source: &str) -> String {
    let source = source.replace("\r\n", "\n");
    let start = source
        .find("fn sprite_grad(")
        .expect("terrain sprite function");
    let level = source
        .find("fn sprite_level(")
        .expect("terrain level function");
    let end = level + source[level..].find("\n}\n").expect("terrain level end") + 3;
    let rgss = source
        .find("fn sample_rgss(")
        .expect("terrain RGSS function");
    let grid = source
        .find("fn rotated_grid(")
        .expect("terrain rotated grid function");
    let rgss_end = grid + source[grid..].find("\n}\n").expect("terrain RGSS end") + 3;
    let mut output = String::with_capacity(source.len());
    output.push_str(&source[..start]);
    output.push_str(include_str!("terrain_gles.wgsl.inc"));
    output.push_str(&source[end..rgss]);
    output.push_str(&source[rgss_end..]);

    // Adreno 304 renders broken terrain with the separated scalar varyings.
    // Pack values sharing their interpolation qualifier; leave the vertex
    // buffers, texture sampling, fade, lighting, fog and HDR arithmetic alone.
    let start = output
        .find("struct TerrainOut {")
        .expect("terrain interface");
    let end = output[start..]
        .find("struct SkyOut {")
        .expect("sky interface")
        + start;
    let fields_end = output[start..]
        .find("\n}\n")
        .expect("terrain interface end")
        + start
        + 3;
    let mut body = output[fields_end..end].to_owned();
    for (member, packed) in [
        ("layer", "codes.x"),
        ("emission", "codes.y"),
        ("fade", "codes.z"),
        ("emissive_layer", "codes.w"),
        ("spherical", "distances.x"),
        ("cylindrical", "distances.y"),
        ("visibility", "distances.z"),
        ("height", "distances.w"),
    ] {
        for variable in ["in", "out"] {
            body = body.replace(
                &format!("{variable}.{member}"),
                &format!("{variable}.{packed}"),
            );
        }
    }
    output.replace_range(start..end, &(PACKED_INTERFACE.to_owned() + &body));
    output
}

const PACKED_INTERFACE: &str = "struct TerrainOut {
    @builtin(position) clip: vec4<f32>,
    @location(0) uv: vec2<f32>,
    @location(1) @interpolate(flat) codes: vec4<u32>,
    @location(2) color: vec4<f32>,
    @location(3) distances: vec4<f32>,
    @location(4) local: vec3<f32>,
    @location(5) scene: vec2<f32>,
}
";

#[cfg(test)]
#[path = "terrain_source/hdr_gpu_tests.rs"]
mod hdr_gpu_tests;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn vulkan_source_is_unchanged_and_gles_validates_every_entrypoint() {
        assert_eq!(terrain_shader_for(false), super::super::terrain_shader());
        let source = terrain_shader_for(true);
        let original = super::super::terrain_shader().replace("\r\n", "\n");
        assert_eq!(
            &source[source.find("struct SkyOut {").unwrap()..],
            &original[original.find("struct SkyOut {").unwrap()..],
            "sky and outline shader bodies must remain unchanged"
        );
        assert!(!source.contains("switch layer / SPRITE_SPLIT"));
        assert!(!source.contains("uv + offsets[i] * pixel"));
        for index in 0..7 {
            assert!(source.contains(&format!("array_index == {index}u")));
        }
        assert!(source.contains("if in.codes.w != 0xffffffffu"));
        assert!(!source.contains("in.emissive_layer"));
        assert_eq!(source.matches("fn sample_rgss(").count(), 1);
        assert_eq!(source.matches("fn rotated_grid(").count(), 1);
        let module = naga::front::wgsl::parse_str(&source).unwrap();
        naga::valid::Validator::new(
            naga::valid::ValidationFlags::all(),
            naga::valid::Capabilities::all(),
        )
        .validate(&module)
        .unwrap();
        let vertex = module
            .entry_points
            .iter()
            .find(|e| e.name == "vs_terrain")
            .unwrap();
        let naga::TypeInner::Struct { members, .. } =
            &module.types[vertex.function.result.as_ref().unwrap().ty].inner
        else {
            panic!("terrain result must be a struct")
        };
        assert_eq!(
            members
                .iter()
                .filter(|m| matches!(m.binding, Some(naga::Binding::Location { .. })))
                .count(),
            6
        );
    }

    #[test]
    fn windows_line_endings_produce_the_same_gles_shader() {
        let source = super::super::terrain_shader().replace("\r\n", "\n");
        assert_eq!(
            gles_source(&source),
            gles_source(&source.replace('\n', "\r\n"))
        );
    }
}
