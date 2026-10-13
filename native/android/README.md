# Android integration

Avvessa supports API 21 and later. Compile the library with JDK 17 and API 36. The base artifact depends on the official native JPush and JCore SDKs and AndroidX Core; it does not require RN, Expo or UNI.

## Install the native artifact

The Maven Central coordinates for this release are:

```gradle
repositories { google(); mavenCentral() }
dependencies {
    implementation "io.github.sandroxy:avvessa:1.0.0"
    // Add only the transports configured for this application:
    // implementation "io.github.sandroxy:avvessa-xiaomi:1.0.0"
}
```

Use the version listed in [GitHub Releases](https://github.com/sandroxy/avvessa/releases) after the corresponding Maven deployment is public. Optional modules are `avvessa-xiaomi`, `avvessa-huawei`, `avvessa-oppo`, `avvessa-vivo`, `avvessa-honor`, `avvessa-meizu` and `avvessa-fcm`; each carries its pinned SDK dependencies. Huawei and Honor additionally require their official Maven repositories, as shown in this repository's `settings.gradle`.

For local source development, `./gradlew publish` in `native/android` writes only to `native/android/build/maven`. Add that directory as a local Maven repository in the consumer. No credentials or remote upload are involved. The [build guide](../../BUILDING.md) describes the toolchain.

Release AAR mirrors have the same bytes as Maven Central. Keep the POM/Gradle metadata and transitive dependencies; copying a bare AAR is not a complete SDK installation.

## Consent and initialization

Run consent changes, initialization and permission requests on the main thread. Callbacks always return on the main thread. Initialize in the application's primary process:

```java
if (Avvessa.isMainProcess(application)) {
    Avvessa push = Avvessa.getInstance(application);

    // The application's consent UI calls this only after the user agrees.
    push.setConsentGranted(true);
    push.initialize(new PushConfiguration.Builder(appKeyFromLocalConfiguration).build());
}
```

The AppKey is the 24-character hexadecimal key from the Jiguang console for this application. No AppKey manifest placeholder is needed for the base module. If the host also sets `JPUSH_APPKEY` in its manifest, it must match the runtime configuration.

On subsequent application launches, read `push.getState().consentGranted` and initialize only if consent remains granted. Do not turn a saved denial into a new grant. Granting consent alone does not initialize push.

Configuration defaults: channel `default`, notification channel `avvessa` / `Notifications`, a white bell small icon, foreground presentation enabled, opening the launcher on click enabled, SDK debug logging disabled. `openApplicationOnClick(false)` records clicks without requesting launcher navigation.

New default notification channels use `IMPORTANCE_HIGH`. Previously created channels keep their system settings; reinstall a test app or use a new channel ID when testing a different importance or sound.

Opening the application restores its existing launcher task without clearing its pages. If no such task exists, Avvessa starts the application's declared launcher. This supports hosts whose launcher forwards to another Activity, including notification clicks that JPush has already used to start the host. Server-defined notification destinations are handled by JPush independently of Avvessa's launcher option.

The SDK's auto-init provider is removed. The primary-process provider restores saved consent without replacing the host's explicit initialization. The private `:pushcore` provider also restores the previously initialized SDK configuration before service work, so vendor notification clicks and independent service-process restarts retain the runtime AppKey. Restoration requires both saved consent and a valid saved configuration. Privacy withdrawal blocks restoration until a subsequent explicit initialization, or an explicit resume on an already initialized client. Keep those providers, the SDK process name and Avvessa's receiver/service intact.

## Observe and acknowledge

```java
Avvessa.Subscription subscription = push.subscribe(new Avvessa.Listener() {
    @Override public void onEvent(PushEvent event) {
        // Persist or finish the application's work before acknowledging it.
        handleInYourApplication(event.type, event.getData(), event.getRaw());
        push.acknowledge(event.id);
    }

    @Override public void onStateChanged(PushState state) {
        showRegistrationAndPermission(state);
    }

    @Override public void onError(PushException error) {
        showDiagnostic(error.code, error.nativeCode);
    }
}, true);

// At the end of this observer's lifetime:
subscription.remove();
```

A subscription holds its listener until removal. Remove Activity-owned listeners when that Activity is destroyed. Acknowledge only after successful application handling; an unacknowledged event can replay after restart. The `true` argument requests pending replay. Full semantics are in the [native contract](../../doc/contract.md).

## Permission, channels and presentation

Request Android 13+ notification authorization from a visible Activity after explaining its purpose:

```java
push.requestNotificationPermission(activity, 4100);
```

Call `push.refreshState()` after the permission result and after returning from system settings. On earlier systems the request does not show a prompt. `push.openNotificationSettings()` opens the application's system settings.

Small icons must be notification-compatible drawable or mipmap resources: use a white alpha silhouette on a transparent background, not an adaptive launcher icon. Keep the resource name available across updates; the configuration stores the name instead of a build-specific integer. Android system and vendor renderers can differ. No location, phone, storage, microphone, call or full-screen-intent permission is requested by the base integration.

Set an application icon through `new PushConfiguration.Builder(appKey).smallIcon(R.drawable.ic_stat_notification)`. The bundled bell is used when no custom small icon is selected. Use `.notificationChannel("messages", "Messages")` to choose a channel and `.presentInForeground(false)` to disable local foreground presentation.

The library manifest removes inherited location, phone-state, package-query and external-storage permissions. If another application feature intentionally needs one of those permissions, declare it explicitly in the higher-priority app manifest with `tools:node="merge"`. Inspect the final merged manifest; adding a different SDK is not an application consent decision.

For custom sound and importance, create the channel before initializing with its ID:

```java
if (Build.VERSION.SDK_INT >= 26) {
    NotificationChannel channel = new NotificationChannel(
        "messages", "Messages", NotificationManager.IMPORTANCE_HIGH);
    channel.setSound(soundUri, new AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION).build());
    push.createNotificationChannel(channel);
}
```

Package custom sounds as consumer resources. Channel sound/importance become system-managed after creation; a new value under the same ID does not override user settings. For Android 5–7, the SDK's notification alert settings control sound. `color(int)` takes an actual ARGB color, not a color resource ID.

`presentInForeground(false)` asks the SDK not to display locally handled notifications while the app process is foreground. This is evaluated before local display. It cannot suppress a notification already rendered by a manufacturer's system service. A server-provided channel ID is used when that channel exists locally; otherwise the configured default is used.

For selective suppression, pass a validated `NotificationPolicy` and optional `NotificationLifecycle` to the configuration builder:

```java
JSONObject rule = new JSONObject().put("activeContext", new JSONObject()
    .put("match", new JSONObject().put("kind", "chat")).put("idField", "conversationId"));
PushConfiguration configuration = new PushConfiguration.Builder(appKeyFromLocalConfiguration)
    .presentationPolicy(new NotificationPolicy(rule))
    .notificationLifecycle(new NotificationLifecycle(new JSONObject().put("clearBadgeOnOpen", true)))
    .build();
push.initialize(configuration);
// Screen entry after initialization:
push.setActiveNotificationContext(new JSONObject().put("id", "conversation-a"));
// Screen leave, including a cached screen becoming hidden:
push.clearActiveNotificationContext();
```

Constructing these JSON objects can throw `JSONException`; handle it alongside invalid configuration. Policy and lifecycle types are immutable copies. Suppression preserves a `notificationReceived` event with presentation metadata. An Application lifecycle observer handles background-to-foreground cleanup without treating Activity navigation as another entry. See the [shared policy contract](../../doc/contract.md#foreground-policy).

Native channels also support `setVibrationPattern`, `enableLights`, `setLightColor` and `setShowBadge` before `createNotificationChannel`. Package bitmap icons under Android density directories or supply a vector drawable. For FCM defaults, the host can override `com.google.firebase.messaging.default_notification_icon` and `com.google.firebase.messaging.default_notification_color` resource metadata; the optional module otherwise uses the bundled icon.

## Optional manufacturer transports

Add only the modules needed by the application. All Avvessa modules must use the same package version.

| Artifact suffix | Required application configuration |
| --- | --- |
| `avvessa-xiaomi` | `XIAOMI_APPID`, `XIAOMI_APPKEY` |
| `avvessa-huawei` | Matching `agconnect-services.json`, AGConnect Gradle configuration, signing fingerprint |
| `avvessa-oppo` | `OPPO_APPID`, `OPPO_APPKEY`, `OPPO_APPSECRET` |
| `avvessa-vivo` | `VIVO_APPID`, `VIVO_APPKEY` |
| `avvessa-honor` | `HONOR_APPID` |
| `avvessa-meizu` | `MEIZU_APPID`, `MEIZU_APPKEY` |
| `avvessa-fcm` | Matching `google-services.json` and Google Services Gradle configuration |

Example for Xiaomi, with values obtained from local Gradle properties:

```groovy
dependencies {
    implementation "io.github.sandroxy:avvessa-xiaomi:1.0.0"
}
android {
    defaultConfig {
        manifestPlaceholders += [
            XIAOMI_APPID: providers.gradleProperty("xiaomiAppId").get(),
            XIAOMI_APPKEY: providers.gradleProperty("xiaomiAppKey").get()
        ]
    }
}
```

Missing placeholders fail the application build. Known empty or unresolved native metadata fails initialization. Huawei and FCM service files must match the actual application ID, and their Gradle plugins must process those files.

Huawei additionally requires `https://developer.huawei.com/repo/`; HONOR requires `https://developer.hihonor.com/repo/` in the consumer's Maven repositories. See the official [Huawei setup](https://developer.huawei.com/consumer/en/codelab/HMSPreparation/index.html), [HONOR guide](https://developer.honor.com/en/doc/guides/101080) and [Firebase setup](https://firebase.google.com/docs/android/setup) for their own application configuration.

Configure the same vendor application and credentials in the Jiguang console. Selecting a dependency does not enable a cloud channel. Some manufacturers require approved message categories and cloud channel IDs. Application signing fingerprints and package names must match the console records.

The FCM module removes `FirebaseInitProvider`, disables default analytics/data collection metadata, and relies on the JPush adapter to initialize Firebase after explicit push initialization. If the application uses Firebase independently, it owns that initialization and consent policy too. The OPPO module excludes its incoming-call service.

Factory offline notification icons and click behavior require manufacturer-specific console/server settings; a local `smallIcon` cannot rewrite a system-rendered offline notification. Keep the SDK's `JNotifyActivity`, `JLocalNotifyActivity` and `JTransitActivity` manifest declarations. The application receives their SDK callbacks through Avvessa.

`getCapabilities().vendorModules` reports included modules only. Validate actual registration and delivery on the target devices.

## Other operations

`getState()`, `getPendingEvents()`, `clearPendingEvents()`, `stopPush()`, `resumePush()`, `setBadge(int)`, `clearNotification(int)` and `clearAllNotifications()` are independent operations. Badge support varies by launcher.

Notification removal uses Android's NotificationManager and remains available after consent withdrawal. Removing all affects this app's delivered notifications, including notifications posted by other features; it does not erase stored events.

Alias APIs: `setAlias`, `getAlias`, `deleteAlias`. Tag APIs: `setTags`, `addTags`, `deleteTags`, `getTags`, `clearTags`, `checkTag`. Each takes a completion callback with a JSON result or `PushException`; asynchronous SDK results time out after 30 seconds.

Use `setConsentGranted(false)` for consent withdrawal. It clears Avvessa's stored events and RegistrationID and asks the SDK to stop. Account logout is an application decision; it does not implicitly mean privacy withdrawal or SDK shutdown.

## Device verification

Use a real AppKey and the signed application registered in the consoles. Record the device model, OS, application ID, RegistrationID, permission and connection state. Manually send a notification and a custom message, then inspect foreground, background, process recreation and notification click behavior. Repeat with each selected manufacturer's offline channel. Build success alone cannot establish any of those outcomes.
