const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const {JSDOM} = require('jsdom');
const template = fs.readFileSync('app/src/main/assets/transport.js','utf8');
const directOrigin = 'http://mlib.cppu.edu.cn';
const vpnOrigin = 'https://webvpn.cppu.edu.cn';
const vpnPrefix = '/http/77726476706e69737468656265737421fdfb489e693378406b468ca88d1b203b';

function fixture(url = directOrigin + '/selectreadingroom', route = {origin: directOrigin, prefix: ''}) {
  const dom = new JSDOM('<form action="/multireadingroomtablelist" method="post"></form>',
    {url, runScripts:'outside-only'});
  const w = dom.window, events = [], calls = [];
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
  return {w,dom,calls,events,sent:()=>sent,locks:()=>locks,deny:()=>{allow=false;}};
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
