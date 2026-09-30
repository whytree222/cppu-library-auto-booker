const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { JSDOM } = require('jsdom');

const script = fs.readFileSync('app/src/main/assets/automation.js', 'utf8');
const seatMap = fs.readFileSync('tests/fixtures/seat-map.html', 'utf8');
const seatCatalog = Array.from({ length: 23 }, (_, i) => ['A', 'B', 'C', 'D']
  .map(letter => `G${String(i + 1).padStart(3, '0')}${letter}`)).flat().concat(['YXS1', 'YXS2']);

// Reproduce the supplied layoutBespeak/commitbesk boundary: clicking 预约
// only opens Alertify; the simulated request happens exclusively on 确认.
function fixture(options = {}) {
  const dom = new JSDOM(`<!doctype html><body>
    <input id="roomno" value="26"><input id="isuseday" value="1">
    <input id="begintime" value="08:10:01"><input id="endtime" value="10:00:00">
    <input id="times"><input id="tableNo"><ul id="selected-seats"></ul>
    ${options.realMap ? seatMap : `<div id="seat-map">
    <div id="G015A" class="seatCharts-seat available" aria-checked="false">G015A</div>
    <div id="G016A" class="seatCharts-seat available" aria-checked="false">G016A</div></div>`}
    <button id="book" onclick="layoutBespeak()">预约</button>
    <div id="confirmation" class="alertify ajs-hidden"><div class="ajs-dialog">
      <div class="ajs-content"></div><div class="ajs-footer">
      <button class="ui positive button">确认</button><button>取消</button></div></div></div>
    <div id="result" class="alertify ajs-hidden"><div class="ajs-dialog">
      <div class="ajs-content"></div><div class="ajs-footer"><button>确认</button></div></div></div>
    </body>`, { url: 'http://mlib.cppu.edu.cn/multireadingroomtablelist', runScripts: 'outside-only' });
  const w = dom.window;
  const d = w.document;
  const messages = [];
  let requests = 0;
  const submittedNumbers = [];
  let clicks = 0;
  let now = Date.now();
  const timers = new Map();
  let timerId = 0;
  w.Date.now = () => now;
  // Virtual time permits testing delayed modals and the full 45-second wait.
  w.setTimeout = (fn, delay = 0) => { timers.set(++timerId, { fn, at: now + delay }); return timerId; };
  w.setInterval = (fn, period) => { timers.set(++timerId, { fn, at: now + period, period }); return timerId; };
  w.clearTimeout = w.clearInterval = id => timers.delete(id);
  w.HTMLElement.prototype.getClientRects = function () {
    if (this.closest('.ajs-hidden,[hidden]')) return [];
    for (let el = this; el; el = el.parentElement) if (el.style.display === 'none') return [];
    return [{ width: 25, height: 25 }];
  };
  w.AutoBooker = { report: (state, detail) => messages.push({ state, detail }) };
  if (options.nativeBridge) w.AutoBooker.booked = (seat, detail) => messages.push({ state: 'success', detail, seat });
  for (const seat of d.querySelectorAll('#seat-map .seatCharts-seat[id]')) {
    if (options.realMap && !options.keepStatus) seat.className = 'seatCharts-seat available';
    const number = seat.id;
    seat.onclick = () => {
    const selected = seat.getAttribute('aria-checked') !== 'true';
    seat.className = `seatCharts-seat ${selected ? 'selected' : 'available'}`;
    seat.setAttribute('aria-checked', String(selected));
    d.getElementById('selected-seats').textContent = selected ? `座位${number}号座位` : '';
    d.getElementById('tableNo').value = options.emptyField || !selected ? '' : number;
    };
  }
  const reserveTomorrow = options.today ? false : true;
  d.getElementById('isuseday').value = reserveTomorrow ? '1' : '0';
  if (options.multi || options.lastThree || options.firstFour) {
    d.getElementById('begintime').value = '';
    d.getElementById('times').value = options.lastThree
      ? '16:31:30-18:00:30,18:01:30-19:29:30,19:31:00-22:01:00'
      : options.firstFour
        ? '08:10:01-10:00:59,10:01:10-11:29:00,11:31:00-14:29:00,14:31:00-16:30:30'
        : '08:10:01-10:00:59,10:01:10-11:29:00';
  }
  w.layoutBespeak = () => {
    clicks++;
    if (options.noConfirmation) return;
    const open = () => {
      const tomorrow = new Date(); tomorrow.setDate(tomorrow.getDate() + 1);
      const day = reserveTomorrow ? `${tomorrow.getFullYear()}-${tomorrow.getMonth() + 1}-${tomorrow.getDate()}` : '今日';
      const time = (options.multi || options.lastThree || options.firstFour) ? d.getElementById('times').value : '08:10:01-10:00:00';
      const content = d.querySelector('#confirmation .ajs-content');
      content.innerHTML = `确认要预约使用时间为：<span class="badge">${options.wrongTime ? '19:31:00-22:01:00' : time}</span><br>使用日期为：<span class="badge">${options.wrongDate ? '1999-1-1' : day}</span><br>座位号码：<span class="badge">${options.wrongSeat ? 'G016A' : d.getElementById('tableNo').value}</span><br>的预约记录吗?`;
      d.getElementById('confirmation').classList.remove('ajs-hidden');
    };
    w.setTimeout(open, options.delay || 0);
  };
  d.getElementById('book').onclick = w.layoutBespeak;
  d.querySelector('#confirmation button').onclick = () => {
    requests++;
    submittedNumbers.push(d.getElementById('tableNo').value);
    d.getElementById('confirmation').classList.add('ajs-hidden');
    if (options.noResponse) return;
    // The response text is inserted while hidden, then only the class changes.
    d.querySelector('#result .ajs-content').textContent = options.conflict && requests === 1 ? '座位已被预约' : options.result || '预约成功';
    w.setTimeout(() => d.getElementById('result').classList.remove('ajs-hidden'), 400);
  };
  d.querySelector('#result button').onclick = () => d.getElementById('result').classList.add('ajs-hidden');
  const config = { seatNumbers: options.conflict ? ['G015A', 'G016A'] : ['G015A'],
    selectedSlots: options.lastThree ? [4, 5, 6] : options.firstFour ? [0, 1, 2, 3] : options.multi ? [0, 1] : [0],
    reserveTomorrow, dryRun: !!options.dryRun, targetDate: options.targetDate, seatCatalog };
  if (options.numbers) config.seatNumbers = options.numbers;
  if (options.unavailable) d.getElementById('G015A').className = 'seatCharts-seat unavailable';
  if (options.duplicate) d.getElementById('G015A').after(d.getElementById('G015A').cloneNode(true));
  if (options.mismatchedLabel) d.getElementById('G015A').textContent = 'G015B';
  w.eval(script.replace('__BOOKING_CONFIG__', JSON.stringify(config)));
  async function advance(duration) {
    const target = now + duration;
    while (true) {
      const next = [...timers.entries()].filter(([, t]) => t.at <= target).sort((a, b) => a[1].at - b[1].at)[0];
      if (!next) break;
      const [id, timer] = next;
      now = timer.at;
      if (timer.period) timer.at += timer.period; else timers.delete(id);
      timer.fn();
      await Promise.resolve();
      await Promise.resolve();
    }
    now = target;
    await Promise.resolve();
  }
  return { messages, advance, requests: () => requests, submittedNumbers, clicks: () => clicks, close: () => w.close() };
}

for (const [name, options] of [
  ['tomorrow', {}], ['today', { today: true }], ['two time slots', { multi: true }],
  ['confirmation delayed beyond the old 500 ms check', { delay: 2000 }]
]) test(`confirm once and recognize success: ${name}`, async () => {
  const f = fixture(options);
  try {
    await f.advance(5000);
    assert.equal(f.requests(), 1);
    assert.equal(f.messages.at(-1).state, 'success');
    await f.advance(50000);
    assert.equal(f.requests(), 1);
  } finally { f.close(); }
});

for (const option of ['wrongSeat', 'wrongDate', 'wrongTime', 'emptyField', 'noConfirmation']) {
  test(`do not send request: ${option}`, async () => {
    const f = fixture({ [option]: true });
    try { await f.advance(12000); assert.equal(f.requests(), 0); assert.equal(f.messages.at(-1).state, 'error'); }
    finally { f.close(); }
  });
}

test('dry run never opens or confirms a booking', async () => {
  const f = fixture({ dryRun: true });
  try { await f.advance(5000); assert.equal(f.clicks(), 0); assert.equal(f.requests(), 0); assert.equal(f.messages.at(-1).state, 'dry-run'); }
  finally { f.close(); }
});

test('show server rejection without treating 未预约成功 as success', async () => {
  const f = fixture({ result: '未预约成功，请勿重复预约' });
  try { await f.advance(5000); assert.equal(f.requests(), 1); assert.equal(f.messages.at(-1).state, 'error'); assert.match(f.messages.at(-1).detail, /请勿重复预约/); }
  finally { f.close(); }
});

test('uncertain response does not cause a second submission', async () => {
  const f = fixture({ noResponse: true });
  try { await f.advance(50000); assert.equal(f.requests(), 1); assert.equal(f.messages.at(-1).state, 'error'); assert.match(f.messages.at(-1).detail, /45 秒/); }
  finally { f.close(); }
});

test('a definite seat conflict closes the result and confirms the next preference', async () => {
  const f = fixture({ conflict: true });
  try { await f.advance(10000); assert.equal(f.requests(), 2); assert.equal(f.messages.at(-1).state, 'success'); assert.match(f.messages.at(-1).detail, /G016A/); }
  finally { f.close(); }
});

for (const batch of ['firstFour', 'lastThree']) test(`native success carries exact seat for ${batch}`, async () => {
  const f = fixture({ [batch]: true, nativeBridge: true });
  try {
    await f.advance(5000);
    assert.equal(f.requests(), 1);
    const successes = f.messages.filter(m => m.state === 'success');
    assert.equal(successes.length, 1);
    assert.equal(successes[0].seat, 'G015A');
  } finally { f.close(); }
});

test('changed calendar date cannot silently move a booking', async () => {
  const f = fixture({ targetDate: '1999-01-01', nativeBridge: true });
  try { await f.advance(5000); assert.equal(f.requests(), 0); assert.equal(f.messages.at(-1).state, 'error'); }
  finally { f.close(); }
});

test('invalid seat format is reported before clicking', async () => {
  const f = fixture({ numbers: ['G15A'] });
  try { await f.advance(15000); assert.equal(f.clicks(), 0); assert.match(f.messages.at(-1).detail, /格式错误.*G15A/); }
  finally { f.close(); }
});

test('valid missing seat is not mislabeled as a format error', async () => {
  const f = fixture({ numbers: ['G023D'] });
  try { await f.advance(15000); assert.equal(f.clicks(), 0); assert.match(f.messages.at(-1).detail, /格式正确.*未找到/); }
  finally { f.close(); }
});

test('all 94 identifiers in supplied HTML select and confirm the exact requested seat', async t => {
  const source = new JSDOM(seatMap);
  const identifiers = [...source.window.document.querySelectorAll('.seatCharts-seat[id]')].map(seat => seat.id.trim());
  source.window.close();
  assert.equal(identifiers.length, 94);
  assert.deepEqual([...identifiers].sort(), [...seatCatalog].sort());
  for (const number of identifiers) await t.test(number, async () => {
    const f = fixture({ realMap: true, numbers: [number], nativeBridge: true });
    try {
      await f.advance(5000);
      assert.equal(f.requests(), 1);
      assert.deepEqual(f.submittedNumbers, [number]);
      assert.equal(f.messages.at(-1).state, 'success');
      assert.equal(f.messages.at(-1).seat, number);
    } finally { f.close(); }
  });
});

for (const [name, options, reason] of [
  ['duplicate identifier', { duplicate: true }, /编号重复/],
  ['mismatched label', { mismatchedLabel: true }, /显示文字不一致/],
  ['existing reservation', { realMap: true, keepStatus: true }, /本次已预约/],
  ['nonexistent catalog number', { numbers: ['G024A'] }, /没有这些座位号/]
]) test(`refuse ambiguous or unavailable seat: ${name}`, async () => {
  const f = fixture(options);
  try { await f.advance(15000); assert.equal(f.clicks(), 0); assert.match(f.messages.at(-1).detail, reason); }
  finally { f.close(); }
});

test('unavailable seat is distinct from unknown identifier', async () => {
  const f = fixture({ unavailable: true });
  try { await f.advance(15000); assert.equal(f.clicks(), 0); assert.match(f.messages.at(-1).detail, /网页标记为不可预约/); }
  finally { f.close(); }
});

for (const [message, reason] of [
  ['座位已被预约', /座位已被预约；学校提示/],
  ['请勿重复预约', /已有预约或重复预约限制/],
  ['预约失败，次数达到上限', /预约数量或次数限制/],
  ['预约失败，登录已失效', /登录状态失效/]
]) test(`classify server rejection: ${message}`, async () => {
  const f = fixture({ result: message });
  try { await f.advance(5000); assert.equal(f.requests(), 1); assert.match(f.messages.at(-1).detail, reason); }
  finally { f.close(); }
});

