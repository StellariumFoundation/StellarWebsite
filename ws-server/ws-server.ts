function log(...args: any[]) {
  const ts = new Date().toISOString();
  console.log(`[${ts}]`, ...args);
}

const PORT = parseInt(process.env.PORT || '3001');
const HOST = process.env.HOST || '0.0.0.0';

const CORS_HEADERS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, POST, DELETE, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type',
};

interface StreamPair {
  readable: ReadableStream<Uint8Array>;
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
  log('encodeControl:', type, '-', text.length, 'bytes');
  return buf;
}

async function writeFrame(writable: WritableStream<Uint8Array> | null, frame: Uint8Array) {
  if (!writable) {
    log('writeFrame: no writable, dropping frame');
    return;
  }
  try {
    const writer = writable.getWriter();
    await writer.write(frame);
    writer.releaseLock();
    log('writeFrame: wrote', frame.length, 'bytes');
  } catch (e) {
    log('writeFrame: write failed:', e);
  }
}

function forwardAudio(writable: WritableStream<Uint8Array> | null, data: Uint8Array) {
  if (!writable) {
    log('forwardAudio: no writable, dropping', data.length, 'bytes');
    return;
  }
  try {
    const len = writeInt32BE(data.length);
    const buf = new Uint8Array(1 + 4 + data.length);
    buf[0] = 1;
    buf.set(len, 1);
    buf.set(data, 5);
    writeFrame(writable, buf);
    log('forwardAudio: forwarded', data.length, 'bytes');
  } catch (e) {
    log('forwardAudio: error:', e);
  }
}

async function hangupAll() {
  log('hangupAll: starting');
  const hangup = encodeControl('hangup');
  if (callerPair) {
    await writeFrame(callerPair.writable, hangup);
    await callerPair.writable.close();
    log('hangupAll: closed caller writable');
  }
  if (calleePair) {
    await writeFrame(calleePair.writable, hangup);
    await calleePair.writable.close();
    log('hangupAll: closed callee writable');
  }
  callerPair = null;
  calleePair = null;
  callActive = false;
  log('hangupAll: done, callActive=false');
}

function makeStreamPair(label: string): StreamPair {
  log('makeStreamPair: creating', label);
  const ts = new TransformStream<Uint8Array>();
  log('makeStreamPair: created', label, 'TransformStream');
  return { readable: ts.readable, writable: ts.writable };
}

function respond(body: string | ReadableStream<Uint8Array>, status = 200): Response {
  const headers: Record<string, string> = { ...CORS_HEADERS };
  if (body instanceof ReadableStream) {
    headers['Content-Type'] = 'application/octet-stream';
  }
  log('respond:', status, typeof body);
  return new Response(body, { status, headers });
}

Bun.serve({
  port: PORT,
  hostname: HOST,
  async fetch(req: Request) {
    const url = new URL(req.url);
    const method = req.method;
    const ip = req.headers.get('x-forwarded-for') || req.headers.get('x-real-ip') || 'unknown';
    log(`--> ${method} ${url.pathname} from ${ip}`);

    try {
      if (method === 'OPTIONS') {
        log('OPTIONS: returning CORS headers');
        return new Response(null, { headers: CORS_HEADERS });
      }

      // ---- CALLER ----

      if (url.pathname === '/caller' && method === 'GET') {
        if (callerPair) {
          log('GET /caller: conflict');
          return respond('Call already in progress', 409);
        }
        callerPair = makeStreamPair('caller');
        log('GET /caller: callerPair set, notifying callee if connected');
        if (calleePair) {
          writeFrame(calleePair.writable, encodeControl('incoming_call'));
        }
        return respond(callerPair.readable);
      }

      if (url.pathname === '/caller' && method === 'POST') {
        if (!req.body) return respond('No body', 400);
        log('POST /caller: reading body');
        const reader = req.body.getReader();
        let totalBytes = 0;
        while (true) {
          const { done, value } = await reader.read();
          if (done) {
            log('POST /caller: done reading, total', totalBytes, 'bytes');
            break;
          }
          totalBytes += value.length;
          log('POST /caller: chunk', value.length, 'bytes (total', totalBytes + ')');
          if (callActive) {
            forwardAudio(calleePair?.writable ?? null, value);
          } else {
            log('POST /caller: call not active, dropping chunk');
          }
        }
        return respond('ok');
      }

      if (url.pathname === '/caller' && method === 'DELETE') {
        log('DELETE /caller');
        await hangupAll();
        return respond('ok');
      }

      // ---- CALLEE ----

      if (url.pathname === '/callee' && method === 'GET') {
        if (calleePair) {
          log('GET /callee: conflict');
          return respond('Already connected', 409);
        }
        calleePair = makeStreamPair('callee');
        log('GET /callee: calleePair set');
        return respond(calleePair.readable);
      }

      if (url.pathname === '/callee' && method === 'POST') {
        if (!req.body) return respond('No body', 400);

        if (!callActive) {
          log('POST /callee: first POST -> answering call');
          callActive = true;
          if (callerPair) {
            await writeFrame(callerPair.writable, encodeControl('call_answered'));
            log('POST /callee: sent call_answered to caller');
          }
        }

        log('POST /callee: reading body');
        const reader = req.body.getReader();
        let totalBytes = 0;
        while (true) {
          const { done, value } = await reader.read();
          if (done) {
            log('POST /callee: done reading, total', totalBytes, 'bytes');
            break;
          }
          totalBytes += value.length;
          log('POST /callee: chunk', value.length, 'bytes (total', totalBytes + ')');
          forwardAudio(callerPair?.writable ?? null, value);
        }
        return respond('ok');
      }

      if (url.pathname === '/callee' && method === 'DELETE') {
        log('DELETE /callee');
        await hangupAll();
        return respond('ok');
      }

      log('404:', url.pathname);
      return respond('Not found', 404);
    } catch (e) {
      log('ERROR:', e);
      return new Response('Internal Server Error', { status: 500, headers: CORS_HEADERS });
    }
  }
});

log(`Server started on http://${HOST}:${PORT}`);
