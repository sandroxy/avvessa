import { invokeAvvessaNative, onAvvessaNativeEvent } from '@/uni_modules/Sandrox-Avvessa';
import { createAvvessa } from './bridge.js';
export { AvvessaError } from './bridge.js';

// One persistent DCloud callback per JS runtime. Per-subscription callbacks live in this JS map.
const streams = new Map();
let listening = false;
const api = createAvvessa((method, json, callback) => {
  if (method !== 'subscribe') { invokeAvvessaNative(method, json, callback); return; }
  if (!listening) {
    onAvvessaNativeEvent((packetJSON) => {
      const packet = JSON.parse(packetJSON);
      const listener = streams.get(packet.subscriptionId);
      if (packet.closed) streams.delete(packet.subscriptionId);
      if (listener) listener(packet.response);
    });
    listening = true;
  }
  const id = JSON.parse(json).subscriptionId;
  streams.set(id, callback);
  try {
    invokeAvvessaNative(method, json, response => {
      if (JSON.parse(response).ok !== true) streams.delete(id);
      callback(response);
    });
  } catch (error) { streams.delete(id); throw error; }
});
export const {
  getState, getCapabilities, initialize, setConsentGranted, refreshState, requestNotificationPermission,
  openNotificationSettings, getPendingEvents, acknowledge, clearPendingEvents, subscribe, unsubscribe,
  stopPush, resumePush, setBadge, clearNotifications, createNotificationChannel, setNotificationCategories,
  setAlias, getAlias, deleteAlias, setTags, addTags, deleteTags, getTags, clearTags, checkTag,
} = api;
