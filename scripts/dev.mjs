import http from 'node:http';
import https from 'node:https';
import { createReadStream } from 'node:fs';
import { stat } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const hopByHopHeaders = new Set([
  'connection', 'keep-alive', 'proxy-authenticate', 'proxy-authorization',
  'te', 'trailer', 'transfer-encoding', 'upgrade',
]);

function sendJson(response, status, body, headers = {}) {
  response.writeHead(status, { 'content-type': 'application/json; charset=utf-8', ...headers });
  response.end(JSON.stringify(body));
}

function endWithError(response, status, error) {
  if (response.headersSent) {
    response.destroy(error);
  } else {
    sendJson(response, status, { ok: false, error: error.message });
  }
}

function backendAddress(value) {
  const address = new URL(value);
  if (!['http:', 'https:'].includes(address.protocol)) {
    throw new Error('LAIN_EDITOR_BACKEND must use HTTP or HTTPS');
  }
  if (address.username || address.password || address.search || address.hash || address.pathname !== '/') {
    throw new Error('LAIN_EDITOR_BACKEND must be an origin without credentials, path, query, or fragment');
  }
  return address;
}

function forwardedHeaders(headers, backend) {
  const forwarded = {};
  for (const [name, value] of Object.entries(headers)) {
    if (!hopByHopHeaders.has(name)) {
      forwarded[name] = value;
    }
  }
  forwarded.host = backend.host;
  forwarded.origin = backend.origin;
  return forwarded;
}

function sameOrigin(request) {
  const origin = request.headers.origin;
  if (origin === undefined) {
    return true;
  }
  if (typeof origin !== 'string' || typeof request.headers.host !== 'string') {
    return false;
  }
  try {
    const expected = new URL(`http://${request.headers.host}`).origin;
    return origin === expected;
  } catch {
    return false;
  }
}

function proxyApi(request, response, backend, pending) {
  if (!sameOrigin(request)) {
    sendJson(response, 403, { ok: false, error: 'Cross-origin editor requests are not allowed' });
    return;
  }
  let transport = http;
  if (backend.protocol === 'https:') {
    transport = https;
  }
  const outgoing = transport.request(new URL('/api', backend), {
    method: 'POST', headers: forwardedHeaders(request.headers, backend),
  });
  pending.add(outgoing);
  outgoing.once('close', () => pending.delete(outgoing));
  outgoing.once('error', error => {
    const code = error.code ?? 'request failed';
    endWithError(response, 502, new Error(`Backend unavailable (${code})`));
  });
  outgoing.once('response', incoming => {
    const headers = {};
    for (const [name, value] of Object.entries(incoming.headers)) {
      if (!hopByHopHeaders.has(name) && !name.startsWith('access-control-')) {
        headers[name] = value;
      }
    }
    response.writeHead(incoming.statusCode, headers);
    incoming.once('error', error => response.destroy(error));
    incoming.pipe(response);
  });
  request.once('aborted', () => outgoing.destroy());
  response.once('close', () => {
    if (!response.writableFinished) {
      outgoing.destroy();
    }
  });
  request.pipe(outgoing);
}

async function serveFile(request, response, file, contentType) {
  try {
    const info = await stat(file);
    if (!info.isFile()) {
      sendJson(response, 404, { ok: false, error: 'Editor asset not found; build the frontend first' });
      return;
    }
    response.writeHead(200, {
      'content-type': contentType, 'content-length': info.size, 'cache-control': 'no-store',
    });
    if (request.method === 'HEAD') {
      response.end();
      return;
    }
    const source = createReadStream(file);
    source.once('error', error => response.destroy(error));
    response.once('close', () => source.destroy());
    source.pipe(response);
  } catch (error) {
    if (error.code === 'ENOENT') {
      sendJson(response, 404, { ok: false, error: 'Editor asset not found; build the frontend first' });
    } else {
      endWithError(response, 500, new Error('Editor asset could not be read'));
    }
  }
}

export function createDevServer({ root = repositoryRoot, backend = 'http://127.0.0.1:4780' } = {}) {
  const address = backendAddress(backend);
  const pending = new Set();
  let closing;
  const assets = new Map([
    ['/', [join(root, 'resources/public/lain-editor/index.html'), 'text/html; charset=utf-8']],
    ['/style.css', [join(root, 'resources/public/lain-editor/style.css'), 'text/css; charset=utf-8']],
    ['/compiled/app.js', [join(root, 'target/lain-editor/public/lain-editor/compiled/app.js'), 'text/javascript; charset=utf-8']],
    ['/compiled/app.js.map', [join(root, 'target/lain-editor/public/lain-editor/compiled/app.js.map'), 'application/json; charset=utf-8']],
  ]);
  const server = http.createServer((request, response) => {
    const path = request.url.split('?')[0];
    if (path === '/api') {
      if (request.method !== 'POST') {
        sendJson(response, 405, { ok: false, error: 'Editor API requires POST' }, { allow: 'POST' });
        return;
      }
      proxyApi(request, response, address, pending);
      return;
    }
    if (!['GET', 'HEAD'].includes(request.method)) {
      sendJson(response, 405, { ok: false, error: 'Editor assets require GET or HEAD' }, { allow: 'GET, HEAD' });
      return;
    }
    const asset = assets.get(path);
    if (asset === undefined) {
      sendJson(response, 404, { ok: false, error: 'Editor asset not found' });
      return;
    }
    void serveFile(request, response, ...asset);
  });
  server.on('clientError', (_error, socket) => socket.end('HTTP/1.1 400 Bad Request\r\n\r\n'));
  return {
    server,
    backend: address.origin,
    close() {
      if (closing !== undefined) {
        return closing;
      }
      for (const request of pending) {
        request.destroy();
      }
      closing = new Promise((accept, reject) => {
        server.close(error => {
          if (error) {
            reject(error);
          } else {
            accept();
          }
        });
        server.closeAllConnections();
      });
      return closing;
    },
  };
}

export async function startDevServer(options = {}) {
  const { port = 4781, host = '127.0.0.1', ...configuration } = options;
  if (!Number.isInteger(port) || port < 0 || port > 65535) {
    throw new Error('LAIN_EDITOR_PORT must be an integer between 0 and 65535');
  }
  if (!['127.0.0.1', '::1', 'localhost'].includes(host)) {
    throw new Error('The editor development server must bind to a loopback host');
  }
  const application = createDevServer(configuration);
  await new Promise((accept, reject) => {
    application.server.once('error', reject);
    application.server.listen(port, host, accept);
  });
  const address = application.server.address();
  let hostname = host;
  if (host === '::1') {
    hostname = '[::1]';
  }
  return { ...application, url: `http://${hostname}:${address.port}` };
}

if (process.argv[1] !== undefined && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const port = Number(process.env.LAIN_EDITOR_PORT ?? '4781');
  const host = process.env.LAIN_EDITOR_HOST ?? '127.0.0.1';
  const backend = process.env.LAIN_EDITOR_BACKEND ?? 'http://127.0.0.1:4780';
  try {
    const application = await startDevServer({ port, host, backend });
    console.log(`Lain editor: ${application.url} (backend ${application.backend})`);
    let stopping = false;
    const shutdown = async () => {
      if (stopping) {
        return;
      }
      stopping = true;
      try {
        await application.close();
      } catch (error) {
        console.error(error.message);
        process.exitCode = 1;
      }
    };
    process.once('SIGINT', shutdown);
    process.once('SIGTERM', shutdown);
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
