import { createAvvessa, AvvessaError } from './bridge.js';
export { AvvessaError } from './bridge.js';
const api = createAvvessa((method, json, callback) => {
  const native = typeof uni === 'undefined' ? null : uni.requireNativePlugin('Sandrox-Avvessa');
  if (!native || typeof native.invoke !== 'function') {
    throw new AvvessaError({ code: 'host_unavailable', message: 'Install Sandrox-Avvessa and rebuild the native UNI app or custom base' });
  }
  native.invoke(method, json, callback);
});
export const {
  getState, getCapabilities, initialize, setConsentGranted, refreshState, requestNotificationPermission,
  openNotificationSettings, getPendingEvents, acknowledge, clearPendingEvents, subscribe, unsubscribe,
  stopPush, resumePush, setBadge, clearNotifications, createNotificationChannel, setNotificationCategories,
  setAlias, getAlias, deleteAlias, setTags, addTags, deleteTags, getTags, clearTags, checkTag,
} = api;
