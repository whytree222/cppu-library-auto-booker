(() => {
  // Credentials are supplied by native code only after this exact library route is verified.
  const reply = (kind, detailCode) => JSON.stringify({kind, detailCode});
  const origin = 'https://webvpn.cppu.edu.cn';
  const prefix = '/http/77726476706e69737468656265737421fdfb489e693378406b468ca88d1b203b';
  const loginPath = prefix + '/login';
  let page;
  try { page = new URL(location.href); } catch (_) { return reply('stopped', 'wrong-page'); }
  if (page.origin !== origin || page.username || page.password || page.pathname !== loginPath) {
    return reply('stopped', 'wrong-page');
  }
  if (window.__libraryVpnLoginSubmitted) return reply('submitted', 'already-submitted');
  const forms = document.querySelectorAll('form#fromuser');
  if (!forms.length) return reply('waiting', 'form-loading');
  if (forms.length !== 1) return reply('stopped', 'unsupported-structure');
  const form = forms[0];
  if (form.method.toLowerCase() !== 'post' || (form.target && form.target.toLowerCase() !== '_self')) {
    return reply('stopped', 'wrong-action');
  }
  let action;
  try { action = new URL(form.action, location.href); } catch (_) { return reply('stopped', 'wrong-action'); }
  const attribute = new URL(form.getAttribute('action') || location.href, location.href);
  const transportSafe = url => url.origin === origin && !url.username && !url.password && url.pathname === loginPath;
  const logicalSafe = url => url.origin === 'http://mlib.cppu.edu.cn' && !url.username && !url.password && url.pathname === '/login';
  // A root /login still targets the WebVPN portal, not the library. Wait for its rewrite.
  if (action.origin === origin && !action.username && !action.password && action.pathname === '/login') {
    // Normalize a known original library action before any credential is written.
    form.setAttribute('action', loginPath);
    return reply('waiting', 'action-not-rewritten');
  }
  if (!transportSafe(attribute) || !(transportSafe(action) || logicalSafe(action))) {
    return reply('stopped', 'wrong-action');
  }
  const users = document.querySelectorAll('input#user');
  const passwords = document.querySelectorAll('input#passwd');
  const destinations = document.querySelectorAll('input#url');
  if (users.length !== 1 || passwords.length !== 1 || destinations.length !== 1) {
    return reply('stopped', 'unsupported-structure');
  }
  const user = users[0], password = passwords[0], destination = destinations[0];
  if (user.form !== form || password.form !== form || destination.form !== form ||
      user.name !== 'user' || password.name !== 'passwd' || destination.name !== 'url' ||
      !['text', 'email'].includes(user.type) || password.type !== 'password' ||
      user.disabled || password.disabled || user.readOnly || password.readOnly) {
    return reply('stopped', 'unsupported-structure');
  }
  if (form.querySelector('input[name*="captcha" i],input[name*="verify" i],input[name*="authcode" i],iframe')) {
    return reply('manual', 'additional-verification');
  }
  if (typeof window.submitForm !== 'function') return reply('waiting', 'validation-loading');
  let state = window.__libraryVpnLoginInputState;
  if (!state || state.form !== form || state.user !== user || state.password !== password) {
    state = {form, user, password, edited: false, writing: false};
    window.__libraryVpnLoginInputState = state;
    for (const field of [user, password]) {
      for (const event of ['input', 'change']) field.addEventListener(event, () => {
        if (!state.writing) state.edited = true;
      });
    }
  }
  // An autofill or user edit always wins, including an edit subsequently cleared by the user.
  if (state.edited) return reply('manual', 'user-edited');
  if (user.value || password.value) return reply('manual', 'occupied-fields');
  const credentials = __LOGIN_CREDENTIALS__;
  if (credentials == null) return reply('ready', 'library-login-ready');
  if (typeof credentials.username !== 'string' || typeof credentials.password !== 'string' ||
      !credentials.username || !credentials.password ||
      /[\r\n\u0000]/.test(credentials.username + credentials.password)) {
    return reply('stopped', 'invalid-credentials');
  }
  // Use the school's normal validation/encoding routine, never form.submit() directly.
  window.__libraryVpnLoginSubmitted = true;
  state.writing = true;
  try {
    user.value = credentials.username;
    password.value = credentials.password;
    destination.value = 'selectreadingroom';
    for (const field of [user, password]) {
      field.dispatchEvent(new Event('input', {bubbles: true}));
      field.dispatchEvent(new Event('change', {bubbles: true}));
    }
    window.submitForm();
    return reply('submitted', 'submission-started');
  } catch (_) {
    // Never retry automatically: the school's routine may already have transmitted the form.
    return reply('stopped', 'submit-exception');
  } finally {
    state.writing = false;
  }
})();
