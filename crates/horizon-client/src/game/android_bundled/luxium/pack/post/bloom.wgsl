// A soft glow for bright pixels, after Luxium's Kawase blur chain (kawase_down, kawase_up,
// kawase_composite): the bright part of the picture is blurred over two wide rings of taps and
// added back, so lamps, fire, lava and the sun bleed softly into their surroundings.
override bloom_on: bool = true;
override bloom_strength: f32 = 0.35;
override bloom_threshold: f32 = 0.85;

const BLOOM_TAPS: i32 = 8;
const GOLDEN_ANGLE: f32 = 2.39996323;

fn bloom_bright(color: vec3<f32>) -> vec3<f32> {
    let peak = max(color.r, max(color.g, color.b));
    let knee = 0.25;
    var soft = clamp(peak - bloom_threshold + knee, 0.0, 2.0 * knee);
    soft = soft * soft / (4.0 * knee + 0.0001);
    let weight = max(soft, peak - bloom_threshold) / max(peak, 0.0001);
    return color * max(weight, 0.0);
}

fn bloom_ring(uv: vec2<f32>, radius: vec2<f32>, turn: f32) -> vec3<f32> {
    var sum = vec3<f32>(0.0);
    for (var i = 0; i < BLOOM_TAPS; i = i + 1) {
        let angle = f32(i) * GOLDEN_ANGLE + turn;
        let r = sqrt((f32(i) + 0.5) / f32(BLOOM_TAPS));
        let tap = clamp(uv + vec2<f32>(cos(angle), sin(angle)) * r * radius, vec2<f32>(0.0), vec2<f32>(1.0));
        sum = sum + bloom_bright(textureSampleLevel(previous, linear_sampler, tap, 0.0).rgb);
    }
    return sum / f32(BLOOM_TAPS);
}

fn post(input: PostInput) -> vec4<f32> {
    let base = input.color;
    if !bloom_on || bloom_strength <= 0.0 {
        return base;
    }
    let size = vec2<f32>(textureDimensions(previous));
    // Radii in pixels at 720p, scaled with the picture's height.
    let scale = size.y / 720.0;
    let near = vec2<f32>(7.0, 7.0) * scale / size;
    let far = vec2<f32>(22.0, 22.0) * scale / size;
    let glow = bloom_bright(base.rgb) * 0.2 + bloom_ring(input.uv, near, 0.0) * 0.45 + bloom_ring(input.uv, far, 1.3) * 0.35;
    return vec4<f32>(base.rgb + glow * bloom_strength * 1.6, base.a);
}
