export type JsonValue = null | boolean | number | string | JsonValue[] | { [key: string]: JsonValue };
export type JsonObject = { [key: string]: JsonValue };
export interface PushError { code: string; message: string; nativeCode: number | null; nativeDomain?: string | null }
export class AvvessaError extends Error implements PushError {
  code: string; nativeCode: number | null; nativeDomain: string | null;
  constructor(payload: Partial<PushError>);
}
export interface PushConfiguration {
  appKey: string;
  channel?: string;
  debug?: boolean;
  maxPendingEvents?: number;
  eventRetentionMilliseconds?: number;
  android?: {
    smallIcon?: string;
    color?: string;
    notificationChannelId?: string;
    notificationChannelName?: string;
    presentInForeground?: boolean;
    openApplicationOnClick?: boolean;
  };
  /** Required when initializing iOS. Must match the application's signed APNs entitlement. */
  ios?: { apnsEnvironment: 'development' | 'production'; foregroundPresentation?: ('banner' | 'list' | 'sound' | 'badge')[] };
}
export interface PushState {
  consentGranted: boolean;
  initialized: boolean;
  registrationId: string | null;
  connected: boolean | null;
  stopped: boolean | null;
  notificationPermission: 'authorized' | 'denied' | 'notDetermined' | 'provisional' | 'unknown';
  droppedEvents: number;
  lastError: { code: string; nativeCode: number | null } | null;
  apnsRegistered?: boolean;
}
export interface PushEvent {
  id: string;
  type: 'notificationReceived' | 'notificationOpened' | 'notificationDismissed' | 'notificationSuppressed' | 'customMessage' | 'voipMessage';
  messageId: string | null;
  platform: 'android' | 'ios';
  source: string;
  receivedAt: number;
  title: string | null;
  body: string | null;
  data: JsonObject | null;
  raw: JsonObject;
  replayed: boolean;
  transportId?: string | null;
  action?: { identifier: string; userText?: string } | null;
}
export type PushObservation = { kind: 'event'; event: PushEvent } | { kind: 'state'; state: PushState } | { kind: 'error'; error: PushError };
export interface NotificationChannel {
  id: string; name: string; importance?: 0 | 1 | 2 | 3 | 4 | 5;
  description?: string; sound?: boolean; vibration?: boolean; showBadge?: boolean;
}
export interface NotificationCategory {
  identifier: string; customDismissAction?: boolean;
  actions: {
    identifier: string; title: string; foreground?: boolean; destructive?: boolean; authenticationRequired?: boolean;
    textInput?: { buttonTitle: string; placeholder?: string };
  }[];
}
export function getState(): Promise<PushState>;
export function getCapabilities(): Promise<JsonObject>;
export function initialize(configuration: PushConfiguration): Promise<void>;
export function setConsentGranted(granted: boolean): Promise<void>;
/** Settings refresh is asynchronous on iOS; an updated state observation follows. */
export function refreshState(): Promise<PushState>;
/** Android resolves after requesting the prompt (granted=null); observe/refetch state after the user's decision. */
export function requestNotificationPermission(): Promise<{ requested: boolean; granted: boolean | null }>;
export function openNotificationSettings(): Promise<void>;
export function getPendingEvents(): Promise<PushEvent[]>;
export function acknowledge(eventId: string): Promise<boolean>;
export function clearPendingEvents(): Promise<void>;
/** Resolves once the native observer is installed, before any replay callbacks. */
export function subscribe(listener: (message: PushObservation) => void, options?: { replayPending?: boolean }): Promise<string>;
/** Only removes the subscription owned by this bridge; never stops push. */
export function unsubscribe(subscriptionId: string): Promise<void>;
export function stopPush(): Promise<void>;
export function resumePush(): Promise<void>;
export function setBadge(badge: number): Promise<void>;
/** Pass no options to remove all delivered notifications. Identifiers for only the other platform are rejected. */
export function clearNotifications(options?: { androidIds?: number[]; iosIdentifiers?: string[] }): Promise<void>;
export function createNotificationChannel(channel: NotificationChannel): Promise<void>;
export function setNotificationCategories(categories: NotificationCategory[]): Promise<void>;
export function setAlias(alias: string): Promise<JsonObject>;
export function getAlias(): Promise<JsonObject>;
export function deleteAlias(): Promise<JsonObject>;
export function setTags(tags: string[]): Promise<JsonObject>;
export function addTags(tags: string[]): Promise<JsonObject>;
export function deleteTags(tags: string[]): Promise<JsonObject>;
export function getTags(): Promise<JsonObject>;
export function clearTags(): Promise<JsonObject>;
export function checkTag(tag: string): Promise<JsonObject>;
