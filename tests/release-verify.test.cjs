const { test } = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');

const APK_BYTES = Buffer.concat([Buffer.from([0x50, 0x4b, 0x03, 0x04]), Buffer.from('verify apk payload')]);

function makeRoot(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'cppu-verify-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  fs.writeFileSync(path.join(root, 'version.properties'),
    'versionCode=50\nversionName=0.19.0\nminSupportedVersionCode=50\n');
  fs.writeFileSync(path.join(root, 'distribution.properties'), [
    'giteeOwner=tester',
    'giteeRepo=cppu-library-auto-booker-release',
    'giteeBranch=main',
    'githubRepo=whytree222/cppu-library-auto-booker',
    ''
  ].join('\n'));
  fs.mkdirSync(path.join(root, 'release-notes'));
  fs.writeFileSync(path.join(root, 'release-notes', '0.19.0.md'), '# v0.19.0\n\n校验测试。\n');
  fs.writeFileSync(path.join(root, 'fake.apk'), APK_BYTES);
  return root;
}

async function prepare(t) {
  const { prepareDistribution } = await import('../scripts/prepare-dist.mjs');
  const root = makeRoot(t);
  const result = prepareDistribution({
    root, apk: 'fake.apk', strict: true, publishedAt: '2026-10-25T00:00:00Z'
  });
  return { root, expected: result.manifest };
}

function localServer(handler) {
  const server = http.createServer((request, response) => {
    const chunks = [];
    request.on('data', (chunk) => chunks.push(chunk));
    request.on('end', () => handler(request, response, Buffer.concat(chunks)));
  });
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => resolve({
      server,
      baseUrl: `http://127.0.0.1:${server.address().port}`
    }));
  });
}

test('manifest comparison checks version, size, hash, URLs and page', async (t) => {
  const { assertManifestMatches } = await import('../scripts/verify-release.mjs');
  const { expected } = await prepare(t);
  assert.equal(assertManifestMatches(expected, expected), expected);

  assert.throws(
    () => assertManifestMatches({ ...expected, sha256: '0'.repeat(64) }, expected),
    /sha256/
  );
  assert.throws(
    () => assertManifestMatches({ ...expected, apkUrls: [expected.apkUrls[0]] }, expected),
    /apkUrls/
  );
});

test('waitForManifest retries until both stale and valid responses settle', async (t) => {
  const { waitForManifest } = await import('../scripts/verify-release.mjs');
  const { expected } = await prepare(t);
  let attempts = 0;
  const { server, baseUrl } = await localServer((request, response) => {
    attempts += 1;
    if (attempts < 3) {
      response.writeHead(503);
      response.end('not ready');
      return;
    }
    response.writeHead(200, { 'Content-Type': 'application/json' });
    response.end(JSON.stringify(expected));
  });
  t.after(() => new Promise((resolve) => {
    server.closeAllConnections();
    server.close(resolve);
  }));

  const manifest = await waitForManifest(`${baseUrl}/update.json`, expected,
    { timeoutMs: 3000, intervalMs: 20 });
  assert.equal(manifest.versionCode, 50);
  assert.equal(attempts, 3);
});

test('APK download is hashed and size checked while streaming', async (t) => {
  const { sha256Download } = await import('../scripts/verify-release.mjs');
  const { server, baseUrl } = await localServer((request, response) => {
    response.writeHead(200, {
      'Content-Type': 'application/vnd.android.package-archive',
      'Content-Length': String(APK_BYTES.length)
    });
    response.end(APK_BYTES);
  });
  t.after(() => new Promise((resolve) => {
    server.closeAllConnections();
    server.close(resolve);
  }));

  const hash = crypto.createHash('sha256').update(APK_BYTES).digest('hex');
  assert.equal(await sha256Download(`${baseUrl}/app.apk`, APK_BYTES.length), hash);
  await assert.rejects(
    () => sha256Download(`${baseUrl}/app.apk`, APK_BYTES.length + 1),
    /Content-Length/
  );
});

test('waitForDownload retries a missing fixed link and rejects a persistent mismatch', async (t) => {
  const { waitForDownload } = await import('../scripts/verify-release.mjs');
  const { expected } = await prepare(t);

  let attempts = 0;
  const { server, baseUrl } = await localServer((request, response) => {
    attempts += 1;
    if (attempts === 1) {
      response.writeHead(404);
      response.end('not ready');
      return;
    }
    response.writeHead(200, { 'Content-Length': String(APK_BYTES.length) });
    response.end(APK_BYTES);
  });
  t.after(() => new Promise((resolve) => {
    server.closeAllConnections();
    server.close(resolve);
  }));

  const hash = await waitForDownload(`${baseUrl}/library-auto-booker.apk`, expected,
    { timeoutMs: 3000, intervalMs: 20 });
  assert.equal(hash, expected.sha256);
  assert.equal(attempts, 2);

  const wrong = Buffer.from(APK_BYTES);
  wrong[wrong.length - 1] ^= 0xff;
  let wrongAttempts = 0;
  const wrongServer = await localServer((request, response) => {
    wrongAttempts += 1;
    response.writeHead(200, { 'Content-Length': String(wrong.length) });
    response.end(wrong);
  });
  t.after(() => new Promise((resolve) => {
    wrongServer.server.closeAllConnections();
    wrongServer.server.close(resolve);
  }));

  await assert.rejects(
    () => waitForDownload(`${wrongServer.baseUrl}/library-auto-booker.apk`, expected,
      { timeoutMs: 150, intervalMs: 20 }),
    (error) => {
      assert.match(error.message, /Timed out waiting/);
      assert.match(error.message, /SHA-256 mismatch/);
      return true;
    }
  );
  assert.ok(wrongAttempts >= 1);
});

test('fixed download verification modes read the published manifest', async (t) => {
  const { verifyDownloadAliases, verifyDownloadUrl } =
    await import('../scripts/verify-release.mjs');
  const { root, expected } = await prepare(t);
  const manifestFile = path.join(root, 'dist', 'update.json');
  const fetchImpl = async () => new Response(APK_BYTES, {
    status: 200,
    headers: { 'Content-Length': String(APK_BYTES.length) }
  });

  const all = await verifyDownloadAliases({
    root, manifestFile, fetchImpl, timeoutMs: 1000, intervalMs: 10
  });
  assert.equal(all.versionCode, 50);
  assert.equal(all.sha256, expected.sha256);
  assert.equal(all.downloads.length, 2);
  assert.deepEqual(all.downloads.map((entry) => entry.size),
    [APK_BYTES.length, APK_BYTES.length]);

  const one = await verifyDownloadUrl({
    root,
    manifestFile,
    url: 'https://gitee.com/tester/cppu-library-auto-booker-release/releases/download/latest/library-auto-booker.apk',
    fetchImpl,
    timeoutMs: 1000,
    intervalMs: 10
  });
  assert.equal(one.sha256, expected.sha256);
  assert.equal(one.size, APK_BYTES.length);

  await assert.rejects(
    () => verifyDownloadUrl({
      root, manifestFile, url: 'http://example.com/library-auto-booker.apk'
    }),
    /must use HTTPS/
  );
});

test('verifyRelease fetches both manifests, both APK URLs and both fixed links', async (t) => {
  const { verifyRelease } = await import('../scripts/verify-release.mjs');
  const { root, expected } = await prepare(t);
  const urls = [];
  const fetchImpl = async (rawUrl) => {
    const url = new URL(rawUrl);
    urls.push(`${url.origin}${url.pathname}`);
    if (url.pathname.endsWith('/update.json')) {
      return new Response(JSON.stringify(expected), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      });
    }
    if (url.pathname.endsWith('.apk')) {
      return new Response(APK_BYTES, {
        status: 200,
        headers: { 'Content-Length': String(APK_BYTES.length) }
      });
    }
    return new Response('not found', { status: 404 });
  };

  const result = await verifyRelease({ root, fetchImpl, timeoutMs: 1000, intervalMs: 10 });
  assert.equal(result.versionCode, 50);
  assert.equal(result.hashes.length, 2);
  assert.deepEqual(new Set(result.hashes.map((entry) => entry.sha256)),
    new Set([expected.sha256]));
  const fixed = [
    'https://gitee.com/tester/cppu-library-auto-booker-release/releases/download/latest/library-auto-booker.apk',
    'https://github.com/whytree222/cppu-library-auto-booker/releases/latest/download/library-auto-booker.apk'
  ];
  assert.equal(result.downloads.length, 2);
  assert.deepEqual(result.downloads.map((entry) => entry.url), fixed);
  for (const url of fixed) assert.ok(urls.includes(url));
  assert.ok(urls.some((url) => url.includes('gitee.com/tester/')));
  assert.ok(urls.some((url) => url.includes('github.com/whytree222/')));
});
