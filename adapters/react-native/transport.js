// The native command resolves once. Subscription messages use a separate event
// channel; buffer them until the acknowledgement reaches the public JS facade.
export function createTransport(getNative, listen) {
  const streams = new Map();
  let eventSubscription;
  let disposed = false;
  const closed = { code: 'bridge_closed', message: 'This React Native transport has been disposed' };
  const failure = error => JSON.stringify({
    ok: false,
    error: {
      code: typeof error?.code === 'string' ? error.code : 'host_unavailable',
      message: typeof error?.message === 'string' ? error.message : 'The React Native bridge is unavailable',
      nativeCode: null,
    },
  });
  function release(id) {
    streams.delete(id);
    if (streams.size === 0 && eventSubscription) {
      eventSubscription.remove();
      eventSubscription = undefined;
    }
  }
  function deliver(id, json) {
    const stream = streams.get(id);
    if (!stream) return;
    let closed = false;
    try { closed = JSON.parse(json)?.data?.kind === 'closed'; } catch { /* The public facade reports malformed envelopes. */ }
    if (closed) release(id);
    stream.callback(json);
  }
  const transport = (method, json, callback) => {
    if (disposed) { callback(failure(closed)); return; }
    const native = getNative();
    if (method !== 'subscribe') {
      Promise.resolve().then(() => native.invoke(method, json)).then(response => {
        if (method === 'unsubscribe') {
          try {
            if (JSON.parse(response).ok === true) release(JSON.parse(json).subscriptionId);
          } catch { /* Let the public facade reject malformed replies. */ }
        }
        callback(response);
      }, error => callback(failure(error)));
      return;
    }
    const id = JSON.parse(json).subscriptionId;
    const stream = { callback, native, ready: false, acknowledged: false, pending: [] };
    streams.set(id, stream);
    try {
      if (!eventSubscription) eventSubscription = listen(native, packet => {
        const owner = streams.get(packet?.subscriptionId);
        if (!owner) return;
        if (!owner.ready) owner.pending.push(packet.response);
        else deliver(packet.subscriptionId, packet.response);
      });
    } catch (error) {
      release(id);
      throw error;
    }
    Promise.resolve().then(() => {
      if (disposed) throw closed;
      return native.invoke(method, json);
    }).then(response => {
      if (disposed) { detach(native, id); return; }
      let acknowledged = false;
      try {
        const envelope = JSON.parse(response);
        acknowledged = envelope.ok === true && envelope.data?.kind === 'subscribed' && envelope.data.subscriptionId === id;
      } catch { /* Decode through the public facade. */ }
      if (!acknowledged) {
        release(id);
        detach(native, id);
        callback(response);
        return;
      }
      stream.acknowledged = true;
      callback(response);
      // Promise continuations waiting for subscribe() run before replay.
      queueMicrotask(() => {
        if (streams.get(id) !== stream) return;
        stream.ready = true;
        for (const pending of stream.pending.splice(0)) deliver(id, pending);
      });
    }, error => { release(id); if (!disposed) callback(failure(error)); });
  };
  function detach(native, id) {
    Promise.resolve().then(() => native.invoke('unsubscribe', JSON.stringify({ subscriptionId: id })))
      .then(response => {
        const envelope = JSON.parse(response);
        if (envelope?.ok !== true) throw new Error(envelope?.error?.message || 'Native unsubscribe returned an invalid response');
      })
      .catch(error => console.warn('Avvessa could not detach a retired React subscription', error));
  }
  transport.dispose = () => {
    if (disposed) return;
    disposed = true;
    for (const [id, stream] of [...streams]) {
      release(id);
      stream.callback(stream.acknowledged
        ? JSON.stringify({ ok: true, data: { kind: 'closed', subscriptionId: id } })
        : failure(closed));
      detach(stream.native, id);
    }
  };
  return transport;
}
