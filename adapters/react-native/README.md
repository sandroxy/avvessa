# Avvessa for React Native

Add Jiguang push notifications to Android and iOS applications with `@sandrox/avvessa`. The package supports React Native's legacy bridge and TurboModules, with optional Expo integration.

Receive notifications, custom messages and notification interactions, replay stored events, and control foreground presentation. The application owns authentication, account binding, navigation, consent decisions and server-side sending.

## Requirements

- React Native 0.81 or later. Expo SDK 54 is the integration baseline; check newer RN and Expo combinations against your application's dependencies.
- The RN integration baseline requires Android API 24 and iOS 15.1. Use the highest minimum required by React Native, Avvessa and your selected vendor SDKs.
- Expo requires a development build containing Avvessa's native modules. Expo Go does not include them.

## Install

```sh
npm install @sandrox/avvessa
```

React Native autolinks the Android module and the `AvvessaReactNative` Pod. For a project without Expo, complete the native setup below, run `npx pod-install`, and rebuild the application. For Expo, configure the plugin in the next section, then run `npx expo prebuild` and `npx expo run:android` or `npx expo run:ios`.

Android uses Avvessa's Maven core with optional vendor modules. iOS includes the core sources and privacy resources; CocoaPods resolves the official JPush and JCore versions pinned by the package metadata. Do not also link a separate Avvessa core, `jpush-react-native`, or another integration that initializes JPush independently.

A React Native project without Expo does not depend on ExpoModulesCore. Expo projects additionally link `AvvessaExpo`, which forwards application launch and APNs callbacks. You can keep `expo-notifications` for other notifications, but handle each Avvessa message through one application consumer to avoid duplicate actions or local notifications.

## Configure Expo

Add this entry to the `plugins` array in `app.config.js`:

```js
['@sandrox/avvessa', {
  ios: { apnsEnvironment: 'development' },
  android: {
    vendors: [],
    vendorProperties: {},
  },
}]
```

`ios.apnsEnvironment` must match the application's signed APNs entitlement: `development` for development signing or `production` for distribution signing. Use the same value when initializing Avvessa. The plugin adds `aps-environment` and the `remote-notification` background mode; the application still controls consent and notification permission requests.

For a notification small icon, set `android.notificationIcon` to an existing asset such as `./assets/push-small.xml`. Supply a monochrome Android vector XML or PNG with a transparent background. The generated resource name is `drawable/avvessa_notification_custom`; pass that name as `android.smallIcon` at initialization and set `android.color` for its tint. System or vendor notification layouts may also display the application icon.

Select the modules you need in `android.vendors`. Set their string parameters in `android.vendorProperties`; Huawei and FCM instead require service file options directly under `android`:

| Module | Configuration |
| --- | --- |
| `xiaomi` | `XIAOMI_APPID`, `XIAOMI_APPKEY` |
| `huawei` | `android.agconnectServicesFile`: path to the service JSON matching the application package name |
| `honor` | `HONOR_APPID` |
| `oppo` | `OPPO_APPID`, `OPPO_APPKEY`, `OPPO_APPSECRET` |
| `vivo` | `VIVO_APPID`, `VIVO_APPKEY` |
| `meizu` | `MEIZU_APPID`, `MEIZU_APPKEY` |
| `fcm` | `android.googleServicesFile`: path to the service JSON matching the application package name |

File paths are relative to the application root or absolute. Unknown fields, missing parameters, duplicate modules and mismatched service files cause configuration errors. Unselected vendors do not add native dependencies. Read application settings from local or CI configuration; never include the server-side Master Secret in the client.

The config plugin handles standard Groovy Gradle projects. Configure Kotlin DSL projects manually. After changing native configuration, run prebuild and rebuild the application; reloading JavaScript does not update manifests, resources or entitlements.

## Native setup without Expo

On Android, autolinking requires no changes to the Activity's notification-click entry point. The native core manifest supplies the base permissions, receivers and notification entry points. Select vendor modules in `gradle.properties`, for example `avvessaVendorModules=xiaomi,huawei`, then follow the [Android vendor setup](https://github.com/sandroxy/avvessa/blob/main/native/android/README.md#optional-manufacturer-transports) for placeholders, repositories and service files. Place small icons in the application's `res/drawable` and pass the resource name at initialization. Push does not require location, phone-state or storage-read permissions; the core removes these optional SDK permissions. Check the application's final merged manifest.

On iOS, import `AvvessaReactNative` in AppDelegate and add these calls to the existing callbacks. Do not define a second implementation of the same callback:

| Application callback | Swift forwarding call |
| --- | --- |
| Application launch, before starting React | `AVReactNativeLifecycle.shared.launch(launchOptions)` |
| Application becomes active | `AVReactNativeLifecycle.shared.activate()` |
| APNs registration succeeds | `AVReactNativeLifecycle.shared.registerDeviceToken(deviceToken)` |
| APNs registration fails | `AVReactNativeLifecycle.shared.registrationFailed(error as NSError)` |
| Background remote notification | `AVReactNativeLifecycle.shared.receive(userInfo)` |

The application must call the background completion handler exactly once. `receive` returns whether the payload belongs to Avvessa; aggregate results when other services also handle notifications. Enable Push Notifications and Background Modes → Remote notifications, with matching signing capabilities.

The launch call installs a notification delegate coordinator that preserves later delegate assignments by other modules. Avvessa decides foreground presentation for its notifications and forwards other messages to the existing delegate. Keep a strong reference to any delegate being observed. These lifecycle calls do not grant consent or initialize JPush. With Expo's automatic forwarding, do not forward the same callbacks manually.

## Initialize and consume events

The [TypeScript declarations](https://github.com/sandroxy/avvessa/blob/main/adapters/uniapp/js_sdk/index.d.ts) define the full API; the [shared contract](https://github.com/sandroxy/avvessa/blob/main/doc/contract.md) defines its behavior. React Native and UNI use the same JSON commands and error codes.

Run this sequence in an async startup function using application-supplied `appConfiguration`. Consent, system notification permission and login are separate decisions:

```js
import * as push from '@sandrox/avvessa';

// Call this only when the application's consent decision permits push.
await push.setConsentGranted(true);
const subscriptionId = await push.subscribe(message => {
  if (message.kind === 'state') console.log(message.state.registrationId);
  else if (message.kind === 'error') console.error(message.error);
  else console.log(message.event); // Acknowledge event.id after successful handling.
}, { replayPending: true });

await push.initialize({
  appKey: appConfiguration.appKey,
  channel: appConfiguration.channel,
  ios: { apnsEnvironment: appConfiguration.apnsEnvironment },
  android: {
    notificationChannelId: 'messages',
    notificationChannelName: 'Messages',
    presentInForeground: true,
  },
});
await push.requestNotificationPermission();
```

Keep the subscription ID and call `unsubscribe(subscriptionId)` when that observer is no longer needed. To customize Android channel sound or vibration, call `createNotificationChannel` before initialization; existing channels remain subject to system settings. On Android, the permission request can resolve before the user's decision. If `granted` is `null`, observe state updates or call `refreshState` after returning to the foreground. iOS initialization still requires APNs forwarding and signing configuration.

For a subscription owned by a React component, import `useEffect` from `react` and use the following effect inside the component instead of the subscription above. It handles unmounting before the subscription Promise resolves. Supply `handleObservation` and `reportError` from your application:

```js
useEffect(() => {
  let disposed = false;
  let id;
  push.subscribe(message => {
    if (!disposed) handleObservation(message);
  }).then(value => {
    if (disposed) return push.unsubscribe(value);
    id = value;
  }).catch(reportError);
  return () => {
    disposed = true;
    if (id) void push.unsubscribe(id).catch(reportError);
  };
}, [handleObservation, reportError]);
```

Unsubscribing releases the observer without stopping the SDK or deleting pending events. Notification interactions can arrive before React starts and be replayed later with the same `event.id` and `replayed: true`. Call `acknowledge(event.id)` only after successful handling, and use the event ID to make application side effects idempotent.

## Presentation and lifecycle

Set `presentationPolicy` at initialization to define static rules and active-context field mappings. Screens update the context with `setActiveNotificationContext` and `clearActiveNotificationContext`; clear it when leaving the relevant screen. The application defines accounts, routes and field meanings. Suppressing a foreground banner preserves the receipt event.

The optional `notificationLifecycle` settings control badge cleanup at initialization, on return to the foreground and on notification open, plus delivered-notification cleanup at initialization and on return to the foreground. All are disabled by default. At runtime, `clearNotifications` removes delivered notifications, `clearPendingEvents` clears the pending event queue, and `acknowledge` marks one event as consumed.

Use `stopPush` and `resumePush` to control SDK push delivery; `unsubscribe` only controls an observer. Withdraw consent with `setConsentGranted(false)`. After granting consent again, explicitly initialize or resume the client. The application manages aliases, tags and server-side device binding. Original SDK message types are retained as described in the shared contract.

## iOS notification actions

Register action categories with `setNotificationCategories`, then use the same identifier in the server's iOS notification `category` field, not in custom extras. A button interaction produces `notificationOpened` with the registered action ID in `event.action.identifier`. Text responses also include `event.action.userText`. Avvessa delivers the action and text; the application sends any business reply. See `NotificationCategory` in the [TypeScript declarations](https://github.com/sandroxy/avvessa/blob/main/adapters/uniapp/js_sdk/index.d.ts) for category and action options, and the [notification contract](https://github.com/sandroxy/avvessa/blob/main/doc/contract.md#notifications-and-platform-limits) for platform behavior and limits.
