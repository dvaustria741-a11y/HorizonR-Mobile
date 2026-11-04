// Rain-wet terrain after Luxium's wet_surface, and Luxium's water surface (the analytic wave
// normals, Fresnel and sun glint of water_surface.glsl; the screen-space reflections are not here).
override wet_on: bool = true;
override water_on: bool = true;
override water_strength: f32 = 1.0;

// Sprite tags (manifest): bit 3 water, bit 4 seagrass and kelp.
fn water_normal(world: vec3<f32>, time: f32) -> vec3<f32> {
    let d0 = vec2<f32>(0.9138, 0.4061);
    let d1 = vec2<f32>(-0.5260, 0.8505);
    var g = d0 * cos(dot(world.xz, d0) * 0.82 + time * 0.72) * 0.21;
    g = g + d1 * cos(dot(world.xz, d1) * 1.37 - time * 0.57) * 0.14;
    return normalize(vec3<f32>(-g.x, 1.0, -g.y));
}

fn material(input: MaterialInput) -> Surface {
    // Derivatives first, where every pixel runs them.
    let gn = normalize(cross(dpdx(input.position), dpdy(input.position)));
    var color = input.albedo * input.tint * vec4<f32>(input.lightmap, 1.0);
    if water_on && (input.tags & 8u) != 0u {
        let top = smoothstep(0.5, 0.9, abs(gn.y));
        let world = input.position + frame.camera_position;
        let n = water_normal(world, frame.time);
        let v = normalize(-input.position);
        let facing = clamp(dot(n, v), 0.0, 1.0);
        let fresnel = 0.02 + 0.98 * pow(1.0 - facing, 5.0);
        let r = reflect(-v, n);
        let sky_up = smoothstep(-0.05, 0.5, r.y);
        let sky = mix(frame.fog_color, frame.sky_color, sky_up);
        let day = smoothstep(-0.1, 0.1, frame.sun_direction.y) * (1.0 - frame.rain * 0.8);
        let glint = pow(max(dot(r, frame.sun_direction), 0.0), 300.0) * 6.0 * day;
        let k = fresnel * 0.85 * top * water_strength;
        let lit = mix(color.rgb, sky * (0.35 + 0.65 * input.light.y), k);
        color = vec4<f32>(lit + vec3<f32>(1.0, 0.95, 0.85) * glint * top * water_strength,
            mix(color.a, 1.0, k * 0.6));
    } else if wet_on && !input.translucent {
        let wet = clamp(frame.wetness, 0.0, 1.0) * smoothstep(0.3, 0.9, input.light.y);
        let dark = 1.0 - 0.28 * wet;
        let sheen = wet * 0.05 * input.light.y;
        color = vec4<f32>(color.rgb * dark + vec3<f32>(sheen), color.a);
    }
    return Surface(color);
}

// ---- Plant wave: Luxium's plantswave (wind_common, grass_wave, leaf_wave) through `displace`. ----
override wave_on: bool = true;
override wave_strength: f32 = 1.0;
override wave_speed: f32 = 1.0;

const WAVE_TAU: f32 = 6.28318530718;
const GRASS_BLADE_HEIGHT: f32 = 0.5;
const GRASS_HALF_PI: f32 = 1.5707963;
const GRASS_MAX_BEND: f32 = 0.263;
const GRASS_PHASE_DIR: vec2<f32> = vec2<f32>(0.796084, 0.605186);

fn wave_hash(cell: vec2<f32>) -> f32 {
    var q = fract(cell * vec2<f32>(0.1031, 0.11369));
    q = q + dot(q, q.yx + vec2<f32>(33.33));
    return fract((q.x + q.y) * q.x);
}

fn wave_noise(position: vec2<f32>) -> f32 {
    let cell = floor(position);
    var local = fract(position);
    local = local * local * (3.0 - 2.0 * local);
    let a = wave_hash(cell);
    let b = wave_hash(cell + vec2<f32>(1.0, 0.0));
    let c = wave_hash(cell + vec2<f32>(0.0, 1.0));
    let d = wave_hash(cell + vec2<f32>(1.0, 1.0));
    return mix(mix(a, b, local.x), mix(c, d, local.x), local.y);
}

fn wave_direction(time: f32) -> vec2<f32> {
    let angle = 0.78 + sin(time * 0.17) * 0.31 + sin(time * 0.047 + 1.9) * 0.18;
    return vec2<f32>(cos(angle), sin(angle));
}

fn wave_gust(world: vec3<f32>, time: f32, gust_strength: f32) -> f32 {
    let phase = wave_noise(world.xz * 0.071) * WAVE_TAU;
    let broad = sin(time * 0.63 + phase + dot(world.xz, vec2<f32>(0.018, -0.013)));
    let pulse = smoothstep(-0.15, 0.92, broad);
    return mix(0.72, 1.0 + gust_strength * 0.58, pulse);
}

fn grass_wave(world: vec3<f32>, time: f32, weight: f32, phase: f32, strength: f32, gust_strength: f32) -> vec3<f32> {
    let phi1 = phase * WAVE_TAU;
    let t = clamp(weight, 0.0, 1.0);
    let bend_factor = t * t;
    let dot1 = world.x * GRASS_PHASE_DIR.x + world.z * GRASS_PHASE_DIR.y;
    let dot2 = world.x * (-GRASS_PHASE_DIR.y) + world.z * GRASS_PHASE_DIR.x;
    let phi2 = phi1 * 1.5 + 1.1;
    let phi_g = phi1 * 0.625;
    let phi_m = phi1 * 3.25;
    let bend1 = sin(0.483322 * dot1 - 1.111641 * time + phi1) * 0.085;
    let bend2 = sin(1.047198 * dot2 - 3.560472 * time + phi2) * 0.030;
    var gust_env = max(0.0, sin(0.285599 * dot1 - 1.427997 * time + phi_g));
    gust_env = gust_env * gust_env * gust_env;
    let gust_scale = gust_strength / 0.65;
    let bend3 = sin(0.869980 * dot1 - 4.175904 * time + phi1 + 0.7) * 0.13 * gust_env * gust_scale;
    let bend4 = sin(5.463267 * time + phi_m) * 0.018;
    let total = bend1 + bend2 + bend3 + bend4;
    let theta = clamp(total / GRASS_MAX_BEND, -1.0, 1.0) * GRASS_HALF_PI;
    let x_displace = GRASS_BLADE_HEIGHT * sin(theta) * bend_factor;
    let y_displace = GRASS_BLADE_HEIGHT * (cos(theta) - 1.0) * bend_factor;
    return vec3<f32>(x_displace * strength, y_displace * strength, 0.0);
}

fn leaf_wave(world: vec3<f32>, time: f32, strength: f32, gust: f32) -> vec3<f32> {
    let phase_position = world.xz * 0.18 + vec2<f32>(world.y * 0.07, -world.y * 0.05);
    let phase = wave_noise(phase_position) * WAVE_TAU;
    let direction = wave_direction(time * 0.55 + phase * 0.04);
    let cross_direction = vec2<f32>(-direction.y, direction.x);
    let along = dot(world.xz, direction);
    let across = dot(world.xz, cross_direction);
    let primary = sin(along * 0.16 + time * 0.82 + phase);
    let secondary = sin(across * 0.13 - time * 0.54 + phase * 0.67);
    let lift = sin(world.y * 0.19 + along * 0.08 + time * 1.13 + phase * 0.31);
    let amplitude = 0.034 * strength * mix(0.90, gust, 0.48);
    return vec3<f32>(primary + secondary * 0.25, lift * 0.16, secondary + primary * 0.18) * amplitude;
}

fn aquatic_wave(world: vec3<f32>, time: f32, weight: f32, strength: f32) -> vec3<f32> {
    let phase = wave_hash(floor(world.xz * 0.16)) * WAVE_TAU;
    let current = normalize(vec2<f32>(0.82 + sin(time * 0.09) * 0.18, 0.46 + cos(time * 0.07) * 0.16));
    let side = vec2<f32>(-current.y, current.x);
    let sway = sin(dot(world.xz, current) * 0.17 + world.y * 0.36 + time * 0.94 + phase);
    let curl = sin(dot(world.xz, side) * 0.11 - world.y * 0.21 - time * 0.52 + phase * 0.43);
    let response = weight * weight;
    let lateral = (current * sway + side * curl * 0.42) * (0.067 * strength * response);
    let lift = sin(time * 0.61 + world.y * 0.33 + phase) * 0.009 * strength * response;
    return vec3<f32>(lateral.x, lift, lateral.y);
}

// Sprite tags (manifest): bit 0 grass and ferns, bit 1 leaves, bit 2 flowers, bit 3 water, bit 4 seagrass and kelp.
fn displace(input: DisplaceInput) -> vec3<f32> {
    if !wave_on || (input.tags & 23u) == 0u {
        return input.position;
    }
    let max_distance = min(frame.render_distance, 128.0);
    let fade = 1.0 - smoothstep(max_distance * 0.72, max_distance, length(input.position));
    if fade <= 0.0 {
        return input.position;
    }
    let world = input.position + frame.camera_position;
    let time = frame.time * wave_speed;
    let strength = wave_strength * fade;
    var offset = vec3<f32>(0.0);
    if (input.tags & 16u) != 0u {
        // Seagrass and kelp drift with the current, rooted at the bottom of the sprite.
        let weight = clamp(1.0 - input.uv.y, 0.0, 1.0);
        offset = aquatic_wave(world, time, weight, strength);
    } else if (input.tags & 2u) != 0u {
        offset = leaf_wave(world, time, strength, wave_gust(world, time, 0.65));
    } else {
        // Stalks bend from the root: the sprite's top is its tip.
        let weight = clamp(1.0 - input.uv.y, 0.0, 1.0);
        let cell = floor(world.xz);
        let phase = wave_hash(cell);
        let scale = select(1.0, 0.7, (input.tags & 4u) != 0u);
        offset = grass_wave(world, time, weight, phase, strength * scale, 0.65);
    }
    return input.position + offset;
}
