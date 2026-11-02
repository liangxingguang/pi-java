#!/usr/bin/env node
/**
 * Regenerate pi-java's built-in model catalog resources from committed snapshots.
 *
 * Usage:
 *   node catalog-generator/generate-java-catalog.ts             # generate resources from snapshots
 *   node catalog-generator/generate-java-catalog.ts --check     # compare resources with snapshots, no writes
 *   node catalog-generator/generate-java-catalog.ts --fetch     # fetch upstream snapshots, then generate
 *   node catalog-generator/generate-java-catalog.ts --verify-vendor
 *
 * The generator never touches the network unless --fetch is passed. Builds and
 * runtime do not depend on Node; this script is a maintainer tool only.
 */
import { createHash } from "node:crypto";
import {
	existsSync,
	mkdirSync,
	mkdtempSync,
	readFileSync,
	rmSync,
	writeFileSync,
} from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import { buildCatalog, type ProviderCatalog } from "./vendor/catalog/build.ts";
import type { ProviderDefinition } from "./vendor/catalog/define.ts";
import type { CatalogSources } from "./vendor/catalog/sources/index.ts";
import { MODELS_DEV_URL, type ModelsDevV2Catalog } from "./vendor/catalog/sources/models-dev.ts";
import { buildOpenRouterCatalog, type OpenRouterCatalog } from "./vendor/catalog/sources/openrouter.ts";
import {
	createModelDataManifest,
	groupProviderModelData,
	MODEL_DATA_MANIFEST_FILE,
	type ModelDataStructure,
	validateModelDataDirectory,
} from "./vendor/scripts/model-data.ts";
import { getRadiusModelsFromConfig, type RadiusGatewayConfig } from "./vendor/src/providers/radius-config.ts";
import type { Model } from "./vendor/src/types.ts";

import antLing from "./vendor/catalog/providers/ant-ling.ts";
import anthropic from "./vendor/catalog/providers/anthropic.ts";
import deepseek from "./vendor/catalog/providers/deepseek.ts";
import google from "./vendor/catalog/providers/google.ts";
import minimax from "./vendor/catalog/providers/minimax.ts";
import minimaxCn from "./vendor/catalog/providers/minimax-cn.ts";
import mistral from "./vendor/catalog/providers/mistral.ts";
import moonshotai from "./vendor/catalog/providers/moonshotai.ts";
import moonshotaiCn from "./vendor/catalog/providers/moonshotai-cn.ts";
import openai from "./vendor/catalog/providers/openai.ts";
import openrouter from "./vendor/catalog/providers/openrouter.ts";
import qwenTokenPlanCn from "./vendor/catalog/providers/qwen-token-plan-cn.ts";
import radius from "./vendor/catalog/providers/radius.ts";
import xai from "./vendor/catalog/providers/xai.ts";
import xiaomi from "./vendor/catalog/providers/xiaomi.ts";
import xiaomiTokenPlanCn from "./vendor/catalog/providers/xiaomi-token-plan-cn.ts";
import zai from "./vendor/catalog/providers/zai.ts";
import zaiCodingCn from "./vendor/catalog/providers/zai-coding-cn.ts";

const HERE = dirname(fileURLToPath(import.meta.url));
const SNAPSHOT_DIR = join(HERE, "snapshots");
const RESOURCE_DIR = resolve(HERE, "../src/main/resources/catalog");
const SOURCES_FILE = "SOURCES.json";

/** The 18 providers pi-java registers that carry generated data. */
const PROVIDER_IDS = [
	"anthropic", "openai", "google", "deepseek", "mistral",
	"moonshotai", "moonshotai-cn", "zai", "zai-coding-cn",
	"qwen-token-plan-cn", "xiaomi", "xiaomi-token-plan-cn",
	"minimax", "minimax-cn", "ant-ling", "openrouter", "xai", "radius",
] as const;

/** models.dev provider keys read by those 18 definitions (verified from vendor sources). */
const MODELS_DEV_PROVIDER_KEYS = [
	"anthropic", "openai", "google", "mistral",
	"moonshotai", "moonshotai-cn",
	"zai", "zai-coding-plan", "zhipuai-coding-plan",
	"alibaba-token-plan-cn",
	"xiaomi", "xiaomi-token-plan-cn",
	"minimax", "minimax-cn", "xai",
] as const;

const OPENROUTER_MODELS_URL = "https://openrouter.ai/api/v1/models";
const OPENROUTER_IMAGES_URL = `${OPENROUTER_MODELS_URL}?output_modalities=image`;
const RADIUS_CONFIG_URL = "https://radius.pi.dev/v1/config";

const PROVIDERS: readonly ProviderDefinition[] = [
	anthropic, openai, google, deepseek, mistral,
	moonshotai, moonshotaiCn, zai, zaiCodingCn,
	qwenTokenPlanCn, xiaomi, xiaomiTokenPlanCn,
	minimax, minimaxCn, antLing, openrouter, xai, radius,
];

interface GeneratorInputs {
	modelsDev: ModelsDevV2Catalog;
	openRouter: OpenRouterCatalog;
	radius: Model<"pi-messages">[];
}

interface ModelsDevInput {
	url: string;
	fetchedAt: string;
	upstreamBytes: number;
	upstreamSha256: string;
	providerKeys: string[];
	stored: string;
	storedSha256: string;
}

interface OpenRouterInput {
	url: string;
	imageUrl: string;
	fetchedAt: string;
	stored: string[];
	sha256: Record<string, string>;
}

interface RadiusInput {
	url: string;
	fetchedAt: string;
	stored: string;
	sha256: string;
}

interface Snapshots {
	anchor: string;
	generatedAt: string;
	inputs: {
		modelsDev: ModelsDevInput;
		openrouter: OpenRouterInput;
		radius: RadiusInput;
	};
}

interface Options {
	check: boolean;
	fetch: boolean;
	verifyVendor: boolean;
}

function sha256(value: string | Uint8Array): string {
	return createHash("sha256").update(value).digest("hex");
}

function readJson<T>(path: string): T {
	return JSON.parse(readFileSync(path, "utf8")) as T;
}

function readSnapshots(): Snapshots {
	return readJson<Snapshots>(join(SNAPSHOT_DIR, SOURCES_FILE));
}

function assertStoredHash(file: string, expected: string): void {
	const path = join(SNAPSHOT_DIR, file);
	if (!existsSync(path)) throw new Error(`snapshot file is missing: ${file}`);
	const actual = sha256(readFileSync(path));
	if (actual !== expected) {
		throw new Error(`snapshot ${file} does not match SOURCES.json (expected ${expected}, got ${actual})`);
	}
}

/** Validate snapshot metadata and build the three vendor catalog sources. */
function loadSnapshots(): GeneratorInputs {
	const snapshots = readSnapshots();
	const vendor = readJson<{ anchor: string }>(join(HERE, "VENDOR.json"));
	if (snapshots.anchor !== vendor.anchor) {
		throw new Error(`SOURCES.json anchor ${snapshots.anchor} does not match VENDOR.json anchor ${vendor.anchor}`);
	}
	if (typeof snapshots.generatedAt !== "string" || Number.isNaN(Date.parse(snapshots.generatedAt))) {
		throw new Error("SOURCES.json has no valid generatedAt");
	}

	const modelsDevInput = snapshots.inputs.modelsDev;
	assertStoredHash(modelsDevInput.stored, modelsDevInput.storedSha256);
	const rawModelsDev = readJson<ModelsDevV2Catalog>(join(SNAPSHOT_DIR, modelsDevInput.stored));
	const modelsDev: ModelsDevV2Catalog = {};
	for (const key of MODELS_DEV_PROVIDER_KEYS) {
		const entry = rawModelsDev[key];
		if (entry === undefined) {
			throw new Error(`models.dev snapshot is missing required provider key: ${key}`);
		}
		modelsDev[key] = entry;
	}

	const openRouterInput = snapshots.inputs.openrouter;
	for (const file of openRouterInput.stored) {
		assertStoredHash(file, openRouterInput.sha256[file] ?? "");
	}
	const listed = readJson<{ data?: unknown[] }>(join(SNAPSHOT_DIR, "openrouter-models.json")).data ?? [];
	const imageListed = readJson<{ data?: unknown[] }>(join(SNAPSHOT_DIR, "openrouter-images.json")).data ?? [];
	const openRouter = buildOpenRouterCatalog(
		listed as Parameters<typeof buildOpenRouterCatalog>[0],
		imageListed as Parameters<typeof buildOpenRouterCatalog>[1],
		[],
	);

	const radiusInput = snapshots.inputs.radius;
	assertStoredHash(radiusInput.stored, radiusInput.sha256);
	const radiusConfig = readJson<RadiusGatewayConfig>(join(SNAPSHOT_DIR, radiusInput.stored));
	if (typeof radiusConfig.baseUrl !== "string" || !Array.isArray(radiusConfig.models)) {
		throw new Error("radius-config.json must carry a baseUrl string and a models array");
	}

	return { modelsDev, openRouter, radius: getRadiusModelsFromConfig("radius", radiusConfig) };
}

function catalogSources(inputs: GeneratorInputs): CatalogSources {
	return {
		modelsDev: inputs.modelsDev,
		modelsDevDecisions: {},
		openRouter: inputs.openRouter,
		aiGateway: { chat: [], classifiers: [] },
		nvidiaModelIds: new Map(),
		radius: inputs.radius,
	};
}

interface GeneratedCatalog {
	files: Record<string, string>;
	manifest: string;
	generatedAt: string;
	counts: Record<string, { chat: number; image: number }>;
}

/** Build the 18 shards and the manifest. Classifier entries are dropped (R5). */
function buildJavaCatalog(inputs: GeneratorInputs): GeneratedCatalog {
	const catalogs = buildCatalog(PROVIDERS, catalogSources(inputs), true);
	const shards: Record<string, Record<string, Record<string, unknown>>> = {};
	const structure: ModelDataStructure = {};
	const counts: Record<string, { chat: number; image: number }> = {};

	for (const providerId of PROVIDER_IDS) {
		const catalog: ProviderCatalog | undefined = catalogs[providerId];
		if (!catalog) throw new Error(`vendor build produced no catalog for ${providerId}`);
		const entries = [...catalog.chat, ...catalog.image];
		if (catalog.chat.length === 0) {
			throw new Error(`provider ${providerId} produced no chat models; pi-java requires a shard`);
		}
		const grouped = groupProviderModelData(providerId, entries);
		shards[providerId] = grouped.groups;
		structure[providerId] = grouped.structure;
		counts[providerId] = { chat: catalog.chat.length, image: catalog.image.length };
	}

	const files: Record<string, string> = {};
	for (const providerId of PROVIDER_IDS) {
		files[`${providerId}.json`] = `${JSON.stringify(shards[providerId])}\n`;
	}
	const generatedAt = readSnapshots().generatedAt;
	const manifest = `${JSON.stringify(createModelDataManifest(structure, files, generatedAt))}\n`;
	return { files, manifest, generatedAt, counts };
}

function attributions(generated: GeneratedCatalog): string {
	const lines = [
		"The JSON files in this directory are generated by",
		"pi-java-ai/catalog-generator/generate-java-catalog.ts.",
		"",
		"The generation rules are copied verbatim from pi",
		"https://github.com/earendil-works/pi (MIT License, Copyright (c) 2025 Mario Zechner),",
		"at anchor 56cae782341c339d3bb820081f0f9425d8c5b1ec (packages/ai/catalog/).",
		"See catalog-generator/VENDOR.json for per-file sha256 hashes.",
		"",
		"Upstream data:",
		"- models.dev v2 catalog: https://models.dev/experimental/v2.0/api.json (MIT License)",
		"- OpenRouter model listings: https://openrouter.ai/api/v1/models",
		"- Radius public gateway catalog: https://radius.pi.dev/v1/config",
		"Raw input hashes and fetch times are pinned in catalog-generator/snapshots/SOURCES.json.",
		"",
		`Manifest: schemaVersion 6, generatedAt ${generated.generatedAt}.`,
		`Carried shards: ${PROVIDER_IDS.length}.`,
		"",
	];
	return lines.join("\n");
}

function writeResourceDirectory(target: string, generated: GeneratedCatalog): void {
	mkdirSync(target, { recursive: true });
	for (const [file, content] of Object.entries(generated.files)) {
		writeFileSync(join(target, file), content);
	}
	writeFileSync(join(target, MODEL_DATA_MANIFEST_FILE), generated.manifest);
	writeFileSync(join(target, "ATTRIBUTION.txt"), attributions(generated));
}

function structureOf(generated: GeneratedCatalog): ModelDataStructure {
	const structure: ModelDataStructure = {};
	for (const [file, content] of Object.entries(generated.files)) {
		const providerId = file.slice(0, -".json".length);
		const groups = JSON.parse(content) as Record<string, Record<string, unknown>>;
		structure[providerId] = {};
		for (const [api, entries] of Object.entries(groups)) {
			for (const modelKey of Object.keys(entries)) structure[providerId][modelKey] = api;
		}
	}
	return structure;
}

/** Generate into a temp directory, validate it, then compare (--check) or install. */
function writeAndValidate(generated: GeneratedCatalog, check: boolean): boolean {
	const stagingRoot = mkdtempSync(join(HERE, ".model-generation-"));
	try {
		writeResourceDirectory(stagingRoot, generated);
		validateModelDataDirectory(structureOf(generated), stagingRoot);
		const differences: string[] = [];
		for (const name of [...Object.keys(generated.files), MODEL_DATA_MANIFEST_FILE, "ATTRIBUTION.txt"]) {
			const expected = readFileSync(join(stagingRoot, name));
			const actualPath = join(RESOURCE_DIR, name);
			if (!existsSync(actualPath) || !expected.equals(readFileSync(actualPath))) {
				differences.push(name);
			}
		}
		if (check) {
			if (differences.length > 0) {
				console.error(`Catalog resources are stale: ${differences.join(", ")}`);
				return false;
			}
			console.log("Catalog resources match the committed snapshots.");
			return true;
		}
		for (const name of Object.keys(generated.files)) {
			writeFileSync(join(RESOURCE_DIR, name), readFileSync(join(stagingRoot, name)));
		}
		writeFileSync(join(RESOURCE_DIR, MODEL_DATA_MANIFEST_FILE), readFileSync(join(stagingRoot, MODEL_DATA_MANIFEST_FILE)));
		writeFileSync(join(RESOURCE_DIR, "ATTRIBUTION.txt"), readFileSync(join(stagingRoot, "ATTRIBUTION.txt")));
		const totalChat = Object.values(generated.counts).reduce((sum, c) => sum + c.chat, 0);
		const totalImage = Object.values(generated.counts).reduce((sum, c) => sum + c.image, 0);
		console.log(
			`Wrote ${PROVIDER_IDS.length} shards (${totalChat} chat, ${totalImage} image), generatedAt ${generated.generatedAt}.`,
		);
		const lines = Object.entries(generated.counts).map(([id, c]) => `${id}: chat=${c.chat} image=${c.image}`);
		console.log(lines.join("\n"));
		return true;
	} finally {
		rmSync(stagingRoot, { recursive: true, force: true });
	}
}

async function fetchText(url: string): Promise<string> {
	const response = await fetch(url);
	if (!response.ok) throw new Error(`${url} returned ${response.status}`);
	return await response.text();
}

/** Fetch every upstream snapshot. Only runs for the explicit --fetch flag. */
async function fetchSnapshots(): Promise<void> {
	const startedAt = new Date().toISOString();
	const [modelsDevRaw, openRouterRaw, openRouterImagesRaw, radiusRaw] = await Promise.all([
		fetchText(MODELS_DEV_URL),
		fetchText(OPENROUTER_MODELS_URL),
		fetchText(OPENROUTER_IMAGES_URL),
		fetchText(RADIUS_CONFIG_URL),
	]);

	const modelsDevParsed = JSON.parse(modelsDevRaw) as ModelsDevV2Catalog;
	const filtered: ModelsDevV2Catalog = {};
	for (const key of MODELS_DEV_PROVIDER_KEYS) {
		if (modelsDevParsed[key] === undefined) {
			throw new Error(`models.dev response is missing required provider key: ${key}`);
		}
		filtered[key] = modelsDevParsed[key];
	}
	const modelsDevStored = `${JSON.stringify(filtered)}\n`;
	writeFileSync(join(SNAPSHOT_DIR, "models-dev-v2.json"), modelsDevStored);
	writeFileSync(join(SNAPSHOT_DIR, "openrouter-models.json"), openRouterRaw);
	writeFileSync(join(SNAPSHOT_DIR, "openrouter-images.json"), openRouterImagesRaw);
	writeFileSync(join(SNAPSHOT_DIR, "radius-config.json"), radiusRaw);

	const fetchedAt = new Date().toISOString();
	const sources: Snapshots = {
		anchor: readJson<{ anchor: string }>(join(HERE, "VENDOR.json")).anchor,
		generatedAt: startedAt > fetchedAt ? startedAt : fetchedAt,
		inputs: {
			modelsDev: {
				url: MODELS_DEV_URL,
				fetchedAt,
				upstreamBytes: Buffer.byteLength(modelsDevRaw),
				upstreamSha256: sha256(modelsDevRaw),
				providerKeys: [...MODELS_DEV_PROVIDER_KEYS],
				stored: "models-dev-v2.json",
				storedSha256: sha256(modelsDevStored),
			},
			openrouter: {
				url: OPENROUTER_MODELS_URL,
				imageUrl: OPENROUTER_IMAGES_URL,
				fetchedAt,
				stored: ["openrouter-models.json", "openrouter-images.json"],
				sha256: {
					"openrouter-models.json": sha256(openRouterRaw),
					"openrouter-images.json": sha256(openRouterImagesRaw),
				},
			},
			radius: {
				url: RADIUS_CONFIG_URL,
				fetchedAt,
				stored: "radius-config.json",
				sha256: sha256(radiusRaw),
			},
		},
	};
	writeFileSync(join(SNAPSHOT_DIR, SOURCES_FILE), `${JSON.stringify(sources, null, 2)}\n`);
	console.log(`Fetched upstream snapshots at ${fetchedAt}.`);
}

function verifyVendor(): boolean {
	const vendor = readJson<{ anchor: string; files: { path: string; sha256: string }[] }>(
		join(HERE, "VENDOR.json"),
	);
	let ok = true;
	for (const file of vendor.files) {
		const path = join(HERE, file.path);
		if (!existsSync(path)) {
			console.error(`missing vendored file: ${file.path}`);
			ok = false;
			continue;
		}
		const actual = sha256(readFileSync(path));
		if (actual !== file.sha256) {
			console.error(`vendored file hash mismatch: ${file.path}`);
			ok = false;
		}
	}
	if (ok) console.log(`${vendor.files.length} vendored files match VENDOR.json (${vendor.anchor}).`);
	return ok;
}

function parseOptions(args: string[]): Options {
	const options: Options = { check: false, fetch: false, verifyVendor: false };
	for (const arg of args) {
		if (arg === "--check") options.check = true;
		else if (arg === "--fetch") options.fetch = true;
		else if (arg === "--verify-vendor") options.verifyVendor = true;
		else throw new Error(`Unknown argument: ${arg}`);
	}
	return options;
}

async function main(): Promise<void> {
	const options = parseOptions(process.argv.slice(2));
	if (!verifyVendor()) process.exit(1);
	if (options.verifyVendor) return;
	if (options.fetch) await fetchSnapshots();
	const generated = buildJavaCatalog(loadSnapshots());
	if (!writeAndValidate(generated, options.check)) process.exit(1);
}

main().catch((error: unknown) => {
	console.error(error instanceof Error ? error.message : String(error));
	process.exit(1);
});
