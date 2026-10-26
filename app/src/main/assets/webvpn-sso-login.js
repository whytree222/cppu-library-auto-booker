(() => {
  'use strict';
  if (window.SchoolSsoAutoLogin) return {kind: 'installed'};
  const proxy = '/https/77726476706e69737468656265737421e3e44ed224207845300d8db9d6562d';
  let own = null, edited = false, writing = false, submitted = false, cancelled = false;
  let submissionResult = null;
  let activation = null;
  let activationAttempts = 0;
  let captchaRestartGuard = null;
  let restartInteraction = null;
  let remembered = null, rememberEdited = false, rememberWriting = false;
  const result = (kind, detailCode) => ({kind, detailCode});
  function context(repairAction = true) {
    const page = new URL(location.href);
    if (page.protocol !== 'https:' || page.port || page.username || page.password) return null;
    const prefix = page.hostname === 'sso.cppu.edu.cn' ? '' : page.hostname === 'webvpn.cppu.edu.cn' ? proxy : null;
    if (prefix === null || page.pathname !== prefix + '/tpass/login') return null;
    const forms = document.querySelectorAll('form#fm1');
    if (forms.length !== 1) return null;
    const form = forms[0];
    function matches(raw) {
      const url = new URL(raw || location.href, location.href);
      if (url.username || url.password || url.port || url.hash) return false;
      return (url.origin === page.origin && url.pathname === prefix + '/tpass/login') ||
        (prefix && url.origin === 'https://sso.cppu.edu.cn' && url.pathname === '/tpass/login');
    }
    if (form.method.toLowerCase() !== 'post' || (form.target && form.target !== '_self')) return null;
    let raw = form.getAttribute('action') || '';
    const resolveAttribute = text => new URL(text || location.href,
      prefix && /^\/tpass\//.test(text.trim()) ? 'https://sso.cppu.edu.cn/tpass/login' : location.href);
    let attribute = resolveAttribute(raw);
    if (!matches(attribute.href)) return null;
    let action = new URL(form.action, location.href);
    if (!matches(action.href)) {
      if (!prefix || !/^\/(?!\/)/.test(raw.trim()) || action.origin !== page.origin ||
          action.pathname !== '/tpass/login' || action.search !== attribute.search ||
          action.username || action.password || action.port || action.hash) return null;
      if (repairAction) {
        // Normalize the school's known relative target before writing either credential.
        form.setAttribute('action', prefix + attribute.pathname + attribute.search);
        raw = form.getAttribute('action') || '';
        attribute = resolveAttribute(raw);
        action = new URL(form.action, location.href);
        if (!matches(action.href) || !matches(attribute.href)) return null;
      } else {
        // A returned error document can again expose the original root-relative
        // action. Verify its exact known equivalence without writing to the DOM.
        action = attribute;
      }
    }
    const user = form.querySelectorAll('#username'), password = form.querySelectorAll('#password');
    const button = form.querySelectorAll('#passbutton'), captcha = form.querySelectorAll('#authcode');
    if (user.length !== 1 || password.length !== 1 || button.length !== 1 || captcha.length !== 1 ||
        document.querySelectorAll('#username').length !== 1 || document.querySelectorAll('#password').length !== 1 ||
        user[0].tagName !== 'INPUT' || password[0].type !== 'password' ||
        !['text','email','tel'].includes(user[0].type) || user[0].disabled || password[0].disabled ||
        user[0].readOnly || password[0].readOnly || button[0].disabled ||
        typeof window.checkPassLogin !== 'function') return null;
    if (button[0].form !== form || button[0].type !== 'submit' ||
        !['INPUT','BUTTON'].includes(button[0].tagName) ||
        ['formaction','formmethod','formtarget','formnovalidate'].some(name=>button[0].hasAttribute(name))) return null;
    const canonical = rawUrl => {
      const url=new URL(rawUrl,location.href);
      const path=url.origin==='https://webvpn.cppu.edu.cn'?url.pathname.slice(proxy.length):url.pathname;
      return 'https://sso.cppu.edu.cn'+path+url.search;
    };
    return {form, user: user[0], password: password[0], button: button[0], captcha: captcha[0],
      href: page.href, action: canonical(action.href), attribute: canonical(attribute.href)};
  }
  function visible(element) {
    if (!element.isConnected || !Array.from(element.getClientRects()).some(r=>r.width>0&&r.height>0)) return false;
    for(let node=element;node&&node.nodeType===1;node=node.parentElement){
      const style=window.getComputedStyle(node);
      if(style.display==='none'||style.visibility==='hidden'||style.visibility==='collapse'||
          (style.opacity!==''&&Number(style.opacity)===0)) return false;
    }
    return true;
  }
  function outcome() {
    // Observe only the school's known feedback nodes on the verified password
    // form page. In particular, never read credential field values or return
    // arbitrary school text to native diagnostics.
    try {
      if (!context(false)) return result('unknown', 'unsupported-sso');
      const messages = [];
      for (const id of ['msg1', 'swiSpan1']) {
        const nodes = document.querySelectorAll('#' + id);
        if (nodes.length > 1) return result('unknown', 'ambiguous-feedback');
        if (nodes.length === 1 && visible(nodes[0])) {
          const text = nodes[0].textContent || '';
          if (text.length > 512) return result('unknown', 'unsupported-feedback');
          const normalized = text.replace(/[\s\u200b-\u200d\uFEFF]/g, '').replace(/[。.!！?？]+$/, '');
          if (normalized) messages.push(normalized);
        }
      }
      const unique = Array.from(new Set(messages));
      if (!unique.length) return result('unknown', 'no-explicit-result');
      const captchaMessages = new Set([
        '验证码信息无效', '验证码无效', '验证码错误', '验证码不正确',
        '验证码已失效', '验证码失效', '验证码已过期', '验证码过期',
        '验证码信息已失效', '验证码信息已过期',
        '验证码已失效或过期', '验证码已过期或失效'
      ]);
      // Password, lockout, throttling and additional verification always outrank
      // a CAPTCHA error. Mixed or unrecognized feedback is never retryable.
      const otherRejection = /账号|帐号|账户|用户名|密码|学工号|学号|锁定|冻结|停用|禁用|封禁|受限|频繁|次数|限流|频率|短信|手机|动态口令|动态码|二次验证|二次认证|人脸|扫码|安全问题|认证失败|登录失败|拒绝|不允许|禁止/;
      if (unique.some(text => otherRejection.test(text))) return result('other-rejected', 'school-auth-rejected');
      const captcha = unique.filter(text => captchaMessages.has(text));
      if (captcha.length === unique.length) return result('captcha-rejected', 'school-captcha-rejected');
      if (captcha.length) return result('other-rejected', 'mixed-school-feedback');
      return result('unknown', 'no-explicit-result');
    } catch (_) { return result('unknown', 'unsupported-sso'); }
  }
  function captchaRestartState() {
    const guard = captchaRestartGuard;
    if (!guard || cancelled || submitted) return result('unknown', 'restart-not-armed');
    if (guard.touched) return result('manual', 'restart-user-interaction');
    try {
      const info = context(false);
      if (!info || guard.document !== document || info.form !== guard.form || info.href !== guard.href ||
          info.action !== guard.action || info.attribute !== guard.attribute || info.user !== guard.user ||
          info.password !== guard.password || info.captcha !== guard.captcha || info.button !== guard.button)
        return result('unknown', 'restart-document-changed');
      if (outcome().kind !== 'captcha-rejected') return result('unknown', 'restart-feedback-changed');
      return result('ready', 'captcha-restart-ready');
    } catch (_) { return result('unknown', 'restart-document-changed'); }
  }
  function armCaptchaRestart() {
    if (captchaRestartGuard) return captchaRestartState();
    if (cancelled || submitted) return result('unknown', 'restart-not-available');
    try {
      const info = context(false);
      if (!info || outcome().kind !== 'captcha-rejected') return result('unknown', 'restart-not-rejected');
      const guard = restartInteraction;
      if (!guard || guard.form !== info.form || guard.document !== document || guard.href !== info.href ||
          guard.action !== info.action || guard.attribute !== info.attribute || guard.user !== info.user ||
          guard.password !== info.password || guard.captcha !== info.captcha || guard.button !== info.button)
        return result('unknown', 'restart-document-changed');
      if (guard.touched) return result('manual', 'restart-user-interaction');
      captchaRestartGuard = guard;
      return result('armed', 'captcha-restart-armed');
    } catch (_) { return result('unknown', 'restart-not-available'); }
  }
  function setRememberMe(enabled) {
    let info; try {info=context();}catch(_){return result('stopped','unsupported-sso');}
    if(cancelled||submitted||!own||!info||own.form!==info.form||own.href!==info.href) return result('stopped','not-prepared');
    const candidates=Array.from(info.form.querySelectorAll('input#rememberMe[name="rememberMe"][type="checkbox"]'))
      .filter(field=>field.form===info.form&&!field.disabled);
    const shown=candidates.filter(visible);
    const field=shown.length===1?shown[0]:candidates.length===1?candidates[0]:null;
    if(!field) return result('configured',candidates.length?'remember-ambiguous':'remember-unavailable');
    if(remembered&&remembered.field!==field) return result('configured','remember-changed');
    if(!remembered){
      remembered={field,ownChecked:false};
      for(const event of ['input','change','click']) field.addEventListener(event,()=>{
        if(!rememberWriting) rememberEdited=true;
      });
    }
    if(rememberEdited) return result('configured','remember-manual');
    if(enabled===true&&!field.checked){
      rememberWriting=true;
      try{field.click(); remembered.ownChecked=field.checked;}
      finally{rememberWriting=false;}
    }else if(enabled===false&&remembered.ownChecked&&field.checked){
      rememberWriting=true;
      try{field.click(); remembered.ownChecked=false;}
      finally{rememberWriting=false;}
    }
    return result('configured',field.checked?'remember-checked':'remember-not-checked');
  }
  function watch(info) {
    for (const field of [info.user, info.password]) for (const event of ['input','change'])
      field.addEventListener(event, () => { if (!writing) edited = true; });
  }
  function set(field, value) {
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(field, value);
    field.dispatchEvent(new Event('input', {bubbles:true}));
    field.dispatchEvent(new Event('change', {bubbles:true}));
  }
  function prepare(credentials) {
    if (cancelled) return result('manual','cancelled');
    if (submitted) return result('submitted','already-submitted');
    let info; try {info=context();} catch (_) {return result('stopped','unsupported-sso');}
    if (!info) return result('stopped','unsupported-sso');
    if (own) {
      if (edited || own.form !== info.form || own.href !== info.href || own.action !== info.action ||
          own.attribute !== info.attribute || own.user !== info.user || own.password !== info.password ||
          info.user.value !== own.username || info.password.value !== own.secret) return result('manual','user-edited');
      const remember=setRememberMe(credentials?.rememberMe===true);
      return {...result('prepared','own-fields'),rememberCode:remember.detailCode};
    }
    if (!credentials || typeof credentials.username !== 'string' || typeof credentials.password !== 'string' ||
        !credentials.username || !credentials.password || /[\r\n\u0000]/.test(credentials.username+credentials.password))
      return result('stopped','invalid-credentials');
    if (info.user.value || info.password.value) return result('manual','occupied-fields');
    watch(info);
    own={...info, username:credentials.username, secret:credentials.password};
    writing=true;
    try {set(info.user, credentials.username); set(info.password, credentials.password);}
    finally {writing=false;}
    const remember=setRememberMe(credentials.rememberMe===true);
    return {...result('prepared','sso-fields-ready'),rememberCode:remember.detailCode};
  }
  function activationBinding() {
    if (cancelled) return {error:result('manual','cancelled')};
    let info; try {info=context(false);} catch (_) {return {error:result('blocked','unsupported-sso')};}
    if (!info || !own) return {error:result('blocked','not-prepared')};
    if (edited || info.form !== own.form || info.user !== own.user || info.password !== own.password ||
        info.button !== own.button || info.href !== own.href || info.action !== own.action || info.attribute !== own.attribute ||
        info.user.value !== own.username || info.password.value !== own.secret)
      return {error:result('manual','user-edited')};
    const state=window.LibraryCaptchaOcr?.snapshot();
    if (!state || state.kind !== 'filled' || state.detailCode !== 'own-fill-current' || !/^\d{4}$/.test(info.captcha.value))
      return {error:result('waiting','captcha-not-current')};
    if (document.querySelector('#encrypted') && info.password.value.length !== 256 &&
        (typeof window.RSAUtils?.setMaxDigits !== 'function' || typeof window.RSAUtils?.getKeyPair !== 'function' ||
         typeof window.RSAUtils?.encryptedString !== 'function'))
      return {error:result('waiting','school-scripts-loading')};
    return {info};
  }
  function prepareActivation() {
    // Production uses a single native WebView touch, not requestSubmit or JS click.
    // In particular, do not pre-run RSA/checkPassLogin: the original button owns it.
    if (submitted || activation) return result('blocked','activation-already-prepared');
    if (activationAttempts >= 3) return result('blocked','activation-attempt-limit');
    const binding=activationBinding(); if(binding.error) return binding.error;
    const info=binding.info;
    if (!info.form.checkValidity()) return result('blocked','browser-validation-rejected');
    try {
      if (typeof info.button.scrollIntoView === 'function')
        info.button.scrollIntoView({behavior:'instant',block:'center',inline:'nearest'});
    } catch (_) {return result('blocked','button-scroll-failed');}
    // `reason` records WHY the one-shot guard closed. A closed guard normally means
    // "never touch this document again", but two verdicts prove that no click and no
    // submission ever reached the school: the reserved gesture missed the button, or
    // its window expired before any event arrived. Only those may be re-armed or
    // replaced by the school's own handler fallback.
    activation={info, armed:false, clickObserved:false, submitObserved:false, blocked:false,
      schoolSubmit:false, fallbackUsed:false, reason:'', deadline:0};
    activationAttempts++;
    submitted=true;
    return result('activation-ready','original-button-ready');
  }
  // One-shot capture guard for a single authorized activation. Installed for the
  // reserved native gesture and, separately, for the school-handler fallback; the
  // guard's `schoolSubmit` window is synchronous, so no input can interleave with it.
  function armGuards(info, current) {
    current.armed=true; current.deadline=Date.now()+2000;
    const guardDown=event=>{
      if(cancelled||activation!==current)return;
      if(current.abandoned)current.restartTouched=true;
      const targetMatches=event.target===info.button||info.button.contains(event.target);
      const valid=!current.blocked&&!current.clickObserved&&Date.now()<=current.deadline&&targetMatches&&!activationBinding().error;
      if(!valid){
        current.blocked=true;
        if(!current.reason){
          const check=targetMatches?activationBinding():null;
          current.reason=!targetMatches?'point-miss':
            check&&check.error?('binding-'+(check.error.detailCode||'changed')):
            Date.now()>current.deadline?'window-closed':'click-off-target';
        }
        event.preventDefault();event.stopImmediatePropagation();
      }
    };
    const guard=event=>{
      if(cancelled||activation!==current)return;
      if(current.abandoned)current.restartTouched=true;
      // The authorized script submission owns exactly the events it fires itself.
      // This window is synchronous, so no native touch can interleave with it.
      if(current.schoolSubmit){
        if(event.target===info.button||info.button.contains(event.target)){
          current.clickObserved=true;return;
        }
        current.blocked=true;current.reason='foreign-event';event.preventDefault();event.stopImmediatePropagation();return;
      }
      // A delayed UP or a manual click racing with that UP must not escape the
      // one-shot guard. Failure/expiry stays closed until native cancel/navigation.
      if(current.clickObserved||current.blocked||Date.now()>current.deadline){
        if(!current.clickObserved){current.blocked=true;if(!current.reason)current.reason='window-closed';}
        event.preventDefault();event.stopImmediatePropagation();return;
      }
      const check=activationBinding();
      if(check.error || (event.target!==info.button&&!info.button.contains(event.target))) {
        current.blocked=true;
        current.reason=check.error?('binding-'+(check.error.detailCode||'changed')):'click-off-target';
        event.preventDefault(); event.stopImmediatePropagation(); return;
      }
      current.clickObserved=true;
      // Clear only our private copies. The original button still reads the DOM
      // password and performs the school's own validation/encryption exactly once.
      if(own){own.secret='';own.username='';}
    };
    const observe=event=>{
      if(cancelled||activation!==current)return;
      if(current.abandoned){current.restartTouched=true;event.preventDefault();event.stopImmediatePropagation();return;}
      // Enter or another manual submission before the reserved click takes
      // priority. Leave that manual event alone, but block the pending auto-UP.
      if(!current.clickObserved&&!current.schoolSubmit){
        current.blocked=true;current.reason='manual-submit';return;
      }
      if(current.blocked||current.submitObserved||event.target!==info.form){
        current.blocked=true;if(!current.reason)current.reason='submission-target-changed';
        event.preventDefault();event.stopImmediatePropagation();return;
      }
      // The original/proxy handler may normalize a URL while encrypting. Only
      // equivalent trusted targets survive; a changed target must not receive POST.
      // The school's own validator has already replaced the password value during an
      // authorized script submission, so only structural identity is re-checked there.
      let target;try{target=context(false);}catch(_){target=null;}
      if(!target||target.form!==info.form||target.button!==info.button||target.user!==info.user||
          target.password!==info.password||target.captcha!==info.captcha||target.href!==info.href||
          target.action!==info.action||target.attribute!==info.attribute||
          (event.submitter&&event.submitter!==info.button)){
        current.blocked=true;current.reason='submission-target-changed';
        event.preventDefault();event.stopImmediatePropagation();return;
      }
      current.submitObserved=true;
    };
    const finish=event=>{if(current.clickObserved&&(event.target===info.button||info.button.contains(event.target))&&event.defaultPrevented){
      current.blocked=true;if(!current.reason)current.reason='aborted-click';}};
    document.addEventListener('click',guard,true);
    document.addEventListener('submit',observe,true);
    document.addEventListener('click',finish,false);
    for(const event of ['pointerdown','touchstart','mousedown'])document.addEventListener(event,guardDown,{capture:true,passive:false});
    current.detach=()=>{document.removeEventListener('click',guard,true);document.removeEventListener('submit',observe,true);document.removeEventListener('click',finish,false);for(const event of ['pointerdown','touchstart','mousedown'])document.removeEventListener(event,guardDown,true);};
  }
  /** True when the guard closed without any click or submission reaching the school. */
  function untouched(candidate) {
    return !!candidate && !candidate.clickObserved && !candidate.submitObserved && !candidate.abandoned &&
      !candidate.restartTouched && candidate.reason !== 'manual-submit';
  }
  function activationPoint() {
    if (!activation || activation.schoolSubmit || activation.clickObserved || activation.blocked || cancelled)
      return result('blocked','activation-unavailable');
    const binding=activationBinding(); if(binding.error) return binding.error;
    const info=binding.info;
    if (info.form!==activation.info.form || info.button!==activation.info.button || !visible(info.button))
      return result('blocked','button-changed');
    // Arm before geometry checks: a blocked measurement leaves native code deciding
    // whether the school handler may submit, and this document must stay guarded.
    if (!activation.armed) armGuards(info, activation);
    const width=window.innerWidth, height=window.innerHeight, viewport=window.visualViewport;
    if (![width,height].every(n=>Number.isFinite(n)&&n>0&&n<100000) ||
        (viewport && (!Number.isFinite(viewport.scale) || Math.abs(viewport.scale-1)>0.01 ||
          Math.abs(viewport.offsetLeft)>0.5 || Math.abs(viewport.offsetTop)>0.5 ||
          Math.abs(viewport.width-width)>2 || Math.abs(viewport.height-height)>2)))
      return result('blocked','viewport-ambiguous');
    const rect=info.button.getBoundingClientRect();
    if (![rect.left,rect.top,rect.right,rect.bottom,rect.width,rect.height].every(Number.isFinite) ||
        rect.width<8 || rect.height<8) return result('blocked','button-not-visible');
    const style=window.getComputedStyle(info.button);
    if(style.pointerEvents==='none') return result('blocked','button-not-interactive');
    let point=null;
    // A WebVPN floating widget can cover the right side of the original button.
    // Only select an unobstructed point inside that same verified button.
    for(const fraction of [0.5,0.25,0.75]) {
      const x=rect.left+rect.width*fraction, y=rect.top+rect.height/2;
      if(x<=1||x>=width-1||y<=1||y>=height-1)continue;
      const hit=document.elementFromPoint(x,y);
      if(hit===info.button||info.button.contains(hit)){point={x,y};break;}
    }
    if(!point)return result('blocked','button-obscured');
    return {...result('ready','original-button-point'),x:point.x,y:point.y,viewportWidth:width,viewportHeight:height};
  }
  function activationOutcome() {
    if(!activation)return {...result('activation-observed','activation-unobserved'),reason:''};
    const code=activation.blocked?'activation-blocked':activation.submitObserved?'original-submit-observed':
      activation.clickObserved?'original-click-observed':'activation-unobserved';
    // `reason` is a fixed local code. It never carries school text or field values.
    return {...result('activation-observed',code),reason:activation.blocked?(activation.reason||''):''};
  }
  function rearmActivation() {
    // Native requests this only after a read-only school check still requires
    // authentication. Never re-arm after any click/submit or manual takeover.
    // A closed guard also stays closed, with one exception: when the reserved gesture
    // provably missed the button, no click and no submission were ever delivered, so a
    // single re-armed window on the same document is still a first submission.
    if(cancelled||!activation||activation.clickObserved||activation.submitObserved)
      return result('blocked','activation-may-have-submitted');
    if(activation.blocked&&!(untouched(activation)&&
        (activation.reason==='point-miss'||activation.reason==='click-off-target')))
      return result('blocked','activation-may-have-submitted');
    if(activationAttempts>=3)return result('blocked','activation-attempt-limit');
    const binding=activationBinding();if(binding.error)return binding.error;
    activation.detach?.();activation=null;submitted=false;
    return prepareActivation();
  }
  function schoolHandlerSubmit() {
    // The last-resort engine for the case where the in-app touch produces no observable
    // click at all. The proxy keeps the school's own inline handler in the DOM, so the
    // school's validator/RSA still runs: either through the original `return
    // checkPassLogin();` plus a standard submitter, or, when the proxy has rewritten the
    // attribute into its vpn_rewrite_js(...) wrapper, through the button's own click.
    // It is one-shot, never used after any observed click/submit or manual takeover, and
    // never used when a manual submission may already be in flight.
    if (cancelled) return result('manual', 'cancelled');
    if (!activation || !own) return result('blocked', 'not-prepared');
    if (activation.schoolSubmit) return result('blocked', 'school-submit-already-used');
    if (activation.fallbackUsed || !untouched(activation)) return result('blocked', 'activation-may-have-submitted');
    const binding = activationBinding(); if (binding.error) return binding.error;
    const info = binding.info;
    const attribute = info.button.getAttribute('onclick');
    const direct = typeof attribute === 'string' && /^\s*return\s+checkPassLogin\(\s*\)\s*;?\s*$/.test(attribute);
    // The proxy's rewrite keeps the school's own call inside its wrapper.
    const rewritten = !direct && typeof attribute === 'string' &&
      /checkPassLogin\(\)/.test(attribute) && /vpn_rewrite_js|vpn_eval/.test(attribute);
    if (!direct && !rewritten) return result('blocked', 'school-handler-unavailable');
    if (direct ? typeof info.form.requestSubmit !== 'function' : typeof info.button.click !== 'function')
      return result('blocked', 'school-handler-unavailable');
    // The school's handler has already validated the same structure, but the initial
    // constraint check must not be skipped before either engine runs.
    if (!info.form.checkValidity()) return result('blocked', 'browser-validation-rejected');
    // Re-open exactly one synchronous window. The previous closure was a proven miss, so
    // this is not a second submission; the guard stays armed to observe (and veto a
    // changed target for) whatever the school's own handler is about to submit.
    activation.detach?.();
    activation.blocked = false; activation.clickObserved = false; activation.submitObserved = false;
    activation.reason = 'school-submit-authorized'; activation.schoolSubmit = true; activation.fallbackUsed = true;
    armGuards(info, activation);
    try {
      if (!direct) {
        info.button.click();
        return result('submitted', activation.submitObserved
          ? 'school-handler-submit-observed' : 'school-handler-click-unconfirmed');
      }
      // The school's own handler owns validation and RSA encryption. A false return
      // means the school itself refused, and no request may be sent. Nothing was
      // submitted, so the document stays eligible for the conservative restart path.
      if (window.checkPassLogin() !== true) return result('blocked', 'school-validation-rejected');
      let after; try { after = context(); } catch (_) { after = null; }
      // Only structural identity is re-checked: the school's handler has already
      // replaced the password field with its encrypted value.
      if (!after || after.form !== info.form || after.button !== info.button || after.user !== info.user ||
          after.password !== info.password || after.captcha !== info.captcha || after.href !== info.href ||
          after.action !== info.action || after.attribute !== info.attribute ||
          after.user.value !== own.username) {
        activation.blocked = true; activation.reason = 'form-changed-before-submit';
        return result('blocked', 'form-changed-before-submit');
      }
      if (own) { own.secret = ''; own.username = ''; }
      info.form.requestSubmit(info.button);
      return result('submitted', activation.submitObserved
        ? 'school-handler-submit-observed' : 'school-handler-submit-unconfirmed');
    } catch (_) {
      activation.blocked = true; activation.reason = 'submit-uncertain';
      return result('stopped', 'submit-uncertain');
    } finally {
      // The click and submit events fired by requestSubmit are synchronous, so this
      // closes the authorized window before any native input can be dispatched.
      if (activation) activation.schoolSubmit = false;
      if (own) { own.secret = ''; own.username = ''; }
    }
  }
  function unsubmittedRestartState() {
    if(cancelled||!activation?.abandoned||activation.clickObserved||activation.submitObserved||activation.restartTouched)
      return result('blocked','restart-interaction-or-submission');
    const binding=activationBinding();if(binding.error)return binding.error;
    if(outcome().kind!=='unknown')return result('blocked','school-feedback-present');
    return result('ready','unsubmitted-document-retired');
  }
  function abandonUnsubmittedActivation() {
    // Retire the old document atomically before native code may issue a fresh GET.
    // A late UP/click/submit stays blocked, never racing a second credential POST.
    if(activation?.abandoned)return unsubmittedRestartState();
    if(cancelled||!activation?.armed||activation.clickObserved||activation.submitObserved||activation.blocked)
      return result('blocked','activation-may-have-submitted');
    const binding=activationBinding();if(binding.error)return binding.error;
    if(outcome().kind!=='unknown')return result('blocked','school-feedback-present');
    activation.abandoned=true;activation.blocked=true;activation.reason='document-retired';activation.restartTouched=false;
    const current=activation;
    for(const event of ['input','change'])current.info.form.addEventListener(event,()=>{current.restartTouched=true;},true);
    return unsubmittedRestartState();
  }
  function submit() {
    if (cancelled) return result('manual','cancelled');
    if (submitted) return submissionResult || result('submitted','already-submitted');
    let info; try {info=context();} catch (_) {return result('stopped','unsupported-sso');}
    if (!info || !own) return result('stopped','not-prepared');
    if (edited || info.form !== own.form || info.user !== own.user || info.password !== own.password ||
        info.button !== own.button || info.href !== own.href || info.action !== own.action || info.attribute !== own.attribute ||
        info.user.value !== own.username || info.password.value !== own.secret) return result('manual','user-edited');
    const state=window.LibraryCaptchaOcr?.snapshot();
    if (!state || state.kind !== 'filled' || state.detailCode !== 'own-fill-current' || !/^\d{4}$/.test(info.captcha.value))
      return result('waiting','captcha-not-current');
    const standard = /^\s*return\s+checkPassLogin\(\s*\)\s*;?\s*$/.test(info.button.getAttribute('onclick') || '') &&
      typeof info.form.requestSubmit === 'function';
    // This school's exact inline handler only runs checkPassLogin (including RSA).
    // Keep that validation, then use normal submitter/constraint/submit-event semantics.
    // Never call form.submit(): the school also has an input named "submit".
    if (standard && document.querySelector('#encrypted') && info.password.value.length !== 256 &&
        (typeof window.RSAUtils?.setMaxDigits !== 'function' || typeof window.RSAUtils?.getKeyPair !== 'function' ||
         typeof window.RSAUtils?.encryptedString !== 'function')) return result('waiting','school-scripts-loading');
    submitted=true;
    const record = value => {submissionResult=value;return value;};
    try {
      if (!standard) {
        info.button.click();
        return record(result('submitted','school-button-invoked'));
      }
      if (window.checkPassLogin() !== true) return record(result('blocked','school-validation-rejected'));
      const current=context();
      if (!current || current.form!==info.form || current.button!==info.button || current.user!==info.user ||
          current.password!==info.password || current.href!==info.href || current.action!==info.action ||
          current.attribute!==info.attribute || current.user.value!==own.username)
        return record(result('blocked','form-changed-before-submit'));
      if (!info.form.checkValidity()) return record(result('blocked','browser-validation-rejected'));
      let observed=false;
      const observe = event => {if(event.target===info.form) observed=true;};
      info.form.addEventListener('submit',observe,true);
      try {info.form.requestSubmit(info.button);}
      finally {info.form.removeEventListener('submit',observe,true);}
      // A submit event can be intercepted by the proxy. It is not server success,
      // and even a missing event must never cause a second credential submission.
      return record(result('submitted',observed?'form-submit-observed':'form-submit-unconfirmed'));
    }
    catch (_) {return record(result('stopped','submit-uncertain'));}
    finally {own.secret=''; own.username='';}
  }
  function cancel() {cancelled=true; activation?.detach?.(); if(own){own.secret='';own.username='';} return result('manual','cancelled');}
  // Track events from installation, including the small interval before native
  // asks for the outcome. This reads no field values and never prevents an event.
  try {
    const initial = context(false);
    if (initial) {
      restartInteraction = {...initial, document, touched: false};
      const observation = restartInteraction;
      for (const event of ['input', 'change', 'submit', 'click'])
        initial.form.addEventListener(event, () => { observation.touched = true; }, true);
    }
  } catch (_) { /* Unknown pages remain ineligible for an automatic restart. */ }
  Object.defineProperty(window,'SchoolSsoAutoLogin',{value:Object.freeze({prepare,submit,prepareActivation,activationPoint,activationOutcome,rearmActivation,schoolHandlerSubmit,abandonUnsubmittedActivation,unsubmittedRestartState,cancel,setRememberMe,outcome,armCaptchaRestart,captchaRestartState}), writable:false});
  return result('installed','sso-helper-ready');
})();
