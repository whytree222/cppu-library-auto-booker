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
  const room = options.room;
  const roomId = room?.id || '26', roomName = room?.name || '过刊阅览室';
  const dom = new JSDOM(`<body><div id="roomdata"><div onclick="roomonclick('${roomId}')">${roomName}</div></div>
    <div id="dates" style="display:none"><div onclick="selectusedaybuttonclick('${offset}','0','${targetDate}')">${targetDate}</div></div>
    <div id="slots" style="display:none"><form action="/multireadingroomtablelist"><div id="selectdate"></div>
    <button type="button">下一步</button></form></div>
    <div id="selecusetime" style="display:none"><input id="begintime"><input id="endtime">
      <button onclick="selectedtimes();">下一步</button></div></body>`,
    { url: 'http://mlib.cppu.edu.cn/selectreadingroom', runScripts: 'outside-only' });
  const w = dom.window;
  const d = w.document;
  const messages = [];
  const timers = [];
  let freeValues;
  let next = 0;
  w.setTimeout = fn => { timers.push(fn); return timers.length; };
  w.AutoBooker = { report: (state, detail) => messages.push({ state, detail }) };
  if (options.transport) w.eval(fs.readFileSync('app/src/main/assets/transport.js', 'utf8').replace('__TRANSPORT_CONFIG__', JSON.stringify({
    origin:'http://mlib.cppu.edu.cn', prefix:''
  })));
  d.querySelector('#roomdata div').onclick = () => { d.getElementById('dates').style.display = ''; };
  d.querySelector('#dates div').onclick = () => {
    d.getElementById('dates').style.display = 'none';
    w.roominfor = { ControlNo: options.wrongRoom ? '99' : roomId,
      ReadingRoomName: options.wrongName ? '另一个房间' : roomName, timefree: room?.timeMode === 'free' ? 1 : 0 };
    w.usedayid = options.wrongOffset ? 1 - offset : offset;
    if (room?.timeMode === 'free') {
      d.getElementById('selecusetime').style.display = '';
      return;
    }
    d.getElementById('slots').style.display = '';
    const starts = room?.slots.map(slot => slot.begin) || ['08:10:01', '10:01:00', '11:31:00', '14:31:00', '16:31:30', '18:01:30', '19:31:00'];
    const ends = room?.slots.map(slot => slot.end) || ['10:00:00', '11:29:00', '14:29:00', '16:30:30', '18:00:30', '19:29:30', '22:01:00'];
    for (let index = options.omitPastSlots || 0; index < starts.length; index++) {
      const params = new URLSearchParams({ roomno: options.wrongRoom ? '99' : roomId,
        isuseday: String(options.wrongOffset ? 1 - offset : offset), begintime: starts[index],
        endtime: options.wrongEnd ? '23:55:00' : ends[index], beskid: String(index + 1), beskCanId: '72', sign: `fresh-${index}` });
      if (options.missingSignature) params.delete('sign');
      if (options.duplicateRoomParameter) params.append('roomno', roomId);
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
  d.querySelector('#selecusetime button').onclick = () => {
    freeValues = [d.getElementById('begintime').value, d.getElementById('endtime').value];
    next++;
  };
  if (options.foreignAction) d.querySelector('#slots form').action = 'https://example.org/multireadingroomtablelist';
  if (options.duplicateFreeButton) d.querySelector('#selecusetime').append(d.querySelector('#selecusetime button').cloneNode(true));
  w.eval(script.replace('__BOOKING_CONFIG__', JSON.stringify({ targetDate,
    reserveTomorrow: !!options.tomorrow, selectedSlots: options.selectedSlots || [4, 5, 6],
    waitForRelease: !!options.wait, room, startTime: options.startTime, endTime: options.endTime })));
  for (let count = 0; count < 8 && timers.length && !next && !messages.some(m => m.state === 'error'); count++) timers.shift()();
  const selected = [...d.querySelectorAll('#slots input:checked')].map(input => input.id);
  w.close();
  return { messages, selected, next, freeValues };
}

for (const [name, options] of [
  ['today full date', {}], ['today relative zero', { rawDate: '0' }],
  ['today missing date', { rawDate: null }], ['today label', { rawDate: '今日' }],
  ['tomorrow full date', { tomorrow: true }], ['hidden previous tomorrow options', { stale: true }],
  ['campus transport path and form action', { transport: true, tomorrow: true }]
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

test('actual school 未到可预约时间 notice enters waiting only when opted in', () => {
  const schoolNotice = '当前时间为:00:55:01 未到可预约时间!';
  const waiting = run({schoolNotice, wait: true});
  assert.equal(waiting.next, 0);
  assert.equal(waiting.messages.at(-1).state, 'closed');
  const disabled = run({schoolNotice});
  assert.equal(disabled.messages.at(-1).state, 'error');
  const mixed = run({schoolNotice: '登录失效，' + schoolNotice, wait: true});
  assert.equal(mixed.messages.at(-1).state, 'error');
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

const otherRoom = { id: '34', name: '文科样本区', groupId: '4', groupName: '四楼', timeMode: 'fixed', slots: [
  { begin: '07:30:00', end: '09:45:00' }, { begin: '10:00:00', end: '12:00:00' },
  { begin: '13:00:00', end: '17:00:00' }, { begin: '18:00:00', end: '21:30:00' }
] };
test('fixed schedules belong to the chosen room and do not use seven legacy slots', () => {
  const f = run({ room: otherRoom, selectedSlots: [1, 2], omitPastSlots: 1, tomorrow: true, transport: true });
  assert.equal(f.next, 1, JSON.stringify(f.messages));
  assert.deepEqual(f.selected, ['slot1', 'slot2']);
});
for (const option of ['wrongRoom', 'wrongEnd', 'missingSignature', 'duplicateRoomParameter', 'foreignAction'])
  test(`other room refuses stale, unsigned or foreign selection: ${option}`, () => {
    const f = run({ room: otherRoom, selectedSlots: [1, 2], [option]: true });
    assert.equal(f.next, 0);
    assert.equal(f.messages.at(-1).state, 'error');
  });
const freeRoom = { ...otherRoom, id: '35', name: '研修阅览区', timeMode: 'free', slots: [],
  minMinutes: 30, maxMinutes: 180, stepMinutes: 15 };
for (const tomorrow of [true, false]) test(`free room uses original selectedtimes button with exact HH:mm: ${tomorrow}`, () => {
  const f = run({ room: freeRoom, selectedSlots: [], startTime: '09:15', endTime: '11:00', tomorrow, transport: true });
  assert.equal(f.next, 1, JSON.stringify(f.messages));
  assert.deepEqual(f.freeValues, ['09:15', '11:00']);
});
for (const options of [{ startTime: '09:16' }, { endTime: '13:00' }, { endTime: '09:30' },
  { startTime: '24:00' }, { wrongRoom: true }, { wrongName: true }, { wrongOffset: true }, { duplicateFreeButton: true }])
  test(`free room fails closed for mismatched identity or duration: ${JSON.stringify(options)}`, () => {
    const f = run({ room: freeRoom, startTime: '09:15', endTime: '11:00', ...options });
    assert.equal(f.next, 0);
    assert.equal(f.messages.at(-1).state, 'error');
  });
