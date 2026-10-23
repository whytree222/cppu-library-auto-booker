const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const root = 'app/src/main/java/cn/edu/cppu/libraryautobooker/';
test('dashboard has separate immediate and fixed actions while retired modules stay removed',()=>{
  const main = fs.readFileSync(root+'MainActivity.kt','utf8');
  const entry = fs.readFileSync(root+'EntryLoginScreen.kt','utf8');
  assert.doesNotMatch(main+entry,/WebVpn|WebVPN|webvpn|OcrTrial|ReleaseTimesField|ReleaseTimeField|立即预约所选座位|演练模式/);
  assert.equal((main.match(/FixedBookingButton\(/g)||[]).length,1);
  assert.equal((main.match(/ImmediateBookingButton\(/g)||[]).length,1);
  const direct = main.slice(main.indexOf('ImmediateBookingButton('), main.indexOf('FixedBookingButton('));
  assert.match(direct,/ImmediateBookingPlan\.intent/);
  assert.doesNotMatch(direct,/store\.save|scheduler\.schedule|exactPermission\.launch/);
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
