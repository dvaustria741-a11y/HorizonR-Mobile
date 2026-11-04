// Port of Luxium's air_distortion.fsh: a sphere of compressed, jittering air with chromatic edges.
// Luxium drives it from its own commands; here `/luxium warp` places it ahead of the player and
// the pack is rebuilt, so its age is the time since the pack loaded.
override warp_on: bool = false;
override warp_cx: f32 = 0.0;
override warp_cy: f32 = 0.0;
override warp_cz: f32 = 0.0;
override warp_r: f32 = 8.0;

const DISTORTION_STRENGTH: f32 = 0.25;
const EDGE_SHARPNESS: f32 = 4.0;
const CHROMATIC_ABERRATION: f32 = 0.025;
const EDGE_DARKENING: f32 = 0.4;
const JITTER_SCALE: f32 = 35.0;
const JITTER_SPEED: f32 = 20.0;
const JITTER_STRENGTH: f32 = 0.15;
const WARP_SECONDS: f32 = 4.0;

fn hash3(p0: vec3<f32>) -> f32 {
    var p = fract(p0 * 0.3183099 + vec3<f32>(0.1));
    p = p * 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

fn noise3(x: vec3<f32>) -> f32 {
    let i = floor(x);
    var f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(
            mix(hash3(i + vec3<f32>(0.0, 0.0, 0.0)), hash3(i + vec3<f32>(1.0, 0.0, 0.0)), f.x),
            mix(hash3(i + vec3<f32>(0.0, 1.0, 0.0)), hash3(i + vec3<f32>(1.0, 1.0, 0.0)), f.x),
            f.y),
        mix(
            mix(hash3(i + vec3<f32>(0.0, 0.0, 1.0)), hash3(i + vec3<f32>(1.0, 0.0, 1.0)), f.x),
            mix(hash3(i + vec3<f32>(0.0, 1.0, 1.0)), hash3(i + vec3<f32>(1.0, 1.0, 1.0)), f.x),
            f.y),
        f.z);
}

fn fbm3(p0: vec3<f32>) -> f32 {
    var p = p0;
    var f = 0.0;
    var amp = 0.5;
    for (var i = 0; i < 3; i = i + 1) {
        f = f + amp * noise3(p);
        p = p * 2.0;
        amp = amp * 0.5;
    }
    return f;
}

fn post(input: PostInput) -> vec4<f32> {
    let base = input.color;
    let age = frame.time;
    if !warp_on || age > WARP_SECONDS {
        return base;
    }
    let intensity = smoothstep(0.0, 0.25, age) * (1.0 - smoothstep(2.5, WARP_SECONDS, age));
    let radius = warp_r * mix(0.2, 1.0, smoothstep(0.0, 1.2, age));

    // Everything is camera-relative, so the camera is the origin.
    let center = vec3<f32>(warp_cx, warp_cy, warp_cz) - frame.camera_position;
    let ray = normalize(view_position(input.uv, 1.0));
    let is_sky = input.depth <= 0.0;
    var scene_dist = 1000.0;
    if !is_sky {
        scene_dist = length(view_position(input.uv, input.depth));
    }

    let oc = -center;
    let b = dot(oc, ray);
    let c = dot(oc, oc) - radius * radius;
    let h = b * b - c;
    if h <= 0.0 {
        return base;
    }
    let sq = sqrt(h);
    let t1 = -b - sq;
    let t2 = -b + sq;
    var hit = t1;
    var inside = false;
    if t1 < 0.0 {
        hit = t2;
        inside = true;
        if t2 < 0.0 {
            return base;
        }
    }
    let depth_diff = select(scene_dist - hit, 1000.0, is_sky);
    if depth_diff <= 0.0 {
        return base;
    }
    let depth_fade = smoothstep(0.0, radius * 0.2, depth_diff);

    let hit_pos = ray * hit;
    var normal = normalize(hit_pos - center);
    if inside {
        normal = -normal;
    }
    let facing = abs(dot(-ray, normal));
    let ring = pow(1.0 - facing, EDGE_SHARPNESS);

    let noise_pos = hit_pos * JITTER_SCALE - vec3<f32>(0.0, age * JITTER_SPEED, 0.0);
    let jx = (fbm3(noise_pos) - 0.5) * 2.0;
    let jy = (fbm3(noise_pos + vec3<f32>(15.2, -22.4, 31.8)) - 0.5) * 2.0;
    let turbulence = vec2<f32>(jx, jy) * JITTER_STRENGTH;

    let vs = frame.projection * frame.view * vec4<f32>(normal, 0.0);
    let screen_normal = normalize(vs.xy + vec2<f32>(0.0001));
    var offset = (screen_normal * ring + turbulence * ring) * DISTORTION_STRENGTH * intensity * depth_fade;
    offset = vec2<f32>(offset.x, -offset.y) * 0.5;

    let uv = input.uv;
    let uv_r = clamp(uv + offset * (1.0 + CHROMATIC_ABERRATION), vec2<f32>(0.0), vec2<f32>(1.0));
    let uv_g = clamp(uv + offset, vec2<f32>(0.0), vec2<f32>(1.0));
    let uv_b = clamp(uv + offset * (1.0 - CHROMATIC_ABERRATION), vec2<f32>(0.0), vec2<f32>(1.0));
    var color = vec3<f32>(
        textureSampleLevel(previous, linear_sampler, uv_r, 0.0).r,
        textureSampleLevel(previous, linear_sampler, uv_g, 0.0).g,
        textureSampleLevel(previous, linear_sampler, uv_b, 0.0).b);
    color = color * (1.0 - ring * EDGE_DARKENING * intensity);
    color = color + color * abs(jx) * ring * 0.2 * intensity;
    return vec4<f32>(color, base.a);
}
