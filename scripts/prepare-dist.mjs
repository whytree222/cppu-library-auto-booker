import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';
import { releaseConfig } from './version.mjs';

export const MAX_APK_BYTES = 100 * 1024 * 1024;
export const MAX_NOTES_LENGTH = 8000;

function isoInstant(value, name) {
  const time = Date.parse(value);
  if (!Number.isFinite(time)) throw new Error(`${name} is not a valid ISO-8601 time`);
  return new Date(time).toISOString();
}

function defaultPublishedAt(root) {
  const epoch = process.env.SOURCE_DATE_EPOCH;
  if (epoch) {
    const seconds = Number(epoch);
    if (!Number.isInteger(seconds) || seconds < 0) {
      throw new Error('SOURCE_DATE_EPOCH must be a non-negative integer');
    }
    return new Date(seconds * 1000).toISOString();
  }
  try {
    const value = execFileSync('git', ['show', '-s', '--format=%cI', 'HEAD'], {
      cwd: root,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore'],
      timeout: 5000
    }).trim();
    if (value) return isoInstant(value, 'git commit time');
  } catch {
    // A source archive without .git still produces a valid manifest.
  }
  return new Date().toISOString();
}

function readNotes(root, config) {
  const file = path.join(root, config.notesFile);
  if (!fs.existsSync(file)) throw new Error(`Missing release notes: ${config.notesFile}`);
  const notes = fs.readFileSync(file, 'utf8').replace(/\r\n/g, '\n').trim();
  if (!notes) throw new Error(`${config.notesFile} is empty`);
  if (notes.length > MAX_NOTES_LENGTH) {
    throw new Error(`${config.notesFile} exceeds ${MAX_NOTES_LENGTH} characters`);
  }
  return notes;
}

export function inspectApk(file) {
  const stat = fs.statSync(file, { throwIfNoEntry: false });
  if (!stat?.isFile()) throw new Error(`APK not found: ${file}`);
  if (stat.size <= 0 || stat.size > MAX_APK_BYTES) {
    throw new Error(`APK size must be between 1 and ${MAX_APK_BYTES} bytes`);
  }
  const header = Buffer.alloc(4);
  const fd = fs.openSync(file, 'r');
  try {
    fs.readSync(fd, header, 0, 4, 0);
  } finally {
    fs.closeSync(fd);
  }
  if (header.toString('latin1') !== 'PK\u0003\u0004') {
    throw new Error('APK is not a valid ZIP archive');
  }
  return stat.size;
}

export function sha256File(file) {
  return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
}

export function releaseNotesWithDownloads(notes, config, sha256) {
  const lines = [
    notes,
    '',
    '## 下载',
    '',
    `- Gitee 直接下载（国内推荐）：${config.giteeDownloadUrl || '尚未配置'}`,
    `- GitHub 直接下载（备用）：${config.githubDownloadUrl}`,
    `- 版本说明：${config.pageUrl}`,
    '',
    `固定文件名 \`${config.downloadAliasName}\` 与 \`${config.apkName}\` 内容完全相同，SHA-256 相同。`,
    '只下载 `.apk` 安装包；发布页中的 `Source code (zip/tar.gz)` 是源码压缩包，不能安装。',
    '若浏览器把文件保存为 `.apk.zip`，删除文件名末尾的 `.zip` 后再安装，不要解压。',
    '支持 Android 8.0 及以上。请用系统浏览器打开；微信内请点右上角“在浏览器打开”。',
    '首次安装需要允许“安装未知应用”。下载后可用 SHA-256 核对文件完整性。',
    '',
    `SHA-256：\`${sha256}\``
  ];
  return `${lines.join('\n')}\n`;
}

export function prepareDistribution({
  root = process.cwd(),
  apk,
  out = 'dist',
  strict = false,
  publishedAt
} = {}) {
  const config = releaseConfig(root);
  if (strict && !config.giteeOwner) {
    throw new Error('giteeOwner is empty in distribution.properties; refusing a strict release');
  }
  if (!apk) throw new Error('--apk is required');

  const notes = readNotes(root, config);
  const apkPath = path.resolve(root, apk);
  const size = inspectApk(apkPath);
  const sha256 = sha256File(apkPath);
  const timestamp = publishedAt
    ? isoInstant(publishedAt, 'publishedAt')
    : defaultPublishedAt(root);
  if (config.apkUrls.length === 0 || config.apkUrls.length > 4) {
    throw new Error('The release manifest must contain 1 to 4 APK URLs');
  }

  const manifest = {
    schema: 1,
    versionCode: config.versionCode,
    versionName: config.versionName,
    minSupportedVersionCode: config.minSupportedVersionCode,
    publishedAt: timestamp,
    apkUrls: config.apkUrls,
    sha256,
    size,
    notes,
    pageUrl: config.pageUrl
  };

  const outDir = path.resolve(root, out);
  fs.rmSync(outDir, { recursive: true, force: true });
  fs.mkdirSync(outDir, { recursive: true });

  const targetApk = path.join(outDir, config.apkName);
  fs.copyFileSync(apkPath, targetApk);
  if (sha256File(targetApk) !== sha256) throw new Error('Copied APK checksum mismatch');

  const checksums = `${sha256}  ${config.apkName}\n`;
  fs.writeFileSync(path.join(outDir, 'sha256sums.txt'), checksums);
  fs.writeFileSync(
    path.join(outDir, 'release-notes.md'),
    releaseNotesWithDownloads(notes, config, sha256)
  );

  // The manifest is written last so a failed preparation never exposes one.
  const manifestFile = path.join(outDir, 'update.json');
  fs.writeFileSync(`${manifestFile}.tmp`, `${JSON.stringify(manifest, null, 2)}\n`);
  fs.renameSync(`${manifestFile}.tmp`, manifestFile);

  return { outDir, apk: targetApk, manifest, sha256, size };
}

export function readPreparedDistribution({
  root = process.cwd(),
  dist = 'dist',
  config = releaseConfig(root)
} = {}) {
  const dir = path.resolve(root, dist);
  const manifestFile = path.join(dir, 'update.json');
  const raw = fs.readFileSync(manifestFile, 'utf8');
  if (Buffer.byteLength(raw) > 64 * 1024) throw new Error('update.json exceeds 64KB');
  let manifest;
  try {
    manifest = JSON.parse(raw);
  } catch (error) {
    throw new Error(`Cannot parse update.json: ${error.message}`);
  }
  if (manifest.schema !== 1) throw new Error('update.json schema must be 1');
  if (manifest.versionCode !== config.versionCode ||
      manifest.versionName !== config.versionName ||
      manifest.minSupportedVersionCode !== config.minSupportedVersionCode) {
    throw new Error('update.json version does not match version.properties');
  }
  if (!Number.isInteger(manifest.size) || manifest.size <= 0 || manifest.size > MAX_APK_BYTES) {
    throw new Error('update.json size is invalid');
  }
  if (!/^[0-9a-f]{64}$/.test(String(manifest.sha256 ?? ''))) {
    throw new Error('update.json SHA-256 is invalid');
  }
  if (!Number.isFinite(Date.parse(manifest.publishedAt))) {
    throw new Error('update.json publishedAt is invalid');
  }
  if (typeof manifest.notes !== 'string' || manifest.notes.length > MAX_NOTES_LENGTH) {
    throw new Error('update.json notes are invalid');
  }
  if (manifest.pageUrl !== config.pageUrl) {
    throw new Error('update.json pageUrl does not match distribution.properties');
  }
  if (!Array.isArray(manifest.apkUrls) ||
      JSON.stringify(manifest.apkUrls) !== JSON.stringify(config.apkUrls)) {
    throw new Error('update.json APK URLs do not match distribution.properties');
  }

  const apkFiles = fs.readdirSync(dir).filter((name) => name.toLowerCase().endsWith('.apk'));
  if (apkFiles.length !== 1 || apkFiles[0] !== config.apkName) {
    throw new Error(`dist must contain exactly ${config.apkName}`);
  }
  const apkPath = path.join(dir, config.apkName);
  const size = inspectApk(apkPath);
  if (size !== manifest.size) throw new Error('APK size does not match update.json');
  const sha256 = sha256File(apkPath);
  if (sha256 !== manifest.sha256) throw new Error('APK SHA-256 does not match update.json');

  const checksums = fs.readFileSync(path.join(dir, 'sha256sums.txt'), 'utf8').trim();
  if (checksums !== `${sha256}  ${config.apkName}`) {
    throw new Error('sha256sums.txt does not match the APK');
  }
  if (!fs.statSync(path.join(dir, 'release-notes.md')).size) {
    throw new Error('release-notes.md is empty');
  }

  return {
    dir,
    manifestFile,
    apkPath,
    apkName: config.apkName,
    manifest,
    sha256,
    size,
    files: [
      { name: config.apkName, path: apkPath },
      { name: 'update.json', path: manifestFile },
      { name: 'sha256sums.txt', path: path.join(dir, 'sha256sums.txt') }
    ]
  };
}

function parseArgs(argv) {
  const options = { out: 'dist', strict: false };
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === '--strict') {
      options.strict = true;
    } else if (arg === '--apk' || arg === '--out' || arg === '--published-at') {
      const value = argv[index + 1];
      if (!value) throw new Error(`${arg} requires a value`);
      options[arg.slice(2).replace(/-([a-z])/g, (_, letter) => letter.toUpperCase())] = value;
      index += 1;
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
    const result = prepareDistribution(parseArgs(process.argv.slice(2)));
    console.log(`Prepared ${result.manifest.versionName} (${result.manifest.versionCode})`);
    console.log(`  APK: ${result.apk}`);
    console.log(`  SHA-256: ${result.sha256}`);
    console.log(`  Manifest: ${path.join(result.outDir, 'update.json')}`);
  } catch (error) {
    console.error(`prepare-dist: ${error.message}`);
    process.exit(2);
  }
}
