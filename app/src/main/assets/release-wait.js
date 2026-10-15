(() => {
  if (window.__libraryReleaseWaitRunning) return;
  window.__libraryReleaseWaitRunning = true;
  const config = __BOOKING_CONFIG__;
  const duration = __WAIT_MS__;
  const started = Date.now();
  const deadline = started + Math.min(600000, Math.max(0, duration));
  const report = (state, detail) => window.AutoBooker?.report(state, detail);
  // School checktimecanbesk also says “当前时间为:… 未到可预约时间!”.
  // Keep this same explicit opening classifier in selection.js and automation.js.
  const closed = value => !/登录|登陆|会话|密码|验证码|重复|已有预约|已经预约|次数|上限|空闲座位|预约成功/.test(String(value || '')) &&
    /未到(?:可预约|预约|开放)时间|尚未开放|暂未开放|未开放预约|还未开放|未开始预约|预约尚未开始|尚未到.*预约时间/.test(String(value || ''));
  let finished = false, count = 0;
  const finish = (state, detail) => {
    if (finished) return;
    finished = true;
    report(state, detail);
  };
  const day = offset => {
    const date = new Date(); date.setDate(date.getDate() + offset);
    return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
  };
  const target = String(config.targetDate || day(config.reserveTomorrow ? 1 : 0))
    .replace(/-(\d)(?=-|$)/g, '-0$1');
  const expired = () => {
    if (Date.now() >= deadline) {
      finish('error', '10 分钟等待窗口已结束，未确认开放；没有继续提交预约');
      return true;
    }
    return false;
  };
  if (!window.jQuery?.ajax || !document.querySelector('#roomdata')) {
    return finish('error', '开放检查页面不符合预期；未提交预约');
  }
  // These are the read-only eligibility checks observed in the school's own script.
  // No reservation endpoint, cookie export, cached signatures or overlapping requests.
  const request = (url, type, data, callback) => {
    if (expired() || finished) return;
    window.jQuery.ajax({ url, type, data, dataType: 'json', cache: false,
      timeout: Math.min(8000, Math.max(1, deadline - Date.now())),
      success: result => {
        if (finished || expired()) return;
        callback(result);
      },
      error: xhr => finish([0, 408, 502, 503, 504].includes(xhr?.status) ? 'network-error' : 'error', xhr?.status === 429
        ? '学校系统要求降低请求频率，已停止等待'
        : '开放检查网络失败或响应异常，已停止等待；请检查登录和校园网') });
  };
  const tick = () => {
    if (finished || expired()) return;
    if (target !== day(0) && target !== day(1))
      return finish('error', '目标日期已不在今天或明天范围内，已停止等待');
    const roundAt = Date.now();
    const again = message => {
      if (finished || expired()) return;
      if (++count === 1 || count % 30 === 0)
        report('progress', `学校明确提示尚未开放；已检查 ${count} 次，剩余 ${Math.ceil((deadline - Date.now()) / 1000)} 秒。${String(message || '').replace(/<[^>]*>/g, '').slice(0, 120)}`);
      setTimeout(tick, Math.max(0, 1000 - (Date.now() - roundAt)));
    };
    request('/readingroom/checktimecanbesk', 'POST', { id: 26 }, result => {
      if (!result || typeof result.ReturnValue !== 'number')
        return finish('error', '开放检查返回格式异常；未提交预约');
      if (result.ReturnValue !== 0) {
        if (closed(result.Msg)) return again(result.Msg);
        return finish('error', `学校拒绝继续预约，已停止等待：${String(result.Msg || '未说明原因').replace(/<[^>]*>/g, '').slice(0, 120)}`);
      }
      request('/readingRoom/getYY', 'GET', { useday: target, roomId: 26 }, dateResult => {
        if (!dateResult || typeof dateResult.flagSign !== 'string')
          return finish('error', '日期检查返回格式异常；未提交预约');
        if (dateResult.flagSign === 'true')
          return finish('opened', `房间及目标日期 ${target} 检查通过，继续按优先顺序选座（不代表预约成功）`);
        if (closed(dateResult.strReturn)) return again(dateResult.strReturn);
        finish('error', `目标日期不能预约，已停止等待：${String(dateResult.strReturn || '未说明原因').replace(/<[^>]*>/g, '').slice(0, 120)}`);
      });
    });
  };
  tick();
})();
