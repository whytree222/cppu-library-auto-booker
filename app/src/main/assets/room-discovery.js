(function () {
  'use strict';
  // Read-only room/rule discovery. Never invokes roomonclick, date selection,
  // checkIsBesk, seat-map navigation, or any reservation submission endpoint.
  function scope() {
    try {
      const u = new URL(window.location.href);
      if (u.username || u.password || u.hash) return null;
      const direct = u.protocol === 'http:' && u.hostname === 'mlib.cppu.edu.cn' && !u.port;
      if (!direct || u.pathname !== '/selectreadingroom') return null;
      return { href: u.href, prefix: '' };
    } catch (_) { return null; }
  }
  const initialScope = scope();
  if (!initialScope) return 'unsupported';
  if (window.LibraryRoomDiscovery) return 'room-discovery-ready';
  const id = (v, zero) => {
    const s = typeof v === 'number' && Number.isSafeInteger(v) ? String(v) : typeof v === 'string' ? v : '';
    return (zero ? /^(?:0|[1-9]\d{0,8})$/ : /^[1-9]\d{0,8}$/).test(s) ? s : null;
  };
  const name = v => typeof v === 'string' && v.trim() && v.trim().length <= 80 && !/[\u0000-\u001f\u007f<>]/.test(v) ? v.trim() : null;
  const minutes = v => Number.isInteger(Number(v)) && Number(v) >= 0 && Number(v) <= 1440 ? Number(v) : 0;
  const time = v => {
    if (typeof v !== 'string' || !/^(?:\d|[01]\d|2[0-3]):[0-5]\d(?::[0-5]\d)?$/.test(v)) return null;
    const parts = v.split(':');
    return parts[0].padStart(2, '0') + ':' + parts[1] + ':' + (parts[2] || '00');
  };
  const copy = v => JSON.parse(JSON.stringify(v));
  const alive = () => scope()?.href === initialScope.href && !cancelled;
  let cancelled = false, started = false, pending = null, timer = null;
  let state = { kind: 'idle', profiles: [], unavailable: [], completed: 0, total: 0 };
  function inspect() {
    if (!alive()) return { kind: 'stopped', reason: 'page-changed' };
    if (document.querySelector('form#fromuser, input#passwd, input[type="password"]')) return { kind: 'login-required' };
    if (!Array.isArray(window.grouplist) || !Array.isArray(window.datalist?.Data)) return { kind: 'unsupported', reason: 'room-data-missing' };
    if (window.grouplist.length > 40 || window.datalist.Data.length > 200) return { kind: 'unsupported', reason: 'catalog-too-large' };
    const groups = [], groupMap = new Map();
    for (const g of window.grouplist) {
      const gid = id(g?.id, false), label = name(g?.groupname);
      if (!gid || !label) continue;
      if (groupMap.has(gid) && groupMap.get(gid) !== label) return { kind: 'unsupported', reason: 'conflicting-groups' };
      if (!groupMap.has(gid)) { groupMap.set(gid, label); groups.push({ id: gid, name: label }); }
    }
    const groupId = id(window.publishgroupid, true);
    if (!groupId || (groupId !== '0' && !groupMap.has(groupId))) return { kind: 'unsupported', reason: 'group-not-matched' };
    const groupName = groupMap.get(groupId) || '未分组 / 所有分组';
    const rooms = [], roomMap = new Map();
    for (const raw of window.datalist.Data) {
      const roomId = id(raw?.ControlNo, false), roomName = name(raw?.ReadingRoomName);
      if (!roomId || !roomName) continue;
      if (raw.controlNo != null && id(raw.controlNo, false) !== roomId) return { kind: 'unsupported', reason: 'conflicting-room-id' };
      const mode = raw.timefree === 0 || raw.timefree === '0' ? 'fixed' : raw.timefree === 1 || raw.timefree === '1' ? 'free' : 'unknown';
      const room = { id: roomId, name: roomName, groupId, groupName, timeMode: mode, slots: [],
        minMinutes: minutes(raw.mintimes), maxMinutes: minutes(raw.maxtimes), stepMinutes: minutes(raw.timestep) };
      if (room.maxMinutes && room.maxMinutes < room.minMinutes) room.timeMode = 'unknown';
      if (roomMap.has(roomId) && JSON.stringify(roomMap.get(roomId)) !== JSON.stringify(room)) return { kind: 'unsupported', reason: 'conflicting-rooms' };
      if (!roomMap.has(roomId)) { roomMap.set(roomId, room); rooms.push(room); }
    }
    return { kind: 'ready', groupId, groupName, groups, rooms };
  }
  function stop(reason) {
    if (timer !== null) { clearTimeout(timer); timer = null; }
    const request = pending; pending = null;
    cancelled = true;
    if (request?.abort) { try { request.abort(); } catch (_) {} }
    state.kind = 'stopped'; state.reason = reason;
  }
  function request(path, data) {
    return new Promise((resolve, reject) => {
      if (!alive()) { reject('page-changed'); return; }
      if (typeof window.jQuery?.ajax !== 'function') { reject('school-ajax-unavailable'); return; }
      try {
        pending = window.jQuery.ajax({ url: initialScope.prefix + path, type: 'POST', data, dataType: 'json', timeout: 8000, global: false,
          success(value) {
            pending = null;
            if (!alive()) { reject('page-changed'); return; }
            if (!value || typeof value !== 'object' || Array.isArray(value) || !/^-?\d{1,9}$/.test(String(value.ReturnValue))) { reject('response-format'); return; }
            if (Number(value.ReturnValue) !== 0 && /(?:请先登录|尚未登录|未登录|登录(?:已)?(?:过期|失效)|登录超时)/.test(String(value.Msg || ''))) { reject('login-required'); return; }
            resolve(value);
          },
          error(xhr, errorKind) {
            pending = null;
            if (!alive()) { reject('page-changed'); return; }
            if (xhr?.status === 429) reject('rate-limited');
            else if (xhr?.status === 401 || xhr?.status === 403 || /<(?:form|input)\b/i.test(String(xhr?.responseText || '')) && /(?:登录|password|统一身份认证)/i.test(String(xhr?.responseText || ''))) reject('login-required');
            else reject(errorKind === 'parsererror' ? 'response-format' : 'network-error');
          }
        });
      } catch (_) { pending = null; reject('school-ajax-unavailable'); }
    });
  }
  function wait() {
    return new Promise(resolve => { timer = setTimeout(() => { timer = null; resolve(); }, 400); });
  }
  function parseSlots(response, roomId) {
    if (!Array.isArray(response.Data) || response.Data.length > 400) return null;
    const slots = new Map();
    for (const row of response.Data) {
      if (id(row?.roomNo, false) !== roomId) return null;
      const begin = time(row.beginTime), end = time(row.endTime);
      if (!begin || !end || begin >= end) return null;
      slots.set(begin + '-' + end, { begin, end });
    }
    const sorted = [...slots.values()].sort((a, b) => a.begin.localeCompare(b.begin));
    if (!sorted.length || sorted.length > 48 || sorted.some((slot, i) => i && sorted[i - 1].end > slot.begin)) return null;
    return sorted;
  }
  async function run(snapshot) {
    try {
      for (const room of snapshot.rooms) {
        if (!alive()) return;
        if (room.timeMode === 'free') state.profiles.push(room);
        else if (room.timeMode !== 'fixed') state.unavailable.push({ id: room.id, name: room.name, reason: 'time-mode-unknown' });
        else {
          const eligible = await request('/readingroom/checktimecanbesk', { id: room.id });
          if (Number(eligible.ReturnValue) !== 0) state.unavailable.push({ id: room.id, name: room.name, reason: 'school-not-available' });
          else {
            const eligibilityId = id(eligible.CanBaskID, true);
            if (!eligibilityId) throw 'response-format';
            await wait(); if (!alive()) return;
            const response = await request('/readingroom/getbesktimelist', { roomid: room.id, beskids: eligibilityId });
            const slots = Number(response.ReturnValue) === 0 ? parseSlots(response, room.id) : null;
            if (slots) state.profiles.push({ ...room, slots });
            else state.unavailable.push({ id: room.id, name: room.name, reason: 'time-rules-unavailable' });
          }
        }
        state.completed++;
        if (state.completed < state.total) { await wait(); if (!alive()) return; }
      }
      state.kind = 'done';
    } catch (reason) {
      if (!cancelled) stop(['page-changed', 'school-ajax-unavailable', 'response-format', 'rate-limited', 'login-required', 'network-error'].includes(reason) ? reason : 'response-format');
    }
  }
  const api = {
    snapshot: () => copy(inspect()),
    start() {
      if (started) return copy(state);
      const snapshot = inspect();
      if (snapshot.kind !== 'ready') return snapshot;
      started = true;
      state = { kind: 'running', profiles: [], unavailable: [], completed: 0, total: snapshot.rooms.length, groupId: snapshot.groupId };
      run(snapshot);
      return copy(state);
    },
    status: () => copy(state),
    cancel: () => { stop('user-cancelled'); return { kind: 'stopped' }; }
  };
  Object.defineProperty(window, 'LibraryRoomDiscovery', { value: Object.freeze(api), configurable: false, writable: false });
  return 'room-discovery-ready';
})();
