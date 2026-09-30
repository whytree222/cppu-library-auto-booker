const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { JSDOM } = require('jsdom');

const script = fs.readFileSync('app/src/main/assets/selection.js', 'utf8');
const dateString = offset => {
  const date = new Date(); date.setDate(date.getDate() + offset);
  return `${date.getFullYear()}-${date.getMonth() + 1}-${date.getDate()}`;
};

function run(options = {}) {
  const offset = options.tomorrow ? 1 : 0;
  const targetDate = dateString(offset);
  const dom = new JSDOM(`<body><div id="roomdata"><div onclick="roomonclick('26')">过刊阅览室</div></div>
    <div id="dates" style="display:none"><div onclick="selectusedaybuttonclick('${offset}','0','${targetDate}')">${targetDate}</div></div>
    <div id="slots" style="display:none"><form action="/multireadingroomtablelist"><div id="selectdate"></div>
    <button type="button">下一步</button></form></div></body>`,
    { url: 'http://mlib.cppu.edu.cn/selectreadingroom', runScripts: 'outside-only' });
  const w = dom.window;
  const d = w.document;
  const messages = [];
  const timers = [];
  let next = 0;
  w.setTimeout = fn => { timers.push(fn); return timers.length; };
  w.AutoBooker = { report: (state, detail) => messages.push({ state, detail }) };
  d.querySelector('#roomdata div').onclick = () => { d.getElementById('dates').style.display = ''; };
  d.querySelector('#dates div').onclick = () => {
    d.getElementById('dates').style.display = 'none';
    d.getElementById('slots').style.display = '';
    const starts = ['08:10:01', '10:01:00', '11:31:00', '14:31:00', '16:31:30', '18:01:30', '19:31:00'];
    for (let index = 0; index < 7; index++) {
      const params = new URLSearchParams({ roomno: options.wrongRoom ? '20' : '26',
        isuseday: String(options.wrongOffset ? 1 - offset : offset), begintime: starts[index] });
      if (options.rawDate !== null) params.set('useday', options.rawDate ?? targetDate);
      const card = d.createElement('div'); card.className = 'item-info';
      card.innerHTML = `<input style="display:none" type="checkbox" name="url" id="slot${index}">
        <label for="slot${index}">使用日:${options.labelDate ?? targetDate}</label>`;
      card.querySelector('input').value = params.toString();
      d.getElementById('selectdate').appendChild(card);
    }
  };
  if (options.stale) {
    const old = d.createElement('div'); old.style.display = 'none';
    old.innerHTML = `<form action="/multireadingroomtablelist"><div id="selectdate"><div class="item-info">
      <input type="checkbox" name="url" value="roomno=26&isuseday=1&useday=${dateString(1)}">
      </div></div><button type="button">下一步</button></form>`;
    d.body.prepend(old);
  }
  d.querySelector('#slots button').onclick = () => { next++; };
  w.eval(script.replace('__BOOKING_CONFIG__', JSON.stringify({ targetDate,
    reserveTomorrow: !!options.tomorrow, selectedSlots: [4, 5, 6] })));
  for (let count = 0; count < 8 && timers.length && !next && !messages.some(m => m.state === 'error'); count++) timers.shift()();
  const selected = [...d.querySelectorAll('#slots input:checked')].map(input => input.id);
  w.close();
  return { messages, selected, next };
}

for (const [name, options] of [
  ['today full date', {}], ['today relative zero', { rawDate: '0' }],
  ['today missing date', { rawDate: null }], ['today label', { rawDate: '今日' }],
  ['tomorrow full date', { tomorrow: true }], ['hidden previous tomorrow options', { stale: true }]
]) test(`select requested date and time slots: ${name}`, () => {
  const result = run(options);
  assert.equal(result.next, 1, JSON.stringify(result.messages));
  assert.deepEqual(result.selected, ['slot4', 'slot5', 'slot6']);
  assert.equal(result.messages.some(m => m.state === 'error'), false);
});

for (const [name, options] of [
  ['explicit wrong date', { rawDate: dateString(1) }],
  ['relative day disagrees with date', { wrongOffset: true }],
  ['wrong room', { wrongRoom: true }],
  ['unrecognized date', { rawDate: 'unknown' }],
  ['relative day disagrees with card', { rawDate: '0', labelDate: dateString(1) }]
]) test(`refuse incorrect selection: ${name}`, () => {
  const result = run(options);
  assert.equal(result.next, 0);
  assert.ok(result.messages.some(m => m.state === 'error'));
});
