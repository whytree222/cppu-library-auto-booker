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
  const invalid = numbers.filter(number => !/^G\d{3}[A-Z]$/.test(number));
  if (invalid.length) {
    report('error', `座位号格式错误：${invalid.join('、')}。过刊阅览室请按 G015A 格式输入（G + 三位数字 + 一个字母），每行一个`);
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
    '.alertify:not(.ajs-hidden) .ajs-content,.alertify-notifier .ajs-message,.alertify-message,.alertify-log,[role="alert"],.layui-layer-content,.modal.in .modal-body,.modal.show .modal-body,.modal[style*="display: block"] .modal-body')]
    .filter(visible).map(text).join(' ');
  const successText = () => /^(预约成功|预定成功|预约已成功)[！!。\.\s]*$/.test(notices()) ? notices().slice(0, 80) : '';
  const failureText = () => /预约失败|已被预约|座位已被占用|不可预约|预约已满|操作失败/.test(notices()) ? notices().slice(0, 80) : '';
  const serverReason = message => {
    const category = /已被预约|座位已被占用/.test(message) ? '座位已被预约'
      : /登录|登陆|会话/.test(message) ? '登录状态失效或需要重新登录'
      : /格式|座位.*不存在|座位号.*错误/.test(message) ? '学校系统拒绝座位号'
      : /重复预约|已有预约|已预约过/.test(message) ? '已有预约或重复预约限制'
      : /上限|次数|最多|限额|预约已满/.test(message) ? '预约数量或次数限制'
      : /未开放|未开始|放号|已结束|已过期|时间|时段/.test(message) ? '预约日期、时段或开放时间限制'
      : /未知错误|网络|超时/.test(message) ? '学校系统或网络异常'
      : '学校系统拒绝预约（未明确分类）';
    return `${category}；学校提示：${message.slice(0, 120)}`;
  };

  let finished = false;
  const attempted = new Set();
  const rejected = new Map();
  let scanCount = 0;
  let active = false;
  let attemptId = 0;
  const finish = (state, detail, seatNumber = '') => {
    if (finished) return;
    finished = true;
    if (state === 'success' && typeof window.AutoBooker?.booked === 'function') {
      window.AutoBooker.booked(seatNumber, detail);
    } else report(state, detail);
  };
  const tryNext = () => {
    if (finished || active) return;
    const number = numbers.find(value => !attempted.has(value) && locate(value));
    if (!number) {
      if (++scanCount < 30) return setTimeout(tryNext, 400);
      const reasons = numbers.map(value => {
        if (rejected.has(value)) return `${value}：${rejected.get(value)}`;
        if (numberPresent(value)) return `${value}：网页标记为不可预约（可能已预约或停用，页面未明确区分）`;
        return `${value}：格式正确，但当前过刊阅览室座位图未找到该编号，请核对号码或页面是否加载完成`;
      });
      finish('error', `没有可预约的候选座位。${reasons.join('；')}`);
      return;
    }
    const seat = locate(number);
    if (!seat) return setTimeout(tryNext, 100);
    if (config.dryRun) return finish('dry-run', `已识别可预约座位 ${number}；未提交`);
    attempted.add(number);
    active = true;
    const thisAttempt = ++attemptId;
    const previousNotice = notices();
    let submittedAt = 0;
    let confirmationAt = 0;
    let pollTimer;
    const inspect = () => {
      if (finished || !active || thisAttempt !== attemptId) return;
      // commitbesk only sends its AJAX request inside the confirmation's OK callback.
      const confirmation = [...document.querySelectorAll('.alertify:not(.ajs-hidden) .ajs-content')]
        .find(content => visible(content) && text(content).startsWith('确认要预约使用时间为：'));
      if (confirmation && submittedAt && !confirmationAt) {
        const field = id => String(document.getElementById(id)?.value || '').trim();
        const badges = [...confirmation.querySelectorAll('.badge')].map(text);
        const dayOffset = config.reserveTomorrow ? 1 : 0;
        const day = new Date();
        day.setDate(day.getDate() + dayOffset);
        const expectedDay = dayOffset === 0 ? '今日' : `${day.getFullYear()}-${day.getMonth() + 1}-${day.getDate()}`;
        const actualDate = `${day.getFullYear()}-${String(day.getMonth() + 1).padStart(2, '0')}-${String(day.getDate()).padStart(2, '0')}`;
        const expectedTime = (field('begintime') ? `${field('begintime')}-${field('endtime')}` : field('times')).replace(/\s+/g, '');
        const slotStarts = ['08:10', '10:01', '11:31', '14:31', '16:31', '18:01', '19:31'];
        const slotEnds = ['10:00', '11:29', '14:29', '16:30', '18:00', '19:29', '22:01'];
        const slots = [...new Set(config.selectedSlots || [])].sort((a, b) => a - b);
        const ranges = [...expectedTime.matchAll(/(\d{2}:\d{2})(?::\d{2})?[-~至](\d{2}:\d{2})(?::\d{2})?/g)];
        const validSlots = slots.length > 0 && slots.length <= 4 && slots.every((slot, i) =>
          Number.isInteger(slot) && slot >= 0 && slot < 7 && slot === slots[0] + i);
        const timeMatches = validSlots && (ranges.length === slots.length
          ? ranges.every((range, i) => range[1] === slotStarts[slots[i]] && range[2] === slotEnds[slots[i]])
          : ranges.length === 1 && ranges[0][1] === slotStarts[slots[0]] && ranges[0][2] === slotEnds[slots[slots.length - 1]]);
        if ((config.targetDate && config.targetDate !== actualDate) || field('roomno') !== '26' || field('isuseday') !== String(dayOffset) ||
            field('tableNo').toUpperCase() !== number || badges.length !== 3 ||
            badges[0] !== expectedTime || badges[1] !== expectedDay || badges[2].toUpperCase() !== number || !timeMatches) {
          return finish('error', '预约确认框中的阅览室、日期、时段或座位与任务不符；未点击确认');
        }
        const dialog = confirmation.closest('.ajs-dialog');
        const buttons = dialog ? [...dialog.querySelectorAll('.ajs-footer button')]
          .filter(button => visible(button) && !button.disabled && /^(确认|确定)$/.test(text(button))) : [];
        if (buttons.length !== 1) return finish('error', '已出现预约确认框，但无法唯一识别确认按钮；未提交');
        confirmationAt = Date.now();
        report('progress', `已核对 ${number} 的日期和时段，正在确认预约`);
        buttons[0].click();
        return;
      }
      const success = successText();
      if (confirmationAt && success) return finish('success', `${number}：${success}`, number);
      if (failureText() && notices() !== previousNotice) {
        const failure = notices();
        rejected.set(number, serverReason(failure));
        watcher.disconnect();
        clearInterval(pollTimer);
        // Only a definite seat conflict permits moving to the next preference.
        // A timeout or other error may follow a completed request: never resubmit it.
        if (confirmationAt && /已被预约|座位已被占用/.test(failure) && numbers.some(value => !attempted.has(value))) {
          const content = [...document.querySelectorAll('.alertify:not(.ajs-hidden) .ajs-content')]
            .find(element => visible(element) && /已被预约|座位已被占用/.test(text(element)));
          const acknowledgements = content ? [...content.closest('.ajs-dialog').querySelectorAll('.ajs-footer button')]
            .filter(button => visible(button) && !button.disabled && /^(确认|确定)$/.test(text(button))) : [];
          if (acknowledgements.length === 1) {
            active = false;
            acknowledgements[0].click();
            const retryAt = Date.now();
            const retry = () => {
              if (finished || thisAttempt !== attemptId) return;
              if (content.closest('.alertify')?.classList.contains('ajs-hidden') || !visible(content)) {
                const current = document.getElementById(number);
                if (current && (current.getAttribute('aria-checked') === 'true' || current.classList.contains('selected'))) current.click();
                if (String(document.getElementById('tableNo')?.value || '').trim()) {
                  return finish('error', `${number} 被预约后无法清除网页选座状态；已停止`);
                }
                scanCount = 0;
                report('progress', `${number} 已被预约，正在按顺序尝试下一候选座位`);
                return tryNext();
              }
              if (Date.now() - retryAt > 3000) return finish('error', '无法关闭座位冲突提示；已停止');
              setTimeout(retry, 200);
            };
            setTimeout(retry, 200);
            return;
          }
        }
        return finish('error', `${number}：${serverReason(failure)}`);
      }
      const result = notices();
      if (submittedAt && result && result !== previousNotice && !confirmation && !success) {
        return finish('error', `${number}：${serverReason(result)}`);
      }
      if (submittedAt && !confirmationAt && Date.now() - submittedAt > 10000) {
        return finish('error', `${number} 点击预约后未出现可识别的预约确认框；没有确认提交`);
      }
      if (confirmationAt && Date.now() - confirmationAt > 45000) {
        return finish('error', `${number} 已确认预约，但学校系统 45 秒内未返回结果；请核对预约记录`);
      }
    };
    const watcher = new MutationObserver(inspect);
    pollTimer = setInterval(() => {
      if (finished || !active || thisAttempt !== attemptId) {
        clearInterval(pollTimer);
        watcher.disconnect();
        return;
      }
      inspect();
    }, 200);
    watcher.observe(document.body, { childList: true, subtree: true, characterData: true,
      attributes: true, attributeFilter: ['class', 'style', 'hidden', 'aria-hidden'] });
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
      // layoutBespeak reads this field, not the seat icon or selected-seats list.
      const tableNo = document.querySelector('#tableNo');
      if (!tableNo || !String(tableNo.value || '').trim()) {
        watcher.disconnect();
        return finish('error', `${number} 已在座位图选中，但网页提交字段 tableNo 为空；没有发送预约`);
      }
      submittedAt = Date.now();
      report('progress', `正在打开 ${number} 的预约确认框`);
      submit.click();
    }, 800);
  };
  tryNext();
})();
