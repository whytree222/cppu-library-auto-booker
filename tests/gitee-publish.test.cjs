const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');

const APK_BYTES = Buffer.concat([Buffer.from([0x50, 0x4b, 0x03, 0x04]), Buffer.from('gitee publish apk')]);
const NOTES = '# v0.19.0\n\n测试 Gitee 发布。\n';
const TOKEN = 'test-token-never-log';

function makeRoot(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'cppu-gitee-'));
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
  fs.writeFileSync(path.join(root, 'release-notes', '0.19.0.md'), NOTES);
  fs.writeFileSync(path.join(root, 'fake.apk'), APK_BYTES);
  return root;
}

function parseMultipart(body, contentType) {
  const boundary = /boundary=([^;]+)/.exec(contentType || '')?.[1]?.replace(/^"|"$/g, '');
  assert.ok(boundary, 'multipart boundary is present');
  const headerEnd = body.indexOf('\r\n\r\n');
  assert.ok(headerEnd > 0, 'multipart header is present');
  const header = body.subarray(0, headerEnd).toString('latin1');
  const name = /filename="([^"]+)"/.exec(header)?.[1];
  assert.ok(name, 'multipart filename is present');
  const start = headerEnd + 4;
  const closing = body.indexOf(Buffer.from(`\r\n--${boundary}`), start);
  assert.ok(closing > start, 'multipart content is present');
  return { name, content: body.subarray(start, closing) };
}

function startServer(state) {
  const requests = [];
  const server = http.createServer((request, response) => {
    const chunks = [];
    request.on('data', (chunk) => chunks.push(chunk));
    request.on('end', () => {
      const body = Buffer.concat(chunks);
      const url = new URL(request.url, 'http://127.0.0.1');
      requests.push({
        method: request.method,
        path: url.pathname,
        search: url.search,
        headers: request.headers,
        body
      });
      const prefix = '/api/v5/repos/tester/cppu-library-auto-booker-release';
      const origin = `http://${request.headers.host}`;
      const send = (status, payload, raw = false) => {
        response.writeHead(status, { 'Content-Type': raw ? 'text/plain' : 'application/json' });
        response.end(raw ? payload : JSON.stringify(payload));
      };
      const withAssets = () => ({ ...state.release, assets: state.assets });

      if (request.method === 'GET' && url.pathname.startsWith('/files/')) {
        const name = decodeURIComponent(url.pathname.slice('/files/'.length));
        const content = state.files.get(name);
        if (!content) return send(404, { message: 'Not Found' });
        response.writeHead(200, {
          'Content-Type': 'application/octet-stream',
          'Content-Length': content.length,
          'Cache-Control': 'no-cache'
        });
        response.end(content);
        return;
      }

      if (request.method === 'GET' && url.pathname === `${prefix}/releases/tags/v0.19.0`) {
        return state.release ? send(200, withAssets()) : send(404, { message: 'Not Found' });
      }
      if (request.method === 'POST' && url.pathname === `${prefix}/releases`) {
        if (state.failCreateWithToken) return send(400, `invalid token ${TOKEN}`, true);
        const payload = JSON.parse(body.toString('utf8'));
        state.release = {
          id: 42,
          tag_name: payload.tag_name,
          name: payload.name,
          body: payload.body,
          target_commitish: payload.target_commitish
        };
        state.assets = [];
        return send(201, withAssets());
      }
      if (request.method === 'GET' && url.pathname === `${prefix}/releases/42`) {
        return state.release ? send(200, withAssets()) : send(404, { message: 'Not Found' });
      }
      if (request.method === 'POST' && url.pathname === `${prefix}/releases/42/attach_files`) {
        const file = parseMultipart(body, request.headers['content-type']);
        state.files.set(file.name, Buffer.from(file.content));
        const asset = {
          name: file.name,
          browser_download_url: `${origin}/files/${encodeURIComponent(file.name)}`
        };
        state.assets.push(asset);
        return send(201, asset);
      }
      if (request.method === 'GET' && url.pathname === `${prefix}/contents/update.json`) {
        // Gitee returns an empty array rather than 404 for a missing path.
        if (!state.manifest) return send(200, []);
        return send(200, {
          type: 'file',
          content: state.manifest.toString('base64'),
          sha: state.manifestSha
        });
      }
      if (request.method === 'POST' && url.pathname === `${prefix}/contents/update.json`) {
        const payload = JSON.parse(body.toString('utf8'));
        state.lastManifestPost = payload;
        state.manifest = Buffer.from(payload.content, 'base64');
        state.manifestSha = 'manifest-sha-after-post';
        return send(201, { content: { sha: state.manifestSha } });
      }
      if (request.method === 'PUT' && url.pathname === `${prefix}/contents/update.json`) {
        const payload = JSON.parse(body.toString('utf8'));
        state.lastManifestPut = payload;
        state.manifest = Buffer.from(payload.content, 'base64');
        state.manifestSha = 'manifest-sha-after-put';
        return send(200, { content: { sha: state.manifestSha } });
      }
      send(404, { message: `Unhandled ${request.method} ${url.pathname}` });
    });
  });

  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      resolve({
        server,
        baseUrl: `http://127.0.0.1:${server.address().port}/api/v5`,
        requests
      });
    });
  });
}

async function setup(t) {
  const { prepareDistribution } = await import('../scripts/prepare-dist.mjs');
  const { requireGitee } = await import('../scripts/version.mjs');
  const { GiteeClient } = await import('../scripts/publish-gitee.mjs');
  const root = makeRoot(t);
  prepareDistribution({
    root, apk: 'fake.apk', strict: true, publishedAt: '2026-10-25T00:00:00Z'
  });
  const config = requireGitee(root);
  const state = {
    release: null,
    assets: [],
    files: new Map(),
    manifest: null,
    manifestSha: 'manifest-sha',
    failCreateWithToken: false
  };
  const { server, baseUrl, requests } = await startServer(state);
  t.after(() => new Promise((resolve) => {
    server.closeAllConnections();
    server.close(resolve);
  }));
  const client = new GiteeClient({ baseUrl, token: TOKEN });
  return { root, config, client, state, requests };
}

test('Gitee release creates a release and uploads APK, manifest and checksums', async (t) => {
  const { publishRelease } = await import('../scripts/publish-gitee.mjs');
  const { root, config, client, state, requests } = await setup(t);

  const result = await publishRelease({ root, tag: 'v0.19.0', config, client });
  assert.equal(result.reused, false);
  assert.equal(result.url, config.giteeTagUrl);
  assert.deepEqual(result.uploaded.sort(),
    ['library-auto-booker-0.19.0.apk', 'sha256sums.txt', 'update.json']);
  assert.equal(state.release.body, fs.readFileSync(path.join(root, 'dist', 'release-notes.md'), 'utf8'));
  assert.deepEqual(state.assets.map((asset) => asset.name).sort(),
    ['library-auto-booker-0.19.0.apk', 'sha256sums.txt', 'update.json']);
  for (const asset of state.assets) {
    assert.deepEqual(Object.keys(asset).sort(), ['browser_download_url', 'name']);
    assert.deepEqual(state.files.get(asset.name),
      fs.readFileSync(path.join(root, 'dist', asset.name)));
  }
  const downloads = requests.filter((request) => request.path.startsWith('/files/'));
  assert.equal(downloads.length, 3);
  for (const request of downloads) {
    assert.equal(request.headers.authorization, undefined);
  }
  assert.ok(requests.length >= 5);
  for (const request of requests.filter((item) => item.path.startsWith('/api/v5'))) {
    assert.equal(request.headers.authorization, `Bearer ${TOKEN}`);
    assert.ok(!request.search.includes('access_token='));
  }
  assert.ok(requests.some((request) =>
    request.method === 'POST' && request.path.endsWith('/attach_files')));
});

test('re-running the same version reuses the release and skips identical assets', async (t) => {
  const { publishRelease } = await import('../scripts/publish-gitee.mjs');
  const { root, config, client, state, requests } = await setup(t);
  await publishRelease({ root, tag: 'v0.19.0', config, client });
  const attachCount = requests.filter((request) => request.path.endsWith('/attach_files')).length;
  const downloadCount = requests.filter((request) => request.path.startsWith('/files/')).length;

  const second = await publishRelease({ root, tag: 'v0.19.0', config, client });
  assert.equal(second.reused, true);
  assert.equal(second.uploaded.length, 0);
  assert.deepEqual(second.skipped.sort(),
    ['library-auto-booker-0.19.0.apk', 'sha256sums.txt', 'update.json']);
  assert.equal(
    requests.filter((request) => request.path.endsWith('/attach_files')).length,
    attachCount
  );
  assert.equal(
    requests.filter((request) => request.path.startsWith('/files/')).length,
    downloadCount + 3
  );
  assert.equal(state.assets.length, 3);
});

test('Gitee release refuses to overwrite a different asset or different notes', async (t) => {
  const { publishRelease } = await import('../scripts/publish-gitee.mjs');
  const { root, config, client, state } = await setup(t);
  await publishRelease({ root, tag: 'v0.19.0', config, client });

  const target = state.assets[0].name;
  const original = state.files.get(target);
  const tampered = Buffer.from(original);
  // Same length, so only the content hash can detect the difference.
  tampered[0] ^= 0xff;
  state.files.set(target, tampered);
  await assert.rejects(
    () => publishRelease({ root, tag: 'v0.19.0', config, client }),
    /different content/
  );

  state.files.set(target, original);
  state.release.body = '旧的发布说明';
  await assert.rejects(
    () => publishRelease({ root, tag: 'v0.19.0', config, client }),
    /different notes/
  );
});

test('Gitee release refuses to reuse an asset it cannot verify', async (t) => {
  const { publishRelease } = await import('../scripts/publish-gitee.mjs');
  const { root, config, client, state } = await setup(t);
  await publishRelease({ root, tag: 'v0.19.0', config, client });

  delete state.assets[0].browser_download_url;
  await assert.rejects(
    () => publishRelease({ root, tag: 'v0.19.0', config, client }),
    /Cannot verify existing Gitee asset/
  );
});

test('manifest publishing treats Gitee empty array as missing, then is idempotent', async (t) => {
  const { publishGiteeManifest } = await import('../scripts/publish-gitee.mjs');
  const { root, config, client, state, requests } = await setup(t);

  const first = await publishGiteeManifest({ root, config, client });
  assert.equal(first.unchanged, false);
  assert.equal(state.lastManifestPost.branch, 'main');
  assert.match(state.lastManifestPost.message, /v0\.19\.0/);
  assert.deepEqual(state.manifest, fs.readFileSync(path.join(root, 'dist', 'update.json')));
  assert.equal(requests.filter((request) => request.method === 'POST' &&
    request.path.endsWith('/contents/update.json')).length, 1);

  const second = await publishGiteeManifest({ root, config, client });
  assert.equal(second.unchanged, true);
  assert.equal(requests.filter((request) => request.method === 'GET' &&
    request.path.endsWith('/contents/update.json')).length, 2);
  assert.equal(state.lastManifestPut, undefined);
});

test('manifest publishing updates an existing file with its previous sha', async (t) => {
  const { publishGiteeManifest } = await import('../scripts/publish-gitee.mjs');
  const { root, config, client, state } = await setup(t);
  state.manifest = Buffer.from('{"schema":1,"versionCode":49}');
  state.manifestSha = 'previous-file-sha';

  const result = await publishGiteeManifest({ root, config, client });
  assert.equal(result.unchanged, false);
  assert.equal(state.lastManifestPut.sha, 'previous-file-sha');
  assert.equal(state.lastManifestPut.branch, 'main');
  assert.deepEqual(state.manifest, fs.readFileSync(path.join(root, 'dist', 'update.json')));
});

test('Gitee errors never echo the access token', async (t) => {
  const { publishRelease } = await import('../scripts/publish-gitee.mjs');
  const { root, config, client, state } = await setup(t);
  state.failCreateWithToken = true;

  await assert.rejects(
    () => publishRelease({ root, tag: 'v0.19.0', config, client }),
    (error) => {
      assert.ok(!error.message.includes(TOKEN));
      assert.match(error.message, /\[redacted\]/);
      return true;
    }
  );
});

test('GiteeClient requires a token', async () => {
  const { GiteeClient } = await import('../scripts/publish-gitee.mjs');
  assert.throws(() => new GiteeClient({ token: '' }), /Missing GITEE_TOKEN/);
});
