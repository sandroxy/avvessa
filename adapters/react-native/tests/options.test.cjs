const test = require('node:test');
const assert = require('node:assert/strict');
const { options } = require('../plugin/options');
const ios = { apnsEnvironment: 'development' };

test('base configuration adds no optional vendor or permission request', () => {
  assert.deepEqual(options({ ios }), { ios, android: { vendors: [], vendorProperties: {} } });
});
test('enabled vendors require exactly their own fields', () => {
  assert.throws(() => options({ ios, android: { vendors: ['xiaomi'] } }));
  assert.throws(() => options({ ios, android: { vendorProperties: { XIAOMI_APPKEY: 'unused' } } }));
  assert.throws(() => options({ ios, android: { vendors: ['xiaomi', 'xiaomi'] } }));
  const result = options({ ios, android: {
    vendors: ['xiaomi'], vendorProperties: { XIAOMI_APPID: 'example-id', XIAOMI_APPKEY: 'example-key' },
  } });
  assert.deepEqual(result.android.vendors, ['xiaomi']);
});
test('service files and APNs environment must be explicit', () => {
  assert.throws(() => options({}));
  assert.throws(() => options({ ios: { apnsEnvironment: 'sandbox' } }));
  assert.throws(() => options({ ios, android: { vendors: ['huawei'] } }));
  assert.throws(() => options({ ios, android: { googleServicesFile: './google-services.json' } }));
  assert.equal(options({ ios, android: { vendors: ['huawei'], agconnectServicesFile: './agconnect-services.json' } }).android.vendors[0], 'huawei');
});
test('call capabilities and unrelated options are rejected', () => {
  assert.throws(() => options({ ios, android: false }));
  assert.throws(() => options({ ios, android: { vendors: null } }));
  assert.throws(() => options({ ios, android: { agconnectServicesFile: null } }));
  assert.throws(() => options({ ios, incomingCall: {} }));
  assert.throws(() => options({ ios, android: { requestLocation: true } }));
  assert.throws(() => options({ ios, android: { notificationIcon: './icon.jpg' } }));
});
