const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const workflow = fs.readFileSync(
  path.join(__dirname, '..', '.github', 'workflows', 'android.yml'),
  'utf8'
);
const syncWorkflow = fs.readFileSync(
  path.join(__dirname, '..', '.github', 'workflows', 'sync-gitee-manifest.yml'),
  'utf8'
);
const backfillWorkflow = fs.readFileSync(
  path.join(__dirname, '..', '.github', 'workflows', 'backfill-download-alias.yml'),
  'utf8'
);

test('manifest sync workflow is manual-only and never republishes assets', () => {
  assert.match(syncWorkflow, /on:\s*\n\s*workflow_dispatch:/);
  assert.doesNotMatch(syncWorkflow, /^\s{2}(push|pull_request):/m);
  assert.doesNotMatch(syncWorkflow, /gh release (create|edit|upload|delete)/);
  assert.match(syncWorkflow, /GITEE_TOKEN: \$\{\{ secrets\.GITEE_TOKEN \}\}/);
  assert.match(syncWorkflow, /node scripts\/publish-gitee\.mjs manifest/);
  assert.match(syncWorkflow, /node scripts\/verify-release\.mjs/);
  assert.match(syncWorkflow, /--get apkName/);
  assert.match(syncWorkflow, /--pattern "\$APK_NAME"/);
  assert.doesNotMatch(syncWorkflow, /--pattern '\*\.apk'/);
});

test('release workflow refuses to overwrite existing releases', () => {
  assert.doesNotMatch(workflow, /--clobber/);
  assert.doesNotMatch(workflow, /gh release upload/);
  assert.doesNotMatch(workflow, /\bv\d+\.\d+\.\d+\b/);
  assert.match(workflow, /gh release create[\s\S]*--draft --verify-tag/);
  assert.match(workflow, /gh release edit "\$GITHUB_REF_NAME" --draft=false/);
});

test('main pushes test and build but never publish', () => {
  assert.match(workflow, /branches: \[main\]/);
  assert.match(workflow, /tags: \['v\*'\]/);
  assert.match(workflow, /assembleRelease/);
  assert.match(workflow, /needs\.build\.outputs\.is_publish == 'true'/);
  assert.match(workflow, /publish=true requires a v\* tag ref/);
  assert.equal((workflow.match(/contents: write/g) || []).length, 1);
});

test('release version comes from the shared version source', () => {
  assert.match(workflow, /node scripts\/version\.mjs --check-tag/);
  assert.match(workflow, /node scripts\/version\.mjs --get versionCode/);
  assert.match(workflow, /node scripts\/version\.mjs --get downloadAliasName/);
  assert.match(workflow, /node scripts\/version\.mjs --get giteeDownloadUrl/);
  assert.match(workflow, /node scripts\/version\.mjs --require-gitee/);
  assert.match(workflow, /node scripts\/prepare-dist\.mjs/);
  assert.match(workflow, /node scripts\/publish-gitee\.mjs release/);
  assert.match(workflow, /node scripts\/publish-gitee\.mjs manifest/);
  assert.match(workflow, /node scripts\/verify-release\.mjs/);
});

test('release uploads the fixed APK alias to both platforms', () => {
  assert.match(workflow, /ALIAS_NAME=\$\(node scripts\/version\.mjs --get downloadAliasName\)/);
  assert.match(workflow, /cp "\$APK" "\$ALIAS"/);
  assert.match(workflow, /cmp "\$APK" "\$ALIAS"/);
  assert.match(workflow,
    /gh release create "\$TAG" "\$APK" "\$ALIAS" dist\/update\.json dist\/sha256sums\.txt/);
  assert.match(workflow,
    /node scripts\/verify-release\.mjs --manifest dist\/update\.json --download-url "\$URL"/);
});

test('backfill workflow is manual, additive-only and verifies the alias', () => {
  assert.match(backfillWorkflow, /on:\s*\n\s*workflow_dispatch:/);
  assert.doesNotMatch(backfillWorkflow, /^\s{2}(push|pull_request):/m);
  assert.match(backfillWorkflow, /permissions:\s*\n\s*contents: write/);
  assert.doesNotMatch(backfillWorkflow, /--clobber/);
  assert.doesNotMatch(backfillWorkflow, /gh release (create|edit|delete)/);
  assert.doesNotMatch(backfillWorkflow, /--check-tag/);
  assert.match(backfillWorkflow, /gh release upload "\$TAG" "\$ALIAS"/);
  assert.equal(
    (backfillWorkflow.match(/GITEE_TOKEN: \$\{\{ secrets\.GITEE_TOKEN \}\}/g) || []).length,
    1
  );
  assert.match(backfillWorkflow, /--get downloadAliasName/);
  assert.match(backfillWorkflow, /cmp "dist\/\$APK_NAME" "\$ALIAS"/);
  assert.match(backfillWorkflow,
    /node scripts\/publish-gitee\.mjs alias --tag "\$TAG" --apk "\$ALIAS"/);
  assert.match(backfillWorkflow, /--sha256 "\$EXPECTED_SHA" --size "\$EXPECTED_SIZE"/);
  assert.match(backfillWorkflow,
    /node scripts\/verify-release\.mjs --manifest dist\/update\.json --downloads-only/);
  assert.match(backfillWorkflow, /sha256sum "dist\/\$APK_NAME"/);
});

test('release accepts either apksigner certificate label', () => {
  assert.doesNotMatch(workflow, /\^Signer #1 certificate SHA-256 digest:/);
  assert.match(workflow, /\.\*certificate SHA-256 digest:/);
});

test('release publishes Gitee first, verifies the fixed link, promotes GitHub, then updates the manifest last', () => {
  const gitee = workflow.indexOf('Publish Gitee release');
  const fixed = workflow.indexOf('Verify Gitee fixed download');
  const github = workflow.indexOf('Publish GitHub release');
  const manifest = workflow.indexOf('Update Gitee raw manifest');
  const verify = workflow.indexOf('Verify public release');
  assert.ok(gitee > 0 && fixed > gitee && github > fixed && manifest > github && verify > manifest);
  assert.match(workflow, /Release \$TAG already exists; refusing to modify it/);
  assert.match(workflow, /gh release delete "\$GITHUB_REF_NAME" --yes/);
  assert.match(workflow, /isDraft/);
  assert.equal(
    (workflow.match(/GITEE_TOKEN: \$\{\{ secrets\.GITEE_TOKEN \}\}/g) || []).length,
    2
  );
});
