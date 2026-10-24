const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const root = 'app/src/main/java/cn/edu/cppu/libraryautobooker/';

test('direct feedback watches only its request id and consumes only terminal results',()=>{
  const main=fs.readFileSync(root+'MainActivity.kt','utf8');
  const service=fs.readFileSync(root+'booking/BookingService.kt','utf8');
  const feedback=fs.readFileSync(root+'DirectBookingFeedback.kt','utf8');
  assert.match(main,/history.firstOrNull \{ it.id == id \}/);
  assert.match(main,/watchedManualId = requireNotNull\(ImmediateBookingPlan.actionId\(request\)\)/);
  assert.match(main,/DirectBookingFeedbackDialog/);
  assert.match(service,/else manualActionId \?:/);
  assert.match(feedback,/setOf\("DONE", "FAILED", "INTERRUPTED"\)/);
  assert.match(feedback,/record.id.startsWith\("manual:"\)/);
  assert.match(feedback,/verticalScroll/);
  assert.doesNotMatch(feedback,/startForegroundService|\.launch\(|SYSTEM_ALERT_WINDOW/);
});

test('room and date switches are local; online rules loading requires explicit button action',()=>{
  const main = fs.readFileSync(root+'MainActivity.kt','utf8');
  assert.doesNotMatch(main,/rulesRefreshSerial|rulesToday|LaunchedEffect\(config\.room/);
  const choose = main.slice(main.indexOf('val selectRoom:'),main.indexOf('val roomBrowser ='));
  assert.doesNotMatch(choose,/\.launch\(|refreshIntent|refreshRules\(/);
  assert.match(main,/val refreshRules: \(\) -> Unit/);
  assert.match(main,/onRefreshRules = refreshRules/);
  const dateCard = fs.readFileSync(root+'BookingDateTimeCard.kt','utf8');
  assert.match(dateCard,/onClick = onRefreshRules/);
  assert.doesNotMatch(dateCard,/LaunchedEffect\(/);
  assert.match(dateCard,/listOf\(false, true\)\.forEachIndexed/);
  assert.match(dateCard,/onConfigChange\(config\.copy\(reserveTomorrow = tomorrow\)\)/);
  assert.match(main,/切换房间、日期不会在线同步/);
});
test('dashboard has separate immediate and fixed actions while retired modules stay removed',()=>{
  const main = fs.readFileSync(root+'MainActivity.kt','utf8');
  const entry = fs.readFileSync(root+'EntryLoginScreen.kt','utf8');
  assert.doesNotMatch(main+entry,/WebVpn|WebVPN|webvpn|OcrTrial|ReleaseTimesField|ReleaseTimeField|立即预约所选座位|演练模式/);
  assert.equal((main.match(/onDirect = direct@\{/g)||[]).length,1);
  assert.equal((main.match(/onSchedule = book@\{/g)||[]).length,1);
  const direct = main.slice(main.indexOf('onDirect = direct@{'), main.indexOf('onSchedule = book@{'));
  assert.match(direct,/ImmediateBookingPlan\.intent/);
  assert.doesNotMatch(direct,/store\.save|scheduler\.schedule|exactPermission\.launch/);
  const actions = fs.readFileSync(root+'FixedBookingControls.kt','utf8');
  assert.match(actions,/onClick = onSchedule/);
  assert.match(actions,/onClick = onDirect/);
  assert.match(main,/FixedBookingPlan\.prepare/);
  assert.match(main,/LibraryAppPages/);
});
test('retired authentication, conversion, OCR and custom time modules are absent',()=>{
  for(const file of ['WebVpnAutoLogin.kt','WebVpnLoginDriver.kt','WebVpnAccountsActivity.kt','WebViewLoginTap.kt',
    'OcrTrialActivity.kt','ReleaseTimeField.kt','data/WebVpnCredentialStore.kt','network/FallbackPolicy.kt']){
    assert.equal(fs.existsSync(root+file),false,file);
  }
  for(const file of ['webvpn-sso-login.js','webvpn-library-login.js','webvpn-captcha.js'])
    assert.equal(fs.existsSync('app/src/main/assets/'+file),false,file);
  assert.doesNotMatch(fs.readFileSync('app/build.gradle.kts','utf8'),/onnxruntime|verifyOcrAssets/);
  assert.doesNotMatch(fs.readFileSync('app/src/main/AndroidManifest.xml','utf8'),/WebVpn|OcrTrial/);
});
