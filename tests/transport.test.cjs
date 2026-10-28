const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const {JSDOM} = require('jsdom');
const template = fs.readFileSync('app/src/main/assets/transport.js','utf8');
const directOrigin = 'http://mlib.cppu.edu.cn';
const vpnOrigin = 'https://webvpn.cppu.edu.cn';
const vpnPrefix = '/http/77726476706e69737468656265737421fdfb489e693378406b468ca88d1b203b';

function fixture(url = directOrigin + '/selectreadingroom', route = {origin: directOrigin, prefix: ''}, options = {}) {
  const dom = new JSDOM('<form action="/multireadingroomtablelist" method="post"></form>',
    {url, runScripts:'outside-only'});
  const w = dom.window, events = [], calls = [];
  let formWrites = 0, mutationCallback, observerTurns = 0, observerLoop = false;
  const nativeGetAttribute = w.Element.prototype.getAttribute;
  if (options.virtualFormAction) {
    const native = Object.getOwnPropertyDescriptor(w.HTMLFormElement.prototype, 'action');
    Object.defineProperty(w.HTMLFormElement.prototype, 'action', {
      configurable: true,
      get() { return native.get.call(this).replace(vpnOrigin + vpnPrefix, directOrigin); },
      set(value) { formWrites++; native.set.call(this, value); }
    });
    const setAttribute = w.Element.prototype.setAttribute;
    w.Element.prototype.setAttribute = function(name, value) {
      if (this.tagName === 'FORM' && name === 'action') formWrites++;
      return setAttribute.call(this, name, value);
    };
    // Manually deliver observer turns so the old implementation cannot lock up
    // the test runner with an infinite microtask loop.
    if (!options.realObserver) w.MutationObserver = class {
        constructor(callback) { mutationCallback = callback; }
        observe() {}
        disconnect() {}
      };
  }
  if (options.virtualAttribute) {
    w.Element.prototype.getAttribute = function(name) {
      const value = nativeGetAttribute.call(this, name);
      return this.tagName === 'FORM' && name === 'action'
        ? value?.replace(vpnOrigin + vpnPrefix, directOrigin) : value;
    };
  }
  if (options.realObserver) {
    const NativeObserver = w.MutationObserver;
    w.MutationObserver = class {
      constructor(callback) {
        this.inner = new NativeObserver(records => {
          // Bound a regression so an infinite old-code microtask loop reports
          // failure instead of hanging the entire Node test process.
          if (++observerTurns > 12) { observerLoop = true; this.disconnect(); return; }
          callback(records, this);
        });
      }
      observe(target, settings) { this.inner.observe(target, settings); }
      disconnect() { this.inner.disconnect(); }
    };
  }
  let sent = 0, locks = 0, allow = true;
  class XHR {
    constructor(){this.handlers={};}
    open(method,url){calls.push({method,url});}
    addEventListener(event,fn){this.handlers[event]=fn;}
    send(){sent++;}
  }
  w.XMLHttpRequest = XHR;
  w.AutoBooker = {beginSubmission:()=>{locks++;return allow;}, report:(state,detail)=>events.push({state,detail})};
  w.eval(template.replace('__TRANSPORT_CONFIG__',JSON.stringify(route)));
  return {w,dom,calls,events,sent:()=>sent,locks:()=>locks,deny:()=>{allow=false;},
    formWrites:()=>formWrites, mutations:()=>mutationCallback?.(),
    rawAction:()=>nativeGetAttribute.call(w.document.querySelector('form'), 'action'),
    observerTurns:()=>observerTurns, observerLoop:()=>observerLoop};
}
test('campus paths and forms stay on the original library origin',()=>{
  const f=fixture(); const t=f.w.LibraryTransport;
  assert.equal(t.path(),'/selectreadingroom');
  assert.equal(t.url('/readingRoom/getYY?useday=2026-10-14'),directOrigin+'/readingRoom/getYY?useday=2026-10-14');
  assert.equal(t.url('http://mlib.cppu.edu.cn/isLogin'),directOrigin+'/isLogin');
  assert.equal(t.url(directOrigin+'/isLogin'),directOrigin+'/isLogin');
  assert.equal(t.url('/readingroomtablelist?roomno=42'),directOrigin+'/readingroomtablelist?roomno=42');
  assert.equal(t.url('/selectreadingroom?groupid=4'),directOrigin+'/selectreadingroom?groupid=4');
  assert.equal(f.w.document.querySelector('form').action,directOrigin+'/multireadingroomtablelist');
  assert.equal(t.url('https://external.example/login'),'https://external.example/login');
  f.dom.window.close();
});
test('reservation is locked before send, connection error reports without resending',()=>{
  const f=fixture(); const x=new f.w.XMLHttpRequest();
  x.open('POST','/readingroom/multipostbeskdata'); x.send();
  assert.equal(f.locks(),1); assert.equal(f.sent(),1);
  x.handlers.error(); assert.equal(f.events[0].state,'network-error'); assert.equal(f.sent(),1);
  f.deny(); const y=new f.w.XMLHttpRequest();y.open('POST','/readingroom/postbeskdata');y.send();
  assert.equal(f.sent(),1); f.dom.window.close();
});
test('read-only probe does not mark submission and external errors do not trigger library network reports',()=>{
  const f=fixture();const x=new f.w.XMLHttpRequest();x.open('POST','/isLogin');x.send();
  assert.equal(f.locks(),0);
  const y=new f.w.XMLHttpRequest();y.open('GET','https://external.example/image');y.send();y.handlers.error();
  assert.equal(f.events.length,0); f.dom.window.close();
});
test('WebVPN pages rewrite only library endpoints into the proxy prefix',()=>{
  const f=fixture(vpnOrigin+vpnPrefix+'/selectreadingroom', {origin: vpnOrigin, prefix: vpnPrefix});
  const t=f.w.LibraryTransport;
  assert.equal(t.path(),'/selectreadingroom');
  assert.equal(t.url('http://mlib.cppu.edu.cn/isLogin'),vpnOrigin+vpnPrefix+'/isLogin');
  assert.equal(t.url('http://mlib.cppu.edu.cn/readingRoom/getYY?useday=2026-10-14'),
    vpnOrigin+vpnPrefix+'/readingRoom/getYY?useday=2026-10-14');
  assert.equal(t.url(vpnOrigin+vpnPrefix+'/isLogin'),vpnOrigin+vpnPrefix+'/isLogin');
  assert.equal(t.url('/selectreadingroom?groupid=4'),vpnOrigin+vpnPrefix+'/selectreadingroom?groupid=4');
  assert.equal(t.url('/login'),vpnOrigin+vpnPrefix+'/login');
  assert.equal(t.url('/https/77726476706e69737468656265737421e3e44ed224207845300d8db9d6562d/tpass/login'),
    '/https/77726476706e69737468656265737421e3e44ed224207845300d8db9d6562d/tpass/login');
  assert.equal(t.url('/vpn-control/resource'),'/vpn-control/resource');
  assert.equal(f.w.document.querySelector('form').action,
    vpnOrigin+vpnPrefix+'/multireadingroomtablelist');
  const x=new f.w.XMLHttpRequest();x.open('POST','/readingroom/multipostbeskdata');x.send();
  assert.equal(f.locks(),1); assert.equal(f.sent(),1);
  f.dom.window.close();
});
test('foreign pages and a WebVPN page with the wrong proxy prefix never install transport',()=>{
  const cases = [
    ['https://other.example/selectreadingroom', {origin: directOrigin, prefix: ''}],
    ['https://webvpn.cppu.edu.cn/http/retired/selectreadingroom', {origin: vpnOrigin, prefix: vpnPrefix}],
    ['https://webvpn.cppu.edu.cn/selectreadingroom', {origin: vpnOrigin, prefix: vpnPrefix}],
  ];
  for(const [url, route] of cases){
    const f=fixture(url, route);
    assert.equal(f.w.LibraryTransport,undefined);
    const x=new f.w.XMLHttpRequest();x.open('POST','/readingroom/multipostbeskdata');x.send();
    assert.equal(f.locks(),0); assert.equal(f.events.length,0);
    f.dom.window.close();
  }
});

test('proxy-virtualized form.action cannot create a self-triggering mutation loop', () => {
  const f = fixture(vpnOrigin + vpnPrefix + '/selectreadingroom',
    {origin: vpnOrigin, prefix: vpnPrefix}, {virtualFormAction: true});
  const form = f.w.document.querySelector('form');
  assert.equal(form.action, directOrigin + '/multireadingroomtablelist');
  assert.equal(form.getAttribute('action'), vpnOrigin + vpnPrefix + '/multireadingroomtablelist');
  assert.equal(f.formWrites(), 1);
  for (let turn = 0; turn < 12; turn++) f.mutations();
  assert.equal(f.formWrites(), 1, 'observer must stop mutating an already-proxied action');
  // A genuinely changed action must still be checked and mapped, not ignored.
  form.setAttribute('action', '/readingroomtablelist?roomno=34');
  f.mutations();
  assert.equal(form.getAttribute('action'), vpnOrigin + vpnPrefix + '/readingroomtablelist?roomno=34');
  const after = f.formWrites();
  f.mutations();
  assert.equal(f.formWrites(), after);
  f.dom.window.close();
});

test('real observer excludes its own writes even when proxy virtualizes attribute getters', async () => {
  const f = fixture(vpnOrigin + vpnPrefix + '/selectreadingroom',
    {origin: vpnOrigin, prefix: vpnPrefix},
    {virtualFormAction: true, virtualAttribute: true, realObserver: true});
  try {
    f.w.document.body.appendChild(f.w.document.createElement('div'));
    await new Promise(resolve => f.w.setTimeout(resolve, 10));
    assert.equal(f.observerLoop(), false);
    assert.equal(f.observerTurns(), 1);
    assert.equal(f.rawAction(), vpnOrigin + vpnPrefix + '/multireadingroomtablelist');
    const writes = f.formWrites();
    await new Promise(resolve => f.w.setTimeout(resolve, 10));
    assert.equal(f.formWrites(), writes, 'page timers must run without more self-triggered writes');
  } finally { f.dom.window.close(); }
});
