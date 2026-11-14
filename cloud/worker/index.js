// Horizon Cloud Performance Profile worker.
// GET /perf-profile.json -> the profile JSON the launcher reads (see CloudPerfProfile.kt).
// Source of truth: the KV key "profile" when a KV namespace is bound as PROFILE_KV, else the embedded copy below.
// Edit the KV value to retune every phone without a new APK. Values are uncalibrated until measured on devices.
const EMBEDDED = {
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

const HEADERS = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "public, max-age=3600",
  "access-control-allow-origin": "*",
};

function valid(p) {
  return p && p.version === 1 && p.tiers && typeof p.tiers === "object";
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method !== "GET" || (url.pathname !== "/perf-profile.json" && url.pathname !== "/")) {
      return new Response("not found", { status: 404 });
    }
    let profile = EMBEDDED;
    if (env.PROFILE_KV) {
      try {
        const stored = await env.PROFILE_KV.get("profile", "json");
        if (valid(stored)) profile = stored;
      } catch (_) {
        // KV failed: serve the embedded copy.
      }
    }
    return new Response(JSON.stringify(profile), { headers: HEADERS });
  },
};
