import { Hono } from 'hono';
import { stream } from 'hono/streaming';
import { cors } from 'hono/cors';

const START_TIME = Date.now();

function log(...args: any[]) {
  const ts = new Date().toISOString();
  const delta = (Date.now() - START_TIME).toString().padStart(6, ' ');
  console.log(`[${ts} +${delta}ms]`, ...args);
}

interface StreamPair {
  writable: WritableStream<Uint8Array>;
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
  log('CTRL', type, `(${text.length} bytes payload, ${buf.length} total)`);
  return buf;
}

function stateLabel(): string {
  return `[caller=${callerPair ? 'connected' : 'null'} callee=${calleePair ? 'connected' : 'null'} active=${callActive}]`;
}

async function writeFrame(writable: WritableStream<Uint8Array> | null, frame: Uint8Array, label: string) {
  if (!writable) {
    log('WRITE', `${label} => no writable (dropping ${frame.length}B)`);
    return;
  }
  try {
    const writer = writable.getWriter();
    await writer.write(frame);
    writer.releaseLock();
    log('WRITE', `${label} => ${frame.length} bytes written`);
  } catch (e) {
    log('WRITE', `${label} => FAILED:`, e);
  }
}

async function hangupAll() {
  log('HANGUP starting', stateLabel());
  const hangup = encodeControl('hangup');
  if (callerPair) {
    try {
      await writeFrame(callerPair.writable, hangup, 'hangup->caller');
      await callerPair.writable.close();
      log('HANGUP caller writable closed');
    } catch (e) {
      log('HANGUP caller writable close error:', e);
    }
  } else {
    log('HANGUP no callerPair to close');
  }
  if (calleePair) {
    try {
      await writeFrame(calleePair.writable, hangup, 'hangup->callee');
      await calleePair.writable.close();
      log('HANGUP callee writable closed');
    } catch (e) {
      log('HANGUP callee writable close error:', e);
    }
  } else {
    log('HANGUP no calleePair to close');
  }
  callerPair = null;
  calleePair = null;
  callActive = false;
  log('HANGUP done', stateLabel());
}

function makeTransformStream(label: string): TransformStream<Uint8Array> {
  log('STREAM creating TransformStream for', label);
  const ts = new TransformStream<Uint8Array>({}, {
    flush() { log('STREAM', label, 'flush called'); },
  });
  log('STREAM created', label);
  return ts;
}

async function streamReaderLoop(label: string, reader: ReadableStreamDefaultReader<Uint8Array>, honoStream: any) {
  log('STREAM', label, 'reader loop starting');
  let seq = 0;
  let heartbeatId: ReturnType<typeof setInterval> | null = null;

  // Send heartbeat every 5s to prevent proxy idle timeout
  heartbeatId = setInterval(async () => {
    try {
      await honoStream.write(encodeControl('heartbeat'));
    } catch (e) {
      log('STREAM', label, 'heartbeat error (client likely gone):', e);
      if (heartbeatId) clearInterval(heartbeatId);
    }
  }, 5000);

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) {
        log('STREAM', label, 'reader done (stream closed)');
        break;
      }
      seq++;
      const type = value[0];
      const size = value.length;
      log('STREAM', label, `write seq=${seq} type=${type} size=${size}B`);
      await honoStream.write(value);
      log('STREAM', label, `write seq=${seq} done`);
    }
  } catch (e) {
    log('STREAM', label, 'reader error:', e);
  } finally {
    if (heartbeatId) clearInterval(heartbeatId);
    reader.releaseLock();
    log('STREAM', label, 'reader loop ended, seq=', seq, stateLabel());
    if (label === 'caller' && callerPair) {
      log('STREAM caller: clearing callerPair');
      callerPair = null;
      if (callActive) hangupAll();
    } else if (label === 'callee' && calleePair) {
      log('STREAM callee: clearing calleePair');
      calleePair = null;
      if (callActive) hangupAll();
    }
    log('STREAM', label, 'cleanup done', stateLabel());
  }
}

async function readBody(label: string, body: ReadableStream<Uint8Array>, onChunk: (data: Uint8Array) => void) {
  log('BODY', label, 'start reading');
  const reader = body.getReader();
  let totalBytes = 0;
  let chunks = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) {
      log('BODY', label, `done: ${chunks} chunks, ${totalBytes} total bytes`);
      break;
    }
    chunks++;
    totalBytes += value.length;
    const kb = (value.length / 1024).toFixed(1);
    const totalKb = (totalBytes / 1024).toFixed(1);
    log('BODY', label, `chunk #${chunks}: ${value.length}B (${kb}KB), total ${totalBytes}B (${totalKb}KB)`);
    onChunk(value);
  }
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
  log('CALLER-GET', 'incoming', stateLabel());
  if (callerPair) {
    log('CALLER-GET', 'CONFLICT - already has a caller pair');
    return c.text('Call already in progress', 409);
  }
  const ts = makeTransformStream('caller');
  callerPair = { writable: ts.writable };
  log('CALLER-GET', 'callerPair assigned, sending connected frame', stateLabel());

  writeFrame(callerPair.writable, encodeControl('connected'), 'connected->caller');

  if (calleePair) {
    log('CALLER-GET', 'callee already connected, sending incoming_call');
    writeFrame(calleePair.writable, encodeControl('incoming_call'), 'incoming_call->callee');
  } else {
    log('CALLER-GET', 'no callee connected yet, will notify when they connect');
  }

  log('CALLER-GET', 'returning stream response');
  return stream(c, async (s) => {
    const reader = ts.readable.getReader();
    await streamReaderLoop('caller', reader, s);
  });
});

app.post('/caller', async (c) => {
  const body = c.req.raw.body;
  log('CALLER-POST', 'body present:', !!body, stateLabel());
  if (body) {
    await readBody('POST/caller', body, (data) => {
      if (callActive) {
        forwardAudio('POST/caller', calleePair?.writable ?? null, data);
      } else {
        log('AUDIO', 'POST/caller: call not active, dropping', data.length, 'bytes');
      }
    });
  } else {
    log('CALLER-POST', 'no body, nothing to forward');
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
  log('CALLEE-GET', 'incoming', stateLabel());
  if (calleePair) {
    log('CALLEE-GET', 'CONFLICT - already has a callee pair');
    return c.text('Already connected', 409);
  }
  const ts = makeTransformStream('callee');
  calleePair = { writable: ts.writable };
  log('CALLEE-GET', 'calleePair assigned, sending connected frame', stateLabel());

  writeFrame(calleePair.writable, encodeControl('connected'), 'connected->callee');

  log('CALLEE-GET', 'returning stream response');
  return stream(c, async (s) => {
    const reader = ts.readable.getReader();
    await streamReaderLoop('callee', reader, s);
  });
});

app.post('/callee', async (c) => {
  const body = c.req.raw.body;
  log('CALLEE-POST', 'body present:', !!body, stateLabel());

  if (!callActive) {
    log('CALLEE-POST', 'FIRST POST - answering call');
    callActive = true;
    if (callerPair) {
      log('CALLEE-POST', 'sending call_answered to caller');
      await writeFrame(callerPair.writable, encodeControl('call_answered'), 'call_answered->caller');
    } else {
      log('CALLEE-POST', 'WARNING: no callerPair to send call_answered');
    }
  } else {
    log('CALLEE-POST', 'call already active, this is audio data');
  }

  if (body) {
    await readBody('POST/callee', body, (data) => {
      forwardAudio('POST/callee', callerPair?.writable ?? null, data);
    });
  } else {
    log('CALLEE-POST', 'no body (empty answer signal)');
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

function forwardAudio(source: string, writable: WritableStream<Uint8Array> | null, data: Uint8Array) {
  if (!writable) {
    log('AUDIO', `${source}: no writable, dropping ${data.length}B`);
    return;
  }
  try {
    const len = writeInt32BE(data.length);
    const buf = new Uint8Array(1 + 4 + data.length);
    buf[0] = 1;
    buf.set(len, 1);
    buf.set(data, 5);
    const kb = (data.length / 1024).toFixed(1);
    log('AUDIO', `${source}: wrapping ${data.length}B (${kb}KB) as audio frame -> forwarding`);
    writeFrame(writable, buf, `audio(${source})->${writable === callerPair?.writable ? 'caller' : 'callee'}`);
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
