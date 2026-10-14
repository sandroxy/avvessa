# Avvessa

[简体中文](README.md)

Avvessa provides native Android and iOS push APIs on top of the official Jiguang SDKs. Initialize with an AppKey, configure APNs or the required Android vendor channels, and consume notifications, custom messages, and notification interactions.

Supported integrations include native Android and iOS, UNI legacy, classic UTS, uni-app x, and React Native on both mobile platforms. Authentication, account binding, navigation, and server-side sending belong to the application.

## Capabilities

- Explicit consent and initialization, with separate system notification authorization.
- RegistrationID, connection and stopped state, notification authorization, and structured errors.
- Durable native events with subscriptions, replay, deduplication, and explicit acknowledgement.
- Android small icons, colors, notification channels, sound, vibration and lights.
- Foreground presentation rules based on message fields and the current application context, preserving receipt events.
- iOS APNs, foreground presentation, notification actions, badges, and multiple cooperating delegates.
- Optional badge and delivered-notification cleanup at application lifecycle boundaries.
- Alias and tag operations, push stop/resume, and delivered-notification removal.
- Optional Xiaomi, Huawei, OPPO, vivo, HONOR, Meizu, and FCM modules.

Persistent connections, APNs, and vendor channels have different delivery conditions. System presentation does not imply an application callback. Event storage recovers only events that the operating system or SDK has actually delivered to the application.

## Integration

| Platform | Integration guide |
| --- | --- |
| Android | [Java / Kotlin](native/android/README.md): Maven dependencies, notification settings, and optional vendor channels |
| iOS | [Swift / Objective-C](native/ios/README.md): SPM, XCFramework, APNs, and lifecycle forwarding |
| React Native / Expo | [RN integration (Chinese)](adapters/react-native/README.md): npm installation, autolinking, native configuration, and event consumption |
| UNI legacy / classic UTS / uni-app x | [UNI integration](adapters/uniapp/README.md): installation, consumer configuration, and JavaScript / UTS APIs |

An AppKey does not replace platform setup. iOS requires APNs configuration and signing capabilities matching the Bundle ID. Android vendor channels require the corresponding vendor application settings. Never embed the server-side Master Secret in a client.

Choose a version from [GitHub Releases](https://github.com/sandroxy/avvessa/releases). The default branch maintains integration guides and documentation corrections for released features; consult the corresponding tag for historical APIs and dependencies. [plugin.json](plugin.json) maintains package and SDK versions; each platform guide describes minimum system and host requirements.

- [Event, state, and lifecycle contract](doc/contract.md)
- [Third-party dependencies and privacy resources](THIRD_PARTY_NOTICES.md)
- [Changelog](CHANGELOG.md)

Development and maintenance: [architecture, builds, and checks](DEVELOPMENT.md) · [publication](RELEASING.md).

## License

Avvessa code is licensed under [Apache-2.0](LICENSE). Jiguang and vendor SDKs retain their own terms.
