import { Hono } from 'hono';
import { stream } from 'hono/streaming';
import { cors } from 'hono/cors';

const START_TIME = Date.now();

function log(...args: any[]) {
  const ts = new Date().toISOString();
  const delta = (Date.now() - START_TIME).toString().padStart(6, ' ');
  console.log(`[${ts} +${delta}ms]`, ...args);
}

// A simple push/pop queue with timeout that avoids dangling promise issues
class MessageQueue {
  private items: Uint8Array[] = [];
  private resolver: ((value: Uint8Array | null) => void) | null = null;

  push(data: Uint8Array) {
    this.items.push(data);
    if (this.resolver) {
      const r = this.resolver;
      this.resolver = null;
      r(this.items.shift()!);
    }
  }

  async pop(timeoutMs: number): Promise<Uint8Array | null> {
    if (this.items.length > 0) return this.items.shift()!;
    return new Promise((resolve) => {
      this.resolver = resolve;
      setTimeout(() => {
        if (this.resolver) {
          this.resolver(null);
          this.resolver = null;
        }
      }, timeoutMs);
    });
  }
}

interface StreamPair {
  queue: MessageQueue;
}

let callerPair: StreamPair | null = null;
let calleePair: StreamPair | null = null;
let callActive = false;

const encoder = new TextEncoder();

function writeInt32BE(value: number): Uint8Array {
  return new Uint8Array([
    (value >> 24) & 0xFF,
    (value >> 16) & 0xFF,
    (value >> 8) & 0xFF,
    value & 0xFF
  ]);
}

function encodeControl(type: string): Uint8Array {
  const json = JSON.stringify({ type });
  const text = encoder.encode(json);
  const len = writeInt32BE(text.length);
  const buf = new Uint8Array(1 + 4 + text.length);
  buf[0] = 0;
  buf.set(len, 1);
  buf.set(text, 5);
  log('CTRL', type, `(${text.length}B payload, ${buf.length}B total)`);
  return buf;
}

function stateLabel(): string {
  return `[caller=${callerPair ? 'connected' : 'null'} callee=${calleePair ? 'connected' : 'null'} active=${callActive}]`;
}

function queuePush(queue: MessageQueue | undefined | null, frame: Uint8Array, label: string) {
  if (!queue) {
    log('QUEUE', `${label} => no queue (dropping ${frame.length}B)`);
    return;
  }
  log('QUEUE', `${label} => pushing ${frame.length}B`);
  queue.push(frame);
}

async function hangupAll() {
  log('HANGUP starting', stateLabel());
  const hangup = encodeControl('hangup');
  if (callerPair) {
    queuePush(callerPair.queue, hangup, 'hangup->caller');
    log('HANGUP caller notified');
  }
  if (calleePair) {
    queuePush(calleePair.queue, hangup, 'hangup->callee');
    log('HANGUP callee notified');
  }
  callerPair = null;
  calleePair = null;
  callActive = false;
  log('HANGUP done', stateLabel());
}

const app = new Hono();

app.use('*', async (c, next) => {
  const ip = c.req.header('x-forwarded-for') || c.req.header('x-real-ip') || 'unknown';
  log('REQ', `${c.req.method} ${c.req.path} from ${ip}`, stateLabel());
  await next();
  log('RES', `${c.req.method} ${c.req.path} => ${c.res.status}`, stateLabel());
});

app.use('*', cors({
  origin: '*',
  allowMethods: ['GET', 'POST', 'DELETE', 'OPTIONS'],
  allowHeaders: ['Content-Type'],
}));

// ---- CALLER ----

app.get('/caller', (c) => {
  log('CALLER-GET', stateLabel());
  if (callerPair) {
    log('CALLER-GET', 'CONFLICT');
    return c.text('Call already in progress', 409);
  }
  const q = new MessageQueue();
  callerPair = { queue: q };
  log('CALLER-GET', 'callerPair created', stateLabel());

  queuePush(callerPair.queue, encodeControl('connected'), 'connected->caller');

  if (calleePair) {
    log('CALLER-GET', 'callee connected, sending incoming_call');
    queuePush(calleePair.queue, encodeControl('incoming_call'), 'incoming_call->callee');
  }

  return stream(c, async (s) => {
    let seq = 0;
    try {
      while (true) {
        const data = await q.pop(5000);
        if (data === null) {
          // Timeout - send heartbeat
          try {
            await s.write(encodeControl('heartbeat'));
          } catch (e) {
            log('CALLER-GET stream', 'heartbeat write failed:', e);
            break;
          }
          continue;
        }
        seq++;
        log('CALLER-GET stream', `seq=${seq} type=${data[0]} size=${data.length}B`);
        try {
          await s.write(data);
        } catch (e) {
          log('CALLER-GET stream', 'write error:', e);
          break;
        }
      }
    } finally {
      log('CALLER-GET stream', 'cleanup: seq=', seq, stateLabel());
      await hangupAll();
      log('CALLER-GET stream', 'cleanup done', stateLabel());
    }
  });
});

app.post('/caller', async (c) => {
  const body = c.req.raw.body;
  log('CALLER-POST', 'body present:', !!body, stateLabel());
  if (body && callActive) {
    const payload = await readAllChunks(body, 'CALLER-POST');
    forwardAudio('POST/caller', calleePair?.queue ?? null, payload);
  } else if (body && !callActive) {
    const reader = body.getReader();
    let total = 0;
    while (true) { const { done, value } = await reader.read(); if (done) break; total += value.length; }
    log('CALLER-POST', `call not active, drained ${total}B`);
  }
  return c.text('ok');
});

app.delete('/caller', async (c) => {
  log('CALLER-DELETE', stateLabel());
  await hangupAll();
  return c.text('ok');
});

// ---- CALLEE ----

app.get('/callee', (c) => {
  log('CALLEE-GET', stateLabel());
  if (calleePair) {
    log('CALLEE-GET', 'CONFLICT');
    return c.text('Already connected', 409);
  }
  const q = new MessageQueue();
  calleePair = { queue: q };
  log('CALLEE-GET', 'calleePair created', stateLabel());

  queuePush(calleePair.queue, encodeControl('connected'), 'connected->callee');

  return stream(c, async (s) => {
    let seq = 0;
    try {
      while (true) {
        const data = await q.pop(5000);
        if (data === null) {
          try {
            await s.write(encodeControl('heartbeat'));
          } catch (e) {
            log('CALLEE-GET stream', 'heartbeat write failed:', e);
            break;
          }
          continue;
        }
        seq++;
        log('CALLEE-GET stream', `seq=${seq} type=${data[0]} size=${data.length}B`);
        try {
          await s.write(data);
        } catch (e) {
          log('CALLEE-GET stream', 'write error:', e);
          break;
        }
      }
    } finally {
      log('CALLEE-GET stream', 'cleanup: seq=', seq, stateLabel());
      await hangupAll();
      log('CALLEE-GET stream', 'cleanup done', stateLabel());
    }
  });
});

app.post('/callee', async (c) => {
  const body = c.req.raw.body;
  log('CALLEE-POST', 'body present:', !!body, stateLabel());

  if (!callActive) {
    log('CALLEE-POST', 'FIRST POST - answering call');
    callActive = true;
    if (callerPair) {
      queuePush(callerPair.queue, encodeControl('call_answered'), 'call_answered->caller');
    } else {
      log('CALLEE-POST', 'WARNING: no callerPair');
    }
  }

  if (body) {
    const payload = await readAllChunks(body, 'CALLEE-POST');
    forwardAudio('POST/callee', callerPair?.queue ?? null, payload);
  } else {
    log('CALLEE-POST', 'empty answer signal');
  }
  return c.text('ok');
});

app.delete('/callee', async (c) => {
  log('CALLEE-DELETE', stateLabel());
  await hangupAll();
  return c.text('ok');
});

app.notFound((c) => {
  log('404', c.req.method, c.req.path);
  return c.text('Not found', 404);
});

async function readAllChunks(stream: ReadableStream<Uint8Array>, label: string): Promise<Uint8Array> {
  const reader = stream.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    chunks.push(value);
    total += value.length;
  }
  log('READ', `${label}: ${chunks.length} chunks, ${total}B total`);
  if (chunks.length === 1) return chunks[0];
  const merged = new Uint8Array(total);
  let offset = 0;
  for (const c of chunks) { merged.set(c, offset); offset += c.length; }
  return merged;
}

function forwardAudio(source: string, queue: MessageQueue | null, data: Uint8Array) {
  if (!queue) {
    log('AUDIO', `${source}: no queue, dropping ${data.length}B`);
    return;
  }
  try {
    const len = writeInt32BE(data.length);
    const buf = new Uint8Array(1 + 4 + data.length);
    buf[0] = 1;
    buf.set(len, 1);
    buf.set(data, 5);
    const kb = (data.length / 1024).toFixed(1);
    log('AUDIO', `${source}: wrapping ${data.length}B (${kb}KB) as audio frame`);
    queuePush(queue, buf, `audio(${source})`);
  } catch (e) {
    log('AUDIO', `${source}: error:`, e);
  }
}

const PORT = parseInt(process.env.PORT || '3001');
log('========================================');
log(`Server starting on port ${PORT}`);
log('========================================');

export default {
  port: PORT,
  fetch: app.fetch,
};
