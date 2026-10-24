const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const workflow = fs.readFileSync(
  path.join(__dirname, '..', '.github', 'workflows', 'android.yml'),
  'utf8'
);

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
  assert.match(workflow, /node scripts\/prepare-dist\.mjs/);
  assert.match(workflow, /node scripts\/publish-gitee\.mjs release/);
  assert.match(workflow, /node scripts\/publish-gitee\.mjs manifest/);
  assert.match(workflow, /node scripts\/verify-release\.mjs/);
});
