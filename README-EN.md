# Avvessa

[简体中文](README.md)

Avvessa provides native Android and iOS push APIs on top of the official Jiguang SDKs. Initialize with an AppKey, configure APNs or the required Android vendor channels, and consume notifications, custom messages, and notification interactions.

The core has no React Native, Expo, or DCloud dependency. Authentication, account binding, navigation, and server-side sending belong to the application. UNI adapters support legacy nativeplugins, classic UTS, and uni-app x. RN, Flutter, and incoming-call APIs are not provided.

## Native capabilities

- Explicit consent and initialization, with separate system notification authorization.
- RegistrationID, connection and stopped state, notification authorization, and structured errors.
- Durable native events with subscriptions, replay, deduplication, and explicit acknowledgement.
- Android small icons, colors, notification channels, and foreground presentation policy.
- iOS APNs, foreground presentation, notification actions, badges, and delegate forwarding.
- Alias and tag operations, push stop/resume, and delivered-notification removal.
- Optional Xiaomi, Huawei, OPPO, vivo, HONOR, Meizu, and FCM modules.

Persistent connections, APNs, and vendor channels have different delivery conditions. System presentation does not imply an application callback. Event storage recovers only events that the operating system or SDK has actually delivered to the application.

## Integration

| Platform | Minimum OS | Entry point |
| --- | --- | --- |
| Android | Android 5.0 / API 21 | [Java / Kotlin guide](native/android/README.md), AAR and Maven metadata |
| iOS | iOS 13 | [Swift / Objective-C guide](native/ios/README.md), Swift Package Manager or CocoaPods source integration |
| UNI legacy / UTS / uni-app x | Native core and DCloud host requirements both apply | [Android/iOS integration and packaging](adapters/uniapp/README.md) |

An AppKey does not replace platform setup. iOS requires APNs configuration and signing capabilities matching the Bundle ID. Android vendor channels require the corresponding vendor application settings. Never embed the server-side Master Secret in a client.

Source integration and local build commands are available in this repository. [plugin.json](plugin.json) declares package and SDK versions; a source version is not evidence of publication to a public package registry.

- [Event, state, and lifecycle contract](doc/contract.md)
- [Native architecture](doc/architecture.md)
- [Local builds and checks](BUILDING.md)
- [Third-party dependencies and privacy resources](THIRD_PARTY_NOTICES.md)

## License

Avvessa code is licensed under [Apache-2.0](LICENSE). Jiguang and vendor SDKs retain their own terms.
