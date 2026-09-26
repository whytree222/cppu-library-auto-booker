(() => {
  if (window.__libraryAutoBookerRunning) return;
  window.__libraryAutoBookerRunning = true;
  const config = __BOOKING_CONFIG__;
  const report = (state, detail) => window.AutoBooker?.report(state, detail);
  const seats = config.seatChoices || [];

  const setField = (pattern, value) => {
    const field = [...document.querySelectorAll('input,select')].find(el =>
      pattern.test(`${el.name} ${el.id} ${el.placeholder} ${el.getAttribute('aria-label')}`)
    );
    if (!field) return;
    if (field.tagName === 'SELECT') {
      const option = [...field.options].find(item => item.value === value || item.textContent.trim() === value);
      if (!option) return;
      if (field.value === option.value) return;
      field.value = option.value;
    } else {
      if (field.value === value) return;
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

  if (location.pathname === '/login' || document.querySelector('form#fromuser input#passwd')) {
    report('error', '登录已失效，请先在应用内重新登录');
    return;
  }
  if (!seats.length) {
    report('error', '尚未预选座位');
    return;
  }

  const parseRgb = value => {
    const match = /rgba?\((\d+)[,\s]+(\d+)[,\s]+(\d+)/i.exec(value || '');
    return match ? [Number(match[1]), Number(match[2]), Number(match[3])] : null;
  };
  const green = rgb => rgb && rgb[1] > rgb[0] * 1.12 && rgb[1] > rgb[2] * 1.08 && rgb[1] > 85;
  const isAvailable = (element, choice) => {
    const bounds = element.getBoundingClientRect();
    if (!bounds.width || !bounds.height || bounds.width > 160 || bounds.height > 160) return false;
    const sample = [element, ...element.querySelectorAll('*')].slice(0, 30);
    if (sample.some(item => {
      const style = getComputedStyle(item);
      return [style.color, style.backgroundColor, style.fill, style.stroke, style.borderColor]
        .some(color => green(parseRgb(color)));
    })) return true;

    if (element instanceof HTMLCanvasElement) {
      try {
        const ctx = element.getContext('2d');
        const x = Math.round((choice.x * innerWidth - bounds.left) * element.width / bounds.width);
        const y = Math.round((choice.y * innerHeight - bounds.top) * element.height / bounds.height);
        const pixel = ctx.getImageData(x, y, 1, 1).data;
        return green([pixel[0], pixel[1], pixel[2]]);
      } catch (_) { return false; }
    }
    return false;
  };
  const locate = choice => {
    let element = null;
    try { element = document.querySelector(choice.selector); } catch (_) { }
    if (!element) element = document.elementFromPoint(choice.x * innerWidth, choice.y * innerHeight);
    return element;
  };
  const visible = el => !!(el && el.getClientRects().length && getComputedStyle(el).visibility !== 'hidden');
  const text = el => (el.textContent || el.value || '').replace(/\s+/g, '');
  const successText = () => {
    const notice = [...document.querySelectorAll('.alertify-message,.alertify-log,[role="alert"],.layui-layer-content')]
      .filter(visible).map(text).join(' ');
    return /预约成功|预定成功|预约已成功/.test(notice) ? notice.slice(0, 80) : '';
  };
  const failureText = () => {
    const notice = [...document.querySelectorAll('.alertify-message,.alertify-log,[role="alert"],.layui-layer-content')]
      .filter(visible).map(text).join(' ');
    return /预约失败|已被预约|不可预约|预约已满|操作失败/.test(notice) ? notice.slice(0, 80) : '';
  };
  const modalButton = () => [...document.querySelectorAll(
    '#alertify button,#alertify a,.alertify-dialog button,.alertify-dialog a,[role="dialog"] button,.popup_wrap button'
  )].find(el => visible(el) && /^(确定|确认|预约|提交)$/.test(text(el)));

  let attempt = 0;
  const run = () => {
    attempt += 1;
    setDesiredPeriod();
    const choice = seats.find(item => {
      const element = locate(item);
      return element && isAvailable(element, item);
    });
    if (!choice) {
      if (attempt < 30) return setTimeout(run, 400);
      report('error', '候选位置未显示为绿色；请检查座位图或重新预选');
      return;
    }

    const seat = locate(choice);
    if (config.dryRun) {
      report('dry-run', `可预约候选：${choice.label}`);
      return;
    }

    let finished = false;
    const finish = (state, detail) => {
      if (finished) return;
      finished = true;
      report(state, detail);
    };
    const watcher = new MutationObserver(() => {
      const success = successText();
      if (success) return finish('success', success);
      const failure = failureText();
      if (failure) return finish('error', failure);
    });
    watcher.observe(document.body, { childList: true, subtree: true, characterData: true });

    seat.dispatchEvent(new MouseEvent('click', {
      bubbles: true, cancelable: true, view: window,
      clientX: choice.x * innerWidth,
      clientY: choice.y * innerHeight
    }));
    setTimeout(() => {
      if (finished) return;
      const confirm = modalButton();
      if (confirm) confirm.click();
    }, 500);
    setTimeout(() => {
      if (!finished) finish('submitted', `已尝试 ${choice.label}，请到学校系统核对结果`);
      watcher.disconnect();
    }, 9000);
  };
  run();
})();
