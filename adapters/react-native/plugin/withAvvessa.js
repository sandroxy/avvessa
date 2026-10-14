'use strict';

const fs = require('node:fs');
const path = require('node:path');
const {
  withAndroidManifest, withAppBuildGradle, withProjectBuildGradle, withSettingsGradle,
  withGradleProperties, withDangerousMod, withEntitlementsPlist, withInfoPlist,
} = require('@expo/config-plugins');
const { options } = require('./options');
function block(contents, body, name = 'configuration', prepend = false) {
  const BEGIN = '// @sandrox/avvessa ' + name + ' begin';
  const END = '// @sandrox/avvessa ' + name + ' end';
  const begin = contents.indexOf(BEGIN);
  const end = contents.indexOf(END);
  if ((begin >= 0) !== (end >= 0) || (begin >= 0 && end < begin)) throw new Error('Avvessa: damaged generated Gradle block');
  const clean = begin < 0 ? contents : contents.slice(0, begin) + contents.slice(end + END.length);
  const generated = BEGIN + '\n' + body + '\n' + END;
  return prepend ? generated + '\n\n' + clean.trimStart() : clean.trimEnd() + '\n\n' + generated + '\n';
}
function groovy(text) { return "'" + text.replace(/\\/g, '\\\\').replace(/'/g, "\\'") + "'"; }

module.exports = function withAvvessa(config, input) {
  const { android, ios } = options(input);
  const huawei = android.vendors.includes('huawei');
  const honor = android.vendors.includes('honor');
  const fcm = android.vendors.includes('fcm');
  config = withEntitlementsPlist(config, mod => {
    mod.modResults['aps-environment'] = ios.apnsEnvironment;
    return mod;
  });
  config = withInfoPlist(config, mod => {
    mod.modResults.UIBackgroundModes = [...new Set([...(mod.modResults.UIBackgroundModes || []), 'remote-notification'])];
    return mod;
  });
  config = withGradleProperties(config, mod => {
    mod.modResults = mod.modResults.filter(item => item.key !== 'avvessaVendorModules');
    mod.modResults.push({ type: 'property', key: 'avvessaVendorModules', value: android.vendors.join(',') });
    return mod;
  });
  config = withAndroidManifest(config, mod => {
    const application = mod.modResults.manifest.application?.[0];
    if (!application) throw new Error('Avvessa: Android application is missing');
    const name = 'com.google.firebase.messaging.default_notification_icon';
    const entries = application['meta-data'] || [];
    // Only the FCM transport needs a build-time icon override; common JPush uses runtime smallIcon.
    application['meta-data'] = entries.filter(item => !(item.$?.['android:name'] === name &&
      item.$?.['android:resource'] === '@drawable/avvessa_notification_custom'));
    if (fcm && android.notificationIcon) {
      application['meta-data'] = application['meta-data'].filter(item => item.$?.['android:name'] !== name);
      mod.modResults.manifest.$['xmlns:tools'] = 'http://schemas.android.com/tools';
      application['meta-data'].push({ $: { 'android:name': name,
        'android:resource': '@drawable/avvessa_notification_custom', 'tools:replace': 'android:resource' } });
    }
    return mod;
  });
  config = withProjectBuildGradle(config, mod => {
    if (mod.modResults.language !== 'groovy') throw new Error('Avvessa Expo plugin requires Groovy Gradle files; configure Kotlin DSL manually');
    const repositories = [];
    if (huawei) repositories.push("maven { url 'https://developer.huawei.com/repo/' }");
    if (honor) repositories.push("maven { url 'https://developer.hihonor.com/repo/' }");
    const classpath = [];
    if (huawei) classpath.push("classpath 'com.huawei.agconnect:agcp:1.9.6.300'");
    if (fcm) classpath.push("classpath 'com.google.gms:google-services:4.4.4'");
    const buildscript = classpath.length ? 'buildscript { repositories { google(); mavenCentral(); ' + repositories.join('; ') + ' }; dependencies { ' + classpath.join('; ') + ' } }' : '';
    const projects = repositories.length ? 'allprojects { repositories { ' + repositories.join('; ') + ' } }' : '';
    // Gradle requires buildscript before plugins; allprojects must follow it.
    mod.modResults.contents = block(block(mod.modResults.contents, buildscript, 'classpath', true), projects, 'repositories');
    return mod;
  });
  config = withSettingsGradle(config, mod => {
    if (mod.modResults.language !== 'groovy') throw new Error('Avvessa Expo plugin requires Groovy Gradle files');
    // AGConnect requires libs.android.application. Expo names the RN catalog
    // expoLibs, so expose that same authoritative input when the host has no libs.
    const body = huawei ? `if (!dependencyResolutionManagement.versionCatalogs.findByName('libs') && !file('gradle/libs.versions.toml').exists()) {
  def avvessaReactNativePackage = providers.exec {
    workingDir(rootDir)
    commandLine('node', '--print', "require.resolve('react-native/package.json')")
  }.standardOutput.asText.get().trim()
  dependencyResolutionManagement.versionCatalogs.create('libs') {
    from(files(new File(avvessaReactNativePackage, '../gradle/libs.versions.toml')))
  }
}` : '';
    mod.modResults.contents = block(mod.modResults.contents, body, 'agconnect-catalog');
    return mod;
  });
  config = withAppBuildGradle(config, mod => {
    if (mod.modResults.language !== 'groovy') throw new Error('Avvessa Expo plugin requires Groovy Gradle files');
    const properties = Object.entries(android.vendorProperties).map(([key, value]) => groovy(key) + ': ' + groovy(value)).join(', ');
    const body = [
      properties ? 'android { defaultConfig { manifestPlaceholders += [' + properties + '] } }' : '',
      huawei ? "apply plugin: 'com.huawei.agconnect'" : '',
      fcm ? "apply plugin: 'com.google.gms.google-services'" : '',
    ].filter(Boolean).join('\n');
    mod.modResults.contents = block(mod.modResults.contents, body);
    return mod;
  });
  return withDangerousMod(config, ['android', async mod => {
    const base = mod.modRequest.projectRoot;
    const app = path.join(mod.modRequest.platformProjectRoot, 'app');
    for (const [enabled, option, filename] of [[huawei, 'agconnectServicesFile', 'agconnect-services.json'], [fcm, 'googleServicesFile', 'google-services.json']]) {
      if (!enabled) continue;
      const source = path.resolve(base, android[option]);
      const service = JSON.parse(fs.readFileSync(source, 'utf8'));
      const matches = option === 'agconnectServicesFile'
        ? service.client?.package_name === config.android?.package
        : Array.isArray(service.client) && service.client.some(client => client.client_info?.android_client_info?.package_name === config.android?.package);
      if (!matches) throw new Error('Avvessa: ' + filename + ' does not match android.package');
      fs.copyFileSync(source, path.join(app, filename));
    }
    if (android.notificationIcon) {
      const source = path.resolve(base, android.notificationIcon);
      const target = path.join(app, 'src/main/res/drawable');
      fs.mkdirSync(target, { recursive: true });
      for (const suffix of ['png', 'xml']) {
        const stale = path.join(target, 'avvessa_notification_custom.' + suffix);
        if (fs.existsSync(stale)) fs.unlinkSync(stale);
      }
      fs.copyFileSync(source, path.join(target, 'avvessa_notification_custom' + path.extname(source).toLowerCase()));
    }
    return mod;
  }]);
};
module.exports.block = block;
