import { NativeEventEmitter, NativeModules, TurboModuleRegistry } from 'react-native';
import { createAvvessa, AvvessaError } from './shared/javascript/bridge.js';
import { createTransport } from './transport.js';

export { AvvessaError } from './shared/javascript/bridge.js';
function getNative() {
  const native = TurboModuleRegistry.get('Avvessa') || NativeModules.Avvessa;
  if (!native) throw new AvvessaError({
    code: 'host_unavailable',
    message: 'Install @sandrox/avvessa and rebuild the native application. Expo Go does not include Avvessa.',
  });
  return native;
}
const transport = createTransport(getNative, (native, callback) =>
  new NativeEventEmitter(native).addListener('AvvessaObservation', callback));
const api = createAvvessa(transport, 'rn');
if (typeof module !== 'undefined' && module.hot) module.hot.dispose(() => transport.dispose());
export const {
  getState, getCapabilities, initialize, setConsentGranted, refreshState, requestNotificationPermission,
  openNotificationSettings, getPendingEvents, acknowledge, clearPendingEvents, subscribe, unsubscribe,
  stopPush, resumePush, setBadge, clearNotifications, createNotificationChannel, setNotificationCategories,
  setActiveNotificationContext, clearActiveNotificationContext,
  setAlias, getAlias, deleteAlias, setTags, addTags, deleteTags, getTags, clearTags, checkTag,
} = api;
