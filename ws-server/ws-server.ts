const PORT = parseInt(process.env.PORT || '3001');
const HOST = process.env.HOST || '0.0.0.0';

let callerController: ReadableStreamDefaultController<Uint8Array> | null = null;
let calleeController: ReadableStreamDefaultController<Uint8Array> | null = null;
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
  return buf;
}

function forwardAudio(controller: ReadableStreamDefaultController<Uint8Array> | null, data: Uint8Array) {
  if (!controller) return;
  try {
    const len = writeInt32BE(data.length);
    const buf = new Uint8Array(1 + 4 + data.length);
    buf[0] = 1;
    buf.set(len, 1);
    buf.set(data, 5);
    controller.enqueue(buf);
  } catch {}
}

function hangupAll() {
  [callerController, calleeController].forEach((ctrl) => {
    if (ctrl) {
      try {
        ctrl.enqueue(encodeControl('hangup'));
        ctrl.close();
      } catch {}
    }
  });
  callerController = null;
  calleeController = null;
  callActive = false;
}

function createStream(
  setController: (c: ReadableStreamDefaultController<Uint8Array>) => void,
  onCancel?: () => void
): ReadableStream<Uint8Array> {
  return new ReadableStream({
    start(controller) {
      setController(controller);
    },
    cancel() {
      onCancel?.();
    }
  });
}

Bun.serve({
  port: PORT,
  hostname: HOST,
  async fetch(req: Request) {
    const url = new URL(req.url);
    const method = req.method;

    // ---- CALLER endpoints ----

    if (url.pathname === '/caller' && method === 'GET') {
      if (callerController) {
        return new Response('Call already in progress', { status: 409 });
      }
      return new Response(createStream(
        (ctrl) => {
          callerController = ctrl;
          // Notify callee of incoming call
          if (calleeController) {
            try { calleeController.enqueue(encodeControl('incoming_call')); } catch {}
          }
        },
        () => {
          callerController = null;
          if (callActive) hangupAll();
        }
      ), {
        headers: { 'Content-Type': 'application/octet-stream' }
      });
    }

    if (url.pathname === '/caller' && method === 'POST') {
      if (!req.body) return new Response('No body', { status: 400 });
      const reader = req.body.getReader();
      (async () => {
        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          if (callActive) {
            forwardAudio(calleeController, value);
          }
        }
      })();
      return new Response('ok');
    }

    if (url.pathname === '/caller' && method === 'DELETE') {
      hangupAll();
      return new Response('ok');
    }

    // ---- CALLEE endpoints ----

    if (url.pathname === '/callee' && method === 'GET') {
      if (calleeController) {
        return new Response('Already connected', { status: 409 });
      }
      return new Response(createStream(
        (ctrl) => {
          calleeController = ctrl;
        },
        () => {
          calleeController = null;
          if (callActive) hangupAll();
        }
      ), {
        headers: { 'Content-Type': 'application/octet-stream' }
      });
    }

    if (url.pathname === '/callee' && method === 'POST') {
      if (!req.body) return new Response('No body', { status: 400 });

      // First POST from callee = answer (even if empty)
      if (!callActive) {
        callActive = true;
        if (callerController) {
          try { callerController.enqueue(encodeControl('call_answered')); } catch {}
        }
      }

      const reader = req.body.getReader();
      (async () => {
        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          forwardAudio(callerController, value);
        }
      })();
      return new Response('ok');
    }

    if (url.pathname === '/callee' && method === 'DELETE') {
      hangupAll();
      return new Response('ok');
    }

    return new Response('Not found', { status: 404 });
  }
});

console.log(`[HTTP Chunked Server] Running on http://${HOST}:${PORT}`);
