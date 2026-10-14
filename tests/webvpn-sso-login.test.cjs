const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const {JSDOM}=require('jsdom');
const script=fs.readFileSync('app/src/main/assets/webvpn-sso-login.js','utf8');
const prefix='/https/77726476706e69737468656265737421e3e44ed224207845300d8db9d6562d';
function fixture(options={}) {
  const proxied=options.proxy;
  const origin=proxied?'https://webvpn.cppu.edu.cn':'https://sso.cppu.edu.cn';
  const path=(proxied?prefix:'')+'/tpass/login';
  const d=new JSDOM(`<form id="fm1" method="post" action="${options.action||path}"><input id="username"><input id="password" type="password"><input id="authcode"><input id="passbutton" type="submit"></form>`,{url:options.url||origin+path,runScripts:'outside-only'});
  const w=d.window, user=w.document.querySelector('#username'), pass=w.document.querySelector('#password');
  const state={posted:0,encrypted:false,captcha:'filled'};
  w.checkPassLogin=()=>true;
  w.LibraryCaptchaOcr={snapshot:()=>({kind:state.captcha,detailCode:'own-fill-current'})};
  w.document.querySelector('#authcode').value='0123';
  w.document.querySelector('#passbutton').onclick=e=>{e.preventDefault();state.encrypted=true;pass.value='RSA:'+pass.value;state.posted++;};
  w.eval(script);
  return {w,user,pass,state,api:w.SchoolSsoAutoLogin,close:()=>w.close(),credentials:{username:'sso-only',password:'sso-secret'}};
}
function use(options,fn){const f=fixture(options);try{fn(f);}finally{f.close();}}
test('SSO uses only its explicit credentials and normal RSA login button once',()=>use({},f=>{
  assert.equal(f.api.prepare(f.credentials).kind,'prepared');
  assert.equal(f.user.value,'sso-only');assert.equal(f.pass.value,'sso-secret');
  assert.equal(f.api.submit().kind,'submitted');assert.equal(f.api.submit().kind,'submitted');
  assert.equal(f.state.posted,1);assert.equal(f.state.encrypted,true);
}));
test('known proxy target supports an original DOM action getter',()=>use({proxy:true},f=>{
  Object.defineProperty(f.w.document.querySelector('form'),'action',{get:()=> 'https://sso.cppu.edu.cn/tpass/login'});
  assert.equal(f.api.prepare(f.credentials).kind,'prepared');assert.equal(f.api.submit().kind,'submitted');
}));
test('school root-relative action is normalized before either credential is written',()=>use({proxy:true,action:'/tpass/login?execution=fixture-execution'},f=>{
  assert.equal(f.api.prepare(f.credentials).kind,'prepared');
  const action=new URL(f.w.document.querySelector('form').action);
  assert.equal(action.origin,'https://webvpn.cppu.edu.cn');
  assert.equal(action.pathname,prefix+'/tpass/login');
  assert.equal(action.search,'?execution=fixture-execution');
  assert.equal(f.api.submit().kind,'submitted'); assert.equal(f.state.posted,1); assert.equal(f.state.encrypted,true);
}));
test('root-relative action attribute supports the WebVPN original upstream getter without changing it',()=>use({proxy:true,action:'/tpass/login'},f=>{
  const form=f.w.document.querySelector('form');
  Object.defineProperty(form,'action',{get:()=> 'https://sso.cppu.edu.cn/tpass/login'});
  assert.equal(f.api.prepare(f.credentials).kind,'prepared');
  assert.equal(form.getAttribute('action'),'/tpass/login');
  assert.equal(f.api.submit().kind,'submitted'); assert.equal(f.state.posted,1);
}));
test('root-relative compatibility does not permit foreign targets or explicit VPN portal forms',()=>{
  for(const getter of ['https://attacker.example/tpass/login','http://sso.cppu.edu.cn/tpass/login']) use({proxy:true,action:'/tpass/login'},f=>{
    Object.defineProperty(f.w.document.querySelector('form'),'action',{get:()=>getter});
    assert.equal(f.api.prepare(f.credentials).kind,'stopped');
    assert.equal(f.user.value,''); assert.equal(f.pass.value,''); assert.equal(f.state.posted,0);
  });
  use({proxy:true,action:'https://webvpn.cppu.edu.cn/tpass/login'},f=>{
    assert.equal(f.api.prepare(f.credentials).kind,'stopped'); assert.equal(f.user.value,''); assert.equal(f.pass.value,'');
  });
});
test('wrong origin/action never receives credentials',()=>{
  for(const options of [{url:'https://attacker.example/tpass/login'},{action:'https://attacker.example/login'},{url:'http://sso.cppu.edu.cn/tpass/login'},{proxy:true,action:'/other'}])use(options,f=>{
    assert.equal(f.api.prepare(f.credentials).kind,'stopped');assert.equal(f.user.value,'');assert.equal(f.pass.value,'');assert.equal(f.state.posted,0);
  });
});
test('manual account fields, even edited then emptied, are never overwritten or posted',()=>use({},f=>{
  assert.equal(f.api.prepare(f.credentials).kind,'prepared');f.pass.value='other';f.pass.dispatchEvent(new f.w.Event('input'));
  f.pass.value='';f.pass.dispatchEvent(new f.w.Event('input'));
  assert.equal(f.api.submit().kind,'manual');assert.equal(f.state.posted,0);
}));
test('occupied fields before preparation are not replaced',()=>use({},f=>{
  f.user.value='manual-account';assert.equal(f.api.prepare(f.credentials).kind,'manual');assert.equal(f.pass.value,'');
}));
test('a manual or stale CAPTCHA cannot trigger auto submission',()=>use({},f=>{
  f.api.prepare(f.credentials);f.state.captcha='manual';assert.equal(f.api.submit().kind,'waiting');assert.equal(f.state.posted,0);
}));
test('query/action changes and cancellation stop submission without exposing values',()=>{
  for(const change of [f=>f.w.history.replaceState({},'','?service=other'),f=>f.w.document.querySelector('form').action='https://attacker.example/login',f=>f.api.cancel()])use({},f=>{
    f.api.prepare(f.credentials);change(f);const r=f.api.submit();assert.notEqual(r.kind,'submitted');assert.equal(f.state.posted,0);assert.doesNotMatch(JSON.stringify(r),/sso-secret|sso-only|attacker|https/);
  });
});
