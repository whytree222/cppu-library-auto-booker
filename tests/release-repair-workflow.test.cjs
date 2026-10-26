const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const workflow = fs.readFileSync(
  path.join(__dirname, '..', '.github', 'workflows', 'repair-release.yml'),
  'utf8'
);

test('repair workflow is manual-only and reuses a verified build artifact', () => {
  assert.match(workflow, /on:\s*\n\s*workflow_dispatch:/);
  assert.doesNotMatch(workflow, /^\s{2}(push|pull_request):/m);
  assert.match(workflow, /run-id: \$\{\{ inputs\.run_id \}\}/);
  assert.doesNotMatch(workflow, /gradle|assembleRelease|testDebugUnitTest/);
  assert.match(workflow, /--check-tag "\$TAG"/);
  assert.match(workflow, /readPreparedDistribution/);
  assert.match(workflow, /RELEASE_CERT_SHA256/);
  assert.match(workflow, /gh release create[\s\S]*--draft --verify-tag/);
  assert.doesNotMatch(workflow, /--clobber|gh release upload/);
  assert.doesNotMatch(workflow, /\bv\d+\.\d+\.\d+\b/);
});

test('repair publishes Gitee first and the manifest last', () => {
  const gitee = workflow.indexOf('Publish Gitee release');
  const fixed = workflow.indexOf('Verify Gitee fixed download');
  const github = workflow.indexOf('Publish GitHub release');
  const manifest = workflow.indexOf('Update Gitee raw manifest');
  const verify = workflow.indexOf('Verify public release');
  assert.ok(gitee > 0 && fixed > gitee && github > fixed && manifest > github && verify > manifest);
  assert.match(workflow, /Release \$TAG already exists; refusing to modify it/);
  assert.match(workflow, /if: failure\(\) && steps\.github_draft\.outputs\.created == 'true'/);
  assert.equal(
    (workflow.match(/GITEE_TOKEN: \$\{\{ secrets\.GITEE_TOKEN \}\}/g) || []).length,
    2
  );
});
