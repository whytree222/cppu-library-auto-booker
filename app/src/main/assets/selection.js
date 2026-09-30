(() => {
  if (window.__librarySelectionRunning) return;
  window.__librarySelectionRunning = true;
  const config = __BOOKING_CONFIG__;
  const report = (state, detail) => window.AutoBooker?.report(state, detail);
  const slots = [...new Set(config.selectedSlots || [])].sort((a, b) => a - b);
  if (!slots.length || slots.length > 4 || slots.some((value, index) => value !== slots[0] + index)) {
    report('error', '请选择 1–4 个连续使用时段');
    return;
  }
  const day = new Date();
  if (config.reserveTomorrow) day.setDate(day.getDate() + 1);
  const targetDate = config.targetDate || `${day.getFullYear()}-${day.getMonth() + 1}-${day.getDate()}`;
  const normalizeDate = value => {
    const match = /^(\d{4})-(\d{1,2})-(\d{1,2})$/.exec((value || '').trim());
    return match ? `${Number(match[1])}-${Number(match[2])}-${Number(match[3])}` : '';
  };
  const wantedDate = normalizeDate(targetDate);
  const calendarDate = offset => {
    const date = new Date();
    date.setDate(date.getDate() + offset);
    return normalizeDate(`${date.getFullYear()}-${date.getMonth() + 1}-${date.getDate()}`);
  };
  const visible = element => {
    for (let current = element; current; current = current.parentElement) {
      if (current.hidden || getComputedStyle(current).display === 'none' ||
          getComputedStyle(current).visibility === 'hidden') return false;
    }
    return true;
  };
  const text = element => (element.textContent || element.value || '').replace(/\s+/g, '').trim();
  const documents = () => {
    const result = [];
    const visit = current => {
      if (!current || result.includes(current)) return;
      result.push(current);
      for (const frame of current.querySelectorAll('iframe')) {
        try { visit(frame.contentDocument); } catch (_) { }
      }
    };
    visit(document);
    return result;
  };
  const queryAll = selector => documents().flatMap(current => [...current.querySelectorAll(selector)]);
  const room = () => queryAll('#roomdata [onclick]').find(element =>
    /roomonclick\(['"]26['"]\)/.test(element.getAttribute('onclick') || '') &&
    text(element).includes('过刊阅览室'));
  const dateHandlers = () => queryAll('[onclick*="selectusedaybuttonclick"]');
  const dateFromHandler = element => {
    const handler = element.getAttribute('onclick') || '';
    const match = /selectusedaybuttonclick\(\s*['"]\d+['"]\s*,\s*['"]\d+['"]\s*,\s*['"]([^'"]+)['"]/.exec(handler);
    return match ? normalizeDate(match[1]) : '';
  };
  const dateCard = () => dateHandlers().find(element => visible(element) && dateFromHandler(element) === wantedDate);
  // Checkbox inputs are deliberately hidden; inspect their surrounding card instead.
  const boxes = () => queryAll('#selectdate input[type="checkbox"][name="url"]')
    .filter(box => visible(box.closest('.item-info') || box.parentElement));
  const slotDate = box => {
    const params = new URLSearchParams(box.value || '');
    const raw = (params.get('useday') || '').trim();
    const explicit = normalizeDate(raw);
    const offset = params.get('isuseday');
    const relative = offset === '0' || offset === '1' ? calendarDate(Number(offset)) : '';
    // Today may be encoded as a relative day rather than a full date. Never let
    // that fallback override an explicit date or accept an unknown date string.
    if (explicit) return !relative || relative === explicit ? explicit : '';
    const label = text(box.closest('.item-info') || box.parentElement);
    const labelMatch = /使用[日日期]*[:：](\d{4}-\d{1,2}-\d{1,2})/.exec(label);
    const labelDate = labelMatch ? normalizeDate(labelMatch[1]) : '';
    if (labelDate && relative && labelDate !== relative) return '';
    if (raw === '' || raw === offset || (offset === '0' && /^(今日|今天)$/.test(raw))) {
      return relative || labelDate;
    }
    return '';
  };
  const slotStarts = ['08:10', '10:01', '11:31', '14:31', '16:31', '18:01', '19:31'];

  let roomClicked = false;
  let dateClicked = false;
  let nextClicked = false;
  let stageAt = Date.now();
  let finished = false;
  const fail = detail => {
    if (finished) return;
    finished = true;
    report('error', detail);
  };
  const stage = (detail) => {
    stageAt = Date.now();
    report('progress', detail);
  };
  const tick = () => {
    if (finished || location.pathname === '/multireadingroomtablelist') return;
    if (Date.now() - stageAt > 20000) {
      const seenDates = [...new Set(dateHandlers().map(dateFromHandler).filter(Boolean))].join('、');
      const frameCount = queryAll('iframe').length;
      fail(nextClicked ? '时段已选，但未进入座位图；请检查学校网页'
        : dateClicked ? '已选日期，但未出现七个时段'
        : roomClicked ? seenDates
          ? `未找到目标日期 ${targetDate}；页面日期：${seenDates}；内嵌页：${frameCount}`
          : `已点击过刊阅览室，但日期选项未出现；目标日期：${targetDate}；内嵌页：${frameCount}`
        : '未找到过刊阅览室卡片');
      return;
    }
    if (nextClicked) return setTimeout(tick, 300);

    const choices = boxes();
    if (choices.length) {
      const mismatch = choices.find(box => {
        const params = new URLSearchParams(box.value || '');
        return params.get('roomno') !== '26' || slotDate(box) !== wantedDate;
      });
      if (mismatch) {
        const params = new URLSearchParams(mismatch.value || '');
        const safe = value => String(value || '空').replace(/[^\dA-Za-z\u4e00-\u9fff:./ -]/g, '').slice(0, 40);
        return fail(`时段不属于过刊阅览室或目标日期；目标 ${targetDate}，房间 ${safe(params.get('roomno'))}，使用日 ${safe(params.get('useday'))}，相对日 ${safe(params.get('isuseday'))}；已停止`);
      }
      const bySlot = new Map();
      for (const box of choices) {
        const begin = new URLSearchParams(box.value || '').get('begintime') || '';
        const index = slotStarts.findIndex(prefix => begin.startsWith(prefix));
        if (index < 0 || bySlot.has(index)) return fail('网页时段与预设七段不一致；已停止');
        bySlot.set(index, box);
      }
      if (slots.some(index => !bySlot.has(index) || bySlot.get(index).disabled)) {
        return fail('所选时段在目标日期不可预约；已停止');
      }
      choices.forEach(box => {
        const index = [...bySlot].find(([, value]) => value === box)?.[0];
        const shouldCheck = slots.includes(index);
        if (box.checked === shouldCheck) return;
        const label = [...box.ownerDocument.querySelectorAll('label')].find(item => item.htmlFor === box.id);
        (label || box).click();
      });
      if (choices.some(box => box.checked !== slots.includes([...bySlot].find(([, value]) => value === box)?.[0]))) {
        return fail('无法按所选顺序勾选使用时段；已停止');
      }
      const bookingForm = choices[0].closest('form[action*="multireadingroomtablelist"]');
      if (choices.some(box => box.closest('form') !== bookingForm)) return fail('出现多个时段页面；已停止');
      const next = bookingForm && [...bookingForm.querySelectorAll('button,input[type="submit"],a')]
        .find(element => /^下一步$/.test(text(element)));
      if (!next) return fail('未找到时段页面的“下一步”按钮');
      nextClicked = true;
      stage(`已选择 ${slots.map(index => index + 1).join('、')} 时段，正在进入座位图`);
      next.click();
      return setTimeout(tick, 300);
    }

    if (!roomClicked) {
      const target = room();
      if (!target) return setTimeout(tick, 300);
      roomClicked = true;
      stage('已找到过刊阅览室，正在选择使用日');
      target.click();
      return setTimeout(tick, 300);
    }
    if (!dateClicked) {
      const target = dateCard();
      if (!target) return setTimeout(tick, 300);
      dateClicked = true;
      stage(`正在选择使用日 ${targetDate}`);
      target.click();
      return setTimeout(tick, 300);
    }
    setTimeout(tick, 300);
  };
  tick();
})();
