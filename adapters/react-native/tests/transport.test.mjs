import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

async function load(relative) {
  const source = await readFile(new URL(relative, import.meta.url), 'utf8');
  return import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
}
const { createTransport } = await load('../transport.js');
const { createAvvessa, AvvessaError } = await load('../../uniapp/js_sdk/bridge.js');
const ok = data => JSON.stringify({ ok: true, data });
const tick = () => new Promise(resolve => setImmediate(resolve));

function host(implementation) {
  const calls = [];
  let listener, adds = 0, removes = 0;
  const native = { invoke(method, json) {
    const args = JSON.parse(json);
    calls.push([method, args]);
    return implementation ? implementation(method, args, send) : Promise.resolve(ok(null));
  }};
  function send(id, value) { listener?.({ subscriptionId: id, response: ok(value) }); }
  const transport = createTransport(() => native, (_, callback) => {
    adds++; listener = callback;
    return { remove() { removes++; listener = undefined; } };
  });
  return { api: createAvvessa(transport, 'rn'), transport, calls, send,
    get adds() { return adds; }, get removes() { return removes; } };
}

test('queues early native replay until the subscription promise resolves', async () => {
  const order = [];
  const h = host((method, args, send) => {
    assert.equal(method, 'subscribe');
    send(args.subscriptionId, { kind: 'event', event: { id: 'durable-a', replayed: true } });
    return Promise.resolve(ok({ kind: 'subscribed', subscriptionId: args.subscriptionId }));
  });
  const id = await h.api.subscribe(message => order.push(message.event.id));
  order.push('subscribed');
  await tick();
  assert.match(id, /^rn:/);
  assert.deepEqual(order, ['subscribed', 'durable-a']);
});

test('independent observers share one emitter and unsubscribe never stops or acknowledges', async () => {
  const h = host((method, args) => Promise.resolve(ok(method === 'subscribe'
    ? { kind: 'subscribed', subscriptionId: args.subscriptionId } : null)));
  const first = [], second = [];
  const a = await h.api.subscribe(message => first.push(message));
  const b = await h.api.subscribe(message => second.push(message));
  await tick();
  await h.api.unsubscribe(a);
  h.send(a, { kind: 'state', state: { initialized: true } });
  h.send(b, { kind: 'event', event: { id: 'durable-b' } });
  assert.equal(first.length, 0); assert.equal(second.length, 1);
  assert.equal(h.adds, 1); assert.equal(h.removes, 0);
  await h.api.unsubscribe(b);
  assert.equal(h.removes, 1);
  assert.deepEqual(h.calls.map(([method]) => method), ['subscribe', 'subscribe', 'unsubscribe', 'unsubscribe']);
});

test('failed native registration releases the emitter and preserves the error', async () => {
  const h = host(() => Promise.reject({ code: 'bridge_closed', message: 'Disposed by React' }));
  await assert.rejects(h.api.subscribe(() => {}), error =>
    error instanceof AvvessaError && error.code === 'bridge_closed');
  assert.equal(h.removes, 1);
});

test('malformed unsubscribe reply is rejected instead of leaving a pending promise', async () => {
  const h = host(() => Promise.resolve('invalid json'));
  await assert.rejects(h.api.unsubscribe('owned-id'), { code: 'invalid_response' });
});

test('invalid subscription envelope releases the emitter', async () => {
  const h = host(() => Promise.resolve(ok({ kind: 'subscribed', subscriptionId: 'different-owner' })));
  await assert.rejects(h.api.subscribe(() => {}), { code: 'invalid_response' });
  assert.equal(h.removes, 1);
});

test('hot disposal releases owned listeners and only unsubscribes', async () => {
  const h = host((method, args) => Promise.resolve(ok(method === 'subscribe'
    ? { kind: 'subscribed', subscriptionId: args.subscriptionId } : null)));
  const observations = [];
  await h.api.subscribe(value => observations.push(value));
  h.transport.dispose();
  await tick();
  assert.equal(h.removes, 1);
  assert.deepEqual(observations, []);
  assert.deepEqual(h.calls.map(([method]) => method), ['subscribe', 'unsubscribe']);
  await assert.rejects(h.api.getState(), { code: 'bridge_closed' });
});

test('disposal settles pending subscribe and detaches a late native acknowledgement', async () => {
  let complete, id;
  const h = host((method, args) => {
    if (method !== 'subscribe') return Promise.resolve(ok(null));
    id = args.subscriptionId;
    return new Promise(resolve => { complete = resolve; });
  });
  const pending = h.api.subscribe(() => { throw new Error('retired listener'); });
  await tick();
  const rejected = assert.rejects(pending, { code: 'bridge_closed' });
  h.transport.dispose();
  await rejected;
  complete(ok({ kind: 'subscribed', subscriptionId: id }));
  await tick();
  assert.equal(h.removes, 1);
  assert.equal(h.calls.filter(([method]) => method === 'unsubscribe').length, 2);
});

test('native closure drops subsequent packets and consumer JSON is not normalized', async () => {
  const h = host((method, args) => Promise.resolve(ok(method === 'subscribe'
    ? { kind: 'subscribed', subscriptionId: args.subscriptionId } : null)));
  const seen = [];
  const id = await h.api.subscribe(value => seen.push(value));
  await tick();
  const value = { kind: 'event', event: { id: 'e', raw: { conversationId: '9007199254740993', value: false }, action: { userText: '回复' } } };
  h.send(id, value); h.send(id, { kind: 'closed', subscriptionId: id }); h.send(id, value);
  assert.deepEqual(seen, [value]);
  assert.equal(h.removes, 1);
});
