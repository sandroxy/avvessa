# Native contract

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

iOS also includes `transportId` when a notification request ID is available and `action` for responses. Action metadata has `identifier` and, for a text response, `userText`; it does not overwrite payload keys.

Normal event types are `notificationReceived`, `notificationOpened`, `notificationDismissed` and `customMessage`. Android can additionally report `notificationSuppressed` and the SDK's raw `voipMessage` transport event. The latter does not provide incoming-call handling, UI, CallKit or PushKit.

On Android, `raw` snapshots the pinned SDK message object's public JSON-compatible fields; it is not the original HTTP request or an Android Context/Parcelable serialization. The extras string is retained verbatim. On iOS, `raw` is the SDK's userInfo dictionary. Malformed extras produce `invalid_payload` without removing the original extras from `raw`. Payloads that cannot be encoded as JSON are rejected explicitly.

Registration and connection changes are state observations, not durable message events. Read state when subscribing or returning to the foreground.

## Persistence and acknowledgement

Events are persisted before dispatch. Subscription and replay registration are coordinated with insertion. Callbacks run on the main thread and observers must return promptly. Acknowledgement is global to the event store: designate one application consumer to acknowledge work, while diagnostic observers only observe.

Defaults and limits:

| Limit | Value |
| --- | --- |
| Pending events | 256, configurable from 1 to 1,000 |
| Retention | 72 hours, configurable from 1 minute to 30 days |
| Encoded event size | 256 KiB |
| Encoded pending payloads | 4 MiB |
| Deduplication window | 60 seconds |
| Deduplication identities | At most 5,000 |

Count, byte and age eviction removes the oldest applicable events and increments `droppedEvents`. Oversized events fail with `event_too_large`. Storage overhead is additional to the encoded payload limit.

Deduplication uses phase, source and a supplied message identity. iOS can use a notification request ID when supplied. Action identifiers distinguish iOS responses. Matching text is never an identity. Receive and open are distinct events; a missing reliable ID does not cause content-based deduplication.

Acknowledgement removes a pending event but retains its short-lived deduplication identity. It is not a server receipt. If a process exits after business handling but before acknowledgement, replay is expected; the application must make its own side effects idempotent. Already queued callbacks may still run after acknowledgement.

Revoking consent clears pending events and RegistrationID and cancels pending SDK command callbacks. Previously scheduled event callbacks cannot reappear after a revoke/regrant cycle. It does not remotely retract a push already handed to APNs or a manufacturer, or erase all data held internally by the SDK.

## Notifications and platform limits

Android's local small icon and foreground policy apply to notifications constructed inside the app. Factory-managed system notifications may use manufacturer resources, categories and cloud settings. Android channel importance and sound are controlled by the system after channel creation; recreating the same ID does not reset the user's settings.

The Android core includes a default `jpush_notification_icon` resource for the SDK's initial notification construction, including hosts without an application icon. The configured `smallIcon` is then applied by Avvessa's notification builder. A host can override the default drawable resource.

iOS only records a background receive event when iOS actually calls the app. Ordinary APNs alerts can be displayed without waking application code. A subsequent click can therefore appear without a preceding receive event in the queue. Silent notifications and force-stopped applications have platform restrictions.

A payload or deep link never authorizes login, navigation or another business action. The application interprets it after any necessary authentication.

## Errors and operations

Android errors are `PushException` with `code`, optional `nativeCode` and the underlying cause. iOS uses `AVPushErrorDomain`, `AVPushErrorCodeKey` and `AVPushNativeErrorCodeKey`. Original platform errors may be attached as the underlying error.

Common categories include `invalid_argument`, `invalid_configuration`, `configuration_conflict`, `consent_required`, `not_initialized`, `host_unavailable`, `unsupported`, `invalid_payload`, `invalid_data`, `storage_corrupt`, `storage_write`, `event_too_large`, `sdk_error` and `timeout`.

Alias/tag callbacks have a 30-second deadline and are correlated by operation identity. iOS SDK-backed push controls and badge operations use the same deadline. A timeout is a local completion boundary, not proof that a remote SDK side effect was rolled back. Alias/tag values require 1–40 UTF-8 bytes, with 1–1,000 tags per mutation; use the explicit clear operation to remove all tags. Further server validation remains authoritative.
