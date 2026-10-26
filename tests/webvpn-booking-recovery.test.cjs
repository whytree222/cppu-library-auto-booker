const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const root = 'app/src/main/java/cn/edu/cppu/libraryautobooker/';

test('booking WebVPN relogin is pre-submission, once per batch and route-locked',()=>{
  const service = fs.readFileSync(root+'booking/BookingService.kt','utf8');
  assert.match(service,/fun recoverWebVpnLogin\(/);
  assert.match(service,/webVpnReloginDecision\(fallback\?\.hasSubmitted\(\) == true, automationStarted, recoveredLogin\)/);
  assert.match(service,/SessionCoordinator\.checkRoute\(this@BookingService, LibraryRoute\.WEBVPN\)/);
  const submitted = service.slice(service.indexOf('WebVpnReloginDecision.AFTER_SUBMISSION'),
    service.indexOf('WebVpnReloginDecision.ALLOWED'));
  assert.match(submitted,/已经进入提交阶段/);
  assert.match(submitted,/不重新登录、不重发/);
  assert.doesNotMatch(submitted,/AutoLogin\.login|startBatch|loadUrl/);
  const policy = fs.readFileSync(root+'booking/WebVpnReloginPolicy.kt','utf8');
  assert.match(policy,/submitted -> WebVpnReloginDecision\.AFTER_SUBMISSION/);
});
