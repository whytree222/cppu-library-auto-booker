const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { JSDOM } = require('jsdom');
const template = fs.readFileSync('app/src/main/assets/login.js', 'utf8');

function fixture(options = {}) {
  const dom = new JSDOM(`<form id="fromuser" method="post" action="${options.action || '/login'}">
    <input id="user" name="user"><input id="passwd" name="passwd"><input id="url" name="url">
    ${options.captcha ? '<input name="captcha">' : ''}</form>`,
    { url: options.origin || 'http://mlib.cppu.edu.cn/login', runScripts: 'outside-only' });
  const w = dom.window;
  let requests = 0;
  let submitted;
  // Equivalent to the school's supplied submitForm: Base64 then form.submit.
  w.submitForm = () => {
    const d = w.document;
    d.getElementById('passwd').value = Buffer.from(d.getElementById('passwd').value, 'utf8').toString('base64');
    submitted = { user: d.getElementById('user').value, passwd: d.getElementById('passwd').value,
      url: d.getElementById('url').value };
    requests++;
  };
  if (options.unsupported) delete w.submitForm;
  const credentials = { username: 'test-account', password: options.newline ? 'a\nb' : `测试密码'"\\</script>` };
  const script = template.replace('__LOGIN_CREDENTIALS__', JSON.stringify(credentials));
  return { w, credentials, run: () => w.eval(script), requests: () => requests, posted: () => submitted, close: () => w.close() };
}

test('same-origin school form submits once and preserves special password characters', () => {
  const f = fixture();
  try {
    assert.equal(f.run(), 'submitted');
    assert.equal(f.run(), 'submitted');
    assert.equal(f.requests(), 1);
    assert.equal(f.posted().user, f.credentials.username);
    assert.equal(Buffer.from(f.posted().passwd, 'base64').toString('utf8'), f.credentials.password);
    assert.equal(f.posted().url, 'selectreadingroom');
  } finally { f.close(); }
});

for (const [name, options, reason] of [
  ['other site', { origin: 'http://example.com/login' }, 'wrong-origin'],
  ['other submission host', { action: 'http://example.com/login' }, 'wrong-action'],
  ['wrong submission path', { action: '/other' }, 'wrong-action'],
  ['captcha', { captcha: true }, 'manual-required'],
  ['unknown encoding function', { unsupported: true }, 'unsupported'],
  ['password contains a newline', { newline: true }, 'invalid-credentials']
]) test(`no credential transmission: ${name}`, () => {
  const f = fixture(options);
  try { assert.equal(f.run(), reason); assert.equal(f.requests(), 0); assert.equal(f.w.document.getElementById('passwd').value, ''); }
  finally { f.close(); }
});

