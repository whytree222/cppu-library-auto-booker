import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

export const VERSION_FILE = 'version.properties';
export const DISTRIBUTION_FILE = 'distribution.properties';
export const DOWNLOAD_ALIAS_NAME = 'library-auto-booker.apk';

const SEMVER = /^\d+\.\d+\.\d+$/;
const OWNER = /^[A-Za-z0-9][A-Za-z0-9_.-]{0,99}$/;
const REPO = /^[A-Za-z0-9_.-]{1,100}$/;
const BRANCH = /^[A-Za-z0-9._-]{1,100}$/;
const GITHUB_REPO = /^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/;

export function parseProperties(text, source = 'properties') {
  const values = {};
  for (const raw of String(text).split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const eq = line.indexOf('=');
    if (eq <= 0) throw new Error(`${source}: invalid line "${line}"`);
    const key = line.slice(0, eq).trim();
    const value = line.slice(eq + 1).trim();
    if (!key) throw new Error(`${source}: empty key`);
    if (Object.hasOwn(values, key)) throw new Error(`${source}: duplicate key ${key}`);
    values[key] = value;
  }
  return values;
}

function readRequired(root, name) {
  const file = path.join(root, name);
  if (!fs.existsSync(file)) throw new Error(`Missing ${name}`);
  return fs.readFileSync(file, 'utf8');
}

export function readVersion(root = process.cwd()) {
  const values = parseProperties(readRequired(root, VERSION_FILE), VERSION_FILE);
  const versionCode = Number(values.versionCode);
  const versionName = values.versionName ?? '';
  const minSupportedVersionCode = Number(values.minSupportedVersionCode ?? values.versionCode);
  if (!Number.isInteger(versionCode) || versionCode <= 0 || versionCode > 2_100_000_000) {
    throw new Error(`${VERSION_FILE}: versionCode must be a positive integer`);
  }
  if (!SEMVER.test(versionName)) {
    throw new Error(`${VERSION_FILE}: versionName must look like 1.2.3`);
  }
  if (!Number.isInteger(minSupportedVersionCode) ||
      minSupportedVersionCode <= 0 ||
      minSupportedVersionCode > versionCode) {
    throw new Error(`${VERSION_FILE}: minSupportedVersionCode must be between 1 and versionCode`);
  }
  return {
    versionCode,
    versionName,
    minSupportedVersionCode,
    tag: `v${versionName}`,
    apkName: `library-auto-booker-${versionName}.apk`,
    notesFile: `release-notes/${versionName}.md`
  };
}

export function readDistribution(root = process.cwd()) {
  const values = parseProperties(readRequired(root, DISTRIBUTION_FILE), DISTRIBUTION_FILE);
  const giteeOwner = (values.giteeOwner ?? '').trim();
  const giteeRepo = (values.giteeRepo ?? '').trim();
  const giteeBranch = (values.giteeBranch ?? 'main').trim();
  const githubRepo = (values.githubRepo ?? '').trim();
  if (giteeOwner && !OWNER.test(giteeOwner)) throw new Error(`${DISTRIBUTION_FILE}: invalid giteeOwner`);
  if (!REPO.test(giteeRepo)) throw new Error(`${DISTRIBUTION_FILE}: invalid giteeRepo`);
  if (!BRANCH.test(giteeBranch)) throw new Error(`${DISTRIBUTION_FILE}: invalid giteeBranch`);
  if (!GITHUB_REPO.test(githubRepo)) throw new Error(`${DISTRIBUTION_FILE}: invalid githubRepo`);
  return { giteeOwner, giteeRepo, giteeBranch, githubRepo };
}

export function assertHttpsUrl(value, name) {
  let url;
  try {
    url = new URL(value);
  } catch {
    throw new Error(`${name} is not a valid URL`);
  }
  if (url.protocol !== 'https:') throw new Error(`${name} must use HTTPS`);
  if (url.username || url.password) throw new Error(`${name} must not contain user information`);
  if (url.port && url.port !== '443') throw new Error(`${name} must use the default HTTPS port`);
  if (!url.hostname) throw new Error(`${name} must have a host`);
  return url;
}

export function releaseConfig(root = process.cwd()) {
  const version = readVersion(root);
  const distribution = readDistribution(root);
  const giteeBase = distribution.giteeOwner
    ? `https://gitee.com/${distribution.giteeOwner}/${distribution.giteeRepo}`
    : '';
  const githubBase = `https://github.com/${distribution.githubRepo}`;
  const giteeReleaseUrl = giteeBase ? `${giteeBase}/releases/latest` : '';
  const giteeTagUrl = giteeBase ? `${giteeBase}/releases/tag/${version.tag}` : '';
  const giteeApkUrl = giteeBase ? `${giteeBase}/releases/download/${version.tag}/${version.apkName}` : '';
  const giteeManifestUrl = giteeBase
    ? `${giteeBase}/raw/${distribution.giteeBranch}/update.json`
    : '';
  const downloadAliasName = DOWNLOAD_ALIAS_NAME;
  // Gitee and GitHub expose different stable-asset paths.
  const giteeDownloadUrl = giteeBase
    ? `${giteeBase}/releases/download/latest/${downloadAliasName}`
    : '';
  const githubReleaseUrl = `${githubBase}/releases/latest`;
  const githubTagUrl = `${githubBase}/releases/tag/${version.tag}`;
  const githubApkUrl = `${githubBase}/releases/download/${version.tag}/${version.apkName}`;
  const githubManifestUrl = `${githubBase}/releases/latest/download/update.json`;
  const githubDownloadUrl = `${githubBase}/releases/latest/download/${downloadAliasName}`;
  const pageUrl = giteeTagUrl || githubTagUrl;
  for (const [name, value] of Object.entries({
    giteeManifestUrl,
    githubManifestUrl,
    pageUrl,
    giteeApkUrl,
    githubApkUrl,
    giteeDownloadUrl,
    githubDownloadUrl
  })) {
    if (value) assertHttpsUrl(value, name);
  }
  return {
    ...version,
    ...distribution,
    giteeBase,
    giteeReleaseUrl,
    giteeTagUrl,
    giteeApkUrl,
    giteeManifestUrl,
    downloadAliasName,
    giteeDownloadUrl,
    githubReleaseUrl,
    githubTagUrl,
    githubApkUrl,
    githubManifestUrl,
    githubDownloadUrl,
    downloadUrls: [giteeDownloadUrl, githubDownloadUrl].filter(Boolean),
    manifestUrl: giteeManifestUrl,
    fallbackManifestUrl: githubManifestUrl,
    pageUrl,
    apkUrls: [giteeApkUrl, githubApkUrl].filter(Boolean)
  };
}

export function requireGitee(root = process.cwd()) {
  const config = releaseConfig(root);
  if (!config.giteeOwner) {
    throw new Error(`${DISTRIBUTION_FILE}: giteeOwner is empty; fill it before releasing`);
  }
  return config;
}

function isMain() {
  return Boolean(process.argv[1]) &&
    import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href;
}

if (isMain()) {
  try {
    const args = process.argv.slice(2);
    if (args[0] === '--get') {
      const config = releaseConfig();
      const key = args[1];
      if (!Object.hasOwn(config, key)) throw new Error(`unknown version key: ${key}`);
      process.stdout.write(`${config[key]}\n`);
    } else if (args[0] === '--check-tag') {
      const version = readVersion();
      const tag = args[1];
      if (!tag) throw new Error('--check-tag requires a tag');
      if (tag !== version.tag) throw new Error(`tag ${tag} does not match ${version.tag}`);
      console.log(`tag ${tag} matches version ${version.versionName}`);
    } else if (args[0] === '--require-gitee') {
      const config = requireGitee();
      console.log(`Gitee release repository: ${config.giteeOwner}/${config.giteeRepo}`);
    } else {
      console.log(JSON.stringify(releaseConfig(), null, 2));
    }
  } catch (error) {
    console.error(`version: ${error.message}`);
    process.exit(2);
  }
}
