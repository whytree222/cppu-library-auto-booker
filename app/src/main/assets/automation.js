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

  const parseRgb = value => {
    const match = /rgba?\((\d+)[,\s]+(\d+)[,\s]+(\d+)/i.exec(value || '');
    return match ? [Number(match[1]), Number(match[2]), Number(match[3])] : null;
  };
  const green = rgb => rgb && rgb[1] > rgb[0] * 1.12 && rgb[1] > rgb[2] * 1.08 && rgb[1] > 85;
  const seatSized = el => {
    const box = el.getBoundingClientRect();
    return box.width > 3 && box.height > 3 && box.width <= 160 && box.height <= 160;
  };
  const isAvailable = el => {
    if (el.classList.contains('seatCharts-seat')) {
      return el.classList.contains('available') && !el.classList.contains('unavailable');
    }
    return [el, ...el.querySelectorAll('*')].slice(0, 30).some(item => {
    const style = getComputedStyle(item);
    return [style.color, style.backgroundColor, style.fill, style.stroke, style.borderColor]
      .some(color => green(parseRgb(color)));
    });
  };
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
    const exactSeat = document.getElementById(number);
    if (exactSeat?.classList.contains('seatCharts-seat')) {
      return isAvailable(exactSeat) ? exactSeat : null;
    }
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
  const numberPresent = number => !!document.getElementById(number) || [...document.querySelectorAll('body *')].some(el =>
    labels(el).some(value => exactNumber(value, number)));
  const notices = () => [...document.querySelectorAll(
    '.alertify-message,.alertify-log,[role="alert"],.layui-layer-content,.modal.in .modal-body,.modal.show .modal-body,.modal[style*="display: block"] .modal-body')]
    .filter(visible).map(text).join(' ');
  const successText = () => /预约成功|预定成功|预约已成功/.test(notices()) ? notices().slice(0, 80) : '';
  const failureText = () => /预约失败|已被预约|不可预约|预约已满|操作失败/.test(notices()) ? notices().slice(0, 80) : '';

  let finished = false;
  const attempted = new Set();
  let scanCount = 0;
  let active = false;
  let attemptId = 0;
  const finish = (state, detail) => {
    if (finished) return;
    finished = true;
    report(state, detail);
  };
  const tryNext = () => {
    if (finished || active) return;
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
    const thisAttempt = ++attemptId;
    const previousNotice = notices();
    const watcher = new MutationObserver(() => {
      if (finished || !active || thisAttempt !== attemptId) return;
      const success = successText();
      if (success) return finish('success', `${number}：${success}`);
      if (failureText() && notices() !== previousNotice) {
        watcher.disconnect();
        const current = document.getElementById(number);
        if (current && (current.getAttribute('aria-checked') === 'true' || current.classList.contains('selected'))) {
          current.click();
        }
        setTimeout(() => {
          if (finished || thisAttempt !== attemptId) return;
          if (current && (current.getAttribute('aria-checked') === 'true' || current.classList.contains('selected'))) {
            return finish('error', `${number} 预约失败，且无法安全取消选中；已停止`);
          }
          active = false;
          scanCount = 0;
          tryNext();
        }, 200);
      }
    });
    watcher.observe(document.body, { childList: true, subtree: true, characterData: true });
    seat.click();
    report('progress', `已选中 ${number}，正在核对网页选座状态`);
    setTimeout(() => {
      if (finished || !active || thisAttempt !== attemptId) return;
      const current = document.getElementById(number);
      if (!current || (current.getAttribute('aria-checked') !== 'true' && !current.classList.contains('selected'))) {
        watcher.disconnect();
        return finish('error', `${number} 未能在网页上选中；没有提交预约`);
      }
      const submit = [...document.querySelectorAll('button[onclick*="layoutBespeak"]')]
        .find(button => text(button) === '预约');
      if (!submit) {
        watcher.disconnect();
        return finish('error', '未找到座位图的预约按钮；没有提交');
      }
      const selectedList = document.querySelector('#selected-seats');
      if (selectedList && !text(selectedList).toUpperCase().includes(number)) {
        watcher.disconnect();
        return finish('error', `${number} 未进入网页的已选座位列表；没有提交`);
      }
      report('progress', `已点击 ${number} 的预约按钮，等待学校系统返回结果`);
      submit.click();
    }, 800);
    setTimeout(() => {
      watcher.disconnect();
      if (!finished && active && thisAttempt === attemptId) {
        const notice = notices();
        finish('error', notice
          ? `${number} 未确认预约成功；网页提示：${notice.slice(0, 80)}`
          : `${number} 点击预约后 45 秒未收到网页结果；不能视为预约成功`);
      }
    }, 45000);
  };
  tryNext();
})();
