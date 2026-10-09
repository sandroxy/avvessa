# Third-party dependencies

Avvessa's Apache-2.0 license applies to its own implementation. It does not relicense the Jiguang, manufacturer or platform SDKs.

| Dependency | Role | Source |
| --- | --- | --- |
| JPush / JCore Android | Native push and SDK infrastructure | [Jiguang Android documentation](https://docs.jiguang.cn/jpush/client/Android/android_guide), Maven Central under `cn.jiguang.sdk` |
| Jiguang vendor adapters | Optional manufacturer transport integration | Maven Central under `cn.jiguang.sdk.plugin` |
| JPush iOS | Native push | [Official SDK repository](https://github.com/jpush/jpush-sdk) |
| JCore iOS | Native infrastructure | [Official SDK repository](https://github.com/jpush/jcore-sdk) |
| AndroidX Core | Platform notification compatibility | [AndroidX](https://developer.android.com/jetpack/androidx/releases/core), Apache-2.0 |
| Gradle wrapper | Build bootstrap | [Gradle](https://github.com/gradle/gradle), Apache-2.0 |
| JUnit / Robolectric | Isolated local checks | [JUnit](https://github.com/junit-team/junit4), EPL-1.0; [Robolectric](https://github.com/robolectric/robolectric), MIT |

SDK versions are recorded in [plugin.json](plugin.json). Vendor dependencies may bring additional SDKs with their own terms; consult their published metadata and official documentation for distribution requirements.

The JPush and JCore iOS package specifications identify their license as Copyright, owned by Jiguang. Avvessa resolves their original packages and does not check in their binaries.

## iOS privacy resources

`native/ios/Sources/Avvessa/Resources/PrivacyInfo.xcprivacy` is the combined declaration supplied by the pinned JPush and JCore SDKs. It preserves their declared API reasons and collection categories, including optional SDK capabilities that Avvessa disables. It is not a replacement for the application's own privacy disclosures or a statement that every SDK capability runs.

The source manifests are `PrivacyInfo.xcprivacy` in each official XCFramework slice. [The generator](scripts/ios-privacy.py) verifies matching device/simulator declarations and preserves their union. Swift Package Manager and CocoaPods include that file as a resource bundle, so it survives static linking. See [Apple's privacy-manifest packaging documentation](https://developer.apple.com/documentation/bundleresources/adding-a-privacy-manifest-to-your-app-or-third-party-sdk).
