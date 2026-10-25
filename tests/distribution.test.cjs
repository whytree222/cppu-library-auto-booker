const { test } = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const APK_BYTES = Buffer.concat([Buffer.from([0x50, 0x4b, 0x03, 0x04]), Buffer.from('fake apk payload')]);
const NOTES = [
  '# 图书馆自动预约 v0.19.0',
  '',
  '本次更新加入国内下载与应用内更新。',
  '',
  '- 应用内更新会校验 SHA-256 和签名。',
  '- 从 v0.18.0 升级需要先卸载旧版。'
].join('\n');

function makeRepo(t, { giteeOwner = 'tester', notes = NOTES } = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'cppu-dist-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  fs.writeFileSync(path.join(root, 'version.properties'),
    'versionCode=50\nversionName=0.19.0\nminSupportedVersionCode=50\n');
  fs.writeFileSync(path.join(root, 'distribution.properties'), [
    'giteeOwner=' + giteeOwner,
    'giteeRepo=cppu-library-auto-booker-release',
    'giteeBranch=main',
    'githubRepo=whytree222/cppu-library-auto-booker',
    ''
  ].join('\n'));
  fs.mkdirSync(path.join(root, 'release-notes'));
  fs.writeFileSync(path.join(root, 'release-notes', '0.19.0.md'), notes);
  fs.writeFileSync(path.join(root, 'fake.apk'), APK_BYTES);
  return root;
}

test('prepare-dist creates a manifest that matches the Android parser contract', async (t) => {
  const { prepareDistribution, readPreparedDistribution, sha256File } =
    await import('../scripts/prepare-dist.mjs');
  const { releaseConfig } = await import('../scripts/version.mjs');
  const root = makeRepo(t);
  const config = releaseConfig(root);
  const publishedAt = '2026-10-25T01:02:03Z';

  const result = prepareDistribution({ root, apk: 'fake.apk', strict: true, publishedAt });
  const sha = crypto.createHash('sha256').update(APK_BYTES).digest('hex');
  assert.equal(result.sha256, sha);
  assert.equal(sha256File(result.apk), sha);
  assert.deepEqual(result.manifest, {
    schema: 1,
    versionCode: 50,
    versionName: '0.19.0',
    minSupportedVersionCode: 50,
    publishedAt: '2026-10-25T01:02:03.000Z',
    apkUrls: [config.giteeApkUrl, config.githubApkUrl],
    sha256: sha,
    size: APK_BYTES.length,
    notes: NOTES,
    pageUrl: 'https://gitee.com/tester/cppu-library-auto-booker-release/releases/tag/v0.19.0'
  });
  assert.equal(result.manifest.size, APK_BYTES.length);
  assert.deepEqual(
    fs.readdirSync(result.outDir).sort(),
    ['library-auto-booker-0.19.0.apk', 'release-notes.md', 'sha256sums.txt', 'update.json']
  );
  assert.equal(fs.readFileSync(path.join(result.outDir, 'sha256sums.txt'), 'utf8'),
    `${sha}  library-auto-booker-0.19.0.apk\n`);

  const notes = fs.readFileSync(path.join(result.outDir, 'release-notes.md'), 'utf8');
  assert.match(notes, /Gitee（国内推荐）：https:\/\/gitee\.com\/tester\/cppu-library-auto-booker-release/);
  assert.match(notes, /GitHub（备用）：https:\/\/github\.com\/whytree222\/cppu-library-auto-booker\/releases\/latest/);
  assert.match(notes, new RegExp(`SHA-256：\`${sha}\``));
  assert.match(notes, /Android 8\.0 及以上/);
  assert.match(notes, /微信内请点右上角/);

  const loaded = readPreparedDistribution({ root });
  assert.equal(loaded.manifest.sha256, sha);
  assert.deepEqual(loaded.files.map((file) => file.name).sort(),
    ['library-auto-booker-0.19.0.apk', 'sha256sums.txt', 'update.json']);
});

test('strict releases reject an empty giteeOwner but relaxed builds stay usable', async (t) => {
  const { prepareDistribution } = await import('../scripts/prepare-dist.mjs');
  const root = makeRepo(t, { giteeOwner: '' });
  const options = { root, apk: 'fake.apk', publishedAt: '2026-10-25T00:00:00Z' };
  assert.throws(() => prepareDistribution({ ...options, strict: true }), /giteeOwner is empty/);

  const relaxed = prepareDistribution({ ...options, strict: false });
  assert.deepEqual(relaxed.manifest.apkUrls,
    ['https://github.com/whytree222/cppu-library-auto-booker/releases/download/v0.19.0/library-auto-booker-0.19.0.apk']);
  assert.equal(relaxed.manifest.pageUrl,
    'https://github.com/whytree222/cppu-library-auto-booker/releases/tag/v0.19.0');
});

test('prepare-dist rejects missing notes, oversized notes and non-APK inputs', async (t) => {
  const { prepareDistribution } = await import('../scripts/prepare-dist.mjs');
  const base = { apk: 'fake.apk', publishedAt: '2026-10-25T00:00:00Z' };

  const missing = makeRepo(t);
  fs.rmSync(path.join(missing, 'release-notes', '0.19.0.md'));
  assert.throws(() => prepareDistribution({ ...base, root: missing }), /Missing release notes/);

  const oversized = makeRepo(t, { notes: 'a'.repeat(8001) });
  assert.throws(() => prepareDistribution({ ...base, root: oversized }), /exceeds 8000 characters/);

  const bad = makeRepo(t);
  fs.writeFileSync(path.join(bad, 'fake.apk'), 'not a zip');
  assert.throws(() => prepareDistribution({ ...base, root: bad }), /not a valid ZIP archive/);

  const empty = makeRepo(t);
  fs.writeFileSync(path.join(empty, 'release-notes', '0.19.0.md'), '   \n');
  assert.throws(() => prepareDistribution({ ...base, root: empty }), /is empty/);
});

test('readPreparedDistribution detects a tampered APK or manifest', async (t) => {
  const { prepareDistribution, readPreparedDistribution } = await import('../scripts/prepare-dist.mjs');
  const root = makeRepo(t);
  const result = prepareDistribution({
    root, apk: 'fake.apk', strict: true, publishedAt: '2026-10-25T00:00:00Z'
  });
  fs.appendFileSync(result.apk, 'x');
  assert.throws(() => readPreparedDistribution({ root }), /size does not match/);

  const fresh = makeRepo(t);
  const prepared = prepareDistribution({
    root: fresh, apk: 'fake.apk', strict: true, publishedAt: '2026-10-25T00:00:00Z'
  });
  const manifestFile = path.join(prepared.outDir, 'update.json');
  const manifest = JSON.parse(fs.readFileSync(manifestFile, 'utf8'));
  manifest.apkUrls = [manifest.apkUrls[0]];
  fs.writeFileSync(manifestFile, JSON.stringify(manifest));
  assert.throws(() => readPreparedDistribution({ root: fresh }), /APK URLs do not match/);
});

test('version and distribution files are validated before any publish', async (t) => {
  const { readVersion, readDistribution, releaseConfig } = await import('../scripts/version.mjs');
  const root = makeRepo(t);
  const version = readVersion(root);
  assert.equal(version.versionCode, 50);
  assert.equal(version.tag, 'v0.19.0');
  assert.equal(version.apkName, 'library-auto-booker-0.19.0.apk');

  const config = releaseConfig(root);
  assert.equal(config.giteeManifestUrl,
    'https://gitee.com/tester/cppu-library-auto-booker-release/raw/main/update.json');
  assert.equal(config.fallbackManifestUrl,
    'https://github.com/whytree222/cppu-library-auto-booker/releases/latest/download/update.json');
  assert.deepEqual(config.apkUrls, [config.giteeApkUrl, config.githubApkUrl]);

  fs.writeFileSync(path.join(root, 'version.properties'),
    'versionCode=50\nversionName=0.19\nminSupportedVersionCode=50\n');
  assert.throws(() => readVersion(root), /versionName/);

  fs.writeFileSync(path.join(root, 'distribution.properties'),
    'giteeOwner=bad owner\ngiteeRepo=cppu-library-auto-booker-release\ngiteeBranch=main\n' +
    'githubRepo=whytree222/cppu-library-auto-booker\n');
  assert.throws(() => readDistribution(root), /invalid giteeOwner/);
});

test('QR code points at the Gitee latest release page and is a real PNG', async (t) => {
  const { makeDownloadQr } = await import('../scripts/make-qr.mjs');
  const root = makeRepo(t);
  const result = await makeDownloadQr({ root, out: 'docs/test-qr.png', size: 256 });
  assert.equal(result.url, 'https://gitee.com/tester/cppu-library-auto-booker-release/releases/latest');
  const bytes = fs.readFileSync(result.target);
  assert.deepEqual([...bytes.subarray(0, 8)], [137, 80, 78, 71, 13, 10, 26, 10]);
  assert.ok(result.size > 100);
});
