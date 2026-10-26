const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const {JSDOM} = require('jsdom');
const template = fs.readFileSync('app/src/main/assets/webvpn-library-login.js', 'utf8');
const prefix = '/http/77726476706e69737468656265737421fdfb489e693378406b468ca88d1b203b';
const login = prefix + '/login';

function fixture(options = {}) {
  const dom = new JSDOM(`${options.before||''}<form id="fromuser" method="${options.method || 'post'}" action="${options.action || login}" target="${options.target || ''}">
    <input id="user" name="user"><input id="passwd" name="passwd" type="password"><input id="url" name="url" value="selectreadingroom">
    ${options.captcha ? '<input name="captcha">' : ''}</form>${options.extra || ''}`,
    {url: options.url || 'https://webvpn.cppu.edu.cn' + login, runScripts: 'outside-only'});
  const w = dom.window, form=w.document.querySelector('form#fromuser'), user = form.querySelector('#user'), password = form.querySelector('#passwd');
  const state = {requests: 0, posted: null};
  w.submitForm = () => {
    password.value = Buffer.from(password.value, 'utf8').toString('base64');
    state.posted = {user: user.value, passwd: password.value, url: w.document.getElementById('url').value};
    state.requests++;
    if (options.throwAfterSend) throw new Error('synthetic-exception-secret');
  };
  if (options.unsupported) delete w.submitForm;
  const credentials = {username: 'library-only-account', password: `图书馆密码'"\\</script>`};
  const run = value => JSON.parse(w.eval(template.replace('__LOGIN_CREDENTIALS__', JSON.stringify(value))));
  return {w, user, password, credentials, state, inspect: () => run(null), submit: value => run(value === undefined ? credentials : value),
    edit: value => {user.value = value; user.dispatchEvent(new w.Event('input', {bubbles: true}));}, close: () => w.close()};
}

function using(options, run) {const f = fixture(options); try {run(f);} finally {f.close();}}

test('inspect-only mode never fills credentials and returns only a safe enum', () => using({}, f => {
  assert.deepEqual(f.inspect(), {kind: 'ready', detailCode: 'library-login-ready'});
  assert.equal(f.user.value, ''); assert.equal(f.password.value, ''); assert.equal(f.state.requests, 0);
}));

test('verified proxy form uses normal school encoding once and preserves Unicode and punctuation', () => using({}, f => {
  assert.equal(f.inspect().kind, 'ready');
  assert.deepEqual(f.submit(), {kind: 'submitted', detailCode: 'submission-started'});
  assert.deepEqual(f.submit(), {kind: 'submitted', detailCode: 'already-submitted'});
  assert.equal(f.state.requests, 1); assert.equal(f.state.posted.user, f.credentials.username);
  assert.equal(Buffer.from(f.state.posted.passwd, 'base64').toString('utf8'), f.credentials.password);
  assert.equal(f.state.posted.url, 'selectreadingroom');
}));

test('relative login action is accepted only when resolved within the exact proxy prefix', () => using({action: 'login'}, f => {
  assert.equal(f.submit().kind, 'submitted'); assert.equal(f.state.requests, 1);
}));

for (const [name, options, detail] of [
  ['other host', {url: 'https://attacker.example' + login}, 'wrong-page'],
  ['HTTP proxy', {url: 'http://webvpn.cppu.edu.cn' + login}, 'wrong-page'],
  ['proxy custom port', {url: 'https://webvpn.cppu.edu.cn:444' + login}, 'wrong-page'],
  ['URL userinfo', {url: 'https://someone@webvpn.cppu.edu.cn' + login}, 'wrong-page'],
  ['SSO page', {url: 'https://sso.cppu.edu.cn/tpass/login'}, 'wrong-page'],
  ['WebVPN portal', {url: 'https://webvpn.cppu.edu.cn/login'}, 'wrong-page'],
  ['different proxy', {url: 'https://webvpn.cppu.edu.cn/http/other/login'}, 'wrong-page'],
  ['library seat map', {url: 'https://webvpn.cppu.edu.cn' + prefix + '/multireadingroomtablelist'}, 'wrong-page'],
  ['external action', {action: 'https://attacker.example' + login}, 'wrong-action'],
  ['action custom port', {action: 'https://webvpn.cppu.edu.cn:444' + login}, 'wrong-action'],
  ['action userinfo', {action: 'https://someone@webvpn.cppu.edu.cn' + login}, 'wrong-action'],
  ['direct library action', {action: 'http://mlib.cppu.edu.cn/login'}, 'wrong-action'],
  ['different action path', {action: prefix + '/other'}, 'wrong-action'],
  ['different proxy action', {action: '/http/other/login'}, 'wrong-action'],
  ['GET form', {method: 'get'}, 'wrong-action'],
  ['other browsing context', {target: '_blank'}, 'wrong-action'],
  ['duplicate form', {extra: '<form id="fromuser"></form>'}, 'unsupported-structure'],
  ['duplicate password', {extra: '<input id="passwd" name="passwd" type="password">'}, 'unsupported-structure'],
  ['additional verification', {captcha: true}, 'additional-verification']
]) test(`no credential transmission: ${name}`, () => using(options, f => {
  const result = f.submit(); assert.equal(result.detailCode, detail);
  assert.equal(f.state.requests, 0); assert.equal(f.user.value, ''); assert.equal(f.password.value, '');
  assert.doesNotMatch(JSON.stringify(result), /library-only-account|密码|attacker|https:|http\//);
}));

test('real header login duplicate user ID does not block or receive the main form credentials',()=>using({
  before:'<form id="fromuser1" method="post" action="/login"><input id="user" name="user"><input id="passwd1" name="passwd" type="password"></form>',
  action:'login'
},f=>{
  assert.equal(f.inspect().kind,'ready'); assert.equal(f.submit().kind,'submitted');
  assert.equal(f.state.requests,1); assert.equal(f.state.posted.user,f.credentials.username);
  const header=f.w.document.querySelector('#fromuser1');
  assert.equal(header.querySelector('#user').value,''); assert.equal(header.querySelector('#passwd1').value,'');
}));
test('main-form duplicates are still rejected while unrelated destination fields are not filled',()=>{
  for(const id of ['user','url']) using({},f=>{
    const form=f.w.document.querySelector('#fromuser'); form.appendChild(form.querySelector('#'+id).cloneNode());
    assert.equal(f.submit().detailCode,'unsupported-structure'); assert.equal(f.state.requests,0); assert.equal(f.user.value,'');
  });
  using({extra:'<input id="url" name="url" value="unrelated">'},f=>{
    assert.equal(f.submit().kind,'submitted'); assert.equal(f.w.document.querySelector('body > input#url').value,'unrelated');
  });
});
test('WebVPN original root attribute with an upstream action getter stays within the verified main form',()=>using({action:'/login'},f=>{
  const form=f.w.document.querySelector('#fromuser');
  Object.defineProperty(form,'action',{get:()=> 'http://mlib.cppu.edu.cn/login'});
  assert.equal(f.inspect().kind,'ready'); assert.equal(f.submit().kind,'submitted'); assert.equal(f.state.requests,1);
}));

test('unrewritten portal action waits without credentials, then accepts a verified rewrite', () => using({action: '/login'}, f => {
  assert.deepEqual(f.submit(), {kind: 'waiting', detailCode: 'action-not-rewritten'});
  assert.equal(f.user.value, ''); assert.equal(f.password.value, ''); assert.equal(f.state.requests, 0);
  f.w.document.getElementById('fromuser').action = login;
  assert.equal(f.submit().kind, 'submitted'); assert.equal(f.state.requests, 1);
}));

test('missing form and school validation routine wait rather than bypass validation', () => {
  using({unsupported: true}, f => {
    assert.deepEqual(f.submit(), {kind: 'waiting', detailCode: 'validation-loading'});
    assert.equal(f.state.requests, 0); assert.equal(f.password.value, '');
  });
  using({}, f => {
    f.w.document.getElementById('fromuser').remove();
    assert.deepEqual(f.submit(), {kind: 'waiting', detailCode: 'form-loading'});
    assert.equal(f.state.requests, 0);
  });
});

test('manual and autofilled values are not overwritten or exposed', () => {
  for (const field of ['user', 'password']) using({}, f => {
    f[field].value = 'private-manual-value';
    assert.deepEqual(f.submit(), {kind: 'manual', detailCode: 'occupied-fields'});
    assert.equal(f[field].value, 'private-manual-value'); assert.equal(f.state.requests, 0);
    assert.doesNotMatch(JSON.stringify(f.inspect()), /private-manual-value/);
  });
});

test('a user edit subsequently cleared remains manual after read-only inspection and reinjection', () => using({}, f => {
  assert.equal(f.inspect().kind, 'ready'); f.edit('manual-account'); f.edit('');
  assert.deepEqual(f.submit(), {kind: 'manual', detailCode: 'user-edited'});
  assert.deepEqual(f.inspect(), {kind: 'manual', detailCode: 'user-edited'});
  assert.equal(f.password.value, ''); assert.equal(f.state.requests, 0);
}));

test('input and change events are dispatched for the school page without marking own input manual', () => using({}, f => {
  const events = [];
  for (const field of [f.user, f.password]) for (const type of ['input', 'change']) field.addEventListener(type, () => events.push(field.id + ':' + type));
  assert.equal(f.inspect().kind, 'ready'); assert.equal(f.submit().kind, 'submitted');
  assert.deepEqual(events, ['user:input', 'user:change', 'passwd:input', 'passwd:change']);
}));

test('invalid or absent credentials never reach the page or normal validation function', () => {
  for (const credentials of [{}, {username: '', password: 'p'}, {username: 'u', password: ''},
    {username: 'u', password: 'p\nq'}, {username: 'u\r', password: 'p'}, {username: 'u', password: 'p\u0000q'},
    {username: 123, password: 'p'}, {username: 'u', password: 123}]) using({}, f => {
      assert.equal(f.submit(credentials).detailCode, 'invalid-credentials');
      assert.equal(f.user.value, ''); assert.equal(f.password.value, ''); assert.equal(f.state.requests, 0);
    });
});

test('an exception after the school routine starts never triggers duplicate submission or exposes the exception', () => using({throwAfterSend: true}, f => {
  const result = f.submit(); assert.deepEqual(result, {kind: 'stopped', detailCode: 'submit-exception'});
  assert.equal(f.submit().detailCode, 'already-submitted'); assert.equal(f.state.requests, 1);
  assert.doesNotMatch(JSON.stringify(result), /secret|library-only-account|密码/);
}));

test('route or form target changes after inspection are checked again before injection', () => {
  using({}, f => {
    assert.equal(f.inspect().kind, 'ready');
    f.w.history.replaceState({}, '', prefix + '/other?ticket=secret-ticket');
    assert.equal(f.submit().detailCode, 'wrong-page'); assert.equal(f.password.value, ''); assert.equal(f.state.requests, 0);
  });
  using({}, f => {
    assert.equal(f.inspect().kind, 'ready'); f.w.document.getElementById('fromuser').action = 'https://attacker.example/login';
    assert.equal(f.submit().detailCode, 'wrong-action'); assert.equal(f.password.value, ''); assert.equal(f.state.requests, 0);
  });
});
