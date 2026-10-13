# Local builds and checks

Builds download the pinned SDK dependencies. They do not require AppKeys or sending credentials. Build commands do not start a device or send a push.

## Android

Use JDK 17 and an Android SDK with API 36. Configure `JAVA_HOME` and `ANDROID_HOME`, or use an ignored `native/android/local.properties` for `sdk.dir`.

```sh
cd native/android
./gradlew :avvessa:testDebugUnitTest :avvessa:lintRelease assembleRelease publish
```

`publish` writes only to `native/android/build/maven`. It does not publish to a remote registry. The directory contains the base and optional vendor artifacts, POMs, source jars and Gradle metadata. Consumers should use Maven metadata instead of copying only an AAR and losing transitive dependencies.

The tests run with SDK startup components excluded. Storage and policy checks do not initialize JPush; the isolated SDK fixtures are described below. The tests cover persistence, acknowledgement, cross-callback identity aliases, reused notification IDs, unidentified messages, eviction, concurrent creation, privacy withdrawal, receipt preservation during suppression, and foreground transition boundaries. Android and iOS policy tests consume the same JSON fixtures.

The Android dispatch regression also calls the pinned JPush notification dispatcher with JCore network operations replaced by a test shadow. It checks ordinary payloads that omit `display_foreground`, the explicit pre-display gate, silence before posting, removal, context changes and the application-provider boundary. The fixture's SDK-internal entry points are test-only and must be reviewed when updating JPush. These checks cannot validate a manufacturer's notification UI.

The process-restoration regression runs the pinned SDK's public initialization and real AppKey validation with service/network dispatch isolated. It covers missing-key diagnostics, service recreation with retained app data, repeated consent synchronization, invalid configuration, consent withdrawal and explicit resume. These fixtures initialize SDK state inside the test sandbox only; they neither register a device nor establish a push connection.

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
./scripts/check-ios-notifications.sh
pod ipc spec Avvessa.podspec
```

The scripts compile event/configuration types, storage, policy and delegate coordination against macOS Foundation and UserNotifications. They do not load JPush, launch a simulator, register APNs or establish a push connection. They check persistence and storage upgrades, selective presentation, delegate ownership, duplicate completion, recursion and timeout behavior. These isolated checks are not macOS product support.

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

## UNI adapters

The [UNI guide](adapters/uniapp/README.md) describes packaging, consumer resources and the separate legacy, classic UTS and uni-app x entry points. `scripts/package-uniapp.py` builds Android AARs and device iOS frameworks, resolves the selected vendor graph, and emits independent legacy/UTS ZIPs. The iOS framework builder also links an unsigned probe to catch missing SDK symbols and Swift runtime dependencies; it does not run that probe.

After packaging, check the bridge contracts and a complete Android dependency merge:

```sh
node --test adapters/uniapp/tests/*.test.mjs
python3 -m unittest discover -s scripts/tests -v
native/android/gradlew -p adapters/android \
  -PavvessaCoreAar="$PWD/native/android/avvessa/build/outputs/aar/avvessa-release.aar" \
  :bridge:testDebugUnitTest
python3 scripts/check-uniapp-android.py dist/uniapp/avvessa-uniapp-1.0.0.zip
```

For a ZIP containing vendor transports, provide both `--configuration-aar` and `--consumer-manifest` from `prepare-uniapp-android.py`. A legacy ZIP additionally requires `--dcloud-android-aar`. The Android check links resources and bytecode and verifies the final manifest's consumer metadata, permission removals and disabled implicit JPush initialization. It creates an unsigned artifact without installing or running it.

`scripts/check-uniapp-uts.py` compiles the packaged Android/iOS UTS transports against local classic and uni-app x SDKs. The separate integration repository also builds all three consumer frontends on both platforms and checks that persistent event callbacks survive DCloud's generated JS/native proxy. These checks do not prove notification delivery or lifecycle behavior on devices.

## Distribution builds

See [RELEASING.md](RELEASING.md) for local signed Central bundles, native XCFrameworks, the source SPM package and UNI archives. Preparation never performs remote publication. Ordinary `publish` remains a local Maven task.
