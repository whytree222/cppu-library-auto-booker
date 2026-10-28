(() => {
  if (window.LibraryTransport) return;
  const route = __TRANSPORT_CONFIG__;
  const origin = route.origin, prefix = route.prefix;
  if (location.origin !== origin || !location.pathname.startsWith(prefix + '/')) return;
  const pathOf = url => {
    const u = new URL(url, location.href);
    if (u.origin !== origin || u.username || u.password || !u.pathname.startsWith(prefix + '/')) return null;
    return u.pathname.slice(prefix.length);
  };
  const map = value => {
    const raw = String(value);
    const u = new URL(raw, location.href);
    if (u.origin === 'http://mlib.cppu.edu.cn') return origin + prefix + u.pathname + u.search + u.hash;
    if (u.origin !== origin) return raw;
    if (u.pathname.startsWith(prefix + '/')) return u.href;
    // Only library endpoints, not the VPN's own authentication/control resources.
    if (/^\/(?:readingroom\/|readingRoom\/|isLogin(?:$|\/)|login(?:$|\/)|selectreadingroom(?:$|\/)|(?:multi)?readingroomtablelist(?:$|\/)|checkIsBesk(?:$|\/))/.test(u.pathname))
      return origin + prefix + u.pathname + u.search + u.hash;
    return raw;
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
    const raw = form.getAttribute('action');
    const mapped = map(raw);
    // Reverse proxies may virtualize form.action to expose the original library
    // URL even after the DOM attribute has been rewritten. Comparing that getter
    // to the proxy URL causes our observer to write the same attribute forever,
    // starving timers and the school's date dialog. Compare the actual attribute.
    if (new URL(raw, location.href).href !== new URL(mapped, location.href).href)
      form.setAttribute('action', mapped);
  });
  forms();
  const observation = {childList:true, subtree:true, attributes:true, attributeFilter:['action']};
  const observer = new MutationObserver(() => {
    // Some proxies virtualize attribute getters too. Do not observe our own
    // writes even in that case; otherwise the observer can starve every timer.
    observer.disconnect();
    try { forms(); } finally {
      const root = window.document?.documentElement;
      if (root) observer.observe(root, observation);
    }
  });
  observer.observe(document.documentElement, observation);
})();
