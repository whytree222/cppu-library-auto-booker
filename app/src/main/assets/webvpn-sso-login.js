(() => {
  'use strict';
  if (window.SchoolSsoAutoLogin) return {kind: 'installed'};
  const proxy = '/https/77726476706e69737468656265737421e3e44ed224207845300d8db9d6562d';
  let own = null, edited = false, writing = false, submitted = false, cancelled = false;
  let remembered = null, rememberEdited = false, rememberWriting = false;
  const result = (kind, detailCode) => ({kind, detailCode});
  function context() {
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
    if (!matches(form.action)) {
      const action = new URL(form.action, location.href);
      if (!prefix || !/^\/(?!\/)/.test(raw.trim()) || action.origin !== page.origin ||
          action.pathname !== '/tpass/login' || action.username || action.password || action.port || action.hash) return null;
      // Normalize the school's known relative target before writing either credential.
      form.setAttribute('action', prefix + attribute.pathname + attribute.search);
      raw = form.getAttribute('action') || '';
      attribute = resolveAttribute(raw);
      if (!matches(form.action) || !matches(attribute.href)) return null;
    }
    const user = form.querySelectorAll('#username'), password = form.querySelectorAll('#password');
    const button = form.querySelectorAll('#passbutton'), captcha = form.querySelectorAll('#authcode');
    if (user.length !== 1 || password.length !== 1 || button.length !== 1 || captcha.length !== 1 ||
        document.querySelectorAll('#username').length !== 1 || document.querySelectorAll('#password').length !== 1 ||
        user[0].tagName !== 'INPUT' || password[0].type !== 'password' ||
        !['text','email','tel'].includes(user[0].type) || user[0].disabled || password[0].disabled ||
        user[0].readOnly || password[0].readOnly || button[0].disabled ||
        typeof window.checkPassLogin !== 'function') return null;
    const canonical = rawUrl => {
      const url=new URL(rawUrl,location.href);
      const path=url.origin==='https://webvpn.cppu.edu.cn'?url.pathname.slice(proxy.length):url.pathname;
      return 'https://sso.cppu.edu.cn'+path+url.search;
    };
    return {form, user: user[0], password: password[0], button: button[0], captcha: captcha[0],
      href: page.href, action: canonical(form.action), attribute: canonical(attribute.href)};
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
  function submit() {
    if (cancelled) return result('manual','cancelled');
    if (submitted) return result('submitted','already-submitted');
    let info; try {info=context();} catch (_) {return result('stopped','unsupported-sso');}
    if (!info || !own) return result('stopped','not-prepared');
    if (edited || info.form !== own.form || info.user !== own.user || info.password !== own.password ||
        info.button !== own.button || info.href !== own.href || info.action !== own.action || info.attribute !== own.attribute ||
        info.user.value !== own.username || info.password.value !== own.secret) return result('manual','user-edited');
    const state=window.LibraryCaptchaOcr?.snapshot();
    if (!state || state.kind !== 'filled' || state.detailCode !== 'own-fill-current' || !/^\d{4}$/.test(info.captcha.value))
      return result('waiting','captcha-not-current');
    // School onclick invokes checkPassLogin and its RSA encryption; never replace it with form.submit().
    submitted=true;
    try {info.button.click(); return result('submitted','sso-submit-started');}
    catch (_) {return result('stopped','submit-uncertain');}
    finally {own.secret=''; own.username='';}
  }
  function cancel() {cancelled=true; if(own){own.secret='';own.username='';} return result('manual','cancelled');}
  Object.defineProperty(window,'SchoolSsoAutoLogin',{value:Object.freeze({prepare,submit,cancel,setRememberMe}), writable:false});
  return result('installed','sso-helper-ready');
})();
