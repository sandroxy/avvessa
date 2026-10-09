# iOS integration

Avvessa supports iOS 13 and later and exposes Objective-C and Swift APIs. It directly depends on the official JPush and JCore packages.

## Install

For local source development, add this repository's root directory as a local Swift package in Xcode and select the `Avvessa` product. Commit the consumer's resolved package lock when using a published Git revision. The root `Package.swift` pins both official SDKs.

Keep `$(inherited) -ObjC` in the application's Other Linker Flags so Objective-C SDK categories are linked from the static libraries.

Alternatively use the local CocoaPods specification:

```ruby
platform :ios, '13.0'
target 'YourApplication' do
  pod 'Avvessa', :path => ENV.fetch('AVVESSA_SOURCE')
end
```

`AVVESSA_SOURCE` is the checkout's absolute directory, supplied locally. Select one dependency manager per app target. Do not add another JPush framework wrapper or initialize JPush separately.

The package bundles the pinned SDKs' combined privacy declarations as a resource. Check the application's archive for `PrivacyInfo.xcprivacy` before distribution. The application retains responsibility for its own privacy manifest and disclosures.

## Configure the application

1. Register the exact Bundle ID with Apple and the Jiguang console.
2. Enable Push Notifications in the app target's Signing & Capabilities and use a provisioning profile containing the matching `aps-environment`.
3. Configure the matching APNs certificate or authentication key in Jiguang's console.
4. Pass `production: false` for the APNs development environment, or `true` for production. Match the actual signing entitlement; build configuration names alone do not establish the APNs environment.
5. If the application handles background remote notifications, enable Background Modes → Remote notifications. Ordinary alert display does not imply background execution.

Keep APNs private keys and the Jiguang Master Secret outside client source. No PushKit, CallKit, microphone or tracking authorization is required by this integration.

## Initialize and observe

All public methods and observer callbacks use the main thread. Set up notification delegation during application launch so cold-start responses can be forwarded.

```swift
import Avvessa
import UserNotifications

let push = AVPushClient.shared

// Use this assignment only when the application has no other notification delegate.
UNUserNotificationCenter.current().delegate = push

// Call this grant only from the application's explicit consent flow.
try push.setConsentGranted(true)
let configuration = AVPushConfiguration(appKey: appKeyFromLocalConfiguration, production: false)
try push.initialize(with: configuration, launchOptions: launchOptions)
```

On subsequent launches, initialize with the saved consent decision; do not grant again merely because the application launched. Pass the application's actual launch options. Configuration is copied and cannot be changed after initialization within that process.

Example observer:

```swift
final class PushObserver: NSObject, AVPushObserver {
    func pushClient(_ client: AVPushClient, didReceive event: AVPushEvent) {
        // Complete or persist application handling before acknowledging.
        handleInYourApplication(event.dictionary)
        do { _ = try client.acknowledgeEvent(event.eventID) }
        catch { showDiagnostic(error) }
    }
}

let observer = PushObserver()
let subscription = push.addObserver(observer, replayPending: true)
// Keep the subscription for its intended lifetime.
push.removeObserver(subscription)
```

The client retains observers until removal. Removing the observer does not stop push. Use `state` to inspect consent, RegistrationID, connection and permission separately. See the [event contract](../../doc/contract.md) for replay and acknowledgement semantics.

## Forward APNs registration

Your application delegate must forward the system token and registration errors:

```swift
func application(_ application: UIApplication,
                 didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
    AVPushClient.shared.registerDeviceToken(deviceToken)
}

func application(_ application: UIApplication,
                 didFailToRegisterForRemoteNotificationsWithError error: Error) {
    AVPushClient.shared.didFailToRegisterForRemoteNotifications(withError: error)
}
```

Request notification authorization from an explicit application action:

```swift
push.requestNotificationAuthorization([.alert, .badge, .sound]) { result, error in
    // Show the actual result or error.
}
```

Initialization queries existing settings and registers with APNs if permission was already granted. It does not show a prompt. Forwarded APNs registration proves token submission in this process, not successful Jiguang delivery. Call `refreshNotificationSettings()` when returning from system settings.

## Cooperate with an existing notification delegate

Avvessa does not replace or swizzle delegates. If the host already has a `UNUserNotificationCenterDelegate`, retain it and forward JPush notifications using:

- `canHandleRemoteNotification(_:)`
- `handleForegroundNotification(_:)`, which returns presentation options
- `handleNotificationResponse(_:)`

`canHandleRemoteNotification` recognizes the documented JPush `_j_msgid` marker. Forwarding ignores notifications without it. When assigned as the sole delegate, unrelated foreground notifications receive standard system presentation options and are not persisted by Avvessa.

The host owns each system completion handler and must invoke it exactly once. If the host delegate receives a callback off the main thread, dispatch the Avvessa call to the main thread first. Route notifications owned by other components to their own handlers.

For a UIKit app using scenes, also forward a cold-start response supplied through scene connection options:

```swift
func scene(_ scene: UIScene, willConnectTo session: UISceneSession,
           options connectionOptions: UIScene.ConnectionOptions) {
    if let response = connectionOptions.notificationResponse {
        AVPushClient.shared.handleNotificationResponse(response)
    }
    // Continue the host's normal window and scene setup.
}
```

If both system entry points supply the same response within the deduplication window, its phase and message identity prevent a second queued record.

If the host implements `application(_:didReceiveRemoteNotification:fetchCompletionHandler:)`, forward owned payloads with `handleRemoteNotification(_:)` on the main thread and complete the system callback. Avvessa performs no application content fetch, so forwarding alone should not be reported as new downloaded content.

A click response is persisted as `notificationOpened`; dismiss actions use `notificationDismissed`. Custom categories and text responses retain action information beside the original payload. The host remains responsible for navigation.

## Presentation and notification controls

`foregroundPresentationOptions` configures native foreground presentation before delivery to observers. The default requests alert/sound/badge on iOS 13 and list/banner/sound/badge on newer systems. An empty option set suppresses foreground presentation for owned notifications.

Notification icons use the application's iOS app icon. Include custom sounds in the host app bundle and request their file names in the APNs payload. Categories registered through `setNotificationCategories` are merged by identifier with existing system categories; a host category using the same identifier is replaced.

Other APIs:

- `stopPushWithCompletion:` and `resumePushWithCompletion:` for SDK transport control.
- `setBadge:completion:` for both JPush's badge state and the application icon.
- `removeDeliveredNotificationsWithIdentifiers:` and `removeAllDeliveredNotifications` for system notification removal.
- `pendingEventsWithError:`, `acknowledgeEvent:error:`, `clearPendingEventsWithError:` for the native event store.
- Alias and tag methods declared in [Avvessa.h](Sources/Avvessa/include/Avvessa.h).

The delivered-notification removal methods act on this app's notification center, including notifications from other features when removing all. Clearing delivered notifications does not acknowledge events. Clearing event storage does not remove delivered notifications.

## Device verification

Use a signed physical device build with the intended APNs entitlement and Jiguang configuration. Inspect RegistrationID and authorization before manually sending from the console. Check foreground presentation, background alert display, notification clicks after process recreation, custom messages while connected, acknowledgement/replay, and stop/resume behavior.

Simulator compilation confirms only the simulator binary path. It cannot establish APNs configuration, real-device SDK registration, offline delivery or App Store acceptance.
