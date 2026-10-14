# Push behavior and event contract

This contract defines shared behavior for the native APIs and UNI adapters. Platform setup and API syntax belong to the [Android](../native/android/README.md), [iOS](../native/ios/README.md) and [UNI](../adapters/uniapp/README.md) guides.

## Initialization and state

Grant consent only after the application's own consent flow. Granting it does not initialize JPush or prompt for notification permission. Explicit initialization requires consent and a valid configuration.

Initialization is idempotent for the same configuration within a process. A different configuration fails. The persisted AppKey cannot change within the same application data container; iOS also treats the APNs environment as part of that identity. Reinstall a test application when changing identity.

A state snapshot contains:

| Field | Meaning |
| --- | --- |
| `consentGranted` | The application's persisted consent decision |
| `initialized` | This process completed the SDK initialization call |
| `registrationId` | Last observed RegistrationID, or null |
| `connected` | Observable SDK connection state, or null when unavailable |
| `stopped` | Observable SDK stopped state, or null when unavailable |
| `notificationPermission` | Platform notification authorization |
| `droppedEvents` | Cumulative events evicted by count, size or age limits |
| `lastError` | Latest recorded diagnostic code and optional SDK code, or null |
| `apnsRegistered` | iOS only: a token was forwarded in this process |

Initialization, registration, connection and notification authorization are distinct facts. A cached RegistrationID is not a delivery receipt. Android reports effective authorization as `authorized` or `denied`; `denied` does not prove that a permission prompt has already been shown. iOS additionally reports `notDetermined`, `provisional` or `unknown`.

Stopping push calls the platform SDK's stop API. On iOS this requests APNs token unbinding and connection shutdown. Android SDK operations can complete asynchronously even where the SDK exposes a void return; observe state and device behavior. Stopping does not remove existing system notifications, revoke system permission or acknowledge stored events. Resuming after consent withdrawal requires granting consent again and explicitly resuming an already initialized client.

Removing an observer only removes that observer. It does not stop the process-wide SDK.

## Events

| Field | Meaning |
| --- | --- |
| `id` | Stable Avvessa event ID used for acknowledgement |
| `type` | Event phase or kind |
| `messageId` | Original SDK message ID represented as a string, or null |
| `platform` | `android` or `ios` |
| `source` | `apns`, `jpush`, or Android `jpush:<SDK platform code>` |
| `receivedAt` | Local observation time, milliseconds since Unix epoch |
| `title`, `body` | Available display text, or null |
| `data` | Uninterpreted extras dictionary; null if malformed extras were reported |
| `raw` | Native SDK payload snapshot |
| `replayed` | Whether this delivery came from pending storage |
| `presentation` | Where a local presentation decision is observed: `{suppressed, reason, method?}`; absent for callbacks without that decision |
| `notificationId` | Android system notification ID for notification events; may be negative |

iOS also includes `transportId` when a notification request ID is available and `action` for responses. Action metadata has `identifier` and, for a text response, `userText`; it does not overwrite payload keys.

Normal event types are `notificationReceived`, `notificationOpened`, `notificationDismissed` and `customMessage`. A suppressed foreground notification remains `notificationReceived` with `presentation.suppressed: true`; it is not discarded or converted into a custom message. Additional SDK event types are defined in the public types; their original payload is retained in `raw`.

On Android, `raw` snapshots the pinned SDK message object's public JSON-compatible fields; it is not the original HTTP request or an Android Context/Parcelable serialization. The extras string is retained verbatim. On iOS, `raw` is the SDK's userInfo dictionary. Malformed extras produce `invalid_payload` without removing the original extras from `raw`. Payloads that cannot be encoded as JSON are rejected explicitly.

Registration and connection changes are state observations, not durable message events. Read state when subscribing or returning to the foreground.

## Persistence and acknowledgement

Events are persisted before dispatch. Subscription with replay delivers pending events as well as newly received events. Native callbacks run on the main thread; all subscribers should return promptly. Acknowledgement is global to the event store: designate one application consumer to acknowledge work, while diagnostic observers only observe.

Defaults and limits:

| Limit | Value |
| --- | --- |
| Pending events | 256, configurable from 1 to 1,000 |
| Retention | 72 hours, configurable from 1 minute to 30 days |
| Encoded event size | 256 KiB |
| Encoded pending payloads | 4 MiB |
| Deduplication window | 60 seconds |
| Deduplication identities | At most 5,000 |

Configure count and retention before initialization: Android uses `PushConfiguration.Builder.eventRetention(count, ttlMillis)`; iOS uses `AVPushConfiguration.maxPendingEvents` and `eventRetention` in seconds; UNI uses `maxPendingEvents` and `eventRetentionMilliseconds`.

Count, byte and age eviction removes the oldest applicable events and increments `droppedEvents`. Oversized events fail with `event_too_large`. Storage overhead is additional to the encoded payload limit.

Deduplication uses phase and the supplied message identity across transport callbacks. Android system notification IDs and iOS request IDs form fallback aliases, including when the SDK message ID appears in a later callback. Reusing a system ID with a different known message ID remains a new event. Action identifiers distinguish iOS responses. Matching text is never an identity. Receive and open are distinct events; a callback with no identity is not deduplicated by content. A fallback ID alone cannot distinguish two unidentified messages deliberately reusing that ID within the deduplication window.

Acknowledgement and clearing pending events retain short-lived deduplication identities. Neither is a server receipt. If a process exits after business handling but before acknowledgement, replay is expected; the application must make its own side effects idempotent. Already queued callbacks may still run after acknowledgement.

Revoking consent clears pending events and RegistrationID and cancels pending SDK command callbacks. Previously scheduled event callbacks cannot reappear after a revoke/regrant cycle. It does not remotely retract a push already handed to APNs or a manufacturer, or erase all data held internally by the SDK.

## Notifications and platform limits

### Foreground policy

`presentationPolicy` is an immutable initialization option shared by both native cores and all UNI entry points:

```json
{
  "suppressForeground": [
    {"match": {"kind": "quiet"}, "reason": "quietMessage"}
  ],
  "activeContext": {
    "match": {"kind": "chat"},
    "idField": "conversationId",
    "categoryField": "conversationType",
    "reason": "activeConversation"
  }
}
```

These field names and values are examples supplied by the application. Rules read top-level `event.data` fields, never guess aliases or parse nested strings. Every `match` field must match. Optional `platforms` limits a rule to `android` and/or `ios`. Static suppression is evaluated first, then the active-context rule. Missing fields or missing context allow presentation. A global foreground-disable option takes precedence over rules.

`setActiveNotificationContext({id, category})` requires consent and initialization. `clearActiveNotificationContext()` is safe before initialization. Context is copied into process memory; it does not survive process death or consent withdrawal. It is not tied to a subscription. The host must clear it when leaving the relevant screen, including cached-page hide events, and set it again when that screen becomes active. Background notifications are not suppressed by these rules. Runtime context changes affect subsequent decisions, not a notification already being displayed.

Scalar comparisons use canonical text: `1` matches `"1"`, while `"01"` does not. Booleans use `"true"`/`"false"`; null, objects and arrays do not match. Numeric magnitude is limited to JavaScript's safe integer boundary; send long identifiers as strings. Each rule accepts 1–32 match fields, up to 32 static rules, field names and reasons of 1–128 characters, and scalar strings of up to 1,024 characters. Unknown configuration fields fail explicitly.

Default suppression reasons are `rule`, `activeContext` and `foregroundDisabled`; rules can supply their own reason. Android may report `sdk` for a notification suppressed by the SDK without an Avvessa decision. Allowed local presentation reports `{suppressed:false, reason:null}`. This is the application's decision, not proof that the OS actually displayed a banner. On iOS, rules remove visible presentation and sound while retaining the configured badge option. Payload and acknowledgement semantics remain unchanged.

Android reports an additional `presentation.method` when suppressed:

- `blocked`: the SDK skipped posting the notification. The pinned Android JPush SDK invokes its custom pre-display gate when the sender explicitly includes `notification.android.display_foreground: "1"`. The value `"0"` instead applies the SDK's own foreground suppression.
- `silentRemoval`: the SDK bypassed that gate, as it does for ordinary payloads without `display_foreground`. Avvessa applies AndroidX per-notification silence before posting, then removes that notification in the SDK receipt/display callback. This prevents the notification from alerting on compliant Android implementations; a short-lived silent entry may still reach notification history or system notification listeners. It is not a guarantee that no notification was ever posted. The removal is independent of optional lifecycle cleanup.

The normal console payload needs no extra field. Both paths preserve the receipt and freeze the decision before a subsequent page change. Notifications displayed entirely by a manufacturer's service bypass this local policy. See [JPush payload options](https://docs.jiguang.cn/jpush/server/push/rest_api_v3_push).

### Optional lifecycle cleanup

`notificationLifecycle` accepts five independent booleans, all false by default: `clearBadgeOnStart`, `clearBadgeOnForeground`, `clearBadgeOnOpen`, `clearDeliveredOnStart`, `clearDeliveredOnForeground`. Start means the first successful initialization in the current process. Foreground means a return from background, not navigation between screens. Open means a newly captured notification-open event; a cold-start open can defer badge cleanup until initialization. Replay alone does not repeat cleanup.

Badge cleanup uses the native badge API, including JPush badge state on iOS. Delivered cleanup removes all of this application's delivered notifications, including notifications from other components. Enable only the operations the host owns. Cleanup does not acknowledge pending events or clear the active context. SDK failures are reported through error observations. These options never grant consent or request notification authorization.

### Delivery limits

Android icon, channel and manufacturer settings are described in the [Android guide](../native/android/README.md#permission-channels-and-presentation).

iOS only records a background receive event when iOS actually calls the app. Ordinary APNs alerts can be displayed without waking application code. A subsequent click can therefore appear without a preceding receive event in the queue. Silent notifications and force-stopped applications have platform restrictions.

A payload or deep link never authorizes login, navigation or another business action. The application interprets it after any necessary authentication.

## Errors and operations

Android errors are `PushException` with `code`, optional `nativeCode` and the underlying cause. iOS uses `AVPushErrorDomain`, `AVPushErrorCodeKey` and `AVPushNativeErrorCodeKey`. Original platform errors may be attached as the underlying error.

Common categories include `invalid_argument`, `invalid_configuration`, `configuration_conflict`, `consent_required`, `not_initialized`, `host_unavailable`, `unsupported`, `invalid_payload`, `invalid_data`, `storage_corrupt`, `storage_write`, `event_too_large`, `sdk_error` and `timeout`.

Alias/tag callbacks have a 30-second deadline and are correlated by operation identity. iOS SDK-backed push controls and badge operations use the same deadline. A timeout is a local completion boundary, not proof that a remote SDK side effect was rolled back. Alias/tag values require 1–40 UTF-8 bytes, with 1–1,000 tags per mutation; use the explicit clear operation to remove all tags. Further server validation remains authoritative.
