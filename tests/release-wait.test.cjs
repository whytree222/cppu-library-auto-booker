const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { JSDOM } = require('jsdom');
const script = fs.readFileSync('app/src/main/assets/release-wait.js', 'utf8');

function fixture(options = {}) {
  const dom = new JSDOM('<div id="roomdata">过刊阅览室</div>', {
    url: 'http://mlib.cppu.edu.cn/selectreadingroom', runScripts: 'outside-only'
  });
  const w = dom.window;
  let now = Date.now(), timerId = 0, inFlight = 0, maxFlight = 0;
  const timers = new Map(), requests = [], reports = [];
  w.Date.now = () => now;
  w.setTimeout = (fn, delay = 0) => { timers.set(++timerId, {fn, at: now + delay}); return timerId; };
  w.AutoBooker = {report: (state, detail) => reports.push({state, detail})};
  w.jQuery = {ajax: args => {
    requests.push({url: args.url, data: args.data, at: now});
    inFlight++; maxFlight = Math.max(maxFlight, inFlight);
    w.setTimeout(() => {
      inFlight--;
      if (options.network) return args.error({status: options.network});
      if (args.url.includes('checktimecanbesk')) {
        if (options.malformed) return args.success({});
        if (options.denied) return args.success({ReturnValue: -1, Msg: options.denied});
        if (options.roomClosed && now - start < options.roomClosed) return args.success({ReturnValue: -1, Msg: '未到预约时间'});
        args.success({ReturnValue: 0, Msg: '认证通过', CanBaskID: '72'});
      } else {
        if (options.dateDenied) return args.success({flagSign: 'false', strReturn: options.dateDenied});
        args.success(now - start < (options.openAfter || 0)
          ? {flagSign: 'false', strReturn: '尚未开放预约'}
          : {flagSign: options.flag || 'true', strReturn: ''});
      }
    }, options.delay || 0);
  }};
  const start = now;
  const date = new w.Date(); date.setDate(date.getDate() + 1);
  const targetDate = `${date.getFullYear()}-${String(date.getMonth()+1).padStart(2,'0')}-${String(date.getDate()).padStart(2,'0')}`;
  w.eval(script.replace('__BOOKING_CONFIG__', JSON.stringify({targetDate, reserveTomorrow: true}))
    .replace('__WAIT_MS__', String(options.duration ?? 600000)));
  function advance(ms) {
    const end = now + ms;
    let count = 0;
    while (true) {
      const next = [...timers].filter(([,t]) => t.at <= end).sort((a,b) => a[1].at - b[1].at)[0];
      if (!next) break;
      assert.ok(++count < 5000, 'bounded timers');
      const [id, t] = next; timers.delete(id); now = t.at; t.fn();
    }
    now = end;
  }
  return {dom, reports, requests, advance, maxFlight: () => maxFlight};
}

test('closed date is checked each second and opens once without submitting a reservation', () => {
  const f = fixture({openAfter: 3000}); f.advance(10000);
  assert.equal(f.reports.filter(r => r.state === 'opened').length, 1);
  assert.equal(f.requests.filter(r => r.url.includes('checktimecanbesk')).length, 4);
  assert.ok(f.requests.every(r => /checktimecanbesk|getYY/.test(r.url)));
  assert.ok(f.requests.filter(r => r.url.includes('getYY')).every(r => r.data.roomId === 26));
  f.dom.window.close();
});

test('ten minute deadline is not extended by repeated closed responses', () => {
  const f = fixture({roomClosed: 900000}); f.advance(700000);
  assert.equal(f.requests.length, 600);
  assert.ok(f.reports.some(r => r.state === 'error' && r.detail.includes('10 分钟')));
  assert.equal(f.reports.some(r => r.state === 'opened'), false); f.dom.window.close();
});

for (const denied of ['登录已失效', '已超过预约次数上限', '没有共同的空闲座位', '座位号格式错误', '登录失效，尚未开放预约'])
  test(`no repeated checks for non-opening refusal: ${denied}`, () => {
    const f = fixture({denied}); f.advance(60000);
    assert.equal(f.requests.length, 1); assert.equal(f.reports.at(-1).state, 'error'); f.dom.window.close();
  });

for (const options of [{network: 429}, {network: 500}, {malformed: true},
  {dateDenied: '已经预约了该时间段的座位'}, {flag: 'not true'}])
  test(`network and unknown results stop rather than poll: ${JSON.stringify(options)}`, () => {
    const f = fixture(options); f.advance(20000);
    assert.ok(f.requests.length <= 2); assert.equal(f.reports.at(-1).state, 'error'); f.dom.window.close();
  });

test('slow responses are serialized, not overlapping', () => {
  const f = fixture({delay: 2000, openAfter: 6000}); f.advance(20000);
  assert.equal(f.maxFlight(), 1); assert.equal(f.reports.at(-1).state, 'opened'); f.dom.window.close();
});

test('remaining deadline is honored when re-entering wait after definite refusal', () => {
  const f = fixture({roomClosed: 60000, duration: 2000}); f.advance(20000);
  assert.equal(f.requests.length, 2); assert.equal(f.reports.at(-1).state, 'error'); f.dom.window.close();
});

