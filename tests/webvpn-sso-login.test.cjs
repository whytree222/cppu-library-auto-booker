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
  const remember=options.remember?'<input id="rememberMe" name="rememberMe" value="true" type="checkbox">':'';
  const duplicate=options.duplicateRemember?'<div style="display:none"><input id="rememberMe" name="rememberMe" value="true" type="checkbox"></div>':'';
  const d=new JSDOM(`<form id="fm1" method="post" action="${options.action||path}"><input id="username" name="username"><input id="password" name="password" type="password"><input id="authcode" name="authcode">${remember}${duplicate}<input id="passbutton" type="submit"></form>`,{url:options.url||origin+path,runScripts:'outside-only'});
  const w=d.window, user=w.document.querySelector('#username'), pass=w.document.querySelector('#password');
  w.HTMLElement.prototype.getClientRects=()=>[{width:120,height:40}];
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

test('five-day option is checked before the normal login button and manual unchecking wins',()=>use({remember:true},f=>{
  const box=f.w.document.querySelector('#rememberMe');
  const prepared=f.api.prepare({...f.credentials,rememberMe:true});
  assert.equal(prepared.rememberCode,'remember-checked'); assert.equal(box.checked,true);
  box.click(); assert.equal(box.checked,false);
  assert.equal(f.api.setRememberMe(true).detailCode,'remember-manual'); assert.equal(box.checked,false);
  assert.equal(f.api.submit().kind,'submitted'); assert.equal(f.state.posted,1);
}));
test('duplicate desktop/mobile remember IDs select only the visible option in the password form',()=>use({remember:true,duplicateRemember:true},f=>{
  f.api.prepare({...f.credentials,rememberMe:true});
  const boxes=f.w.document.querySelectorAll('#rememberMe');
  assert.equal(boxes.length,2); assert.equal(boxes[0].checked,true); assert.equal(boxes[1].checked,false);
  assert.equal(f.api.submit().kind,'submitted');
}));
test('disabling remembered-login undoes only the application selection, never a manual initial selection',()=>{
  use({remember:true},f=>{
    f.api.prepare({...f.credentials,rememberMe:true});
    assert.equal(f.api.setRememberMe(false).detailCode,'remember-not-checked');
    assert.equal(f.w.document.querySelector('#rememberMe').checked,false);
  });
  use({remember:true},f=>{
    f.w.document.querySelector('#rememberMe').checked=true;
    f.api.prepare({...f.credentials,rememberMe:false});
    assert.equal(f.w.document.querySelector('#rememberMe').checked,true);
  });
});
test('equivalent original/proxy action representations retain the prepared account and allow one login',()=>use({proxy:true},f=>{
  f.api.prepare(f.credentials);
  const form=f.w.document.querySelector('form');
  form.setAttribute('action','/tpass/login');
  Object.defineProperty(form,'action',{get:()=> 'https://sso.cppu.edu.cn/tpass/login'});
  assert.equal(f.api.submit().kind,'submitted'); assert.equal(f.state.posted,1);
}));
test('real browser submit event follows CAPTCHA fill, five-day selection and school RSA validation exactly once',()=>use({proxy:true,remember:true},f=>{
  const captchaScript=fs.readFileSync('app/src/main/assets/webvpn-captcha.js','utf8');
  delete f.w.LibraryCaptchaOcr;
  const form=f.w.document.querySelector('form'),image=f.w.document.createElement('img');
  image.className='codeImg'; image.src=prefix+'/tpass/captcha.jpg?cache=fixture'; form.appendChild(image);
  Object.defineProperties(image,{complete:{get:()=>true},naturalWidth:{get:()=>120},naturalHeight:{get:()=>40},currentSrc:{get:()=>image.src}});
  f.w.HTMLCanvasElement.prototype.getContext=()=>({drawImage(){}});
  f.w.HTMLCanvasElement.prototype.toDataURL=()=> 'data:image/png;base64,'+Buffer.from('synthetic-captcha-pixels').toString('base64');
  f.w.document.querySelector('#authcode').value='';
  f.w.checkPassLogin=()=>{
    assert.equal(f.user.value,'sso-only'); assert.equal(f.w.document.querySelector('#authcode').value,'0123');
    assert.equal(f.w.document.querySelector('#rememberMe').checked,true);
    f.pass.value='RSA:'+f.pass.value; f.state.encrypted=true; return true;
  };
  f.w.document.querySelector('#passbutton').onclick=()=> f.w.checkPassLogin();
  form.addEventListener('submit',e=>{e.preventDefault();f.state.posted++;});
  f.w.eval(captchaScript); f.api.prepare({...f.credentials,rememberMe:true});
  const ready=f.w.LibraryCaptchaOcr.snapshot(); assert.equal(ready.kind,'ready');
  assert.equal(f.w.LibraryCaptchaOcr.fill(ready.token,'0123').kind,'filled');
  assert.equal(f.api.submit().kind,'submitted'); assert.equal(f.state.encrypted,true); assert.equal(f.state.posted,1);
  f.api.submit(); assert.equal(f.state.posted,1);
}));
