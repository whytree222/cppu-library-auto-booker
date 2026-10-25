import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { readPreparedDistribution } from './prepare-dist.mjs';
import { assertHttpsUrl, requireGitee } from './version.mjs';

const MAX_MANIFEST_BYTES = 64 * 1024;
const MAX_APK_BYTES = 100 * 1024 * 1024;

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function cacheBusted(url) {
  const target = new URL(url);
  target.searchParams.set('t', String(Date.now()));
  return target.toString();
}

export function assertManifestMatches(actual, expected) {
  const errors = [];
  if (!actual || typeof actual !== 'object' || Array.isArray(actual)) {
    throw new Error('Manifest is not a JSON object');
  }
  const fields = ['versionCode', 'versionName', 'minSupportedVersionCode', 'sha256', 'size', 'pageUrl'];
  for (const field of fields) {
    if (actual[field] !== expected[field]) {
      errors.push(`${field}: ${JSON.stringify(actual[field])} != ${JSON.stringify(expected[field])}`);
    }
  }
  if (!Array.isArray(actual.apkUrls) ||
      JSON.stringify(actual.apkUrls) !== JSON.stringify(expected.apkUrls)) {
    errors.push(`apkUrls: ${JSON.stringify(actual.apkUrls)} != ${JSON.stringify(expected.apkUrls)}`);
  }
  if (errors.length) throw new Error(`Manifest mismatch: ${errors.join('; ')}`);
  return actual;
}

async function fetchBuffer(url, {
  fetchImpl = globalThis.fetch,
  timeoutMs = 20_000,
  maxBytes = MAX_MANIFEST_BYTES,
  headers = {}
} = {}) {
  const response = await fetchImpl(cacheBusted(url), {
    headers: { 'Cache-Control': 'no-cache', 'User-Agent': 'cppu-library-auto-booker-verify', ...headers },
    redirect: 'follow',
    signal: AbortSignal.timeout(timeoutMs)
  });
  if (!response.ok) throw new Error(`HTTP ${response.status} for ${url}`);
  if (!response.body) throw new Error(`Empty response body for ${url}`);
  const chunks = [];
  let total = 0;
  for await (const chunk of response.body) {
    total += chunk.length;
    if (total > maxBytes) throw new Error(`Response exceeds ${maxBytes} bytes for ${url}`);
    chunks.push(Buffer.from(chunk));
  }
  return Buffer.concat(chunks);
}

export async function waitForManifest(url, expected, {
  fetchImpl = globalThis.fetch,
  timeoutMs = 90_000,
  intervalMs = 3_000
} = {}) {
  const deadline = Date.now() + timeoutMs;
  let lastError;
  while (true) {
    try {
      const body = await fetchBuffer(url, { fetchImpl });
      let parsed;
      try {
        parsed = JSON.parse(body.toString('utf8'));
      } catch (error) {
        throw new Error(`Invalid JSON: ${error.message}`);
      }
      return assertManifestMatches(parsed, expected);
    } catch (error) {
      lastError = error;
      const remaining = deadline - Date.now();
      if (remaining <= 0) {
        throw new Error(`Timed out waiting for ${url}: ${lastError.message}`);
      }
      await sleep(Math.min(intervalMs, remaining));
    }
  }
}

function readManifestFile(file) {
  const stat = fs.statSync(file, { throwIfNoEntry: false });
  if (!stat?.isFile()) throw new Error(`Manifest not found: ${file}`);
  if (stat.size > MAX_MANIFEST_BYTES) throw new Error(`Manifest exceeds ${MAX_MANIFEST_BYTES} bytes`);
  let manifest;
  try {
    manifest = JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (error) {
    throw new Error(`Cannot parse ${file}: ${error.message}`);
  }
  if (!manifest || typeof manifest !== 'object' || Array.isArray(manifest)) {
    throw new Error('Manifest is not a JSON object');
  }
  if (manifest.schema !== 1) throw new Error('Manifest schema must be 1');
  if (!/^[0-9a-f]{64}$/.test(String(manifest.sha256 ?? ''))) {
    throw new Error('Manifest SHA-256 is invalid');
  }
  if (!Number.isInteger(manifest.size) || manifest.size <= 0 || manifest.size > MAX_APK_BYTES) {
    throw new Error('Manifest size is invalid');
  }
  return manifest;
}

export async function sha256Download(url, expectedSize, {
  fetchImpl = globalThis.fetch,
  timeoutMs = 180_000
} = {}) {
  const response = await fetchImpl(cacheBusted(url), {
    headers: {
      'Cache-Control': 'no-cache',
      'User-Agent': 'cppu-library-auto-booker-verify'
    },
    redirect: 'follow',
    signal: AbortSignal.timeout(timeoutMs)
  });
  if (!response.ok) throw new Error(`HTTP ${response.status} for ${url}`);
  if (!response.body) throw new Error(`Empty response body for ${url}`);
  const declared = Number(response.headers.get('content-length'));
  if (Number.isFinite(declared) && declared > 0 && declared !== expectedSize) {
    throw new Error(`Content-Length ${declared} does not match expected ${expectedSize} for ${url}`);
  }

  const hash = crypto.createHash('sha256');
  let total = 0;
  for await (const chunk of response.body) {
    total += chunk.length;
    if (total > expectedSize || total > MAX_APK_BYTES) {
      throw new Error(`Download exceeds expected size for ${url}`);
    }
    hash.update(chunk);
  }
  if (total !== expectedSize) {
    throw new Error(`Downloaded ${total} bytes, expected ${expectedSize} for ${url}`);
  }
  return hash.digest('hex');
}

export async function waitForDownload(url, expected, {
  fetchImpl = globalThis.fetch,
  timeoutMs = 90_000,
  intervalMs = 3_000
} = {}) {
  const deadline = Date.now() + timeoutMs;
  let lastError;
  while (true) {
    try {
      const hash = await sha256Download(url, expected.size, { fetchImpl });
      if (hash !== expected.sha256) {
        throw new Error(`SHA-256 mismatch for ${url}: ${hash} != ${expected.sha256}`);
      }
      return hash;
    } catch (error) {
      lastError = error;
      const remaining = deadline - Date.now();
      if (remaining <= 0) {
        throw new Error(`Timed out waiting for ${url}: ${lastError.message}`);
      }
      await sleep(Math.min(intervalMs, remaining));
    }
  }
}

export async function verifyDownloadAliases({
  root = process.cwd(),
  manifestFile = 'dist/update.json',
  fetchImpl = globalThis.fetch,
  timeoutMs = 90_000,
  intervalMs = 3_000
} = {}) {
  const config = requireGitee(root);
  const expected = readManifestFile(path.resolve(root, manifestFile));
  const urls = [config.giteeDownloadUrl, config.githubDownloadUrl].filter(Boolean);
  if (!urls.length) throw new Error('No fixed download URLs are configured');
  const downloads = [];
  for (const url of urls) {
    const hash = await waitForDownload(url, expected, { fetchImpl, timeoutMs, intervalMs });
    downloads.push({ url, sha256: hash, size: expected.size });
  }
  return {
    versionCode: expected.versionCode,
    versionName: expected.versionName,
    sha256: expected.sha256,
    size: expected.size,
    downloads
  };
}

export async function verifyDownloadUrl({
  root = process.cwd(),
  manifestFile = 'dist/update.json',
  url,
  fetchImpl = globalThis.fetch,
  timeoutMs = 90_000,
  intervalMs = 3_000
} = {}) {
  if (!url) throw new Error('A download URL is required');
  assertHttpsUrl(url, 'download URL');
  const expected = readManifestFile(path.resolve(root, manifestFile));
  const hash = await waitForDownload(url, expected, { fetchImpl, timeoutMs, intervalMs });
  return { url, sha256: hash, size: expected.size };
}

export async function verifyRelease({
  root = process.cwd(),
  dist = 'dist',
  fetchImpl = globalThis.fetch,
  timeoutMs = 90_000,
  intervalMs = 3_000
} = {}) {
  const config = requireGitee(root);
  const prepared = readPreparedDistribution({ root, dist, config });
  const expected = prepared.manifest;

  const [gitee, github] = await Promise.all([
    waitForManifest(config.giteeManifestUrl, expected, { fetchImpl, timeoutMs, intervalMs }),
    waitForManifest(config.githubManifestUrl, expected, { fetchImpl, timeoutMs, intervalMs })
  ]);

  const hashes = [];
  for (const url of expected.apkUrls) {
    const hash = await sha256Download(url, expected.size, { fetchImpl });
    if (hash !== expected.sha256) {
      throw new Error(`SHA-256 mismatch for ${url}: ${hash} != ${expected.sha256}`);
    }
    hashes.push({ url, sha256: hash, size: expected.size });
  }

  const downloads = [];
  for (const url of [config.giteeDownloadUrl, config.githubDownloadUrl].filter(Boolean)) {
    const hash = await waitForDownload(url, expected, { fetchImpl, timeoutMs, intervalMs });
    downloads.push({ url, sha256: hash, size: expected.size });
  }

  return {
    versionCode: expected.versionCode,
    versionName: expected.versionName,
    sha256: expected.sha256,
    size: expected.size,
    gitee,
    github,
    pageUrl: expected.pageUrl,
    hashes,
    downloads
  };
}

function parseArgs(argv) {
  const options = {};
  while (argv.length) {
    const arg = argv.shift();
    if (arg === '--downloads-only') {
      options.downloadsOnly = true;
    } else if (arg === '--dist' || arg === '--manifest' || arg === '--download-url' ||
        arg === '--timeout-ms' || arg === '--interval-ms') {
      const value = argv.shift();
      if (!value) throw new Error(`${arg} requires a value`);
      options[arg.slice(2).replace(/-([a-z])/g, (_, letter) => letter.toUpperCase())] = value;
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }
  const timeoutMs = options.timeoutMs === undefined ? 90_000 : Number(options.timeoutMs);
  const intervalMs = options.intervalMs === undefined ? 3_000 : Number(options.intervalMs);
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) throw new Error('timeout-ms must be positive');
  if (!Number.isFinite(intervalMs) || intervalMs <= 0) throw new Error('interval-ms must be positive');
  if (options.downloadsOnly && options.downloadUrl) {
    throw new Error('--downloads-only and --download-url cannot be combined');
  }
  const dist = options.dist ?? 'dist';
  return {
    dist,
    manifest: options.manifest ?? path.join(dist, 'update.json'),
    downloadsOnly: options.downloadsOnly === true,
    downloadUrl: options.downloadUrl,
    timeoutMs,
    intervalMs
  };
}

function isMain() {
  return Boolean(process.argv[1]) &&
    import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href;
}

if (isMain()) {
  try {
    const options = parseArgs(process.argv.slice(2));
    if (options.downloadUrl) {
      const result = await verifyDownloadUrl({
        manifestFile: options.manifest,
        url: options.downloadUrl,
        timeoutMs: options.timeoutMs,
        intervalMs: options.intervalMs
      });
      console.log(`Verified fixed download: ${result.url}`);
      console.log(`  SHA-256: ${result.sha256}`);
    } else if (options.downloadsOnly) {
      const result = await verifyDownloadAliases({
        manifestFile: options.manifest,
        timeoutMs: options.timeoutMs,
        intervalMs: options.intervalMs
      });
      console.log(`Verified fixed downloads for v${result.versionName} (${result.versionCode})`);
      for (const entry of result.downloads) console.log(`  OK: ${entry.url}`);
    } else {
      const result = await verifyRelease(options);
      console.log(`Verified v${result.versionName} (${result.versionCode})`);
      console.log(`  SHA-256: ${result.sha256}`);
      for (const entry of result.hashes) console.log(`  OK: ${entry.url}`);
      for (const entry of result.downloads) console.log(`  OK (fixed): ${entry.url}`);
    }
  } catch (error) {
    console.error(`verify-release: ${error.message}`);
    process.exit(2);
  }
}
