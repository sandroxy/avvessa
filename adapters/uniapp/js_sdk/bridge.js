export class AvvessaError extends Error {
  constructor(payload) {
    super(typeof payload?.message === 'string' ? payload.message : 'The push bridge failed');
    this.name = 'AvvessaError';
    this.code = typeof payload?.code === 'string' ? payload.code : 'invalid_response';
    this.nativeCode = payload?.nativeCode ?? null;
    this.nativeDomain = payload?.nativeDomain ?? null;
  }
}
const invalid = (message) => new AvvessaError({ code: 'invalid_argument', message });
function encode(value) {
  const seen = new Set();
  function check(item) {
    if (item === null || typeof item === 'string' || typeof item === 'boolean') return;
    if (typeof item === 'number' && Number.isFinite(item)) return;
    if (typeof item !== 'object' || seen.has(item) || (!Array.isArray(item) && Object.getPrototypeOf(item) !== Object.prototype)) {
      throw invalid('Arguments must contain only finite JSON values');
    }
    seen.add(item);
    for (const child of Object.values(item)) check(child);
    seen.delete(item);
  }
  check(value);
  return JSON.stringify(value);
}
function decode(json) {
  let result;
  try { result = JSON.parse(json); } catch { throw new AvvessaError({ code: 'invalid_response', message: 'Native bridge returned invalid JSON' }); }
  if (!result || typeof result !== 'object' || typeof result.ok !== 'boolean') throw new AvvessaError({ code: 'invalid_response', message: 'Native bridge returned an invalid envelope' });
  if (!result.ok) throw new AvvessaError(result.error);
  if (!Object.prototype.hasOwnProperty.call(result, 'data')) throw new AvvessaError({ code: 'invalid_response', message: 'Native response has no data' });
  return result.data;
}
let nextSubscription = 0;
/** Transport emits one envelope for a command and a stream for subscribe. */
export function createAvvessa(transport) {
  const subscriptions = new Set();
  function invoke(method, args = {}) {
    return new Promise((resolve, reject) => {
      transport(method, encode(args), (json) => {
        try { resolve(decode(json)); } catch (error) { reject(error); }
      });
    });
  }
  function subscribe(listener, options = {}) {
    return new Promise((resolve, reject) => {
      if (typeof listener !== 'function') throw invalid('A subscription listener is required');
      if (!options || typeof options !== 'object' || Array.isArray(options) || Object.keys(options).some(key => key !== 'replayPending')) throw invalid('Only replayPending is supported');
      const replay = options.replayPending === undefined ? true : options.replayPending;
      if (typeof replay !== 'boolean') throw invalid('replayPending must be a boolean');
      const id = `uni:${Date.now()}:${++nextSubscription}`;
      let registered = false;
      transport('subscribe', encode({ subscriptionId: id, replayPending: replay }), (json) => {
        let message;
        try { message = decode(json); } catch (error) {
          if (!registered) reject(error);
          else if (subscriptions.has(id)) listener({ kind: 'error', error });
          return;
        }
        if (!registered) {
          if (message?.kind !== 'subscribed' || message.subscriptionId !== id) {
            reject(new AvvessaError({ code: 'invalid_response', message: 'Native subscription acknowledgement is invalid' })); return;
          }
          registered = true; subscriptions.add(id); resolve(id); return;
        }
        if (message?.kind === 'closed') { subscriptions.delete(id); return; }
        if (subscriptions.has(id)) listener(message);
      });
    });
  }
  async function unsubscribe(subscriptionId) {
    if (typeof subscriptionId !== 'string' || !subscriptionId) throw invalid('subscriptionId is required');
    await invoke('unsubscribe', { subscriptionId });
    subscriptions.delete(subscriptionId);
  }
  return {
    getState: () => invoke('getState'), getCapabilities: () => invoke('getCapabilities'),
    initialize: options => invoke('initialize', options),
    setConsentGranted: granted => invoke('setConsentGranted', { granted }),
    refreshState: () => invoke('refreshState'), requestNotificationPermission: () => invoke('requestNotificationPermission'),
    openNotificationSettings: () => invoke('openNotificationSettings'),
    getPendingEvents: () => invoke('getPendingEvents'), acknowledge: eventId => invoke('acknowledge', { eventId }),
    clearPendingEvents: () => invoke('clearPendingEvents'), subscribe, unsubscribe,
    stopPush: () => invoke('stopPush'), resumePush: () => invoke('resumePush'),
    setBadge: badge => invoke('setBadge', { badge }), clearNotifications: (options = {}) => invoke('clearNotifications', options),
    createNotificationChannel: options => invoke('createNotificationChannel', options),
    setNotificationCategories: categories => invoke('setNotificationCategories', { categories }),
    setAlias: alias => invoke('setAlias', { alias }), getAlias: () => invoke('getAlias'), deleteAlias: () => invoke('deleteAlias'),
    setTags: tags => invoke('setTags', { tags }), addTags: tags => invoke('addTags', { tags }), deleteTags: tags => invoke('deleteTags', { tags }),
    getTags: () => invoke('getTags'), clearTags: () => invoke('clearTags'), checkTag: tag => invoke('checkTag', { tag }),
  };
}
