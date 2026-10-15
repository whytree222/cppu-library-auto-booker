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
  const d=new JSDOM(`<form id="fm1" method="post" action="${options.action||path}"><input id="username" name="username"><input id="password" name="password" type="password"><input id="authcode" name="authcode">${remember}${duplicate}<input id="passbutton" name="submit" type="submit" ${options.standard?'onclick="return checkPassLogin();"':''}></form>`,{url:options.url||origin+path,runScripts:'outside-only'});
  const w=d.window, user=w.document.querySelector('#username'), pass=w.document.querySelector('#password');
  w.HTMLElement.prototype.getClientRects=()=>[{width:120,height:40}];
  const state={posted:0,encrypted:false,captcha:'filled'};
  w.checkPassLogin=()=>true;
  w.LibraryCaptchaOcr={snapshot:()=>({kind:state.captcha,detailCode:'own-fill-current'})};
  w.document.querySelector('#authcode').value='0123';
  w.document.querySelector('#passbutton').onclick=e=>{e.preventDefault();state.encrypted=true;pass.value='RSA:'+pass.value;state.posted++;};
  if(options.standard){
    state.validations=0;state.requests=0;state.clicks=0;
    w.document.querySelector('#passbutton').onclick=()=>{state.clicks++;return false;};
    w.checkPassLogin=()=>{
      state.validations++;
      if(options.validationDenied)return false;
      pass.value='RSA:'+pass.value;state.encrypted=true;return true;
    };
    w.document.querySelector('form').requestSubmit=function(button){
      assert.equal(button,this.querySelector('#passbutton'));
      assert.equal(state.encrypted,true);
      state.requests++;
      if(options.noEvent)return;
      const event=new w.Event('submit',{bubbles:true,cancelable:true});
      if(this.dispatchEvent(event))state.posted++;
    };
  }
  w.eval(script);
  return {w,user,pass,state,api:w.SchoolSsoAutoLogin,close:()=>w.close(),credentials:{username:'sso-only',password:'sso-secret'}};
}
function use(options,fn){const f=fixture(options);try{fn(f);}finally{f.close();}}

function feedback(f, text, id = 'msg1', parent = f.w.document.body) {
  const node=f.w.document.createElement('span'); node.id=id; node.textContent=text;
  parent.appendChild(node); return node;
}

function useActivation(fn) {
  use({proxy:true,remember:true}, f=>{
    const {w,state}=f, button=w.document.querySelector('#passbutton'), form=w.document.querySelector('#fm1');
    // Exact public WebVPN-rewritten attribute; its click handler remains the owner
    // of proxy processing and the school's original validation/RSA.
    button.setAttribute('onclick','var vpn_return;eval(vpn_rewrite_js((function () { return checkPassLogin(); }).toString().slice(14, -2), 2));return vpn_return;');
    state.validations=0;state.proxyClicks=0;state.scrolled=0;
    w.checkPassLogin=()=>{state.validations++;f.pass.value='RSA:'+f.pass.value;state.encrypted=true;return true;};
    button.onclick=()=>{state.proxyClicks++;return w.checkPassLogin();};
    form.addEventListener('submit',e=>{e.preventDefault();state.posted++;});
    form.requestSubmit=()=>{throw Error('must-not-use-requestSubmit');};
    button.scrollIntoView=()=>{state.scrolled++;};
    button.getBoundingClientRect=()=>({left:100,top:400,right:500,bottom:460,width:400,height:60});
    Object.defineProperties(w,{innerWidth:{value:600,configurable:true},innerHeight:{value:800,configurable:true}});
    w.visualViewport={scale:1,offsetLeft:0,offsetTop:0,width:600,height:800};
    w.document.elementFromPoint=()=>button;
    fn({...f,button,form});
  });
}

test('native activation preflight preserves the WebVPN original button handler and does not click/encrypt/POST',()=>useActivation(f=>{
  f.api.prepare({...f.credentials,rememberMe:true});
  assert.equal(f.api.prepareActivation().kind,'activation-ready');
  assert.equal(f.state.scrolled,1);
  const point=f.api.activationPoint();
  assert.equal(point.kind,'ready');assert.equal(point.x,300);assert.equal(point.y,430);
  assert.equal(point.viewportWidth,600);assert.equal(point.viewportHeight,800);
  assert.equal(f.state.validations,0);assert.equal(f.state.proxyClicks,0);assert.equal(f.state.posted,0);
  assert.equal(f.api.activationOutcome().detailCode,'activation-unobserved');
  f.button.dispatchEvent(new f.w.MouseEvent('click',{bubbles:true,cancelable:true}));
  assert.equal(f.state.validations,1);assert.equal(f.state.proxyClicks,1);assert.equal(f.state.posted,1);
  assert.equal(f.api.activationOutcome().detailCode,'original-submit-observed');
  assert.equal(f.api.prepareActivation().kind,'blocked');
  f.api.submit();assert.equal(f.state.posted,1); // Legacy API cannot introduce a fallback.
}));

test('native activation waits for both owned fields and current CAPTCHA, and for RSA readiness without invoking RSA',()=>{
  useActivation(f=>{
    assert.equal(f.api.prepareActivation().kind,'blocked');
    f.api.prepare(f.credentials);f.state.captcha='manual';
    assert.equal(f.api.prepareActivation().kind,'waiting');
    assert.equal(f.state.validations,0);assert.equal(f.state.posted,0);
  });
  useActivation(f=>{
    const encrypted=f.w.document.createElement('input');encrypted.id='encrypted';f.form.appendChild(encrypted);
    f.api.prepare(f.credentials);
    assert.equal(f.api.prepareActivation().detailCode,'school-scripts-loading');
    f.w.RSAUtils={setMaxDigits(){throw Error('must-not-encrypt');},getKeyPair(){},encryptedString(){}};
    assert.equal(f.api.prepareActivation().kind,'activation-ready');
    assert.equal(f.state.validations,0);assert.equal(f.state.posted,0);
  });
});

test('native activation refuses unsupported viewport, nonfinite rectangles, hidden or fully covered buttons',()=>{
  for(const mutate of [
    f=>{f.w.visualViewport.scale=1.5;}, f=>{f.w.visualViewport.offsetTop=10;},
    f=>{f.w.visualViewport.width=400;}, f=>{f.button.style.display='none';},
    f=>{f.button.style.pointerEvents='none';},
    f=>{f.button.getBoundingClientRect=()=>({left:NaN,top:0,right:50,bottom:50,width:50,height:50});},
    f=>{f.w.document.elementFromPoint=()=>f.w.document.body;}
  ])useActivation(f=>{
    f.api.prepare(f.credentials);assert.equal(f.api.prepareActivation().kind,'activation-ready');
    mutate(f);assert.equal(f.api.activationPoint().kind,'blocked');assert.equal(f.state.posted,0);
  });
  useActivation(f=>{
    f.api.prepare(f.credentials);f.api.prepareActivation();
    f.w.document.elementFromPoint=x=>x===200?f.button:f.w.document.body;
    assert.equal(f.api.activationPoint().x,200);assert.equal(f.state.posted,0);
  });
});

test('native activation capture guard blocks changed fields, changed CAPTCHA, wrong target or changed action before original handlers',()=>{
  for(const mutate of [
    f=>{f.pass.value='manual';f.pass.dispatchEvent(new f.w.Event('input',{bubbles:true}));},
    f=>{f.state.captcha='manual';}, f=>{f.form.action='https://other.example/login';},
    f=>{f.w.history.replaceState({},'','?changed=fixture');}
  ])useActivation(f=>{
    f.api.prepare(f.credentials);f.api.prepareActivation();f.api.activationPoint();mutate(f);
    f.button.dispatchEvent(new f.w.MouseEvent('click',{bubbles:true,cancelable:true}));
    assert.equal(f.api.activationOutcome().detailCode,'activation-blocked');
    assert.equal(f.state.validations,0);assert.equal(f.state.posted,0);
  });
  useActivation(f=>{
    f.api.prepare(f.credentials);f.api.prepareActivation();f.api.activationPoint();
    let otherClicks=0;const other=f.w.document.createElement('button');
    other.onclick=()=>{otherClicks++;};f.w.document.body.appendChild(other);
    other.dispatchEvent(new f.w.MouseEvent('click',{bubbles:true,cancelable:true}));
    assert.equal(otherClicks,0);assert.equal(f.state.posted,0);
    assert.equal(f.api.activationOutcome().detailCode,'activation-blocked');
  });
});

test('cancelled native activation releases the event guard so later manual login remains possible',()=>useActivation(f=>{
  f.api.prepare(f.credentials);f.api.prepareActivation();f.api.activationPoint();f.api.cancel();
  f.button.dispatchEvent(new f.w.MouseEvent('click',{bubbles:true,cancelable:true}));
  assert.equal(f.state.validations,1);assert.equal(f.state.posted,1);
  assert.doesNotMatch(JSON.stringify(f.api.activationOutcome()),/sso-secret|sso-only|https|0123/);
}));

test('native activation submit guard rejects targets changed by the original click handler before a POST',()=>useActivation(f=>{
  f.api.prepare(f.credentials);f.api.prepareActivation();f.api.activationPoint();
  f.button.onclick=()=>{f.form.action='https://other.example/login';return true;};
  f.button.dispatchEvent(new f.w.MouseEvent('click',{bubbles:true,cancelable:true}));
  assert.equal(f.api.activationOutcome().detailCode,'activation-blocked');assert.equal(f.state.posted,0);
}));

test('native activation stays fail-closed after point expiry, including a delayed click on another node',()=>{
  for(const foreign of [false,true])useActivation(f=>{
    f.api.prepare(f.credentials);f.api.prepareActivation();f.api.activationPoint();
    const now=f.w.Date.now();f.w.Date.now=()=>now+2100;
    let foreignClicks=0;const other=f.w.document.createElement('button');other.onclick=()=>{foreignClicks++;};
    f.w.document.body.appendChild(other);
    (foreign?other:f.button).dispatchEvent(new f.w.MouseEvent('click',{bubbles:true,cancelable:true}));
    f.button.dispatchEvent(new f.w.MouseEvent('click',{bubbles:true,cancelable:true}));
    assert.equal(f.api.activationOutcome().detailCode,'activation-blocked');
    assert.equal(f.state.posted,0);assert.equal(f.state.validations,0);assert.equal(foreignClicks,0);
  });
});

test('one manual click racing with native UP cannot trigger the original login handler or POST twice',()=>useActivation(f=>{
  f.api.prepare(f.credentials);f.api.prepareActivation();f.api.activationPoint();
  f.button.dispatchEvent(new f.w.MouseEvent('click',{bubbles:true,cancelable:true}));
  f.button.dispatchEvent(new f.w.MouseEvent('click',{bubbles:true,cancelable:true}));
  assert.equal(f.state.validations,1);assert.equal(f.state.proxyClicks,1);assert.equal(f.state.posted,1);
}));

test('a late changed hit target cannot receive pointer-down or touch-start handlers before the click guard',()=>{
  for(const event of ['pointerdown','touchstart','mousedown'])useActivation(f=>{
    f.api.prepare(f.credentials);f.api.prepareActivation();f.api.activationPoint();
    const other=f.w.document.createElement('button');let actions=0;
    other.addEventListener(event,()=>{actions++;});f.w.document.body.appendChild(other);
    other.dispatchEvent(new f.w.Event(event,{bubbles:true,cancelable:true}));
    assert.equal(actions,0);assert.equal(f.api.activationOutcome().detailCode,'activation-blocked');
  });
});

test('manual submit before the reserved click takes over and blocks the later native activation',()=>useActivation(f=>{
  f.api.prepare(f.credentials);f.api.prepareActivation();f.api.activationPoint();
  f.form.dispatchEvent(new f.w.Event('submit',{bubbles:true,cancelable:true}));
  f.button.dispatchEvent(new f.w.MouseEvent('click',{bubbles:true,cancelable:true}));
  assert.equal(f.state.posted,1);assert.equal(f.state.validations,0);
  assert.equal(f.api.activationOutcome().detailCode,'activation-blocked');
}));

test('production driver uses only native original-button activation, never the legacy JS submit API',()=>{
  const driver=fs.readFileSync('app/src/main/java/cn/edu/cppu/libraryautobooker/WebVpnLoginDriver.kt','utf8');
  assert.match(driver,/SchoolSsoAutoLogin\?\.prepareActivation\(\)/);
  assert.match(driver,/SchoolSsoAutoLogin\?\.activationPoint\(\)/);
  assert.match(driver,/loginTap\.tap\(/);
  assert.doesNotMatch(driver,/SchoolSsoAutoLogin\?\.submit\(\)/);
});

test('read-only SSO outcome recognizes only explicit visible CAPTCHA rejection variants',()=>{
  for(const text of ['验证码信息无效。','验证码无效','验证码错误！',' 验证码 不正确 ',
      '验证码已失效','验证码失效','验证码已过期','验证码过期',
      '验证码信息已失效','验证码信息已过期','验证码已失效或过期','验证码已过期或失效'])use({proxy:true},f=>{
    feedback(f,text);
    const answer=f.api.outcome();
    assert.deepEqual(JSON.parse(JSON.stringify(answer)),{kind:'captcha-rejected',detailCode:'school-captcha-rejected'});
    assert.equal(f.state.posted,0); assert.equal(f.state.encrypted,false);
  });
});

test('SSO outcome prioritizes account, lockout, throttling, SMS and mixed feedback over CAPTCHA rejection',()=>{
  for(const text of ['账号或密码错误','账户已锁定','请求次数过多，请稍后重试','操作过于频繁',
      '短信验证码错误','验证码错误，密码也不正确','请完成手机验证','登录失败'])use({},f=>{
    feedback(f,'验证码信息无效。'); feedback(f,text,'swiSpan1');
    assert.equal(f.api.outcome().kind,'other-rejected'); assert.equal(f.state.posted,0);
  });
  use({},f=>{
    feedback(f,'验证码信息无效。'); feedback(f,'未知校方要求','swiSpan1');
    assert.equal(f.api.outcome().kind,'other-rejected');
  });
});

test('absent, hidden, ambiguous and generic feedback cannot authorize automatic CAPTCHA retry',()=>{
  for(const variant of ['absent','hidden','hidden-parent','opacity','duplicate','generic','oversized'])use({},f=>{
    if(variant!=='absent'){
      const node=feedback(f,variant==='generic'?'认证中，请稍候':variant==='oversized'?'验'.repeat(513):'验证码信息无效。');
      if(variant==='hidden')node.style.display='none';
      if(variant==='hidden-parent'){
        const parent=f.w.document.createElement('div'); parent.style.visibility='hidden';
        f.w.document.body.appendChild(parent); parent.appendChild(node);
      }
      if(variant==='opacity')node.style.opacity='0';
      if(variant==='duplicate')feedback(f,'验证码信息无效。');
    }
    assert.equal(f.api.outcome().kind,'unknown'); assert.equal(f.state.posted,0);
  });
});

test('SSO feedback observation never reads credentials, writes DOM, invokes validation or exposes school text',()=>use({},f=>{
  feedback(f,'验证码信息无效。');
  const before=f.w.document.documentElement.outerHTML;
  for(const field of [f.user,f.pass])Object.defineProperty(field,'value',{
    get(){throw Error('credential-value-must-not-be-read');},set(){throw Error('credential-value-must-not-be-written');}
  });
  f.w.checkPassLogin=()=>{throw Error('validation-must-not-be-invoked');};
  const answer=f.api.outcome();
  assert.equal(answer.kind,'captcha-rejected');
  assert.equal(f.w.document.documentElement.outerHTML,before);
  assert.doesNotMatch(JSON.stringify(answer),/验证码|sso-secret|sso-only|credential/);
  assert.equal(f.state.posted,0);
}));

test('SSO outcome requires the verified page, unique form structure and exact target without normalization',()=>{
  for(const options of [{url:'https://attacker.example/tpass/login'},
      {action:'https://attacker.example/login'}, {url:'http://sso.cppu.edu.cn/tpass/login'},
      {proxy:true,action:'https://webvpn.cppu.edu.cn/tpass/login'}])use(options,f=>{
    feedback(f,'验证码信息无效。');
    const before=f.w.document.documentElement.outerHTML;
    assert.equal(f.api.outcome().kind,'unknown');
    assert.equal(f.w.document.documentElement.outerHTML,before); assert.equal(f.state.posted,0);
  });
  use({},f=>{
    feedback(f,'验证码信息无效。');
    f.w.document.querySelector('#fm1').appendChild(f.user.cloneNode());
    assert.equal(f.api.outcome().kind,'unknown');
  });
});

test('returned proxy error page permits exact root-relative action observation and guard without a DOM rewrite',()=>use({proxy:true,action:'/tpass/login?execution=fixture-return'},f=>{
  feedback(f,'验证码信息无效。');
  const before=f.w.document.documentElement.outerHTML;
  assert.equal(f.api.outcome().kind,'captcha-rejected');
  assert.equal(f.api.armCaptchaRestart().kind,'armed');
  assert.equal(f.api.captchaRestartState().kind,'ready');
  assert.equal(f.w.document.documentElement.outerHTML,before);
  assert.equal(f.w.document.querySelector('#fm1').getAttribute('action'),'/tpass/login?execution=fixture-return');
}));

test('CAPTCHA restart guard is event-only and any rejected-form input, change, click or submit cancels it',()=>{
  for(const event of ['input','change','click','submit'])use({},f=>{
    feedback(f,'验证码信息无效。');
    for(const field of [f.user,f.pass])Object.defineProperty(field,'value',{
      get(){throw Error('credential-value-must-not-be-read');},set(){throw Error('credential-value-must-not-be-written');}
    });
    assert.equal(f.api.armCaptchaRestart().kind,'armed');
    assert.equal(f.api.captchaRestartState().kind,'ready');
    f.user.dispatchEvent(new f.w.Event(event,{bubbles:true,cancelable:true}));
    const result=f.api.captchaRestartState();
    assert.equal(result.kind,'manual');
    assert.equal(f.api.armCaptchaRestart().kind,'manual');
    assert.doesNotMatch(JSON.stringify(result),/credential|验证码|sso-only|sso-secret/);
    assert.equal(f.state.posted,0);
  });
});

test('manual form interaction after helper installation but before outcome and arming already prevents restart',()=>use({proxy:true,action:'/tpass/login'},f=>{
  feedback(f,'验证码信息无效。');
  f.user.dispatchEvent(new f.w.Event('input',{bubbles:true}));
  assert.equal(f.api.outcome().kind,'captcha-rejected');
  assert.equal(f.api.armCaptchaRestart().kind,'manual');
  assert.equal(f.api.captchaRestartState().kind,'unknown');
  assert.equal(f.state.posted,0);
}));

test('CAPTCHA restart guard cannot be armed for unknown or mixed rejection and invalidates changed document binding',()=>{
  for(const text of ['', '登录失败', '验证码信息无效，账号已锁定'])use({},f=>{
    feedback(f,text); assert.equal(f.api.armCaptchaRestart().kind,'unknown');
    assert.equal(f.api.captchaRestartState().kind,'unknown');
  });
  for(const mutate of [
    f=>{f.w.document.querySelector('#fm1').action='https://attacker.example/tpass/login';},
    f=>{f.w.history.replaceState({},'','?service=changed');},
    f=>{f.user.replaceWith(f.user.cloneNode());},
    f=>{f.w.document.querySelector('#msg1').textContent='登录失败';},
    f=>{f.api.cancel();}
  ])use({},f=>{
    feedback(f,'验证码信息无效。'); assert.equal(f.api.armCaptchaRestart().kind,'armed');
    mutate(f); assert.equal(f.api.captchaRestartState().kind,'unknown'); assert.equal(f.state.posted,0);
  });
});

test('exact school handler runs its validation then standard submitter once even when synthetic click is intercepted',()=>use({proxy:true,standard:true},f=>{
  f.api.prepare(f.credentials);
  const result=f.api.submit();
  assert.equal(result.kind,'submitted');assert.equal(result.detailCode,'form-submit-observed');
  assert.equal(f.state.validations,1);assert.equal(f.state.requests,1);assert.equal(f.state.posted,1);
  assert.equal(f.state.clicks,0);
  f.api.submit();assert.equal(f.state.requests,1);assert.equal(f.state.validations,1);
}));

test('school and browser local validation rejection never fall back to another submission',()=>{
  for(const browser of [false,true])use({standard:true,validationDenied:!browser},f=>{
    f.api.prepare(f.credentials);
    if(browser){f.user.required=true;f.user.pattern='[0-9]{9}';}
    assert.equal(f.api.submit().kind,'blocked');
    f.api.submit();assert.equal(f.state.validations,1);assert.equal(f.state.requests,0);assert.equal(f.state.clicks,0);
  });
});

test('missing school RSA script waits without clicking, then submits once after it loads',()=>use({standard:true},f=>{
  const encrypted=f.w.document.createElement('input');encrypted.id='encrypted';encrypted.type='hidden';
  f.w.document.querySelector('form').appendChild(encrypted);
  f.api.prepare(f.credentials);
  assert.equal(f.api.submit().detailCode,'school-scripts-loading');
  assert.equal(f.state.validations,0);assert.equal(f.state.requests,0);
  f.w.RSAUtils={setMaxDigits(){},getKeyPair(){},encryptedString(){}};
  assert.equal(f.api.submit().kind,'submitted');assert.equal(f.state.requests,1);
}));

test('requestSubmit exception, missing submit event and intercepted submit event never cause a resend',()=>{
  for(const behavior of ['throw','no-event','intercept'])use({standard:true,noEvent:behavior==='no-event'},f=>{
    if(behavior==='throw')f.w.document.querySelector('form').requestSubmit=()=>{f.state.requests++;throw Error('secret-error');};
    if(behavior==='intercept')f.w.document.querySelector('form').addEventListener('submit',e=>e.preventDefault());
    f.api.prepare(f.credentials);const result=f.api.submit();f.api.submit();
    assert.equal(f.state.requests,1);assert.equal(f.state.clicks,0);
    assert.doesNotMatch(JSON.stringify(result),/secret-error|sso-secret|sso-only/);
  });
});

test('submitter overrides and target changes during school validation never receive a form submission',()=>{
  for(const attr of ['formaction','formmethod','formtarget','formnovalidate'])use({standard:true},f=>{
    f.w.document.querySelector('#passbutton').setAttribute(attr,'other');
    assert.equal(f.api.prepare(f.credentials).kind,'stopped');assert.equal(f.pass.value,'');
  });
  use({standard:true},f=>{
    f.api.prepare(f.credentials);
    f.w.checkPassLogin=()=>{f.w.document.querySelector('form').action='https://other.example/login';return true;};
    assert.equal(f.api.submit().kind,'blocked');assert.equal(f.state.requests,0);
  });
});
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
test('DOM submit event follows CAPTCHA fill, five-day selection and simulated school validation exactly once',()=>use({proxy:true,remember:true},f=>{
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
