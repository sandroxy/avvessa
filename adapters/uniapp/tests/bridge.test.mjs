import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
const source = await readFile(new URL('../js_sdk/bridge.js', import.meta.url), 'utf8');
const { createAvvessa, AvvessaError } = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
const ok = data => JSON.stringify({ ok: true, data });

test('presentation policy and context preserve exact scalar values across the bridge', async () => {
  const calls = [];
  const api = createAvvessa((method, json, callback) => { calls.push([method, JSON.parse(json)]); callback(ok(null)); });
  const policy = { activeContext: { match: { kind: 'chat' }, idField: 'conversationId', categoryField: 'conversationType' } };
  await api.initialize({ appKey: '000000000000000000000000', presentationPolicy: policy, notificationLifecycle: { clearBadgeOnOpen: true } });
  await api.setActiveNotificationContext({ id: '9007199254740993', category: 'direct' });
  await api.clearActiveNotificationContext();
  assert.deepEqual(calls, [
    ['initialize', { appKey: '000000000000000000000000', presentationPolicy: policy, notificationLifecycle: { clearBadgeOnOpen: true } }],
    ['setActiveNotificationContext', { id: '9007199254740993', category: 'direct' }],
    ['clearActiveNotificationContext', {}]
  ]);
});

test('stream preserves native payloads and event IDs without acknowledging or stopping push', async () => {
  const commands = []; let emit; let id;
  const api = createAvvessa((method, json, callback) => {
    const args = JSON.parse(json); commands.push({ method, args });
    if (method === 'subscribe') { emit = callback; id = args.subscriptionId; callback(ok({ kind: 'subscribed', subscriptionId: id })); }
    else { if (method === 'unsubscribe') emit(ok({ kind: 'closed' })); callback(ok(null)); }
  });
  const received = [];
  const subscription = await api.subscribe(message => received.push(message));
  const event = { id: 'opaque-event-id', messageId: '18103563551404819000', replayed: true, raw: { aps: { alert: '原始消息' }, custom: ['x', 1, null] } };
  emit(ok({ kind: 'event', event })); emit(ok({ kind: 'event', event }));
  assert.deepEqual(received, [{ kind: 'event', event }, { kind: 'event', event }]);
  await api.unsubscribe(subscription);
  emit(ok({ kind: 'event', event }));
  assert.equal(received.length, 2);
  assert.deepEqual(commands.map(x => x.method), ['subscribe','unsubscribe']);
  assert.equal(commands[0].args.replayPending, true);
});

test('command rejection preserves native diagnostics', async () => {
  const api = createAvvessa((_method, _args, callback) => callback(JSON.stringify({ ok: false, error: { code: 'sdk_error', message: 'Provider failure', nativeCode: 6002, nativeDomain: 'JPush' } })));
  await assert.rejects(api.setAlias('example'), error => error instanceof AvvessaError && error.code === 'sdk_error' && error.nativeCode === 6002 && error.nativeDomain === 'JPush');
});

test('invalid JSON values are rejected before reaching a native operation', async () => {
  let calls = 0;
  const api = createAvvessa(() => { calls++; });
  const cycle = {}; cycle.self = cycle;
  for (const value of [NaN,Infinity,undefined,1n,new Date(),cycle]) {
    await assert.rejects(api.initialize({ appKey: value }), { code: 'invalid_argument' });
  }
  await assert.rejects(api.subscribe(() => {}, { replayPending: 'yes' }), { code: 'invalid_argument' });
  assert.equal(calls, 0);
});

test('malformed native responses fail instead of fabricating a result', async () => {
  for (const response of ['bad json', 'null', '{}', '{"ok":true}', '{"ok":"true","data":1}']) {
    const api = createAvvessa((_method, _args, callback) => callback(response));
    await assert.rejects(api.getState(), { code: 'invalid_response' });
  }
});

test('failed subscription is rejected and explicit acknowledgement uses the opaque event ID', async () => {
  const calls = [];
  const api = createAvvessa((method, args, callback) => {
    calls.push([method, JSON.parse(args)]);
    callback(method === 'subscribe' ? JSON.stringify({ok:false,error:{code:'storage_error',message:'Unavailable'}}) : ok(true));
  });
  await assert.rejects(api.subscribe(() => {}), { code: 'storage_error' });
  assert.equal(await api.acknowledge('event:opaque'), true);
  assert.deepEqual(calls[1], ['acknowledge',{eventId:'event:opaque'}]);
});
