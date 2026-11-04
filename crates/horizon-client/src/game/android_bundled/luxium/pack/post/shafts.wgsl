// Screen-space light shafts toward the sun. Luxium's godrays_post marches its own
// block-light volume, which the engine has no equivalent of, so this is the
// sun-only approximation of the same look.
override shafts_on: bool = true;
override shafts_strength: f32 = 0.35;

fn post(input: PostInput) -> vec4<f32> {
    var c = input.color.rgb;
    if !shafts_on || frame.dimension != 0 {
        return vec4<f32>(c, input.color.a);
    }
    let sun = frame.sun_direction;
    let day = smoothstep(-0.05, 0.2, sun.y);
    let facing = frame.view * vec4<f32>(sun, 0.0);
    if day <= 0.0 || facing.z >= 0.0 {
        return vec4<f32>(c, input.color.a);
    }
    let clip = frame.projection * facing;
    let ndc = clip.xy / clip.w;
    let sun_uv = vec2<f32>(ndc.x * 0.5 + 0.5, 0.5 - ndc.y * 0.5);

    let steps = 20;
    let delta = (sun_uv - input.uv) * (0.9 / f32(steps));
    var pos = input.uv;
    var acc = 0.0;
    var weight = 1.0;
    for (var i = 0; i < steps; i = i + 1) {
        pos = pos + delta;
        let inside = pos.x > 0.0 && pos.x < 1.0 && pos.y > 0.0 && pos.y < 1.0;
        if inside && depth_at(pos) <= 0.0 {
            acc = acc + weight;
        }
        weight = weight * 0.94;
    }
    let to_sun = clamp(1.0 - length(sun_uv - input.uv) * 0.8, 0.0, 1.0);
    let energy = acc / f32(steps) * to_sun * day * shafts_strength * (1.0 - frame.rain * 0.8);
    c = c + vec3<f32>(1.0, 0.92, 0.75) * energy;
    return vec4<f32>(c, input.color.a);
}
