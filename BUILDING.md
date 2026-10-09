# Local builds and checks

Builds download the pinned SDK dependencies. They do not require AppKeys or sending credentials. Build commands do not start a device or send a push.

## Android

Use JDK 17 and an Android SDK with API 36. Configure `JAVA_HOME` and `ANDROID_HOME`, or use an ignored `native/android/local.properties` for `sdk.dir`.

```sh
cd native/android
./gradlew :avvessa:testDebugUnitTest :avvessa:lintRelease assembleRelease publish
```

`publish` writes only to `native/android/build/maven`. It does not publish to a remote registry. The directory contains the base and optional vendor artifacts, POMs, source jars and Gradle metadata. Consumers should use Maven metadata instead of copying only an AAR and losing transitive dependencies.

The storage tests run with SDK startup components excluded and do not initialize JPush. They cover persistence, acknowledgement, phase deduplication, unidentified messages, eviction, concurrent creation and privacy withdrawal.

The checked-in Gradle wrapper and Android Gradle Plugin form the supported local build baseline. The official JPush and some vendor jars emit R8 stack-map warnings with this toolchain. Do not hide those warnings or treat compilation as device validation; verify release builds when changing the SDK or shrinker.

## iOS

Use Xcode with the iOS SDK. From the repository root:

```sh
xcodebuild -list
xcodebuild -scheme Avvessa -destination 'generic/platform=iOS' \
  -derivedDataPath .build/ios-device CODE_SIGNING_ALLOWED=NO build
xcodebuild -scheme Avvessa -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath .build/ios-simulator CODE_SIGNING_ALLOWED=NO build
./scripts/check-ios-storage.sh
pod ipc spec Avvessa.podspec
```

The storage script compiles only the event/configuration types and store against macOS Foundation and UserNotifications. It does not load JPush, launch a simulator, register APNs or establish a push connection. This is a storage check, not macOS product support.

For CocoaPods consumer builds, use the source podspec described in the [iOS guide](native/ios/README.md). Swift Package Manager and CocoaPods are alternative dependency paths; do not install both into the same target.

## Dependency metadata and privacy resources

```sh
python3 scripts/check-metadata.py
python3 scripts/ios-privacy.py \
  --jpush-sdk /path/to/pinned/jpush-sdk-checkout \
  --jcore-sdk /path/to/pinned/jcore-sdk-checkout --check
```

The privacy checker expects the SDK versions declared in `plugin.json` and verifies both device and simulator declarations. After an intentional SDK update, review upstream declarations and run the same command without `--check` to regenerate the bundled union.

Public files describe supported behavior and reproducible build procedures. Local machine configuration, generated consumers, SDK downloads, credentials and build evidence are excluded from Git.
