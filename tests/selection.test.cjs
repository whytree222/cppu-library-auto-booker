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
    { url: options.proxy ? 'https://webvpn.cppu.edu.cn/http/77726476706e69737468656265737421fdfb489e693378406b468ca88d1b203b/selectreadingroom'
      : 'http://mlib.cppu.edu.cn/selectreadingroom', runScripts: 'outside-only' });
  const w = dom.window;
  const d = w.document;
  const messages = [];
  const timers = [];
  let next = 0;
  w.setTimeout = fn => { timers.push(fn); return timers.length; };
  w.AutoBooker = { report: (state, detail) => messages.push({ state, detail }) };
  if (options.proxy) w.eval(fs.readFileSync('app/src/main/assets/transport.js', 'utf8').replace('__TRANSPORT_CONFIG__', JSON.stringify({
    origin:'https://webvpn.cppu.edu.cn', prefix:'/http/77726476706e69737468656265737421fdfb489e693378406b468ca88d1b203b'
  })));
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
  d.querySelector('#slots button').onclick = () => {
    if (options.schoolNotice) {
      const notice = d.createElement('div'); notice.className = 'alertify-message';
      notice.textContent = options.schoolNotice; d.body.appendChild(notice);
    } else next++;
  };
  w.eval(script.replace('__BOOKING_CONFIG__', JSON.stringify({ targetDate,
    reserveTomorrow: !!options.tomorrow, selectedSlots: [4, 5, 6], waitForRelease: !!options.wait })));
  for (let count = 0; count < 8 && timers.length && !next && !messages.some(m => m.state === 'error'); count++) timers.shift()();
  const selected = [...d.querySelectorAll('#slots input:checked')].map(input => input.id);
  w.close();
  return { messages, selected, next };
}

for (const [name, options] of [
  ['today full date', {}], ['today relative zero', { rawDate: '0' }],
  ['today missing date', { rawDate: null }], ['today label', { rawDate: '今日' }],
  ['tomorrow full date', { tomorrow: true }], ['hidden previous tomorrow options', { stale: true }],
  ['WebVPN proxy path and form action', { proxy: true, tomorrow: true }]
]) test(`select requested date and time slots: ${name}`, () => {
  const result = run(options);
  assert.equal(result.next, 1, JSON.stringify(result.messages));
  assert.deepEqual(result.selected, ['slot4', 'slot5', 'slot6']);
  assert.equal(result.messages.some(m => m.state === 'error'), false);
});

test('report real school reason when next step is refused, not a generic navigation timeout', () => {
  const result = run({ schoolNotice: '没有共同的空闲座位可以预约或已经预约了选择的时间段的座位' });
  assert.equal(result.next, 0);
  assert.ok(result.messages.some(m => m.state === 'error' && m.detail.includes('没有共同的空闲座位')));
});

test('explicit not-open notice can enter bounded wait but no-seat notice cannot', () => {
  assert.ok(run({ schoolNotice: '尚未开放预约', wait: true }).messages.some(m => m.state === 'closed'));
  assert.equal(run({ schoolNotice: '没有空闲座位可以预约', wait: true }).messages.some(m => m.state === 'closed'), false);
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

