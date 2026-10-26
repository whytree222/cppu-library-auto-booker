const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const {JSDOM} = require('jsdom');
const script = fs.readFileSync('app/src/main/assets/webvpn-captcha.js', 'utf8');
const proxy = '/https/77726476706e69737468656265737421e3e44ed224207845300d8db9d6562d';

function fixture(options = {}) {
  const url = options.url || 'https://sso.cppu.edu.cn/tpass/login?service=secret-service';
  const route = new URL(url).hostname === 'webvpn.cppu.edu.cn' ? proxy : '';
  const dom = new JSDOM(`<form id="fm1" method="post" action="${options.action || route + '/tpass/login?execution=secret-execution'}">
    <input id="username" name="username"><input id="password" name="password" type="password">
    <input id="authcode" name="authcode" type="${options.type || 'text'}">
    <img class="codeImg" src="${options.src || route + '/tpass/captcha.jpg?cache=secret-cache'}">
    <button type="submit">Login</button></form>${options.extra || ''}`, {url, runScripts: 'outside-only'});
  const w = dom.window, input = w.document.querySelector('#authcode'), image = w.document.querySelector('img.codeImg');
  const state = {pixels: 'synthetic-image-A', complete: true, width: 120, height: 50, requests: 0, submits: 0, draws: 0};
  w.HTMLElement.prototype.getClientRects = function () { return [{width: 120, height: 50}]; };
  Object.defineProperties(image, {
    complete: {get: () => state.complete}, naturalWidth: {get: () => state.width}, naturalHeight: {get: () => state.height},
    currentSrc: {get: () => options.currentSrc || image.src, configurable:true}
  });
  w.HTMLCanvasElement.prototype.getContext = () => ({drawImage: img => {assert.equal(img, image); state.draws++;}});
  w.HTMLCanvasElement.prototype.toDataURL = () => options.dataUrl || 'data:image/png;base64,' + Buffer.from(state.pixels).toString('base64');
  w.fetch = () => {state.requests++; throw new Error('Network use forbidden');};
  w.XMLHttpRequest = class {constructor() {state.requests++; throw new Error('Network use forbidden');}};
  w.HTMLFormElement.prototype.submit = () => {state.submits++;};
  w.HTMLFormElement.prototype.requestSubmit = () => {state.submits++;};
  // A test failure if OCR ever inspects account/password content.
  for (const field of w.document.querySelectorAll('#username,#password')) {
    Object.defineProperty(field, 'value', {get: () => {throw new Error('Credential read forbidden');}, set: () => {throw new Error('Credential write forbidden');}});
  }
  w.eval(script);
  return {w, input, image, state, api: w.LibraryCaptchaOcr, close: () => w.close(),
    edit: value => {input.value = value; input.dispatchEvent(new w.Event('input', {bubbles: true}));}};
}
const plain = value => JSON.parse(JSON.stringify(value));
function using(options, run) { const f = fixture(options); try {run(f);} finally {f.close();} }

test('SSO captures an existing image without any account read, network call or form submit', () => using({}, f => {
  const first = f.api.snapshot();
  assert.equal(first.kind, 'ready');
  assert.match(first.token, /^[a-f0-9]{32}$/);
  assert.equal(first.data, Buffer.from(f.state.pixels).toString('base64'));
  assert.match(first.imageKey, /^[a-f0-9]{8}$/);
  assert.equal(f.api.snapshot().token, first.token);
  assert.equal(f.state.requests, 0); assert.equal(f.state.submits, 0);
  assert.doesNotMatch(JSON.stringify(first), /secret-|https:|execution|service|captcha\.jpg/);
}));

test('the exact school HTTPS proxy login and CAPTCHA resource are supported', () => using({url: 'https://webvpn.cppu.edu.cn' + proxy + '/tpass/login?service=secret'}, f => {
  const ready = f.api.snapshot(); assert.equal(ready.kind, 'ready');
  assert.equal(f.api.fill(ready.token, '0123').kind, 'filled');
  assert.equal(f.input.value, '0123'); assert.equal(f.state.submits, 0);
}));

for (const [name, options, code] of [
  ['HTTP SSO', {url: 'http://sso.cppu.edu.cn/tpass/login'}, 'unsupported-page'],
  ['other host', {url: 'https://attacker.example/tpass/login'}, 'unsupported-page'],
  ['other school page', {url: 'https://sso.cppu.edu.cn/tpass/other'}, 'unsupported-page'],
  ['lookalike host', {url: 'https://sso.cppu.edu.cn.attacker.example/tpass/login'}, 'unsupported-page'],
  ['SSO custom port', {url: 'https://sso.cppu.edu.cn:444/tpass/login'}, 'unsupported-page'],
  ['URL userinfo', {url: 'https://someone@sso.cppu.edu.cn/tpass/login'}, 'unsupported-page'],
  ['WebVPN root login', {url: 'https://webvpn.cppu.edu.cn/login'}, 'unsupported-page'],
  ['different proxy target', {url: 'https://webvpn.cppu.edu.cn/https/other/tpass/login'}, 'unsupported-page'],
  ['external form target', {action: 'https://attacker.example/tpass/login'}, 'wrong-action-attribute'],
  ['other form resource', {action: '/tpass/other'}, 'wrong-action-attribute'],
  ['external CAPTCHA', {src: 'https://attacker.example/tpass/captcha.jpg'}, 'unsupported-image'],
  ['other CAPTCHA endpoint', {src: '/image.jpg'}, 'unsupported-image'],
  ['data image', {src: 'data:image/png;base64,abcd'}, 'unsupported-image'],
  ['unsupported current source', {currentSrc: 'https://attacker.example/tpass/captcha.jpg'}, 'unsupported-image'],
  ['duplicate form', {extra: '<form id="fm1"></form>'}, 'unsupported-structure'],
  ['duplicate input', {extra: '<input id="authcode" name="authcode">'}, 'unsupported-structure'],
  ['duplicate input with other name', {extra: '<input id="authcode" name="other">'}, 'unsupported-structure'],
  ['duplicate username', {extra: '<input id="username">'}, 'unsupported-structure'],
  ['unsupported input type', {type: 'hidden'}, 'unsupported-structure']
]) test(`fail closed: ${name}`, () => using(options, f => {
  assert.deepEqual(plain(f.api.snapshot()), {kind: 'stopped', detailCode: code});
  assert.equal(f.state.draws, 0); assert.equal(f.state.requests, 0); assert.equal(f.state.submits, 0);
}));

test('unrelated image outside the password form does not prevent a unique CAPTCHA match',()=>using({extra:'<img class="codeImg" src="/tpass/captcha.jpg">'},f=>{
  assert.equal(f.api.snapshot().kind,'ready');
  const duplicate=f.image.cloneNode();f.w.document.querySelector('form').appendChild(duplicate);
  assert.equal(f.api.snapshot().detailCode,'unsupported-structure');
}));
test('proxy DOM getters may expose only the same original SSO action/image target',()=>using({url:'https://webvpn.cppu.edu.cn'+proxy+'/tpass/login'},f=>{
  Object.defineProperty(f.w.document.querySelector('form'),'action',{get:()=> 'https://sso.cppu.edu.cn/tpass/login'});
  Object.defineProperty(f.image,'currentSrc',{get:()=> 'https://sso.cppu.edu.cn/tpass/captcha.jpg?cache=test'});
  assert.equal(f.api.snapshot().kind,'ready');
}));

test('a root CAPTCHA attribute still requires a verified loaded image source; form root action is normalized', () => {
  using({url: 'https://webvpn.cppu.edu.cn' + proxy + '/tpass/login', src: '/tpass/captcha.jpg'}, f => {
    assert.equal(f.api.snapshot().detailCode, 'unsupported-image');
  });
  using({url: 'https://webvpn.cppu.edu.cn' + proxy + '/tpass/login', action: '/tpass/login'}, f => {
    assert.equal(f.api.snapshot().kind, 'ready');
    assert.equal(new URL(f.w.document.querySelector('form').action).pathname, proxy + '/tpass/login');
    assert.equal(f.state.requests, 0); assert.equal(f.state.submits, 0);
  });
});

test('school original root-relative form and image attributes coexist with proxied getters', () => using({
  url: 'https://webvpn.cppu.edu.cn' + proxy + '/tpass/login?service=secret-service',
  action: '/tpass/login?execution=secret-execution', src: '/tpass/captcha.jpg?cache=secret-cache',
  currentSrc: 'https://webvpn.cppu.edu.cn' + proxy + '/tpass/captcha.jpg?cache=secret-cache'
}, f => {
  Object.defineProperty(f.w.document.querySelector('form'), 'action', {
    get: () => 'https://sso.cppu.edu.cn/tpass/login?execution=secret-execution'
  });
  const ready = f.api.snapshot(); assert.equal(ready.kind, 'ready');
  assert.equal(f.api.fill(ready.token, '0123').kind, 'filled');
  assert.equal(f.api.snapshot().kind, 'filled');
  assert.doesNotMatch(JSON.stringify(ready), /secret-|execution|service|https:/);
  assert.equal(f.state.requests, 0); assert.equal(f.state.submits, 0);
}));

test('normalizing a known original action preserves its query and never refreshes the CAPTCHA', () => using({
  url: 'https://webvpn.cppu.edu.cn' + proxy + '/tpass/login', action: '/tpass/login?execution=fixture-execution'
}, f => {
  const source = f.image.src;
  const ready = f.api.snapshot(); assert.equal(ready.kind, 'ready');
  const action = new URL(f.w.document.querySelector('form').action);
  assert.equal(action.origin, 'https://webvpn.cppu.edu.cn');
  assert.equal(action.pathname, proxy + '/tpass/login');
  assert.equal(action.search, '?execution=fixture-execution');
  assert.equal(f.image.src, source); assert.equal(f.state.requests, 0); assert.equal(f.state.submits, 0);
}));

test('a valid original attribute cannot conceal a foreign action getter or an explicit portal action', () => {
  using({url:'https://webvpn.cppu.edu.cn'+proxy+'/tpass/login',action:'/tpass/login'}, f => {
    Object.defineProperty(f.w.document.querySelector('form'),'action',{get:()=> 'https://attacker.example/tpass/login'});
    assert.equal(f.api.snapshot().detailCode, 'wrong-action-property');
    assert.equal(f.state.requests,0); assert.equal(f.state.submits,0);
  });
  using({url:'https://webvpn.cppu.edu.cn'+proxy+'/tpass/login',action:'https://webvpn.cppu.edu.cn/tpass/login'}, f => {
    assert.equal(f.api.snapshot().detailCode,'wrong-action-attribute');
    assert.equal(f.state.draws,0);
  });
});

test('a refused root-action rewrite stops before capture or authentication', () => using({url:'https://webvpn.cppu.edu.cn'+proxy+'/tpass/login'}, f => {
  const form=f.w.document.querySelector('form');
  form.setAttribute('action','/tpass/login');
  form.setAttribute=()=>{};
  assert.equal(f.api.snapshot().detailCode,'wrong-action-rewrite');
  assert.equal(f.state.draws,0); assert.equal(f.state.requests,0); assert.equal(f.state.submits,0);
}));

test('hidden CAPTCHA, hidden parent and invisible input are not processed', () => {
  for (const selector of ['img', 'form', '#authcode']) using({}, f => {
    f.w.document.querySelector(selector).style.display = 'none';
    assert.equal(f.api.snapshot().detailCode, 'not-visible');
    assert.equal(f.state.draws, 0);
  });
  using({}, f => {
    f.image.style.opacity = '0'; assert.equal(f.api.snapshot().detailCode, 'not-visible');
  });
  using({}, f => {
    f.image.getClientRects = () => []; assert.equal(f.api.snapshot().detailCode, 'not-visible');
  });
});

test('an incompletely loaded image waits and oversized images stop before canvas capture', () => using({}, f => {
  f.state.complete = false; assert.equal(f.api.snapshot().detailCode, 'image-loading');
  f.state.complete = true; f.state.width = 513; assert.equal(f.api.snapshot().detailCode, 'image-too-large');
  f.state.width = 120; f.state.height = 257; assert.equal(f.api.snapshot().detailCode, 'image-too-large');
  assert.equal(f.state.draws, 0);
}));

test('canvas refusal, invalid data and excessive base64 fail safely', () => {
  using({}, f => {
    f.w.HTMLCanvasElement.prototype.toDataURL = () => {throw new Error('tainted');};
    assert.equal(f.api.snapshot().detailCode, 'capture-unavailable');
  });
  using({dataUrl: 'data:text/plain;base64,abcd'}, f => assert.equal(f.api.snapshot().detailCode, 'capture-unavailable'));
  using({dataUrl: 'data:image/png;base64,' + 'A'.repeat(350001)}, f => assert.equal(f.api.snapshot().detailCode, 'invalid-image-data'));
});

test('fill requires the current nonce and exactly four digits, preserves leading zeros and emits input/change', () => using({}, f => {
  const ready = f.api.snapshot(), events = [];
  f.input.addEventListener('input', () => events.push('input'));
  f.input.addEventListener('change', () => events.push('change'));
  assert.equal(f.api.fill('wrong', '0123').detailCode, 'stale-token');
  for (const text of ['123', '12345', ' 0123', 'O123', 1234, '<script>']) assert.equal(f.api.fill(ready.token, text).detailCode, 'invalid-text');
  assert.equal(f.input.value, '');
  assert.equal(f.api.fill(ready.token, '0123').kind, 'filled');
  assert.equal(f.input.value, '0123'); assert.deepEqual(events, ['input', 'change']);
  assert.equal(f.api.snapshot().detailCode, 'own-fill-current');
  assert.equal(f.api.fill(ready.token, '4567').detailCode, 'stale-token');
  assert.equal(f.state.submits, 0);
}));

test('a user edit while OCR is pending cannot be overwritten, even after deleting it again', () => using({}, f => {
  const ready = f.api.snapshot(); f.edit('9876'); f.edit('');
  assert.equal(f.api.fill(ready.token, '0123').detailCode, 'stale-token');
  assert.equal(f.api.snapshot().detailCode, 'user-edited');
  assert.equal(f.api.reset().kind, 'reset'); assert.equal(f.api.snapshot().kind, 'ready');
}));

test('user edits are remembered between installation and the first native snapshot', () => using({}, f => {
  f.edit('9876'); f.edit('');
  assert.equal(f.api.snapshot().detailCode, 'user-edited');
  assert.equal(f.api.reset().kind, 'reset'); assert.equal(f.api.snapshot().kind, 'ready');
}));

test('autofilled or otherwise occupied input is not overwritten or exposed in returned data', () => using({}, f => {
  const ready = f.api.snapshot(); f.input.value = '8765';
  assert.equal(f.api.fill(ready.token, '0123').kind, 'manual');
  assert.equal(f.api.reset().kind, 'manual'); assert.equal(f.input.value, '8765');
  assert.doesNotMatch(JSON.stringify(f.api.snapshot()), /8765/);
}));

test('same-URL CAPTCHA pixel refresh rejects stale OCR; the next snapshot has a new token', () => using({}, f => {
  const ready = f.api.snapshot(); f.state.pixels = 'synthetic-image-B';
  assert.equal(f.api.fill(ready.token, '0123').detailCode, 'image-changed'); assert.equal(f.input.value, '');
  const next = f.api.snapshot(); assert.equal(next.kind, 'ready'); assert.notEqual(next.token, ready.token);
  assert.notEqual(next.imageKey, ready.imageKey);
}));

test('source query, dimensions and node replacement all invalidate a pending fill', () => {
  for (const change of [
    f => {f.image.src = '/tpass/captcha.jpg?cache=different';},
    f => {f.state.width = 121;},
    f => {f.input.replaceWith(f.input.cloneNode());},
    f => {f.image.replaceWith(f.image.cloneNode());}
  ]) using({}, f => {
    const ready = f.api.snapshot(); change(f);
    assert.notEqual(f.api.fill(ready.token, '0123').kind, 'filled');
    assert.equal(f.input.value, '');
  });
});

test('image refresh clears only the unchanged own fill and supports a fresh recognition', () => using({}, f => {
  const ready = f.api.snapshot(); f.api.fill(ready.token, '0123');
  f.state.pixels = 'synthetic-image-B'; const next = f.api.snapshot();
  assert.equal(f.input.value, ''); assert.equal(next.kind, 'ready');
  assert.equal(f.api.fill(next.token, '5678').kind, 'filled'); assert.equal(f.input.value, '5678');
}));

test('user edits of an own fill survive image refresh and explicit retry', () => using({}, f => {
  f.api.fill(f.api.snapshot().token, '0123'); f.edit('9876'); f.state.pixels = 'synthetic-image-B';
  assert.equal(f.api.snapshot().kind, 'manual'); assert.equal(f.input.value, '9876');
  assert.equal(f.api.reset().kind, 'manual'); assert.equal(f.input.value, '9876');
}));

test('explicit retry clears an unchanged own fill but never clears a manual nonempty value', () => using({}, f => {
  f.api.fill(f.api.snapshot().token, '0123'); assert.equal(f.api.reset().kind, 'reset'); assert.equal(f.input.value, '');
  const next = f.api.snapshot(); f.edit('0123');
  assert.equal(f.api.reset().kind, 'manual'); assert.equal(f.input.value, '0123');
  assert.notEqual(f.api.fill(next.token, '4567').kind, 'filled');
}));

test('reinjection is idempotent and cannot clear a user edit', () => using({}, f => {
  const original = f.api; f.api.snapshot(); f.edit('');
  assert.equal(f.w.eval(script).kind, 'installed'); assert.equal(f.w.LibraryCaptchaOcr, original);
  assert.equal(f.api.snapshot().detailCode, 'user-edited');
}));

test('path change after snapshot refuses filling and does not leak navigation details', () => using({}, f => {
  const ready = f.api.snapshot(); f.w.history.replaceState({}, '', '/tpass/other?ticket=secret-ticket');
  const returned = f.api.fill(ready.token, '0123');
  assert.equal(returned.detailCode, 'unsupported-page'); assert.equal(f.input.value, '');
  assert.doesNotMatch(JSON.stringify(returned), /ticket|secret|tpass/);
}));

test('query-only page navigation and form-action changes invalidate pending OCR without exposing their values', () => {
  for (const change of [
    f => f.w.history.replaceState({}, '', '/tpass/login?service=another-secret-service'),
    f => {f.w.document.querySelector('#fm1').action = '/tpass/login?execution=another-secret-execution';}
  ]) using({}, f => {
    const ready = f.api.snapshot(); change(f);
    const returned = f.api.fill(ready.token, '0123');
    assert.equal(returned.detailCode, 'image-changed'); assert.equal(f.input.value, '');
    assert.doesNotMatch(JSON.stringify(returned), /secret|service|execution|https:|tpass/);
    const next = f.api.snapshot(); assert.equal(next.kind, 'ready'); assert.notEqual(next.token, ready.token);
    assert.doesNotMatch(JSON.stringify(next), /secret|service|execution|https:|tpass/);
  });
});

test('the value is never followed by an automatic login or image refresh', () => using({}, f => {
  const before = f.image.src; f.api.fill(f.api.snapshot().token, '0123'); f.api.snapshot(); f.api.reset();
  assert.equal(f.image.src, before); assert.equal(f.state.requests, 0); assert.equal(f.state.submits, 0);
}));

test('explicit bounded refresh uses only the original school image handler and invalidates old OCR',()=>using({},f=>{
  let clicks=0;
  f.image.onclick=()=>{clicks++;f.state.pixels='synthetic-image-B';};
  const old=f.api.snapshot();
  assert.equal(f.api.refresh().kind,'refreshing');
  assert.equal(clicks,1);
  assert.notEqual(f.api.fill(old.token,'1234').kind,'filled');
  assert.equal(f.input.value,'');
  const current=f.api.snapshot();
  assert.notEqual(current.imageKey,old.imageKey);
  assert.equal(f.api.fill(current.token,'1234').kind,'filled');
  assert.equal(f.state.submits,0);
  assert.equal(f.state.requests,0);
}));

test('refresh is refused for manual edits even if erased, missing handlers or wrong targets',()=>{
  for(const change of [f=>f.edit('1'),f=>{f.edit('1');f.edit('');},
    f=>{f.w.document.querySelector('form').action='https://other.example/login';},
    f=>{f.image.src='https://other.example/captcha.jpg';}]) using({},f=>{
      let clicks=0;f.image.onclick=()=>{clicks++;};change(f);
      assert.notEqual(f.api.refresh().kind,'refreshing');assert.equal(clicks,0);
    });
  using({},f=>assert.equal(f.api.refresh().detailCode,'refresh-unavailable'));
});

test('an application-filled value is not a manual entry: the bounded refresh recovery still runs',()=>{
  using({},f=>{
    let clicks=0;
    f.image.onclick=()=>{clicks++;f.state.pixels='synthetic-image-B';};
    const old=f.api.snapshot(); assert.equal(old.kind,'ready');
    assert.equal(f.api.fill(old.token,'1234').kind,'filled');
    assert.equal(f.input.value,'1234');
    // The native controller reaches refresh() exactly after its own successful fill,
    // so refusing here would silently disable the documented "refresh then retry" path.
    assert.equal(f.api.refresh().kind,'refreshing');
    assert.equal(clicks,1); assert.equal(f.input.value,'');
    assert.notEqual(f.api.fill(old.token,'1234').kind,'filled');
    const current=f.api.snapshot(); assert.equal(current.kind,'ready');
    assert.notEqual(current.imageKey,old.imageKey);
    assert.equal(f.api.fill(current.token,'5678').kind,'filled');
    assert.equal(f.input.value,'5678');
    assert.equal(f.state.requests,0); assert.equal(f.state.submits,0);
  });
  // A genuinely manual value must still stop the automatic path.
  using({},f=>{
    let clicks=0;f.image.onclick=()=>{clicks++;};
    f.edit('9999'); assert.equal(f.api.refresh().detailCode,'input-occupied');
    assert.equal(clicks,0); assert.equal(f.input.value,'9999');
  });
  // A manual edit followed by manual erasure stays manual: ownership is not inferred.
  using({},f=>{
    let clicks=0;f.image.onclick=()=>{clicks++;};
    f.edit('1');f.edit('');
    assert.equal(f.api.refresh().kind,'manual'); assert.equal(clicks,0);
  });
});

test('representation-only proxy rewriting does not invalidate the same CAPTCHA pixels and query',()=>using({url:'https://webvpn.cppu.edu.cn'+proxy+'/tpass/login'},f=>{
  const ready=f.api.snapshot();
  const form=f.w.document.querySelector('form');
  Object.defineProperty(form,'action',{get:()=> 'https://sso.cppu.edu.cn/tpass/login?execution=secret-execution'});
  form.setAttribute('action','/tpass/login?execution=secret-execution');
  f.image.setAttribute('src','/tpass/captcha.jpg?cache=secret-cache');
  Object.defineProperty(f.image,'currentSrc',{get:()=> 'https://sso.cppu.edu.cn/tpass/captcha.jpg?cache=secret-cache'});
  assert.equal(f.api.fill(ready.token,'0123').kind,'filled');
  assert.equal(f.api.snapshot().kind,'filled'); assert.equal(f.state.requests,0); assert.equal(f.state.submits,0);
}));
