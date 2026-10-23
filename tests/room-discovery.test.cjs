const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { JSDOM } = require('jsdom');
const script = fs.readFileSync('app/src/main/assets/room-discovery.js', 'utf8');
const prefix = '/http/77726476706e69737468656265737421fdfb489e693378406b468ca88d1b203b';
const rawRoom = (overrides = {}) => ({ ControlNo: 26, controlNo: 26, ReadingRoomName: '过刊阅览室', timefree: 0,
  mintimes: 10, maxtimes: 600, timestep: 0, begintime: '01:00:00', endtime: '23:00:00', ...overrides });
const timeRow = (overrides = {}) => ({ roomNo: 26, beginTime: '08:10:01', endTime: '10:00:00', isUseDay: 1,
  sign: 'never-export-this-signature', beskCanId: 72, ...overrides });

function fixture(options = {}) {
  const d = new JSDOM(options.html || '', { url: options.url || 'http://mlib.cppu.edu.cn/selectreadingroom?groupid=1', runScripts: 'outside-only' });
  const w = d.window, requests = [], timers = [];
  const info = { aborted: 0, overlap: 0, inFlight: 0, delays: [] };
  w.datalist = { Data: options.rooms || [rawRoom()], Msg: '获取成功', ReturnValue: 0 };
  w.grouplist = options.groups || [{ id: 1, groupname: '一楼', controlusers: 'never-export-users' }, { id: 2, groupname: '二楼' }];
  w.publishgroupid = options.groupId ?? 1;
  w.setTimeout = (callback, delay) => { info.delays.push(delay); const item = { callback, cancelled: false }; timers.push(item); return item; };
  w.clearTimeout = item => { if (item) item.cancelled = true; };
  w.jQuery = { ajax(config) {
    requests.push({ url: config.url, type: config.type, data: { ...config.data }, dataType: config.dataType });
    info.inFlight++;
    if (info.inFlight > 1) info.overlap++;
    const number = requests.length;
    const handle = { abort() { info.aborted++; if (info.inFlight) info.inFlight--; config.error({ status: 0 }, 'abort'); } };
    if (options.pending) { info.config = config; return handle; }
    queueMicrotask(() => {
      info.inFlight--;
      if (options.response) options.response(config, number);
      else config.success(config.url.endsWith('checktimecanbesk') ? { ReturnValue: 0, CanBaskID: '72', Msg: '认证通过' }
        : { ReturnValue: 0, Data: [timeRow(), timeRow({ isUseDay: 0 })], Msg: '获取成功' });
    });
    return handle;
  } };
  w.eval(script);
  return { w, api: w.LibraryRoomDiscovery, requests, info,
    async drain() {
      for (let round = 0; round < 1200; round++) {
        for (let n = 0; n < 8; n++) await Promise.resolve();
        const timer = timers.shift();
        if (timer && !timer.cancelled) timer.callback();
        if (!timer && !info.inFlight && this.api?.status().kind !== 'running') return this.api?.status();
      }
      throw Error('fixture did not settle');
    }, close() { this.api?.cancel(); w.close(); }
  };
}
async function use(options, run) { const f = fixture(options); try { await run(f); } finally { f.close(); } }

test('discovers actual floor identities and fixed rules without exporting source credentials/signatures', async () => use({}, async f => {
  const snapshot = f.api.snapshot();
  assert.equal(snapshot.kind, 'ready');
  assert.deepEqual(JSON.parse(JSON.stringify(snapshot.groups)), [{ id: '1', name: '一楼' }, { id: '2', name: '二楼' }]);
  assert.equal(snapshot.rooms[0].minMinutes, 10); assert.equal(snapshot.rooms[0].maxMinutes, 600);
  f.api.start(); const state = await f.drain();
  assert.equal(state.kind, 'done'); assert.equal(state.profiles.length, 1);
  assert.deepEqual(JSON.parse(JSON.stringify(state.profiles[0].slots)), [{ begin: '08:10:01', end: '10:00:00' }]);
  assert.equal(state.profiles[0].groupId, '1');
  assert.doesNotMatch(JSON.stringify([snapshot, state]), /sign|CanBask|controlusers|never-export|01:00:00|23:00:00/);
  assert.deepEqual(f.requests, [
    { url: '/readingroom/checktimecanbesk', type: 'POST', data: { id: '26' }, dataType: 'json' },
    { url: '/readingroom/getbesktimelist', type: 'POST', data: { roomid: '26', beskids: '72' }, dataType: 'json' }
  ]);
}));

test('rejects arbitrary origins, paths, wrong library proxy and SSO page before API installation', async () => {
  for (const url of ['https://attacker.example/selectreadingroom', 'http://mlib.cppu.edu.cn/login',
    'https://webvpn.cppu.edu.cn' + prefix + '/selectreadingroom', 'https://webvpn.cppu.edu.cn/http/other/selectreadingroom', 'https://webvpn.cppu.edu.cn/tpass/login',
    'http://user:secret@mlib.cppu.edu.cn/selectreadingroom', 'http://mlib.cppu.edu.cn/selectreadingroom#changed']) {
    await use({ url }, async f => { assert.equal(f.api, undefined); assert.equal(f.requests.length, 0); });
  }
});

test('reads variable free-time metadata, never inventing slots or interpreting release opening as usage range', async () => use({ rooms: [rawRoom({ ControlNo: 37, controlNo: 37, ReadingRoomName: '二层阅览室', timefree: 1, timestep: 15 })] }, async f => {
  f.api.start(); const state = await f.drain();
  assert.equal(state.profiles[0].id, '37'); assert.equal(state.profiles[0].timeMode, 'free');
  assert.equal(state.profiles[0].stepMinutes, 15); assert.equal(state.profiles[0].slots.length, 0);
  assert.equal(f.requests.length, 0); assert.doesNotMatch(JSON.stringify(state), /begintime|endtime|01:00:00|23:00:00/);
}));

test('normalizes valid school clock forms and orders one use day without mixing days', async () => use({ response(c) {
  c.success(c.url.endsWith('checktimecanbesk') ? { ReturnValue: 0, CanBaskID: 72 } : { ReturnValue: 0, Data: [
    timeRow({ beginTime: '10:01', endTime: '11:29' }), timeRow({ beginTime: '8:10:01', endTime: '10:00' }), timeRow({ isUseDay: 0, beginTime: '8:10:01', endTime: '10:00' })
  ] });
} }, async f => {
  f.api.start(); const state = await f.drain();
  assert.deepEqual(JSON.parse(JSON.stringify(state.profiles[0].slots)), [
    { begin: '08:10:01', end: '10:00:00' }, { begin: '10:01:00', end: '11:29:00' }
  ]);
}));

test('targeted refresh reads only the selected room and exact use day with a dated public snapshot', async () => {
  for (const useDay of [0, 1]) await use({
    rooms: [rawRoom(), rawRoom({ ControlNo: 20, controlNo: 20, ReadingRoomName: '报刊阅览区' })],
    response(c) { c.success(c.url.endsWith('checktimecanbesk') ? { ReturnValue: 0, CanBaskID: 72 } : { ReturnValue: 0, Data: [
      timeRow({ roomNo: 20, isUseDay: 0, beginTime: '10:01:00', endTime: '11:29:00' }),
      timeRow({ roomNo: 20, isUseDay: 1, beginTime: '10:01:10', endTime: '11:29:00' })
    ] }); }
  }, async f => {
    f.api.start({ roomId: '20', useDay });
    const state = await f.drain(), room = state.profiles[0];
    assert.equal(state.kind, 'done'); assert.equal(state.total, 1); assert.equal(room.id, '20');
    assert.equal(room.slots[0].begin, useDay ? '10:01:10' : '10:01:00');
    const date = new f.w.Date(); date.setDate(date.getDate() + useDay);
    assert.equal(room.slotsDate, `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`);
    assert.deepEqual(f.requests.map(r => r.data), [{ id: '20' }, { roomid: '20', beskids: '72' }]);
    assert.doesNotMatch(JSON.stringify(state), /never-export|sign|beskCanId/);
  });
});

test('all-room sync prefers a coherent tomorrow snapshot, never a union with incomplete today', async () => use({ response(c) {
  c.success(c.url.endsWith('checktimecanbesk') ? { ReturnValue: 0, CanBaskID: 72 } : { ReturnValue: 0, Data: [
    timeRow({ isUseDay: 0, beginTime: '18:01:30', endTime: '19:29:30' }),
    timeRow({ isUseDay: 1, beginTime: '08:10:01', endTime: '10:00:59' }),
    timeRow({ isUseDay: 1, beginTime: '18:01:30', endTime: '19:29:11' })
  ] });
} }, async f => {
  f.api.start(); const state = await f.drain();
  assert.equal(state.profiles.length, 1);
  assert.deepEqual(JSON.parse(JSON.stringify(state.profiles[0].slots)), [
    { begin: '08:10:01', end: '10:00:59' }, { begin: '18:01:30', end: '19:29:11' }
  ]);
}));

test('requested day absent or duplicate same-day ranges never fallback to another date', async () => {
  for (const rows of [[timeRow({ isUseDay: 0 })], [timeRow(), timeRow()], [timeRow({ isUseDay: 2 })],
    [timeRow({ isUseDay: null })], [timeRow({ isUseDay: true })]]) {
    await use({ response(c) { c.success(c.url.endsWith('checktimecanbesk') ? { ReturnValue: 0, CanBaskID: 72 } : { ReturnValue: 0, Data: rows }); } }, async f => {
      f.api.start({ roomId: '26', useDay: 1 }); const state = await f.drain();
      assert.equal(state.profiles.length, 0); assert.equal(state.unavailable[0].reason, 'time-rules-unavailable');
    });
  }
});

test('invalid targeted room or use day stops before any request', async () => {
  for (const options of [{ roomId: '99', useDay: 1 }, { roomId: '26', useDay: 2 },
    { roomId: '26', useDay: '1' }, { roomId: '26' }, null, []]) await use({}, async f => {
    assert.equal(f.api.start(options).kind, 'unsupported'); assert.equal(f.requests.length, 0);
  });
});

test('unreadable or foreign/overlapping fixed schedules leave room visible but unselectable', async () => {
  for (const rows of [[], [timeRow({ roomNo: 27 })], [timeRow({ beginTime: '24:10:00' })],
    [timeRow(), timeRow({ beginTime: '09:00:00', endTime: '11:00:00' })], [timeRow({ beginTime: '10:00:00', endTime: '08:00:00' })]]) {
    await use({ response(c) { c.success(c.url.endsWith('checktimecanbesk') ? { ReturnValue: 0, CanBaskID: '72' } : { ReturnValue: 0, Data: rows }); } }, async f => {
      f.api.start(); const state = await f.drain();
      assert.equal(state.kind, 'done'); assert.equal(state.profiles.length, 0);
      assert.equal(state.unavailable[0].id, '26'); assert.equal(state.unavailable[0].reason, 'time-rules-unavailable');
    });
  }
});

test('school business refusal does not invent availability, skip to other room with bounded spacing', async () => use({ rooms: [rawRoom(), rawRoom({ ControlNo: 37, controlNo: 37, ReadingRoomName: '另一个阅览室', timefree: 1 })],
  response(c) { c.success({ ReturnValue: 1, Msg: '当前未到预约时间；private-data' }); }
}, async f => {
  f.api.start(); const state = await f.drain();
  assert.equal(state.kind, 'done'); assert.equal(state.profiles[0].id, '37');
  assert.equal(state.unavailable[0].reason, 'school-not-available'); assert.doesNotMatch(JSON.stringify(state), /private-data/);
  assert.ok(f.info.delays.every(ms => ms >= 400));
}));

test('all network reads are serialized and page helper start cannot launch duplicate runs', async () => use({ rooms: [rawRoom(), rawRoom({ ControlNo: 37, controlNo: 37, ReadingRoomName: '另一个阅览室' })], response(c) {
  c.success(c.url.endsWith('checktimecanbesk') ? { ReturnValue: 0, CanBaskID: '72' } : { ReturnValue: 0, Data: [timeRow({ roomNo: Number(c.data.roomid) })] });
} }, async f => {
  f.api.start(); f.api.start(); const state = await f.drain(); f.api.start();
  assert.equal(state.kind, 'done'); assert.equal(f.requests.length, 4); assert.equal(f.info.overlap, 0);
  assert.equal(f.info.delays.length, 3); assert.ok(f.info.delays.every(ms => ms >= 400));
}));

test('429, network failure and auth HTTP errors stop the entire sequence without retries', async () => {
  for (const [status, expected] of [[429, 'rate-limited'], [0, 'network-error'], [401, 'login-required'], [403, 'login-required']]) {
    await use({ rooms: [rawRoom(), rawRoom({ ControlNo: 37, controlNo: 37 })], response(c) { c.error({ status }, 'error'); } }, async f => {
      f.api.start(); const state = await f.drain();
      assert.equal(state.kind, 'stopped'); assert.equal(state.reason, expected); assert.equal(f.requests.length, 1);
    });
  }
});

test('login HTML or explicit JSON auth expiry stops instead of being mislabeled business unavailable', async () => {
  for (const response of [c => c.error({ status: 200, responseText: '<form><input type="password">统一身份认证：登录</form>' }, 'parsererror'),
    c => c.success({ ReturnValue: -1, Msg: '请先登录' })]) {
    await use({ response }, async f => {
      f.api.start(); const state = await f.drain(); assert.equal(state.reason, 'login-required'); assert.equal(f.requests.length, 1);
    });
  }
});

test('malformed response never exports payload and stops rather than trying another auth request', async () => {
  for (const value of [null, '<html>secret</html>', { ReturnValue: null }, { ReturnValue: false }, { ReturnValue: '', account: 'private' }, { ReturnValue: 0, CanBaskID: 'not-an-id' }]) {
    await use({ response(c) { c.success(value); } }, async f => {
      f.api.start(); const state = await f.drain(); assert.equal(state.reason, 'response-format');
      assert.equal(f.requests.length, 1); assert.doesNotMatch(JSON.stringify(state), /private|secret|not-an-id/);
    });
  }
});

test('cancel aborts active read and no follow-up can run', async () => use({ pending: true }, async f => {
  f.api.start(); assert.equal(f.requests.length, 1);
  f.api.cancel(); await Promise.resolve();
  assert.equal(f.info.aborted, 1); assert.equal(f.api.status().kind, 'stopped');
  f.api.start(); assert.equal(f.requests.length, 1);
}));

test('navigation while waiting invalidates the old-page run before its next request', async () => use({}, async f => {
  f.api.start(); for (let n = 0; n < 8; n++) await Promise.resolve();
  f.w.history.replaceState({}, '', '/selectreadingroom?groupid=2');
  // Avoid drain's intentional-running protection: run the single queued throttle.
  assert.equal(f.api.snapshot().kind, 'stopped');
  f.api.cancel(); assert.equal(f.requests.length, 1);
}));

test('unknown time mode remains listed without any guessed template or requests', async () => use({ rooms: [rawRoom({ timefree: 2 })] }, async f => {
  f.api.start(); const state = await f.drain(); assert.equal(state.profiles.length, 0);
  assert.equal(state.unavailable[0].reason, 'time-mode-unknown'); assert.equal(f.requests.length, 0);
}));

test('conflicting room/floor IDs and names fail closed, malformed room entries are skipped', async () => {
  for (const options of [{ rooms: [rawRoom(), rawRoom({ ReadingRoomName: '不同名称' })] },
    { rooms: [rawRoom({ controlNo: 99 })] }, { groups: [{ id: 1, groupname: '一楼' }, { id: 1, groupname: '二楼' }] },
    { groupId: 9 }, { rooms: Array.from({ length: 201 }, () => rawRoom()) }]) {
    await use(options, async f => { assert.equal(f.api.snapshot().kind, 'unsupported'); f.api.start(); assert.equal(f.requests.length, 0); });
  }
  await use({ rooms: [rawRoom({ ControlNo: 'bad' }), rawRoom({ ControlNo: 37, controlNo: 37, ReadingRoomName: '可识别房间', timefree: 1 })] }, async f => {
    assert.equal(f.api.snapshot().rooms.length, 1); f.api.start(); assert.equal((await f.drain()).profiles[0].id, '37');
  });
});

test('verified all-groups identity is preserved without pretending to know a floor', async () => use({ groupId: 0, rooms: [rawRoom({ timefree: 1 })] }, async f => {
  f.api.start(); const state = await f.drain(); assert.equal(state.profiles[0].groupId, '0');
  assert.equal(state.profiles[0].groupName, '未分组 / 所有分组');
}));

test('native room chooser is foreground-only, strict-scoped and never bridges credentials into discovery', () => {
  const activity = fs.readFileSync('app/src/main/java/cn/edu/cppu/libraryautobooker/RoomBrowserActivity.kt', 'utf8');
  assert.match(activity, /route\.pathOf\(url\) == "\/selectreadingroom"/);
  assert.match(activity, /override fun onPause\(\)/); assert.match(activity, /cancelSync\(\)/);
  assert.match(activity, /RoomProfile::fromJson/); assert.match(activity, /putExtra\("room_id", room\.id\)/);
  assert.doesNotMatch(activity, /addJavascriptInterface|CredentialStore|\.password|roomonclick\(/);
  assert.doesNotMatch(script, /\.click\(|\.submit\(|requestSubmit|window\.location\s*=|Cookie/);
});

test('native discovery WebView is hidden/noninteractive and every request is read-only allowlisted', () => {
  const activity = fs.readFileSync('app/src/main/java/cn/edu/cppu/libraryautobooker/RoomBrowserActivity.kt', 'utf8');
  assert.match(activity, /visibility = View\.GONE/);
  assert.match(activity, /isFocusable = false/);
  assert.match(activity, /isFocusableInTouchMode = false/);
  assert.match(activity, /setOnTouchListener \{ _, _ -> true \}/);
  assert.match(activity, /setOnKeyListener \{ _, _, _ -> true \}/);
  assert.match(activity, /IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS/);
  assert.match(activity, /shouldInterceptRequest\(view: WebView, request: WebResourceRequest\)/);
  assert.match(activity, /allowedRequest\(request\.method, request\.url\.toString\(\)\)/);
  assert.match(activity, /if \(method == "POST"\) return path in setOf\("\/readingroom\/checktimecanbesk", "\/readingroom\/getbesktimelist"\)/);
  assert.match(activity, /if \(method != "GET"\) return false/);
  assert.match(activity, /if \(!allowedPage\(url\)\) \{\s*view\.stopLoading\(\)/);
  assert.match(activity, /WebResourceResponse\("text\/plain", "UTF-8", 403/);
});
