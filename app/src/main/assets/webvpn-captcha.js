(() => {
  'use strict';
  // Read only the already-loaded CAPTCHA. Never inspect account/password values,
  // fetch another image, refresh a CAPTCHA, or submit the authentication form.
  if (window.LibraryCaptchaOcr && window.LibraryCaptchaOcr.version === 1) return {kind: 'installed'};
  const boundDocument = document;
  const proxyPrefix = '/https/77726476706e69737468656265737421e3e44ed224207845300d8db9d6562d';
  let watchedInput = null;
  let userEdited = false;
  let ownDispatch = false;
  let pending = null;
  let ownFill = null;
  let nonceCount = 0;

  const result = (kind, detailCode) => detailCode ? {kind, detailCode} : {kind};
  // WebVPN may expose original upstream URLs through patched DOM property getters.
  // Both representations must resolve to the SAME known SSO resource, never another host.
  function target(url, route, path) {
    if (url.username || url.password || url.port || url.hash) return false;
    return (url.origin === route.origin && url.pathname === route.prefix + path) ||
      (route.prefix === proxyPrefix && url.origin === 'https://sso.cppu.edu.cn' && url.pathname === path);
  }
  function resourceFor(url) {
    if (url.protocol !== 'https:' || url.username || url.password || url.port) return null;
    if (url.hostname === 'sso.cppu.edu.cn') return {origin: url.origin, prefix: ''};
    if (url.hostname === 'webvpn.cppu.edu.cn' && url.pathname.startsWith(proxyPrefix + '/')) {
      return {origin: url.origin, prefix: proxyPrefix};
    }
    return null;
  }
  function attributeResource(raw, route) {
    // The proxy's getAttribute can retain the original root-relative SSO path.
    // Interpret only that representation against the known upstream, not the VPN portal.
    const text = (raw || '').trim();
    const base = route.prefix === proxyPrefix && /^\/tpass\//.test(text)
      ? 'https://sso.cppu.edu.cn/tpass/login' : location.href;
    return new URL(text || location.href, base);
  }
  function formAction(form, route) {
    let raw = form.getAttribute('action') || '';
    let attribute = attributeResource(raw, route);
    if (!target(attribute, route, '/tpass/login')) return {error: result('stopped', 'wrong-action-attribute')};
    let action = new URL(form.action, location.href);
    if (!target(action, route, '/tpass/login')) {
      // If DOM rewriting has not occurred, repair ONLY the verified original relative action.
      // No credentials are read, and changing a form action sends no request.
      const rootRelative = /^\/(?!\/)/.test(raw.trim());
      if (route.prefix !== proxyPrefix || !rootRelative || action.origin !== route.origin ||
          action.pathname !== '/tpass/login' || action.username || action.password || action.port || action.hash)
        return {error: result('stopped', 'wrong-action-property')};
      form.setAttribute('action', route.prefix + attribute.pathname + attribute.search);
      raw = form.getAttribute('action') || '';
      attribute = attributeResource(raw, route);
      action = new URL(form.action, location.href);
      if (!target(action, route, '/tpass/login') || !target(attribute, route, '/tpass/login'))
        return {error: result('stopped', 'wrong-action-rewrite')};
    }
    return {action, attribute};
  }
  function visible(element) {
    if (!element || !element.isConnected) return false;
    const rects = element.getClientRects();
    if (!Array.from(rects).some(rect => rect.width > 0 && rect.height > 0)) return false;
    for (let node = element; node && node.nodeType === 1; node = node.parentElement) {
      const style = window.getComputedStyle(node);
      if (style.display === 'none' || style.visibility === 'hidden' || style.visibility === 'collapse' ||
          (style.opacity !== '' && Number(style.opacity) === 0)) return false;
    }
    return true;
  }
  function page() {
    if (document !== boundDocument) return {error: result('stopped', 'document-changed')};
    let current, route;
    try {
      current = new URL(location.href);
      route = resourceFor(current);
    } catch (_) { return {error: result('stopped', 'unsupported-page')}; }
    if (!route || current.pathname !== route.prefix + '/tpass/login') {
      return {error: result('stopped', 'unsupported-page')};
    }
    const forms = document.querySelectorAll('#fm1');
    if (forms.length !== 1 || forms[0].tagName !== 'FORM') return {error: result('stopped', 'unsupported-structure')};
    const inputs = forms[0].querySelectorAll('#authcode');
    const images = forms[0].querySelectorAll('img.codeImg');
    const users = forms[0].querySelectorAll('#username');
    const passwords = forms[0].querySelectorAll('#password');
    if (inputs.length !== 1 || images.length !== 1 || users.length !== 1 || passwords.length !== 1 ||
        document.querySelectorAll('#authcode').length !== 1 || document.querySelectorAll('#username').length !== 1 ||
        document.querySelectorAll('#password').length !== 1) return {error: result('stopped', 'unsupported-structure')};
    const form = forms[0], input = inputs[0], image = images[0];
    if (input.tagName !== 'INPUT' || input.name !== 'authcode' || users[0].tagName !== 'INPUT' || passwords[0].tagName !== 'INPUT' ||
        !form.contains(input) || !form.contains(image) || !form.contains(users[0]) || !form.contains(passwords[0]) ||
        !['text', 'tel', 'number'].includes(input.type) || input.disabled || input.readOnly ||
        form.method.toLowerCase() !== 'post') return {error: result('stopped', 'unsupported-structure')};
    try {
      const destination = formAction(form, route);
      if (destination.error) return destination;
      const action = destination.action, actionAttribute = destination.attribute;
      const source = attributeResource(image.getAttribute('src') || '', route);
      const currentSource = new URL(image.currentSrc || image.src, location.href);
      if (!image.getAttribute('src') || image.getAttribute('srcset') ||
          !target(source, route, '/tpass/captcha.jpg') || !target(currentSource, route, '/tpass/captcha.jpg')) {
        return {error: result('stopped', 'unsupported-image')};
      }
      if (!visible(input) || !visible(image)) return {error: result('waiting', 'not-visible')};
      return {form, input, image, pageHref: current.href, actionHref: action.href, actionAttribute: actionAttribute.href,
        source: source.href, currentSource: currentSource.href};
    } catch (_) { return {error: result('stopped', 'unsupported-structure')}; }
  }
  function watch(input) {
    if (watchedInput === input) return;
    if (watchedInput) {
      watchedInput.removeEventListener('input', edited);
      watchedInput.removeEventListener('change', edited);
    }
    watchedInput = input;
    userEdited = false;
    pending = null;
    ownFill = null;
    input.addEventListener('input', edited);
    input.addEventListener('change', edited);
  }
  function edited(event) {
    if (!ownDispatch && event.target === watchedInput) {
      userEdited = true;
      pending = null;
    }
  }
  function owned(input) {
    return ownFill && ownFill.input === input && !userEdited && input.value === ownFill.text;
  }
  function fingerprint(data, width, height) {
    const text = width + 'x' + height + ':' + data;
    let hash = 2166136261;
    for (let i = 0; i < text.length; i++) hash = Math.imul(hash ^ text.charCodeAt(i), 16777619);
    return (hash >>> 0).toString(16).padStart(8, '0');
  }
  function capture(info) {
    const width = info.image.naturalWidth, height = info.image.naturalHeight;
    if (!info.image.complete || !width || !height) return {error: result('waiting', 'image-loading')};
    if (!Number.isInteger(width) || !Number.isInteger(height) || width > 512 || height > 256 || width < 1 || height < 1) {
      return {error: result('stopped', 'image-too-large')};
    }
    try {
      const canvas = document.createElement('canvas');
      canvas.width = width; canvas.height = height;
      const context = canvas.getContext('2d');
      if (!context) return {error: result('unavailable', 'capture-unavailable')};
      context.drawImage(info.image, 0, 0, width, height);
      const dataUrl = canvas.toDataURL('image/png');
      const prefix = 'data:image/png;base64,';
      if (!dataUrl.startsWith(prefix)) return {error: result('unavailable', 'capture-unavailable')};
      const data = dataUrl.slice(prefix.length);
      if (!data || data.length > 350000 || !/^[A-Za-z0-9+/]+={0,2}$/.test(data)) {
        return {error: result('stopped', 'invalid-image-data')};
      }
      return {...info, width, height, data, imageKey: fingerprint(data, width, height)};
    } catch (_) { return {error: result('unavailable', 'capture-unavailable')}; }
  }
  function sameImage(left, right) {
    // The hash is for the native attempt counter only; filling requires exact pixels.
    return left && right && left.form === right.form && left.input === right.input && left.image === right.image &&
      left.pageHref === right.pageHref && left.actionHref === right.actionHref &&
      left.actionAttribute === right.actionAttribute &&
      left.source === right.source && left.currentSource === right.currentSource &&
      left.width === right.width && left.height === right.height && left.data === right.data;
  }
  function nonce() {
    try {
      const bytes = new Uint32Array(4);
      window.crypto.getRandomValues(bytes);
      return Array.from(bytes, number => number.toString(16).padStart(8, '0')).join('');
    } catch (_) {
      return Date.now().toString(36) + '-' + (++nonceCount).toString(36) + '-' +
        Math.random().toString(36).slice(2) + Math.random().toString(36).slice(2);
    }
  }
  function setValue(input, value) {
    ownDispatch = true;
    try {
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
      setter.call(input, value);
      input.dispatchEvent(new Event('input', {bubbles: true}));
      input.dispatchEvent(new Event('change', {bubbles: true}));
    } finally { ownDispatch = false; }
  }
  function snapshot() {
    const info = page();
    if (info.error) return info.error;
    watch(info.input);
    if (userEdited) return result('manual', 'user-edited');
    if (info.input.value !== '' && !owned(info.input)) return result('manual', 'input-occupied');
    const picture = capture(info);
    if (picture.error) return picture.error;
    if (owned(info.input)) {
      if (sameImage(ownFill, picture)) return result('filled', 'own-fill-current');
      // A manual CAPTCHA refresh invalidates only our own unchanged value.
      setValue(info.input, '');
      ownFill = null;
      pending = null;
      if (userEdited || info.input.value !== '') return result('manual', 'input-changed');
    }
    if (!sameImage(pending, picture)) pending = {...picture, token: nonce()};
    return {kind: 'ready', token: pending.token, data: pending.data, imageKey: pending.imageKey};
  }
  function fill(token, text) {
    if (typeof token !== 'string' || !pending || token !== pending.token) return result('stopped', 'stale-token');
    if (typeof text !== 'string' || !/^\d{4}$/.test(text)) return result('stopped', 'invalid-text');
    const info = page();
    if (info.error) { pending = null; return info.error; }
    if (info.input !== watchedInput || userEdited || info.input.value !== '') {
      pending = null;
      return result('manual', 'input-changed');
    }
    const picture = capture(info);
    if (picture.error) { pending = null; return picture.error; }
    if (!sameImage(pending, picture)) { pending = null; return result('stopped', 'image-changed'); }
    ownFill = {...picture, text};
    pending = null;
    setValue(info.input, text);
    if (!info.input.isConnected || info.input.value !== text || document !== boundDocument) {
      ownFill = null;
      return result('stopped', 'fill-not-retained');
    }
    return result('filled');
  }
  function reset() {
    const info = page();
    if (info.error) return info.error;
    watch(info.input);
    if (info.input.value !== '' && !owned(info.input)) return result('manual', 'input-occupied');
    if (owned(info.input)) setValue(info.input, '');
    if (info.input.value !== '') return result('manual', 'input-changed');
    pending = null;
    ownFill = null;
    userEdited = false;
    return result('reset');
  }
  Object.defineProperty(window, 'LibraryCaptchaOcr', {
    value: Object.freeze({version: 1, snapshot, fill, reset}), writable: false, configurable: false
  });
  // Start tracking edits at installation, not only after the first native poll.
  const initial = page();
  if (!initial.error) watch(initial.input);
  return result('installed');
})();
