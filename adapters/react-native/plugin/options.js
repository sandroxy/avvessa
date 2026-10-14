'use strict';

const VENDORS = {
  xiaomi: ['XIAOMI_APPID', 'XIAOMI_APPKEY'], huawei: [],
  oppo: ['OPPO_APPID', 'OPPO_APPKEY', 'OPPO_APPSECRET'],
  vivo: ['VIVO_APPID', 'VIVO_APPKEY'], honor: ['HONOR_APPID'],
  meizu: ['MEIZU_APPID', 'MEIZU_APPKEY'], fcm: [],
};
function fields(value, allowed, label) {
  if (!value || typeof value !== 'object' || Array.isArray(value) ||
      Object.keys(value).some(key => !allowed.includes(key))) throw new Error('Avvessa: invalid ' + label);
}
function options(input = {}) {
  fields(input, ['android', 'ios'], 'options');
  const android = input.android === undefined ? {} : input.android;
  const ios = input.ios === undefined ? {} : input.ios;
  fields(android, ['vendors', 'vendorProperties', 'agconnectServicesFile', 'googleServicesFile', 'notificationIcon'], 'Android options');
  fields(ios, ['apnsEnvironment'], 'iOS options');
  if (!['development', 'production'].includes(ios.apnsEnvironment)) {
    throw new Error('Avvessa: ios.apnsEnvironment must be development or production');
  }
  const vendors = android.vendors === undefined ? [] : android.vendors;
  if (!Array.isArray(vendors) || new Set(vendors).size !== vendors.length || vendors.some(vendor => !Object.hasOwn(VENDORS, vendor))) {
    throw new Error('Avvessa: unknown or duplicate Android vendor');
  }
  const expected = vendors.flatMap(vendor => VENDORS[vendor]);
  const properties = android.vendorProperties === undefined ? {} : android.vendorProperties;
  fields(properties, expected, 'vendor properties');
  if (expected.some(key => typeof properties[key] !== 'string' || !properties[key].trim() || /[\x00-\x1f\x7f]/.test(properties[key]))) {
    throw new Error('Avvessa: every enabled vendor requires its documented string properties');
  }
  for (const [vendor, file] of [['huawei', 'agconnectServicesFile'], ['fcm', 'googleServicesFile']]) {
    const present = Object.hasOwn(android, file);
    if (vendors.includes(vendor) !== present || (present &&
        (typeof android[file] !== 'string' || !android[file].trim() || /[\x00-\x1f\x7f]/.test(android[file])))) {
      throw new Error('Avvessa: ' + file + ' must be supplied exactly when ' + vendor + ' is enabled');
    }
  }
  if (android.notificationIcon !== undefined &&
      (typeof android.notificationIcon !== 'string' || !/\.(png|xml)$/i.test(android.notificationIcon))) {
    throw new Error('Avvessa: notificationIcon must name a monochrome PNG or Android vector XML');
  }
  return { android: { ...android, vendors, vendorProperties: properties }, ios };
}
module.exports = { options, VENDORS };
