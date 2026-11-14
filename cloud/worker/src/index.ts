// Horizon Cloud Performance Profile worker.
// GET /perf-profile.json returns the profile the launcher reads (CloudPerfProfile.kt).
// Source of truth: KV key "profile" in PROFILE_KV when it holds a valid profile, else the embedded copy below.
// Retune every phone without a new APK by writing a new value to that key. Values are uncalibrated until measured.

interface Env {
  PROFILE_KV: KVNamespace;
}

interface PerfProfile {
  version: 1;
  calibrated?: boolean;
  note?: string;
  tiers: Record<string, Record<string, string | number | boolean>>;
  socs?: Record<string, string>;
}

const EMBEDDED: PerfProfile = {
  "version": 1,
  "calibrated": false,
  "note": "Starting guesses, not measured. Tiers come from total RAM (low <= 4.5 GB, mid <= 8.5 GB, high above). 'socs' maps Build.SOC_MODEL (Android 12+) to a tier and overrides RAM. Only keys the launcher whitelists are applied, each clamped to a safe range.",
  "tiers": {
    "low": {
      "resolutionScale": 80,
      "dynamicResolution": true,
      "dynamicResolutionMin": 60,
      "adaptiveDistance": true,
      "adaptiveDistanceMin": 6,
      "animatedTextures": false
    },
    "mid": {
      "dynamicResolution": true,
      "dynamicResolutionMin": 75,
      "adaptiveDistance": true,
      "adaptiveDistanceMin": 8
    },
    "high": {
      "adaptiveDistance": true,
      "adaptiveDistanceMin": 10
    }
  },
  "socs": {}
};

const HEADERS: HeadersInit = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "public, max-age=3600",
  "access-control-allow-origin": "*",
  "x-content-type-options": "nosniff",
};

/** Same shape check the launcher applies: version 1 and an object of tiers. Keys and ranges are re-checked on the phone. */
function isProfile(value: unknown): value is PerfProfile {
  if (typeof value !== "object" || value === null) return false;
  const profile = value as Partial<PerfProfile>;
  return profile.version === 1 && typeof profile.tiers === "object" && profile.tiers !== null;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: { ...HEADERS, "access-control-allow-methods": "GET, HEAD, OPTIONS" } });
    }
    if (request.method !== "GET" && request.method !== "HEAD") {
      return new Response("method not allowed", { status: 405, headers: { allow: "GET, HEAD, OPTIONS" } });
    }
    if (url.pathname !== "/perf-profile.json" && url.pathname !== "/") {
      return new Response("not found", { status: 404 });
    }

    let profile: PerfProfile = EMBEDDED;
    try {
      const stored = await env.PROFILE_KV.get<unknown>("profile", "json");
      if (isProfile(stored)) {
        profile = stored;
      } else if (stored !== null) {
        console.warn("KV key 'profile' is not a valid profile, serving the embedded copy");
      }
    } catch (error) {
      console.error("KV read failed, serving the embedded copy:", error);
    }

    return new Response(request.method === "HEAD" ? null : JSON.stringify(profile), { headers: HEADERS });
  },
} satisfies ExportedHandler<Env>;
