import { createHash } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { Readable } from 'node:stream';
import { pathToFileURL } from 'node:url';
import {
  MAX_APK_BYTES,
  inspectApk,
  readPreparedDistribution,
  sha256File
} from './prepare-dist.mjs';
import { requireGitee } from './version.mjs';

export const DEFAULT_API_BASE = 'https://gitee.com/api/v5';
const RETRYABLE_STATUS = new Set([429, 500, 502, 503, 504]);

export class GiteeError extends Error {
  constructor(message, { status = 0 } = {}) {
    super(message);
    this.name = 'GiteeError';
    this.status = status;
  }
}

export function redact(value, token) {
  let text = String(value ?? '');
  if (token) text = text.split(token).join('[redacted]');
  return text.replace(/access_token=[^&\s]+/gi, 'access_token=[redacted]').slice(0, 1000);
}

function normalizeBody(value) {
  return String(value ?? '').replace(/\r\n/g, '\n').trim();
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

export class GiteeClient {
  constructor({
    baseUrl = process.env.GITEE_API_BASE || DEFAULT_API_BASE,
    token = process.env.GITEE_TOKEN,
    fetchImpl = globalThis.fetch,
    timeoutMs = 30_000
  } = {}) {
    if (!token) throw new Error('Missing GITEE_TOKEN');
    if (typeof fetchImpl !== 'function') throw new Error('fetch is unavailable');
    this.baseUrl = String(baseUrl).replace(/\/+$/, '');
    this.token = token;
    this.fetchImpl = fetchImpl;
    this.timeoutMs = timeoutMs;
  }

  repoRoute(config) {
    return `/repos/${encodeURIComponent(config.giteeOwner)}/${encodeURIComponent(config.giteeRepo)}`;
  }

  async request(route, {
    method = 'GET',
    json,
    form,
    allow404 = false,
    retries = 0,
    timeoutMs = this.timeoutMs
  } = {}) {
    const headers = {
      Authorization: `Bearer ${this.token}`,
      Accept: 'application/json',
      'User-Agent': 'cppu-library-auto-booker-release'
    };
    let body;
    if (json !== undefined) {
      headers['Content-Type'] = 'application/json';
      body = JSON.stringify(json);
    } else if (form) {
      body = form;
    }

    for (let attempt = 0; ; attempt += 1) {
      let response;
      try {
        response = await this.fetchImpl(`${this.baseUrl}${route}`, {
          method,
          headers,
          body,
          signal: AbortSignal.timeout(timeoutMs)
        });
      } catch (error) {
        if (attempt < retries) {
          await sleep(700 * (attempt + 1));
          continue;
        }
        throw new GiteeError(
          `Gitee request failed (${method} ${route}): ${redact(error.message, this.token)}`
        );
      }

      if (allow404 && response.status === 404) return null;
      if (response.status === 401 || response.status === 403) {
        throw new GiteeError(
          `Gitee authentication failed (${response.status}); check GITEE_TOKEN and its projects scope`,
          { status: response.status }
        );
      }
      if (RETRYABLE_STATUS.has(response.status) && attempt < retries) {
        await sleep(700 * (attempt + 1));
        continue;
      }
      if (!response.ok) {
        const detail = redact(await response.text().catch(() => ''), this.token).slice(0, 400);
        throw new GiteeError(
          `Gitee API failed (${response.status}) for ${method} ${route}${detail ? `: ${detail}` : ''}`,
          { status: response.status }
        );
      }
      if (response.status === 204) return null;

      const text = await response.text();
      if (!text.trim()) return null;
      try {
        return JSON.parse(text);
      } catch {
        throw new GiteeError(
          `Gitee returned invalid JSON for ${method} ${route}: ${redact(text, this.token).slice(0, 200)}`
        );
      }
    }
  }
}

async function getReleaseByTag(client, config, tag) {
  return client.request(
    `${client.repoRoute(config)}/releases/tags/${encodeURIComponent(tag)}`,
    { allow404: true, retries: 2 }
  );
}

async function getRelease(client, config, releaseId) {
  return client.request(
    `${client.repoRoute(config)}/releases/${encodeURIComponent(releaseId)}`,
    { retries: 2 }
  );
}

async function listAssets(client, config, releaseId) {
  const release = await getRelease(client, config, releaseId);
  return Array.isArray(release?.assets) ? release.assets : [];
}

function checkExistingRelease(release, config, notes) {
  if (release.tag_name && release.tag_name !== config.tag) {
    throw new Error(`Gitee returned release ${release.tag_name}, expected ${config.tag}`);
  }
  if (normalizeBody(release.body) && normalizeBody(release.body) !== normalizeBody(notes)) {
    throw new Error(
      `Gitee release ${config.tag} already exists with different notes; refusing to overwrite it`
    );
  }
}

export async function ensureRelease(client, config, notes) {
  const existing = await getReleaseByTag(client, config, config.tag);
  if (existing) {
    checkExistingRelease(existing, config, notes);
    return { release: existing, reused: true };
  }

  let release;
  try {
    release = await client.request(`${client.repoRoute(config)}/releases`, {
      method: 'POST',
      json: {
        tag_name: config.tag,
        name: `图书馆自动预约 ${config.tag}`,
        body: notes,
        target_commitish: config.giteeBranch,
        prerelease: false
      }
    });
  } catch (error) {
    if (error instanceof GiteeError && [400, 409, 422].includes(error.status)) {
      release = await getReleaseByTag(client, config, config.tag);
      if (release) {
        checkExistingRelease(release, config, notes);
        return { release, reused: true };
      }
    }
    throw error;
  }
  if (!release?.id) throw new Error('Gitee did not return a release id');
  return { release, reused: false };
}

async function uploadAsset(client, config, releaseId, file) {
  const form = new FormData();
  form.append('file', new Blob([fs.readFileSync(file.path)]), file.name);
  return client.request(
    `${client.repoRoute(config)}/releases/${encodeURIComponent(releaseId)}/attach_files`,
    { method: 'POST', form, retries: 0, timeoutMs: 180_000 }
  );
}

// Gitee release assets expose no size or checksum, so the only reliable way to
// recognise an already published file is to download it and hash it.
async function downloadAssetDigest(url, {
  fetchImpl = globalThis.fetch,
  timeoutMs = 180_000,
  attempts = 1
} = {}) {
  if (typeof url !== 'string' || !/^https?:\/\//i.test(url)) {
    throw new Error('asset has no usable download URL');
  }
  let lastError;
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    try {
      const response = await fetchImpl(url, {
        headers: {
          'Cache-Control': 'no-cache',
          'User-Agent': 'cppu-library-auto-booker-release'
        },
        redirect: 'follow',
        signal: AbortSignal.timeout(timeoutMs)
      });
      if (!response.ok) throw new Error(`download failed with HTTP ${response.status}`);
      if (!response.body) throw new Error('download returned no content');
      const digest = createHash('sha256');
      let size = 0;
      for await (const chunk of Readable.fromWeb(response.body)) {
        size += chunk.length;
        if (size > MAX_APK_BYTES) throw new Error('download exceeds the asset size limit');
        digest.update(chunk);
      }
      return { sha256: digest.digest('hex'), size };
    } catch (error) {
      lastError = error;
      if (attempt + 1 < attempts) await sleep(2000);
    }
  }
  throw new Error(`download failed: ${lastError?.message ?? 'unknown error'}`);
}

async function assetMatches(asset, expected, options) {
  const remote = await downloadAssetDigest(asset?.browser_download_url, options);
  return remote.size === expected.size && remote.sha256 === expected.sha256;
}

export async function publishAssets(client, config, release, files, fetchOptions = {}) {
  let assets = await listAssets(client, config, release.id);
  const skipped = [];
  const uploaded = [];
  const expectedFiles = files.map((file) => ({
    ...file,
    size: fs.statSync(file.path).size,
    sha256: sha256File(file.path)
  }));

  for (const file of expectedFiles) {
    const existing = assets.find((asset) => asset.name === file.name);
    if (existing) {
      let same;
      try {
        same = await assetMatches(existing, file, fetchOptions);
      } catch (error) {
        throw new Error(`Cannot verify existing Gitee asset ${file.name}: ${error.message}`);
      }
      if (!same) {
        throw new Error(
          `Gitee asset ${file.name} already exists with different content; refusing to overwrite it`
        );
      }
      skipped.push(file.name);
      continue;
    }

    try {
      await uploadAsset(client, config, release.id, file);
    } catch (error) {
      // A lost response can still mean the upload completed; check before failing.
      const after = await listAssets(client, config, release.id).catch(() => []);
      const match = after.find((asset) => asset.name === file.name);
      if (match && await assetMatches(match, file, fetchOptions).catch(() => false)) {
        assets = after;
        uploaded.push(file.name);
        continue;
      }
      throw error;
    }

    assets = await listAssets(client, config, release.id);
    const match = assets.find((asset) => asset.name === file.name);
    if (!match) throw new Error(`Gitee asset ${file.name} is missing after upload`);
    let same;
    try {
      same = await assetMatches(match, file, { ...fetchOptions, attempts: 3 });
    } catch (error) {
      throw new Error(`Cannot verify uploaded Gitee asset ${file.name}: ${error.message}`);
    }
    if (!same) throw new Error(`Gitee asset ${file.name} has unexpected content after upload`);
    uploaded.push(file.name);
  }

  return { uploaded, skipped };
}

async function getManifestContent(client, config) {
  const existing = await client.request(
    `${client.repoRoute(config)}/contents/update.json?ref=${encodeURIComponent(config.giteeBranch)}`,
    { allow404: true, retries: 2 }
  );
  // Gitee answers with an empty array instead of 404 when the path is absent.
  return Array.isArray(existing) ? null : existing;
}

function manifestMatches(existing, content) {
  if (!existing || existing.type !== 'file' || typeof existing.content !== 'string') return false;
  const remote = Buffer.from(existing.content.replace(/\s+/g, ''), 'base64');
  return remote.equals(content);
}

export async function publishManifest(client, config, distDir) {
  const file = path.join(distDir, 'update.json');
  const content = fs.readFileSync(file);
  let lastError;

  for (let attempt = 0; attempt < 2; attempt += 1) {
    const existing = await getManifestContent(client, config);
    if (manifestMatches(existing, content)) {
      return { unchanged: true, sha: existing.sha ?? '' };
    }
    if (existing && existing.type !== 'file') {
      throw new Error('Gitee update.json path is not a file');
    }
    const body = {
      content: content.toString('base64'),
      message: `chore: publish update manifest ${config.tag}`,
      branch: config.giteeBranch
    };
    const method = existing?.sha ? 'PUT' : 'POST';
    if (existing?.sha) body.sha = existing.sha;

    try {
      const result = await client.request(
        `${client.repoRoute(config)}/contents/update.json`,
        { method, json: body, retries: 0 }
      );
      return { unchanged: false, sha: result?.content?.sha ?? '' };
    } catch (error) {
      lastError = error;
      const retryable = !(error instanceof GiteeError) ||
        [400, 404, 409, 422, 429, 500, 502, 503, 504].includes(error.status);
      if (!retryable || attempt === 1) throw error;
      await sleep(1000);
    }
  }
  throw lastError;
}

export async function publishRelease({
  root = process.cwd(),
  dist = 'dist',
  tag,
  client,
  config = requireGitee(root)
} = {}) {
  if (!tag) throw new Error('A release tag is required');
  if (tag !== config.tag) throw new Error(`tag ${tag} does not match ${config.tag}`);
  const prepared = readPreparedDistribution({ root, dist, config });
  const notes = fs.readFileSync(path.join(prepared.dir, 'release-notes.md'), 'utf8');
  const activeClient = client ?? new GiteeClient();

  const { release, reused } = await ensureRelease(activeClient, config, notes);
  const files = [
    { name: config.apkName, path: prepared.apkPath },
    { name: config.downloadAliasName, path: prepared.apkPath },
    ...prepared.files.filter((file) => file.name !== config.apkName)
  ];
  const result = await publishAssets(activeClient, config, release, files);
  return {
    release,
    reused,
    ...result,
    url: config.giteeTagUrl
  };
}

export async function publishGiteeAlias({
  root = process.cwd(),
  tag,
  apk,
  sha256,
  size,
  client,
  config = requireGitee(root)
} = {}) {
  if (!tag || !/^v\d+\.\d+\.\d+$/.test(tag)) {
    throw new Error('A release tag like v1.2.3 is required');
  }
  if (!apk) throw new Error('--apk is required');
  const apkPath = path.resolve(root, apk);
  const actualSize = inspectApk(apkPath);
  if (size !== undefined && size !== null) {
    const expectedSize = Number(size);
    if (!Number.isInteger(expectedSize) || expectedSize <= 0) {
      throw new Error('--size must be a positive integer');
    }
    if (actualSize !== expectedSize) {
      throw new Error(`APK size ${actualSize} does not match expected ${expectedSize}`);
    }
  }
  if (sha256 !== undefined && sha256 !== null) {
    const expectedSha = String(sha256).toLowerCase();
    if (!/^[0-9a-f]{64}$/.test(expectedSha)) {
      throw new Error('--sha256 must be 64 hexadecimal characters');
    }
    const actualSha = sha256File(apkPath);
    if (actualSha !== expectedSha) {
      throw new Error(`APK SHA-256 ${actualSha} does not match expected ${expectedSha}`);
    }
  }

  const activeClient = client ?? new GiteeClient();
  const release = await getReleaseByTag(activeClient, config, tag);
  if (!release) throw new Error(`Gitee release ${tag} does not exist`);
  const result = await publishAssets(activeClient, config, release, [
    { name: config.downloadAliasName, path: apkPath }
  ]);
  return {
    release,
    ...result,
    url: `${config.giteeBase}/releases/tag/${tag}`
  };
}

export async function publishGiteeManifest({
  root = process.cwd(),
  dist = 'dist',
  client,
  config = requireGitee(root)
} = {}) {
  const prepared = readPreparedDistribution({ root, dist, config });
  const activeClient = client ?? new GiteeClient();
  return publishManifest(activeClient, config, prepared.dir);
}

function parseArgs(argv) {
  const command = argv.shift();
  if (!['release', 'manifest', 'alias'].includes(command)) {
    throw new Error('Expected command: release, manifest or alias');
  }
  const options = { command, dist: 'dist', tag: process.env.GITHUB_REF_NAME };
  while (argv.length) {
    const arg = argv.shift();
    if (arg === '--dist' || arg === '--tag' || arg === '--apk' ||
        arg === '--sha256' || arg === '--size') {
      const value = argv.shift();
      if (!value) throw new Error(`${arg} requires a value`);
      options[arg.slice(2)] = value;
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }
  return options;
}

function isMain() {
  return Boolean(process.argv[1]) &&
    import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href;
}

if (isMain()) {
  try {
    const options = parseArgs(process.argv.slice(2));
    if (options.command === 'release') {
      const result = await publishRelease({ dist: options.dist, tag: options.tag });
      console.log(`Gitee release ${result.reused ? 'reused' : 'created'}: ${result.url}`);
      console.log(`  uploaded: ${result.uploaded.join(', ') || 'none'}`);
      console.log(`  already present: ${result.skipped.join(', ') || 'none'}`);
    } else if (options.command === 'manifest') {
      const result = await publishGiteeManifest({ dist: options.dist });
      console.log(result.unchanged
        ? 'Gitee update.json is already up to date'
        : 'Gitee update.json updated');
    } else {
      const result = await publishGiteeAlias({
        tag: options.tag,
        apk: options.apk,
        sha256: options.sha256,
        size: options.size
      });
      console.log(`Gitee alias for ${options.tag}: ${result.url}`);
      console.log(`  uploaded: ${result.uploaded.join(', ') || 'none'}`);
      console.log(`  already present: ${result.skipped.join(', ') || 'none'}`);
    }
  } catch (error) {
    const token = process.env.GITEE_TOKEN;
    console.error(`publish-gitee: ${redact(error?.stack || error?.message || error, token)}`);
    process.exit(2);
  }
}
