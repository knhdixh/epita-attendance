// Local-only pass-through for simulating an unreachable server during process restart.
import http from 'node:http';
let offline = false;
const server = http.createServer((request, response) => {
  if (request.url === '/__e2e/offline' && request.method === 'POST') {
    offline = true;
    response.writeHead(204).end();
    return;
  }
  if (offline) { request.socket.destroy(); return; }
  const upstream = http.request({ host: '127.0.0.1', port: 8788, method: request.method,
    path: request.url, headers: request.headers }, reply => {
    response.writeHead(reply.statusCode, reply.headers);
    reply.pipe(response);
  });
  upstream.on('error', () => { if (!response.headersSent) response.writeHead(503); response.end(); });
  request.pipe(upstream);
});
server.listen(8787, '127.0.0.1');
