// Luxium's screen effects in one pass (one effect at a time, picked by `fx_kind`): the air
// distortion sphere (air_distortion.fsh), a shockwave ring (ShockWaveTest) and the black hole
// (blackhole.fsh). Luxium drives them from its own commands; here `/luxium warp|shockwave|blackhole`
// places the effect ahead of the player and the pack is rebuilt, so its age is the time since the
// pack loaded. `warp_cx/cy/cz` is the centre and `warp_r` the size of whichever effect runs.
override fx_kind: i32 = 0;
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

fn warp_effect(input: PostInput) -> vec4<f32> {
    let base = input.color;
    let age = frame.time;
    if age > WARP_SECONDS {
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

// ---- Shockwave: an expanding ring of refracted air that pushes the picture outwards. ----
const SHOCK_SECONDS: f32 = 3.0;

fn shockwave_effect(input: PostInput) -> vec4<f32> {
    let base = input.color;
    let age = frame.time;
    if age > SHOCK_SECONDS || input.depth <= 0.0 {
        return base;
    }
    let t = age / SHOCK_SECONDS;
    let reach = warp_r * 3.0;
    let front = reach * (1.0 - (1.0 - t) * (1.0 - t));
    let width = 1.5 + 0.06 * front;
    let decay = pow(1.0 - t, 1.5);
    let center = vec3<f32>(warp_cx, warp_cy, warp_cz) - frame.camera_position;
    let pos = view_position(input.uv, input.depth);
    let rel = pos - center;
    let d = max(length(rel), 0.001);
    let x = (d - front) / width;
    let ring = exp(-x * x);
    if ring < 0.003 {
        return base;
    }
    let vs = frame.projection * frame.view * vec4<f32>(rel / d, 0.0);
    let dir = normalize(vs.xy + vec2<f32>(0.0001));
    let mag = 0.05 * decay * x * ring * (12.0 / (12.0 + length(pos)));
    let offset = vec2<f32>(dir.x, -dir.y) * mag;
    let uv_r = clamp(input.uv + offset * 1.08, vec2<f32>(0.0), vec2<f32>(1.0));
    let uv_g = clamp(input.uv + offset, vec2<f32>(0.0), vec2<f32>(1.0));
    let uv_b = clamp(input.uv + offset * 0.92, vec2<f32>(0.0), vec2<f32>(1.0));
    var color = vec3<f32>(
        textureSampleLevel(previous, linear_sampler, uv_r, 0.0).r,
        textureSampleLevel(previous, linear_sampler, uv_g, 0.0).g,
        textureSampleLevel(previous, linear_sampler, uv_b, 0.0).b);
    color = color + vec3<f32>(1.0, 0.92, 0.8) * ring * decay * 0.12;
    return vec4<f32>(color, base.a);
}

// ---- Black hole: gravitational lensing, the event horizon and a glowing accretion disc. ----
const BH_SECONDS: f32 = 10.0;
const BH_PI: f32 = 3.14159265359;

fn bh_hash(p: vec2<f32>) -> f32 {
    return fract(sin(dot(p, vec2<f32>(127.1, 311.7))) * 43758.5453123);
}

fn bh_noise(p: vec2<f32>) -> f32 {
    let i = floor(p);
    var f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    let a = bh_hash(i);
    let b = bh_hash(i + vec2<f32>(1.0, 0.0));
    let c = bh_hash(i + vec2<f32>(0.0, 1.0));
    let d = bh_hash(i + vec2<f32>(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

fn bh_fbm(p: vec2<f32>) -> f32 {
    var value = 0.0;
    var amplitude = 0.5;
    var frequency = 1.0;
    for (var i = 0; i < 4; i = i + 1) {
        value = value + amplitude * bh_noise(p * frequency);
        frequency = frequency * 2.0;
        amplitude = amplitude * 0.5;
    }
    return value;
}

fn bh_disc(u: f32, v: f32) -> vec4<f32> {
    let angle = u * 2.0 * BH_PI;
    let rad = v;
    let rotation = angle + rad * 3.0 + frame.time * 0.6 * (1.2 - rad);
    let p = vec2<f32>(cos(rotation), sin(rotation)) * rad * 10.0;
    let base_noise = bh_fbm(p);
    let swirls = sin(angle * 8.0 + rad * 12.0 + base_noise * 2.0) * 0.5 + 0.5;
    let pattern = base_noise * 0.7 + swirls * 0.3;
    var temp = 1.0 - smoothstep(0.0, 0.6, rad);
    temp = pow(temp, 0.7);
    var brightness = pattern * 0.4 + 0.6;
    brightness = brightness * (1.0 + sin(angle * 15.0 + rad * 20.0) * 0.15);
    var col = mix(vec3<f32>(0.6, 0.1, 0.0), vec3<f32>(1.0, 0.4, 0.1), temp / 0.3);
    if temp > 0.85 {
        col = mix(vec3<f32>(1.0, 0.95, 0.8), vec3<f32>(1.2, 1.2, 1.3), (temp - 0.85) / 0.15);
    } else if temp > 0.6 {
        col = mix(vec3<f32>(1.0, 0.8, 0.3), vec3<f32>(1.0, 0.95, 0.8), (temp - 0.6) / 0.25);
    } else if temp > 0.3 {
        col = mix(vec3<f32>(1.0, 0.4, 0.1), vec3<f32>(1.0, 0.8, 0.3), (temp - 0.3) / 0.3);
    }
    col = col * brightness;
    col = col + vec3<f32>(1.0, 0.9, 0.7) * exp(-rad * 8.0) * 2.0;
    let edge_fade = smoothstep(1.0, 0.7, rad);
    return vec4<f32>(col * edge_fade, edge_fade);
}

fn bh_uv(relative: vec3<f32>, fallback: vec2<f32>) -> vec2<f32> {
    let clip = frame.projection * frame.view * vec4<f32>(relative, 0.0);
    if clip.w <= 0.0001 {
        return fallback;
    }
    let ndc = clip.xy / clip.w;
    return vec2<f32>(ndc.x * 0.5 + 0.5, 0.5 - ndc.y * 0.5);
}

fn blackhole_effect(input: PostInput) -> vec4<f32> {
    let base = input.color;
    let age = frame.time;
    if age > BH_SECONDS {
        return base;
    }
    let intensity = smoothstep(0.0, 0.6, age) * (1.0 - smoothstep(BH_SECONDS - 1.5, BH_SECONDS, age));
    if intensity <= 0.001 {
        return base;
    }
    let ray = normalize(view_position(input.uv, 1.0));
    let is_sky = input.depth <= 0.0;
    var scene_dist = 1000.0;
    if !is_sky {
        scene_dist = length(view_position(input.uv, input.depth));
    }

    let rs = max(warp_r * 0.25, 0.5);
    let m = rs * 0.5;
    // Everything below is relative to the black hole; the camera is at `eye`.
    let center = vec3<f32>(warp_cx, warp_cy, warp_cz) - frame.camera_position;
    let eye = -center;
    let r_inner = rs * 1.5;
    let r_outer = rs * 5.0;
    let disc_n = vec3<f32>(0.0, 1.0, 0.0);
    let disc_s = vec3<f32>(1.0, 0.0, 0.0);

    let rm = length(cross(ray, eye));
    let t_cp = sqrt(max(0.0, dot(eye, eye) - rm * rm));
    let front_influence = t_cp - rs * 10.0;

    var uv = input.uv;
    if rm > rs {
        let cp = eye + ray * t_cp;
        let to_center = normalize(-cp);
        let deflection = 4.0 * m / rm;
        let bent = normalize(ray + to_center * deflection);
        let sample_rel = ray * t_cp + bent * max(100.0, rs * 8.0);
        let new_uv = bh_uv(sample_rel, input.uv);
        let influence = smoothstep(0.0, 10.0 * rs, rm);
        if is_sky || scene_dist > front_influence {
            uv = mix(input.uv, new_uv, 0.8 * intensity * (1.0 - influence));
        }
    }
    uv = clamp(uv, vec2<f32>(0.0), vec2<f32>(1.0));
    var bg = textureSampleLevel(previous, linear_sampler, uv, 0.0).rgb;
    let warp_depth = depth_at(uv);
    if warp_depth > 0.0 {
        let warp_dist = length(view_position(uv, warp_depth));
        if warp_dist < front_influence {
            bg = base.rgb;
        }
    }

    if rm < rs {
        let horizon = t_cp - sqrt(rs * rs - rm * rm);
        if !is_sky && scene_dist < horizon {
            return base;
        }
        return vec4<f32>(mix(base.rgb, vec3<f32>(0.0), intensity), 1.0);
    }

    // The accretion disc: the bent ray's two crossings of the disc plane.
    var disc = vec3<f32>(0.0);
    let alpha_ray = 4.0 * m / rm;
    let tan_a_2 = tan(alpha_ray * 0.5);
    let cp = eye + ray * t_cp;
    let coord_origin = cp + ray * (rm * tan_a_2);
    let x_axis = normalize(-coord_origin);
    let y_axis = normalize(ray + tan_a_2 * normalize(-cp));
    let z_axis = cross(x_axis, y_axis);
    let c = length(coord_origin);
    let k = tan_a_2;
    let iline_r = normalize(cross(z_axis, disc_n));
    var x1 = -1.0;
    var x2 = -1.0;
    var y1 = 0.0;
    var y2 = 0.0;
    let k2 = k * k;
    let b2 = c * c / (1.0 + k2);
    let a2 = k2 * b2;
    let denom = dot(x_axis, iline_r);
    if abs(denom) > 0.0001 {
        let slope = dot(y_axis, iline_r) / denom;
        let ks = slope * slope;
        let qa = a2 * ks - b2;
        let qb = -2.0 * a2 * ks * c;
        let qc = a2 * (ks * c * c + b2);
        let delta = qb * qb - 4.0 * qa * qc;
        if delta >= 0.0 && abs(qa) > 0.00001 {
            let sd = sqrt(delta);
            x1 = (-qb - sd) / (2.0 * qa);
            x2 = (-qb + sd) / (2.0 * qa);
            y1 = slope * (x1 - c);
            y2 = slope * (x2 - c);
        }
    }
    let y_eye = dot(eye - coord_origin, y_axis);
    let p1 = coord_origin + x1 * x_axis + y1 * y_axis;
    let d1 = length(p1);
    if x1 >= 0.0 && y1 >= y_eye && d1 >= r_inner && d1 <= r_outer {
        if is_sky || scene_dist > length(p1 - eye) {
            let u = atan2(dot(p1, disc_s), dot(p1, cross(disc_s, disc_n))) / (2.0 * BH_PI);
            let v = (d1 - r_inner) / (r_outer - r_inner);
            let col = bh_disc(u, v);
            let fade = smoothstep(r_inner, r_inner + rs * 0.5, d1) * smoothstep(r_outer, r_outer - rs * 0.8, d1);
            disc = disc + col.rgb * fade * intensity * 1.5;
        }
    }
    let p2 = coord_origin + x2 * x_axis + y2 * y_axis;
    let d2 = length(p2);
    if x2 >= 0.0 && y2 >= y_eye && d2 >= r_inner && d2 <= r_outer {
        if is_sky || scene_dist > length(p2 - eye) {
            let u = atan2(dot(p2, disc_s), dot(p2, cross(disc_s, disc_n))) / (2.0 * BH_PI);
            let v = (d2 - r_inner) / (r_outer - r_inner);
            let col = bh_disc(u, v);
            let fade = smoothstep(r_inner, r_inner + rs * 0.5, d2) * smoothstep(r_outer, r_outer - rs * 0.8, d2);
            disc = disc + col.rgb * fade * intensity * 1.2;
        }
    }
    let to_center = rm / rs;
    let proximity = smoothstep(r_inner, r_outer, to_center * rs);
    let glow = exp(-abs(to_center - 2.5) * 0.8) * intensity * proximity;
    var color = bg + disc + vec3<f32>(1.0, 0.8, 0.5) * glow * 0.4;
    let brightness = dot(disc, vec3<f32>(0.299, 0.587, 0.114));
    color = color + disc * brightness * 0.5;
    color = color / (1.0 + color * 0.12);
    return vec4<f32>(color, base.a);
}

// ---- Explosion: a raymarched fireball that cools into drifting smoke, with a flash and a push. ----
// Stands in for Luxium's particle and fluid-grid explosion (explosion_particle.fsh).
const EXPLOSION_SECONDS: f32 = 4.5;
const FIRE_SECONDS: f32 = 2.2;

fn fire_color(heat: f32) -> vec3<f32> {
    let h = clamp(heat, 0.0, 1.0);
    var c = mix(vec3<f32>(0.35, 0.05, 0.0), vec3<f32>(1.0, 0.35, 0.05), smoothstep(0.0, 0.35, h));
    c = mix(c, vec3<f32>(1.0, 0.75, 0.25), smoothstep(0.3, 0.65, h));
    c = mix(c, vec3<f32>(1.6, 1.5, 1.2), smoothstep(0.65, 1.0, h));
    return c;
}

fn explosion_effect(input: PostInput) -> vec4<f32> {
    let base = input.color;
    let age = frame.time;
    if age > EXPLOSION_SECONDS {
        return base;
    }
    let grow = 1.0 - pow(1.0 - clamp(age / 1.4, 0.0, 1.0), 3.0);
    let radius = max(warp_r * (0.25 + 0.75 * grow), 0.5);
    let center = vec3<f32>(warp_cx, warp_cy, warp_cz) - frame.camera_position;
    let fade = 1.0 - smoothstep(FIRE_SECONDS + 0.8, EXPLOSION_SECONDS, age);
    let fire = 1.0 - smoothstep(0.0, FIRE_SECONDS, age);

    let ray = normalize(view_position(input.uv, 1.0));
    var scene_dist = 1000.0;
    if input.depth > 0.0 {
        scene_dist = length(view_position(input.uv, input.depth));
    }
    var color = base.rgb;

    // Flash: a brief wash of light that falls off with distance from the blast.
    let away = length(center) / max(warp_r, 1.0);
    let flash = exp(-age * 5.0) / (1.0 + away * away);
    color = color + vec3<f32>(1.0, 0.82, 0.55) * flash * 0.45;

    // Where the ray crosses the (slightly inflated) fireball sphere.
    let outer = radius * 1.25;
    let b = dot(ray, center);
    let h = b * b - dot(center, center) + outer * outer;
    if h > 0.0 {
        let root = sqrt(h);
        let t0 = max(b - root, 0.0);
        let t1 = min(b + root, scene_dist);
        if t1 > t0 {
            let steps = 8;
            let dt = (t1 - t0) / f32(steps);
            let jitter = fract(sin(dot(input.uv * 1731.0, vec2<f32>(12.9898, 78.233))) * 43758.5453);
            var trans = 1.0;
            var emit = vec3<f32>(0.0);
            for (var i = 0; i < steps; i = i + 1) {
                let p = ray * (t0 + (f32(i) + jitter) * dt);
                let q = (p - center) / radius;
                let r = length(q);
                let n = fbm3(q * 2.4 + vec3<f32>(0.0, -age * 1.1, 0.0));
                let density = smoothstep(1.05, 0.15, r + (n - 0.5) * 0.9);
                if density > 0.002 {
                    let heat = fire * (1.15 - r * 0.7) * (0.5 + density);
                    let glow = fire_color(heat) * (0.6 + 2.2 * fire);
                    let smoke = vec3<f32>(0.09, 0.08, 0.07) * (0.4 + 0.6 * n);
                    let source = mix(smoke, glow, clamp(heat * 1.5, 0.0, 1.0));
                    let a = (1.0 - exp(-density * dt * 2.2 / radius * 4.0)) * fade;
                    emit = emit + trans * a * source;
                    trans = trans * (1.0 - a);
                }
            }
            color = color * trans + emit;
        }
    }
    return vec4<f32>(color, base.a);
}

fn post(input: PostInput) -> vec4<f32> {
    if !warp_on {
        return input.color;
    }
    if fx_kind == 3 {
        return explosion_effect(input);
    }
    if fx_kind == 1 {
        return shockwave_effect(input);
    }
    if fx_kind == 2 {
        return blackhole_effect(input);
    }
    return warp_effect(input);
}
