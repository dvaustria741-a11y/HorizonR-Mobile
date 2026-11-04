// Port of Luxium's lensflare.fsh: ghosts with chromatic spread and an anamorphic streak,
// sun by day, moon-styled at night.
override flare_on: bool = true;
override flare_intensity: f32 = 1.0;
override flare_ghosts: f32 = 1.0;
override flare_streak: f32 = 1.0;

const GHOST_SIZE: f32 = 1.0;
const FLARE_SPREAD: f32 = 1.0;
const CHROMA: f32 = 1.0;
const STREAK_LENGTH: f32 = 1.0;
const STREAK_WIDTH: f32 = 1.0;

fn flare_color(uv: vec2<f32>, sun_pos: vec2<f32>, aspect: f32, moon: bool) -> vec3<f32> {
    var center_uv = uv - vec2<f32>(0.5);
    center_uv.x = center_uv.x * aspect;
    var center_sun = sun_pos - vec2<f32>(0.5);
    center_sun.x = center_sun.x * aspect;
    let uvd = center_uv * length(center_uv);
    let chroma_step = 0.05 * CHROMA;
    let inv_size = 1.0 / max(GHOST_SIZE, 0.001);
    let spread = FLARE_SPREAD * center_sun * inv_size;
    var ghosts = vec3<f32>(0.0);

    let base2 = uvd * inv_size + 0.80 * spread;
    let step2 = chroma_step * spread;
    ghosts.r = ghosts.r + max(1.0 / (1.0 + 32.0 * dot(base2, base2)), 0.0) * 0.25;
    ghosts.g = ghosts.g + max(1.0 / (1.0 + 32.0 * dot(base2 + step2, base2 + step2)), 0.0) * 0.23;
    ghosts.b = ghosts.b + max(1.0 / (1.0 + 32.0 * dot(base2 + step2 * 2.0, base2 + step2 * 2.0)), 0.0) * 0.21;

    let uvx4 = mix(center_uv, uvd, -0.5) * inv_size;
    let base4 = uvx4 + 0.40 * spread;
    let step4 = chroma_step * spread;
    ghosts.r = ghosts.r + max(0.01 - dot(base4, base4) * 0.46, 0.0) * 6.0;
    ghosts.g = ghosts.g + max(0.01 - dot(base4 + step4, base4 + step4) * 0.46, 0.0) * 5.0;
    ghosts.b = ghosts.b + max(0.01 - dot(base4 + step4 * 2.0, base4 + step4 * 2.0) * 0.46, 0.0) * 3.0;

    let uvx5 = mix(center_uv, uvd, -0.4) * inv_size;
    let base5 = uvx5 + 0.20 * spread;
    let step5 = chroma_step * 4.0 * spread;
    let d5_1 = dot(base5, base5);
    let d5_2 = dot(base5 + step5, base5 + step5);
    let d5_3 = dot(base5 + step5 * 2.0, base5 + step5 * 2.0);
    ghosts.r = ghosts.r + max(0.01 - d5_1 * d5_1 * 0.28, 0.0) * 2.0;
    ghosts.g = ghosts.g + max(0.01 - d5_2 * d5_2 * 0.28, 0.0) * 2.0;
    ghosts.b = ghosts.b + max(0.01 - d5_3 * d5_3 * 0.28, 0.0) * 2.0;

    let uvx6 = mix(center_uv, uvd, -0.5) * inv_size;
    let base6 = uvx6 - 0.30 * spread;
    let step6 = chroma_step * 0.5 * spread;
    ghosts.r = ghosts.r + max(0.01 - sqrt(dot(base6, base6)) * 0.18, 0.0) * 6.0;
    ghosts.g = ghosts.g + max(0.01 - sqrt(dot(base6 - step6, base6 - step6)) * 0.18, 0.0) * 3.0;
    ghosts.b = ghosts.b + max(0.01 - sqrt(dot(base6 - step6 * 2.0, base6 - step6 * 2.0)) * 0.18, 0.0) * 5.0;
    let tint = select(vec3<f32>(1.4, 1.2, 1.0), vec3<f32>(0.5, 0.7, 1.2), moon);
    ghosts = ghosts * tint * flare_ghosts;

    var streak = 0.0;
    let ana = center_uv - center_sun;
    let streak_y = abs(ana.y);
    let width = 0.02 * STREAK_WIDTH;
    let length_p = 1.5 * STREAK_LENGTH;
    if streak_y < width && abs(ana.x) < length_p {
        let f = (1.0 - smoothstep(0.0, width, streak_y)) * (1.0 - smoothstep(0.0, length_p, abs(ana.x)));
        streak = f * select(0.4, 0.1, moon) * flare_streak;
    }
    let light = select(vec3<f32>(1.0, 0.93, 0.8), vec3<f32>(0.6, 0.75, 1.0), moon);
    return (ghosts + light * streak) * select(0.7, 0.25, moon) * flare_intensity;
}

fn post(input: PostInput) -> vec4<f32> {
    var c = input.color.rgb;
    if !flare_on || frame.dimension != 0 {
        return vec4<f32>(c, input.color.a);
    }
    let moon = frame.sun_direction.y < -0.05;
    let dir = select(frame.sun_direction, frame.moon_direction, moon);
    let facing = frame.view * vec4<f32>(dir, 0.0);
    if facing.z >= 0.0 {
        return vec4<f32>(c, input.color.a);
    }
    let clip = frame.projection * facing;
    let ndc = clip.xy / clip.w;
    let sun_uv = vec2<f32>(ndc.x * 0.5 + 0.5, 0.5 - ndc.y * 0.5);
    let fade = 1.0 - smoothstep(0.9, 1.4, max(abs(ndc.x), abs(ndc.y)));
    // Visible only while the sun (or moon) itself is in the open sky.
    let probe = clamp(sun_uv, vec2<f32>(0.0), vec2<f32>(1.0));
    var seen = 0.0;
    for (var i = 0; i < 4; i = i + 1) {
        let a = f32(i) * 1.5708;
        let o = vec2<f32>(cos(a), sin(a)) * 0.012;
        if depth_at(clamp(probe + o, vec2<f32>(0.0), vec2<f32>(1.0))) <= 0.0 {
            seen = seen + 0.25;
        }
    }
    let day = select(smoothstep(-0.05, 0.15, frame.sun_direction.y), smoothstep(-0.05, 0.15, frame.moon_direction.y) * 0.5, moon);
    let mask = seen * fade * day * (1.0 - frame.rain * 0.9);
    if mask <= 0.0 {
        return vec4<f32>(c, input.color.a);
    }
    let aspect = frame.resolution.x / max(frame.resolution.y, 1.0);
    c = c + flare_color(input.uv, sun_uv, aspect, moon) * mask;
    return vec4<f32>(c, input.color.a);
}
