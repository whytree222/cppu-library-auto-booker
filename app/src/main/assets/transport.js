(() => {
  if (window.LibraryTransport) return;
  const route = __TRANSPORT_CONFIG__;
  const origin = 'http://mlib.cppu.edu.cn', prefix = '';
  if (route.origin !== origin || route.prefix !== '' || location.origin !== origin) return;
  const pathOf = url => {
    const u = new URL(url, location.href);
    if (u.origin !== origin || u.username || u.password || !u.pathname.startsWith(prefix + '/')) return null;
    return u.pathname.slice(prefix.length);
  };
  const map = value => {
    const raw = String(value);
    const u = new URL(raw, location.href);
    return u.origin === origin && !u.username && !u.password ? u.href : raw;
  };
  const reservation = url => /\/(?:multi)?postbeskdata(?:$|\/)/i.test(pathOf(url) || '');
  window.LibraryTransport = { path: () => pathOf(location.href), url: map };
  const open = XMLHttpRequest.prototype.open, send = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function (method, url, ...rest) {
    const mapped = map(url);
    this.__libraryURL = mapped;
    return open.call(this, method, mapped, ...rest);
  };
  XMLHttpRequest.prototype.send = function (...args) {
    if (reservation(this.__libraryURL)) {
      // Synchronous native guard, before the request can leave this process.
      if (window.AutoBooker?.beginSubmission && !window.AutoBooker.beginSubmission()) return;
    }
    this.addEventListener('error', () => {
      if (pathOf(this.__libraryURL) !== null) window.AutoBooker?.report('network-error', '图书馆接口连接失败');
    }, {once: true});
    this.addEventListener('timeout', () => {
      if (pathOf(this.__libraryURL) !== null) window.AutoBooker?.report('network-error', '图书馆接口连接超时');
    }, {once: true});
    this.addEventListener('load', () => {
      if ([408, 502, 503, 504].includes(this.status) && pathOf(this.__libraryURL) !== null)
        window.AutoBooker?.report('network-error', `图书馆网关返回 HTTP ${this.status}`);
    }, {once: true});
    return send.apply(this, args);
  };
  const forms = () => window.document?.querySelectorAll('form[action]').forEach(form => {
    const mapped = map(form.getAttribute('action'));
    if (form.action !== new URL(mapped, location.href).href) form.action = mapped;
  });
  forms();
  new MutationObserver(forms).observe(document.documentElement, {childList:true, subtree:true, attributes:true, attributeFilter:['action']});
})();
