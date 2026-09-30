(() => {
  if (location.origin !== 'http://mlib.cppu.edu.cn') return 'wrong-origin';
  if (window.__libraryLoginSubmitted) return 'submitted';
  const form = document.querySelector('form#fromuser');
  if (!form) return 'no-form';
  const action = new URL(form.action, location.href);
  if (action.origin !== location.origin || action.pathname !== '/login' || form.method.toLowerCase() !== 'post') return 'wrong-action';
  const user = form.querySelector('input#user[name="user"]');
  const password = form.querySelector('input#passwd[name="passwd"]');
  if (!user || !password || typeof window.submitForm !== 'function') return 'unsupported';
  if (form.querySelector('input[name*="captcha" i],input[name*="verify" i],iframe')) return 'manual-required';
  const credentials = __LOGIN_CREDENTIALS__;
  if (!credentials.username || !credentials.password || /[\r\n]/.test(credentials.username + credentials.password)) return 'invalid-credentials';
  user.value = credentials.username;
  password.value = credentials.password;
  const destination = form.querySelector('input#url[name="url"]');
  if (destination) destination.value = 'selectreadingroom';
  window.__libraryLoginSubmitted = true;
  window.submitForm();
  return 'submitted';
})();

