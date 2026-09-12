// Temporary config for the L5 conformance run (pi-java repo, docs/23c §2).
// Mirrors the root vitest.base.ts aliases: the package-local vitest.config.ts
// predates the `@earendil-works/pi-ai/utils/*` subpath imports used by
// src/harness/, so its alias list no longer resolves.
import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";

const p = (rel: string) => fileURLToPath(new URL(rel, import.meta.url));

export default defineConfig({
	test: {
		globals: true,
		environment: "node",
		testTimeout: 30000,
		reporters: ["dot"],
		silent: "passed-only",
	},
	resolve: {
		conditions: ["source"],
		alias: [
			{ find: /^@earendil-works\/chord$/, replacement: p("../../packages/chord/src/index.ts") },
			{ find: /^@earendil-works\/pi-telemetry$/, replacement: p("../../packages/telemetry/src/index.ts") },
			{ find: /^@earendil-works\/pi-agent-core$/, replacement: p("./src/index.ts") },
			{ find: /^@earendil-works\/pi-ai$/, replacement: p("../../packages/ai/src/index.ts") },
			{ find: /^@earendil-works\/pi-ai\/compat$/, replacement: p("../../packages/ai/src/compat.ts") },
			{ find: /^@earendil-works\/pi-ai\/oauth$/, replacement: p("../../packages/ai/src/oauth.ts") },
			{ find: /^@earendil-works\/pi-ai\/utils\/(.+)$/, replacement: `${p("../../packages/ai/src/utils")}/$1.ts` },
			{
				find: /^@earendil-works\/pi-ai\/providers\/(.+)$/,
				replacement: `${p("../../packages/ai/src/providers")}/$1.ts`,
			},
		],
	},
	ssr: { resolve: { conditions: ["source"] } },
});
