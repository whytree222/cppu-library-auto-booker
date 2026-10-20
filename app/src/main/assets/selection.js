(() => {
  if (window.__librarySelectionRunning) return;
  window.__librarySelectionRunning = true;
  const config = __BOOKING_CONFIG__;
  const report = (state, detail) => window.AutoBooker?.report(state, detail);
  // Old persisted tasks keep their original room. New tasks carry an explicit,
  // discovered room profile; never guess another room's schedule or signatures.
  const targetRoom = config.room || { id: '26', name: '过刊阅览室', timeMode: 'fixed', slots: [
    { begin: '08:10:01', end: '10:00:00' }, { begin: '10:01:00', end: '11:29:00' },
    { begin: '11:31:00', end: '14:29:00' }, { begin: '14:31:00', end: '16:30:30' },
    { begin: '16:31:30', end: '18:00:30' }, { begin: '18:01:30', end: '19:29:30' },
    { begin: '19:31:00', end: '22:01:00' }
  ] };
  const roomId = String(targetRoom.id || '');
  const roomName = String(targetRoom.name || '').replace(/\s+/g, '');
  const free = targetRoom.timeMode === 'free';
  const clock = value => /^(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d$/.test(String(value || ''));
  const expectedSlots = Array.isArray(targetRoom.slots) ? targetRoom.slots : [];
  if (!/^\d+$/.test(roomId) || !roomName || !['fixed', 'free'].includes(targetRoom.timeMode) ||
      (!free && (!expectedSlots.length || expectedSlots.some(slot => !clock(slot.begin) || !clock(slot.end) || slot.begin >= slot.end)))) {
    report('error', '阅览室配置或时段目录无效，请重新同步学校阅览室');
    return;
  }
  const slots = [...new Set(config.selectedSlots || [])].sort((a, b) => a - b);
  if (!free && (!slots.length || slots.length > 4 || slots.some((value, index) =>
      !Number.isInteger(value) || value < 0 || value >= expectedSlots.length || value !== slots[0] + index))) {
    report('error', '请选择 1–4 个连续使用时段');
    return;
  }
  const minutes = value => /^(?:[01]\d|2[0-3]):[0-5]\d$/.test(String(value || ''))
    ? Number(value.slice(0, 2)) * 60 + Number(value.slice(3, 5)) : NaN;
  const beginMinutes = minutes(config.startTime), endMinutes = minutes(config.endTime);
  const length = endMinutes - beginMinutes;
  const step = Number(targetRoom.stepMinutes || 0);
  if (free && (!Number.isFinite(length) || length <= 0 ||
      (targetRoom.minMinutes > 0 && length < targetRoom.minMinutes) ||
      (targetRoom.maxMinutes > 0 && length > targetRoom.maxMinutes) ||
      (step > 0 && (beginMinutes % step || endMinutes % step)))) {
    report('error', '自选起止时间无效，或不符合该阅览室的时长／步长要求');
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
  if (!wantedDate || wantedDate !== calendarDate(config.reserveTomorrow ? 1 : 0)) {
    report('error', '目标日期已不符合任务的今天／明天设置；未选择阅览室');
    return;
  }
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
  const roomMatches = () => queryAll('#roomdata [onclick]').filter(element => {
    const match = /^\s*roomonclick\(\s*['"](\d+)['"]\s*\)\s*;?\s*$/.exec(element.getAttribute('onclick') || '');
    return visible(element) && match?.[1] === roomId && text(element) === roomName;
  });
  const dateHandlers = () => queryAll('[onclick*="selectusedaybuttonclick"]');
  const dateFromHandler = element => {
    const handler = element.getAttribute('onclick') || '';
    const match = /selectusedaybuttonclick\(\s*['"]\d+['"]\s*,\s*['"]\d+['"]\s*,\s*['"]([^'"]+)['"]/.exec(handler);
    return match ? normalizeDate(match[1]) : '';
  };
  const dateCards = () => dateHandlers().filter(element => visible(element) && dateFromHandler(element) === wantedDate);
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
    if (finished || ['/multireadingroomtablelist', '/readingroomtablelist'].includes(window.LibraryTransport?.path() || location.pathname)) return;
    if (roomClicked) {
      const notice = queryAll('.alertify:not(.ajs-hidden) .ajs-content,.alertify-message,[role="alert"]')
        .filter(visible).map(text).join(' ');
      if (notice && config.waitForRelease && !/登录|登陆|会话|密码|验证码|重复|已有预约|已经预约|次数|上限|空闲座位|预约成功/.test(notice) &&
          /未到(?:可预约|预约|开放)时间|尚未开放|暂未开放|未开放预约|还未开放|未开始预约|预约尚未开始|尚未到.*预约时间/.test(notice)) {
        finished = true;
        report('closed', '学校明确提示尚未开放预约');
        return;
      }
      if (notice) return fail(`学校未允许继续预约：${notice.slice(0, 200)}；未提交座位预约`);
    }
    if (Date.now() - stageAt > 20000) {
      const seenDates = [...new Set(dateHandlers().map(dateFromHandler).filter(Boolean))].join('、');
      const frameCount = queryAll('iframe').length;
      fail(nextClicked ? '时段已选，但未进入座位图；请检查学校网页'
        : dateClicked ? `已选日期，但未出现${free ? '自选时间表单' : '该阅览室的使用时段'}`
        : roomClicked ? seenDates
          ? `未找到目标日期 ${targetDate}；页面日期：${seenDates}；内嵌页：${frameCount}`
          : `已点击${targetRoom.name}，但日期选项未出现；目标日期：${targetDate}；内嵌页：${frameCount}`
        : `未找到${targetRoom.name}卡片`);
      return;
    }
    if (nextClicked) return setTimeout(tick, 300);

    if (free && roomClicked && dateClicked) {
      const forms = queryAll('#selecusetime').filter(visible);
      if (forms.length > 1) return fail('出现多个自选时间页面；已停止');
      if (!forms.length) return setTimeout(tick, 300);
      const form = forms[0], owner = form.ownerDocument.defaultView;
      const information = owner.roominfor;
      if (!information || String(information.ControlNo ?? information.controlNo) !== roomId ||
          String(information.ReadingRoomName ?? information.readingRoomName ?? '').replace(/\s+/g, '') !== roomName ||
          Number(information.timefree) !== 1 || String(owner.usedayid) !== String(wantedDate === calendarDate(0) ? 0 : 1)) {
        return fail('自选时间表单的阅览室或目标日期不符；已停止');
      }
      const begins = [...form.querySelectorAll('#begintime')].filter(visible);
      const ends = [...form.querySelectorAll('#endtime')].filter(visible);
      const next = [...form.querySelectorAll('[onclick]')].filter(element => visible(element) && !element.disabled &&
        /^\s*(?:return\s+)?selectedtimes\(\s*\)\s*;?\s*$/.test(element.getAttribute('onclick') || ''));
      if (begins.length !== 1 || ends.length !== 1 || next.length !== 1)
        return fail('不能唯一识别学校原有自选时间输入框和下一步按钮；已停止');
      for (const [field, value] of [[begins[0], config.startTime], [ends[0], config.endTime]]) {
        if (field.disabled || field.readOnly) return fail('学校自选时间输入框不可编辑；已停止');
        field.value = value;
        field.dispatchEvent(new owner.Event('input', { bubbles: true }));
        field.dispatchEvent(new owner.Event('change', { bubbles: true }));
        if (field.value !== value) return fail('学校未接受所填写的起止时间；已停止');
      }
      nextClicked = true;
      stage(`已选择 ${config.startTime}–${config.endTime}，正在进入${targetRoom.name}座位图`);
      // Let selectedtimes() use the school's live room/date state and URL rules.
      next[0].click();
      return setTimeout(tick, 300);
    }

    const choices = boxes();
    if (!free && choices.length && roomClicked && dateClicked) {
      const mismatch = choices.find(box => {
        const params = new URLSearchParams(box.value || '');
        return ['roomno', 'isuseday', 'begintime', 'endtime', 'beskid', 'beskCanId', 'sign'].some(key => params.getAll(key).length !== 1) ||
          params.getAll('useday').length > 1 ||
          !params.get('beskid') || !params.get('beskCanId') || !params.get('sign') ||
          params.get('roomno') !== roomId || slotDate(box) !== wantedDate;
      });
      if (mismatch) {
        const params = new URLSearchParams(mismatch.value || '');
        const safe = value => String(value || '空').replace(/[^\dA-Za-z\u4e00-\u9fff:./ -]/g, '').slice(0, 40);
        return fail(`时段不属于${targetRoom.name}或目标日期，或签名字段不完整；目标 ${targetDate}，房间 ${safe(params.get('roomno'))}，使用日 ${safe(params.get('useday'))}，相对日 ${safe(params.get('isuseday'))}；已停止`);
      }
      const bySlot = new Map();
      for (const box of choices) {
        const params = new URLSearchParams(box.value || '');
        const index = expectedSlots.findIndex(slot => slot.begin === params.get('begintime') && slot.end === params.get('endtime'));
        if (index < 0 || bySlot.has(index)) return fail('网页时段与所选阅览室的时段目录不一致，请重新同步；已停止');
        bySlot.set(index, box);
      }
      if (slots.some(index => !bySlot.has(index) || bySlot.get(index).disabled)) {
        return fail('所选时段在目标日期不可预约；已停止');
      }
      choices.forEach(box => {
        if (finished) return;
        const index = [...bySlot].find(([, value]) => value === box)?.[0];
        const shouldCheck = slots.includes(index);
        if (box.checked === shouldCheck) return;
        const labels = [...(box.closest('.item-info') || box.parentElement).querySelectorAll('label')]
          .filter(item => visible(item) && item.htmlFor === box.id);
        // A hidden stale input with the same ID could receive a label's click.
        // Fail closed rather than selecting a different live checkbox by guess.
        if (!box.id || [...box.ownerDocument.querySelectorAll('[id]')].filter(element => element.id === box.id).length !== 1 || labels.length !== 1)
          return fail('时段输入框或标签不能唯一对应；已停止');
        labels[0].click();
      });
      if (finished) return;
      if (choices.some(box => box.checked !== slots.includes([...bySlot].find(([, value]) => value === box)?.[0]))) {
        return fail('无法按所选顺序勾选使用时段；已停止');
      }
      const bookingForm = choices[0].closest('form');
      if (choices.some(box => box.closest('form') !== bookingForm)) return fail('出现多个时段页面；已停止');
      let action;
      try { action = new URL(window.LibraryTransport?.url(bookingForm?.getAttribute('action') || '') ||
        bookingForm?.getAttribute('action') || '', location.href); } catch (_) { }
      const expectedPath = location.pathname.replace(/\/selectreadingroom$/, '/multireadingroomtablelist');
      if (!bookingForm || action?.origin !== location.origin || action.username || action.password || action.pathname !== expectedPath)
        return fail('时段表单目标不是学校原有座位图；已停止');
      const next = bookingForm && [...bookingForm.querySelectorAll('button,input[type="submit"],a')]
        .filter(element => visible(element) && !element.disabled && /^下一步$/.test(text(element)));
      if (next.length !== 1) return fail('不能唯一识别时段页面的“下一步”按钮');
      nextClicked = true;
      stage(`已选择 ${slots.map(index => index + 1).join('、')} 时段，正在进入座位图`);
      next[0].click();
      return setTimeout(tick, 300);
    }

    if (!roomClicked) {
      const matches = roomMatches();
      if (matches.length > 1) return fail('阅览室卡片重复，不能唯一识别；已停止');
      const target = matches[0];
      if (!target) return setTimeout(tick, 300);
      roomClicked = true;
      stage(`已找到${targetRoom.name}，正在选择使用日`);
      target.click();
      return setTimeout(tick, 300);
    }
    if (!dateClicked) {
      const matches = dateCards();
      if (matches.length > 1) return fail('目标日期卡片重复，不能唯一识别；已停止');
      const target = matches[0];
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
