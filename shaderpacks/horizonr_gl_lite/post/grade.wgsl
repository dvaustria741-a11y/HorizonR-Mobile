override saturation: f32 = 1.15;
override contrast: f32 = 1.08;
override warmth: f32 = 0.35;
override vignette: bool = false;

fn post(input: PostInput) -> vec4<f32> {
    var c = input.color.rgb;

    let day = select(0.0, smoothstep(-0.1, 0.1, frame.sun_direction.y), frame.dimension == 0);
    let low = 1.0 - smoothstep(0.0, 0.35, abs(frame.sun_direction.y));
    let golden = warmth * mix(0.3, 1.0, low) * day * (1.0 - frame.rain * 0.7);
    c = c * mix(vec3<f32>(1.0), vec3<f32>(1.06, 1.0, 0.88), golden);

    c = (c - vec3<f32>(0.5)) * contrast + vec3<f32>(0.5);

    let grey = dot(c, vec3<f32>(0.2126, 0.7152, 0.0722));
    c = mix(vec3<f32>(grey), c, saturation);

    if vignette {
        let d = length(input.uv - vec2<f32>(0.5, 0.5));
        c = c * (1.0 - smoothstep(0.45, 0.9, d) * 0.25);
    }

    return vec4<f32>(clamp(c, vec3<f32>(0.0), vec3<f32>(1.0)), input.color.a);
}
