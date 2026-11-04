// Rain-wet terrain, after Luxium's wet_surface: darker, slightly glossier in the lit sky.
override wet_on: bool = true;

fn material(input: MaterialInput) -> Surface {
    var color = input.albedo * input.tint * vec4<f32>(input.lightmap, 1.0);
    if wet_on && !input.translucent {
        let wet = clamp(frame.wetness, 0.0, 1.0) * smoothstep(0.3, 0.9, input.light.y);
        let dark = 1.0 - 0.28 * wet;
        let sheen = wet * 0.05 * input.light.y;
        color = vec4<f32>(color.rgb * dark + vec3<f32>(sheen), color.a);
    }
    return Surface(color);
}
