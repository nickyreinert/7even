// WebSocket speed-test worker.
// Protocol (matches client):
//   ping/pong  -> { type:"ping", seq, t0 } => { type:"pong", seq, t0 }
//   down       -> { type:"down_start", bytes } => server streams binary frames, then { type:"down_end" }
//   up         -> client streams binary frames, then { type:"up_end", bytesSent }
//                 server replies { type:"up_ack", bytesReceived }

const DOWN_CHUNK_SIZE = 65536; // 64KB per frame

export default {
  async fetch(request) {
    const upgradeHeader = request.headers.get('Upgrade');
    if (!upgradeHeader || upgradeHeader.toLowerCase() !== 'websocket') {
      return new Response('Expected WebSocket upgrade', { status: 426 });
    }

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);

    server.accept();

    let uploadBytesReceived = 0;
    let uploadInProgress = false;

    server.addEventListener('message', (event) => {
      const data = event.data;

      // Binary frame => part of an upload test
      if (data instanceof ArrayBuffer) {
        if (uploadInProgress) {
          uploadBytesReceived += data.byteLength;
        }
        return;
      }

      // Text frame => control message (JSON)
      let msg;
      try {
        msg = JSON.parse(data);
      } catch (e) {
        return; // ignore malformed control messages
      }

      switch (msg.type) {
        case 'ping': {
          server.send(JSON.stringify({ type: 'pong', seq: msg.seq, t0: msg.t0 }));
          break;
        }

        case 'down_start': {
          const totalBytes = Math.max(0, Math.min(msg.bytes || 0, 50_000_000)); // cap 50MB
          let sent = 0;
          const chunk = new Uint8Array(DOWN_CHUNK_SIZE);
          crypto.getRandomValues(chunk); // avoid trivially-compressible payload
          while (sent < totalBytes) {
            const remaining = totalBytes - sent;
            const toSend = remaining < DOWN_CHUNK_SIZE ? chunk.slice(0, remaining) : chunk;
            server.send(toSend);
            sent += toSend.byteLength;
          }
          server.send(JSON.stringify({ type: 'down_end', bytesSent: sent }));
          break;
        }

        case 'up_start': {
          uploadInProgress = true;
          uploadBytesReceived = 0;
          break;
        }

        case 'up_end': {
          uploadInProgress = false;
          server.send(JSON.stringify({ type: 'up_ack', bytesReceived: uploadBytesReceived }));
          break;
        }

        default:
          break;
      }
    });

    server.addEventListener('close', () => {
      // nothing to clean up; per-connection state above is GC'd with the closure
    });

    return new Response(null, { status: 101, webSocket: client });
  },
};
