const PORT = parseInt(process.env.PORT || '3001');
const HOST = process.env.HOST || '0.0.0.0';

let browserSocket: WebSocket | null = null;
let androidSocket: WebSocket | null = null;

Bun.serve({
  port: PORT,
  hostname: HOST,
  async fetch(req: Request, server: any) {
    if (server.upgrade(req)) return;
    return new Response('WebSocket only', { status: 426 });
  },
  websocket: {
    open(ws) {
      console.log('[WS] New peer connected');
    },
    message(ws, message) {
      if (typeof message === 'string') {
        try {
          const signal = JSON.parse(message);
          console.log('[WS] Signal:', signal.type);
          switch (signal.type) {
            case 'register':
              if (signal.role === 'browser') {
                if (browserSocket && browserSocket !== ws) {
                  try { browserSocket.close(1000, 'Replaced by new browser'); } catch (_) {}
                }
                browserSocket = ws;
                console.log('[WS] Browser registered (android connected:', androidSocket?.readyState === WebSocket.OPEN ? 'yes' : 'no', ')');
              } else if (signal.role === 'android') {
                if (androidSocket && androidSocket !== ws) {
                  try { androidSocket.close(1000, 'Replaced by new android'); } catch (_) {}
                }
                androidSocket = ws;
                console.log('[WS] Android registered');
              }
              break;
            case 'call_request':
            case 'call_start':
              browserSocket = ws;
              console.log('[WS] call_start received, browserSocket set. androidSocket state:', androidSocket?.readyState);
              if (androidSocket && androidSocket.readyState === WebSocket.OPEN) {
                androidSocket.send(JSON.stringify({ type: 'incoming_call' }));
                console.log('[WS] incoming_call sent to android');
              } else {
                ws.send(JSON.stringify({ type: 'error', message: 'Android offline' }));
                console.log('[WS] Android offline — error sent to browser');
              }
              break;
            case 'call_answered':
            case 'call_accepted':
              if (browserSocket && browserSocket.readyState === WebSocket.OPEN) {
                browserSocket.send(JSON.stringify({ type: 'call_answered' }));
                console.log('[WS] call_answered forwarded to browser');
              }
              break;
            case 'call_ended':
            case 'hangup':
              console.log('[WS] hangup received');
              if (browserSocket) browserSocket.send(JSON.stringify({ type: 'call_ended' }));
              if (androidSocket) androidSocket.send(JSON.stringify({ type: 'call_ended' }));
              break;
          }
        } catch (e) {
          console.warn('[WS] Malformed signal:', e);
        }
      } else {
        if (ws === browserSocket && androidSocket?.readyState === WebSocket.OPEN) {
          androidSocket.send(message);
        } else if (ws === androidSocket && browserSocket?.readyState === WebSocket.OPEN) {
          browserSocket.send(message);
        }
      }
    },
    close(ws) {
      if (ws === browserSocket) {
        browserSocket = null;
        if (androidSocket) androidSocket.send(JSON.stringify({ type: 'hangup' }));
        console.log('[WS] Browser disconnected');
      } else if (ws === androidSocket) {
        androidSocket = null;
        if (browserSocket) browserSocket.send(JSON.stringify({ type: 'hangup' }));
        console.log('[WS] Android disconnected');
      }
    }
  }
});

console.log(`[WS Server] Running on ws://${HOST}:${PORT}`);
