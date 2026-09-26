(() => {
  if (window.__libraryAutoBookerRunning) return;
  window.__libraryAutoBookerRunning = true;
  const config = __BOOKING_CONFIG__;
  const report = (state, detail) => window.AutoBooker?.report(state, detail);
  const numbers = (config.seatNumbers || []).map(value => String(value).trim().toUpperCase()).filter(Boolean);
  const visible = el => !!(el && el.getClientRects().length && getComputedStyle(el).visibility !== 'hidden');
  const text = el => (el.textContent || el.value || '').replace(/\s+/g, '');
  if (location.pathname === '/login' || document.querySelector('form#fromuser input#passwd')) {
    report('error', '登录已失效，请先在应用内重新登录');
    return;
  }
  if (!numbers.length) {
    report('error', '尚未填写候选座位号');
    return;
  }

  const setField = (pattern, value) => {
    const field = [...document.querySelectorAll('input,select')].find(el =>
      pattern.test(`${el.name} ${el.id} ${el.placeholder} ${el.getAttribute('aria-label')}`));
    if (!field || field.value === value) return;
    if (field.tagName === 'SELECT') {
      const option = [...field.options].find(item => item.value === value || item.textContent.trim() === value);
      if (!option) return;
      field.value = option.value;
    } else {
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
      setter.call(field, value);
    }
    field.dispatchEvent(new Event('input', { bubbles: true }));
    field.dispatchEvent(new Event('change', { bubbles: true }));
  };
  const setDesiredPeriod = () => {
    const day = new Date();
    if (config.reserveTomorrow) day.setDate(day.getDate() + 1);
    const date = [day.getFullYear(), String(day.getMonth() + 1).padStart(2, '0'),
      String(day.getDate()).padStart(2, '0')].join('-');
    setField(/date|use.?day|日期/i, date);
    setField(/begin|start|开始/i, config.startTime);
    setField(/end|finish|结束/i, config.endTime);
  };

  const parseRgb = value => {
    const match = /rgba?\((\d+)[,\s]+(\d+)[,\s]+(\d+)/i.exec(value || '');
    return match ? [Number(match[1]), Number(match[2]), Number(match[3])] : null;
  };
  const green = rgb => rgb && rgb[1] > rgb[0] * 1.12 && rgb[1] > rgb[2] * 1.08 && rgb[1] > 85;
  const seatSized = el => {
    const box = el.getBoundingClientRect();
    return box.width > 3 && box.height > 3 && box.width <= 160 && box.height <= 160;
  };
  const isAvailable = el => [el, ...el.querySelectorAll('*')].slice(0, 30).some(item => {
    const style = getComputedStyle(item);
    return [style.color, style.backgroundColor, style.fill, style.stroke, style.borderColor]
      .some(color => green(parseRgb(color)));
  });
  const exactNumber = (value, number) => {
    if (!value) return false;
    const escaped = number.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    return new RegExp(`(^|[^A-Z0-9])${escaped}($|[^A-Z0-9])`, 'i').test(value);
  };
  const labels = el => {
    const attrs = ['data-seat-no', 'data-seat-number', 'data-table-no', 'data-number',
      'data-name', 'title', 'aria-label', 'alt', 'id', 'onclick'];
    const values = attrs.map(name => el.getAttribute(name)).filter(Boolean);
    const ownText = [...el.childNodes].filter(node => node.nodeType === Node.TEXT_NODE)
      .map(node => node.textContent.trim()).join(' ');
    if (ownText.length <= 40) values.push(ownText);
    return values;
  };
  const locate = number => {
    const matches = [...document.querySelectorAll('body *')].filter(el =>
      visible(el) && labels(el).some(value => exactNumber(value, number)));
    for (const match of matches) {
      let el = match;
      for (let depth = 0; depth < 3 && el && el !== document.body; depth++, el = el.parentElement) {
        if (seatSized(el) && isAvailable(el)) return el;
      }
    }
    return null;
  };
  const numberPresent = number => [...document.querySelectorAll('body *')].some(el =>
    labels(el).some(value => exactNumber(value, number)));
  const notices = () => [...document.querySelectorAll(
    '.alertify-message,.alertify-log,[role="alert"],.layui-layer-content')]
    .filter(visible).map(text).join(' ');
  const successText = () => /预约成功|预定成功|预约已成功/.test(notices()) ? notices().slice(0, 80) : '';
  const failureText = () => /预约失败|已被预约|不可预约|预约已满|操作失败/.test(notices()) ? notices().slice(0, 80) : '';
  const modalButton = () => [...document.querySelectorAll(
    '#alertify button,#alertify a,.alertify-dialog button,.alertify-dialog a,[role="dialog"] button,.popup_wrap button')]
    .find(el => visible(el) && /^(确定|确认|预约|提交)$/.test(text(el)));

  let finished = false;
  const attempted = new Set();
  let scanCount = 0;
  let active = false;
  const finish = (state, detail) => {
    if (finished) return;
    finished = true;
    report(state, detail);
  };
  const tryNext = () => {
    if (finished || active) return;
    setDesiredPeriod();
    const number = numbers.find(value => !attempted.has(value) && locate(value));
    if (!number) {
      if (++scanCount < 30) return setTimeout(tryNext, 400);
      finish('error', numbers.some(numberPresent)
        ? '候选座位均未显示为可预约，或尝试后均失败'
        : '网页中未找到输入的座位号；需要进一步适配页面，未点击任何座位');
      return;
    }
    const seat = locate(number);
    if (!seat) return setTimeout(tryNext, 100);
    if (config.dryRun) return finish('dry-run', `已识别可预约座位 ${number}；未提交`);
    attempted.add(number);
    active = true;
    const watcher = new MutationObserver(() => {
      if (finished || !active) return;
      const success = successText();
      if (success) return finish('success', `${number}：${success}`);
      if (failureText()) {
        watcher.disconnect();
        active = false;
        scanCount = 0;
        setTimeout(tryNext, 150);
      }
    });
    watcher.observe(document.body, { childList: true, subtree: true, characterData: true });
    seat.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
    setTimeout(() => {
      if (finished || !active) return;
      const confirm = modalButton();
      if (confirm) confirm.click();
    }, 500);
    setTimeout(() => {
      watcher.disconnect();
      if (!finished && active) finish('submitted', `已尝试 ${number}，未收到明确结果；请到学校系统核对`);
    }, 9000);
  };
  tryNext();
})();
