// Luxium's sky, from sky_bake.fsh: day phases, the Rayleigh/Mie atmosphere and the sun glow.
// Clouds, stars and the milky way are left to the engine, which draws them over this.
override sky_on: bool = true;
override sky_strength: f32 = 0.85;

fn sqr(x: f32) -> f32 { return x * x; }

fn sun_light_color(sun_y: f32) -> vec3<f32> {
    let warm = 1.0 - smoothstep(0.03, 0.38, sun_y);
    let low_sun = vec3<f32>(1.000, 0.315, 0.075);
    let mid_sun = vec3<f32>(1.000, 0.690, 0.355);
    let high_sun = vec3<f32>(1.000, 0.965, 0.885);
    var c = mix(mid_sun, high_sun, smoothstep(0.05, 0.55, sun_y));
    c = mix(c, low_sun, warm * (1.0 - smoothstep(-0.05, 0.08, sun_y)));
    return c;
}

fn sky(input: SkyInput) -> vec4<f32> {
    let vanilla = input.color;
    if !sky_on || frame.dimension != 0 {
        return vanilla;
    }
    let rd = input.direction;
    let sun = frame.sun_direction;
    let sun_y = sun.y;

    let daylight = smoothstep(-0.115, 0.035, sun_y);
    let twilight = exp(-sqr((sun_y + 0.025) / 0.145)) * (1.0 - 0.30 * smoothstep(0.11, 0.35, sun_y));

    let y = rd.y;
    let upper = max(y, 0.0);
    let zenith = pow(clamp(upper, 0.0, 1.0), 0.36);
    let sun_xz = normalize(sun.xz + vec2<f32>(0.0001, 0.0));
    let ray_xz = normalize(rd.xz + vec2<f32>(0.0001, 0.0));
    let sun_az = pow(clamp(dot(ray_xz, sun_xz) * 0.5 + 0.5, 0.0, 1.0), 3.2);

    let day_base = mix(vec3<f32>(0.475, 0.705, 0.930), vec3<f32>(0.025, 0.165, 0.505), zenith);
    let night_base = mix(vec3<f32>(0.014, 0.023, 0.047), vec3<f32>(0.0017, 0.0037, 0.0125), pow(zenith, 0.72));
    let day_mix = clamp(max(daylight, twilight * 0.42), 0.0, 1.0);
    var col = mix(night_base, day_base, day_mix);

    let mu = clamp(dot(rd, sun), -1.0, 1.0);
    let rayleigh = 0.75 * (1.0 + mu * mu);
    let g = 0.76;
    let mie_den = max(1.0 + g * g - 2.0 * g * mu, 0.0025);
    let mie = (1.0 - g * g) / pow(mie_den, 1.5);
    let air_mass = 1.0 / max(0.16 + upper, 0.16);
    let low_alt = clamp((air_mass - 0.85) / 4.0, 0.0, 1.0);

    let sun_color = sun_light_color(sun_y);
    let horizon_band = exp(-abs(y) * 7.5);
    col = col * (0.82 + 0.18 * rayleigh);
    col = col + sun_color * horizon_band * sun_az * twilight * (0.55 + 0.45 * low_alt);
    col = col + sun_color * mie * 0.012 * daylight * (0.4 + 0.6 * low_alt);

    // Below the horizon and in rain the engine's own colour (fog) carries the picture.
    let above = smoothstep(-0.02, 0.06, y);
    let keep = clamp(sky_strength * above * (1.0 - frame.rain * 0.85), 0.0, 1.0);
    return vec4<f32>(mix(vanilla.rgb, col, keep), vanilla.a);
}
