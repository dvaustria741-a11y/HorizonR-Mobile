// Port of Luxium's tonemap.fsh (LDR path; HDR and LSR sharpen are not ported).
override tm_strength: f32 = 1.0;
override tm_exposure: f32 = 0.0;
override tm_contrast: f32 = 0.35;
override tm_highlights: f32 = 0.5;
override tm_shadow_depth: f32 = 0.4;
override tm_saturation: f32 = 1.05;
override tm_vibrance: f32 = 0.2;

const LUMA: vec3<f32> = vec3<f32>(0.2126, 0.7152, 0.0722);
const TM_GAMMA: f32 = 2.2;

fn filmic(input_color: vec3<f32>) -> vec3<f32> {
    let original = clamp(input_color, vec3<f32>(0.0), vec3<f32>(1.0));
    if tm_strength <= 0.0001 {
        return original;
    }
    var lin = pow(max(input_color, vec3<f32>(0.0)), vec3<f32>(TM_GAMMA));
    lin = lin * exp2(tm_exposure);
    var lum = max(dot(lin, LUMA), 1.0e-6);
    let toe = 1.0 - smoothstep(0.025, 0.34, lum);
    lin = lin * (1.0 - toe * tm_shadow_depth * 0.30);
    lum = max(dot(lin, LUMA), 1.0e-6);

    let white = mix(1.75, 7.5, tm_highlights);
    var mapped_luma = lum * (1.0 + lum / (white * white));
    mapped_luma = clamp(mapped_luma / (1.0 + lum), 0.0, 1.0);
    let s_curve = mapped_luma * mapped_luma * (3.0 - 2.0 * mapped_luma);
    mapped_luma = mix(mapped_luma, s_curve, tm_contrast);

    var mapped = lin * (mapped_luma / lum);
    let ml = dot(mapped, LUMA);
    mapped = mix(vec3<f32>(ml), mapped, tm_saturation);
    let mx = max(mapped.r, max(mapped.g, mapped.b));
    let mn = min(mapped.r, min(mapped.g, mapped.b));
    let chroma = (mx - mn) / max(mx, 1.0e-4);
    let gain = 1.0 + tm_vibrance * (1.0 - clamp(chroma, 0.0, 1.0)) * 0.75;
    mapped = mix(vec3<f32>(dot(mapped, LUMA)), mapped, gain);
    let encoded = pow(clamp(mapped, vec3<f32>(0.0), vec3<f32>(1.0)), vec3<f32>(1.0 / TM_GAMMA));
    return mix(original, encoded, tm_strength);
}

fn post(input: PostInput) -> vec4<f32> {
    return vec4<f32>(clamp(filmic(input.color.rgb), vec3<f32>(0.0), vec3<f32>(1.0)), input.color.a);
}
