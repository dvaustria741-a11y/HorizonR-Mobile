# horizon-perf-profile

Serves the Cloud Performance Profile JSON to the launcher.

Deploy (also runs from GitHub Actions on changes here): `npx wrangler deploy`

Test:

    curl -i https://<your-worker>.workers.dev/perf-profile.json
    curl -i -X POST https://<your-worker>.workers.dev/perf-profile.json   # 405

Retune without a deploy (KV namespace `horizon-perf-profile`, key `profile`):

    npx wrangler kv key put profile --path ../perf-profile.json --binding PROFILE_KV --remote
