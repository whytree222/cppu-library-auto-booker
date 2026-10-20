const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const {JSDOM} = require('jsdom');
const template = fs.readFileSync('app/src/main/assets/transport.js','utf8');
const prefix = '/http/77726476706e69737468656265737421fdfb489e693378406b468ca88d1b203b';
const origin = 'https://webvpn.cppu.edu.cn';

function fixture() {
  const dom = new JSDOM('<form action="/multireadingroomtablelist" method="post"></form>',
    {url:origin+prefix+'/selectreadingroom', runScripts:'outside-only'});
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
  w.eval(template.replace('__TRANSPORT_CONFIG__',JSON.stringify({origin,prefix})));
  return {w,dom,calls,events,sent:()=>sent,locks:()=>locks,deny:()=>{allow=false;}};
}
test('proxy paths, forms and original absolute URLs stay in the same library proxy',()=>{
  const f=fixture(); const t=f.w.LibraryTransport;
  assert.equal(t.path(),'/selectreadingroom');
  assert.equal(t.url('/readingRoom/getYY?useday=2026-10-14'),origin+prefix+'/readingRoom/getYY?useday=2026-10-14');
  assert.equal(t.url('http://mlib.cppu.edu.cn/isLogin'),origin+prefix+'/isLogin');
  assert.equal(t.url(origin+prefix+'/isLogin'),origin+prefix+'/isLogin');
  assert.equal(t.url('/readingroomtablelist?roomno=42'),origin+prefix+'/readingroomtablelist?roomno=42');
  assert.equal(t.url('/selectreadingroom?groupid=4'),origin+prefix+'/selectreadingroom?groupid=4');
  assert.equal(f.w.document.querySelector('form').action,origin+prefix+'/multireadingroomtablelist');
  assert.equal(t.url('/wrdvpn/api'),'/wrdvpn/api');
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
test('read-only probe does not mark submission and external errors cannot trigger fallback',()=>{
  const f=fixture();const x=new f.w.XMLHttpRequest();x.open('POST','/isLogin');x.send();
  assert.equal(f.locks(),0);
  const y=new f.w.XMLHttpRequest();y.open('GET','https://external.example/image');y.send();y.handlers.error();
  assert.equal(f.events.length,0); f.dom.window.close();
});
