# catalog-generator

Regenerates `pi-java-ai/src/main/resources/catalog/` from committed upstream
snapshots. **This is a maintainer tool.** Maven builds and the application do
not depend on Node; nothing here is compiled or packaged.

## Requirements

- Node.js ≥ 24 (native TypeScript type stripping; the vendored `.ts` files are
  imported directly, with no build step).

## Commands

```bash
# Regenerate resources from the committed snapshots (never touches the network).
node pi-java-ai/catalog-generator/generate-java-catalog.ts

# Verify resources are byte-identical to what the snapshots produce.
node pi-java-ai/catalog-generator/generate-java-catalog.ts --check

# Verify vendored rules still match VENDOR.json hashes.
node pi-java-ai/catalog-generator/generate-java-catalog.ts --verify-vendor

# Refresh the snapshots from upstream, then regenerate (network required).
node pi-java-ai/catalog-generator/generate-java-catalog.ts --fetch
```

`--fetch` rewrites `snapshots/SOURCES.json` and the four snapshot files. Review
the diff before committing: the snapshot is what makes generation reproducible.

## Layout

- `vendor/` — byte-exact copies of pi's catalog rules at the pinned anchor.
  `VENDOR.json` records the anchor, the source path and the sha256 of every
  file. Do not edit vendored files; re-vendor them all when the anchor moves.
- `snapshots/` — raw upstream data, pinned by `SOURCES.json` (URLs, fetch
  times, upstream hashes, stored hashes).
  - `models-dev-v2.json` holds only the 15 provider keys Java consumes.
    Filtering drops top-level keys only; model entries are untouched, and the
    full-response hash remains recorded in `SOURCES.json`.
  - `openrouter-models.json` / `openrouter-images.json` are the raw listings.
  - `radius-config.json` is the raw public `/v1/config` response.
- `generate-java-catalog.ts` — the repo-specific driver: loads snapshots,
  instantiates the 18 provider definitions Java registers, writes chat + image
  entries (classifier entries are dropped), and emits `.manifest.json`.

## Rules

1. `SOURCES.json#generatedAt` is the maximum input `fetchedAt`, not the time
   the generation command ran; the same snapshots always produce the same
   manifest.
2. The manifest lists only the 18 shards that are actually carried.
3. `--check` exits non-zero and names stale files when resources differ.
4. `ATTRIBUTION.txt` is rewritten together with the shards.
