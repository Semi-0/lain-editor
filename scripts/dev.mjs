import http from 'node:http';
import https from 'node:https';
import { createReadStream, readFileSync } from 'node:fs';
import { timingSafeEqual } from 'node:crypto';
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

function protectedConfiguration(mode, publicOrigin, gatewayCredentialFile, backendCredentialFile, backend) {
  if (!['local', 'protected'].includes(mode)) {
    throw new Error('LAIN_EDITOR_MODE must be local or protected');
  }
  if (mode === 'local') {
    return null;
  }
  let origin;
  try {
    origin = new URL(publicOrigin);
  } catch {
    throw new Error('Protected mode requires a public HTTPS origin');
  }
  if (origin.protocol !== 'https:' || origin.origin !== publicOrigin) {
    throw new Error('Protected mode requires an exact public HTTPS origin without path or credentials');
  }
  if (!['127.0.0.1', '[::1]', 'localhost'].includes(backend.hostname)) {
    throw new Error('Protected backend must use a loopback host');
  }
  function credential(file, name) {
    let value;
    try {
      value = readFileSync(file, 'utf8').trim();
    } catch {
      throw new Error(`Protected mode requires a readable ${name} credential file`);
    }
    if (!/^[A-Za-z0-9_-]{32,}$/.test(value)) {
      throw new Error(`${name} credential must contain at least 32 base64url characters`);
    }
    return value;
  }
  const gateway = credential(gatewayCredentialFile, 'gateway');
  const service = credential(backendCredentialFile, 'backend');
  if (gateway === service) {
    throw new Error('Gateway and backend credentials must be distinct');
  }
  return { publicOrigin, gateway, service };
}

function gatewayAccepted(request, protection) {
  const supplied = request.headers['x-lain-gateway'];
  if (typeof supplied !== 'string') {
    return false;
  }
  const expected = Buffer.from(protection.gateway);
  const actual = Buffer.from(supplied);
  return actual.length === expected.length && timingSafeEqual(actual, expected);
}

function forwardedHeaders(headers, backend, protection) {
  // Protected transport uses an allowlist: browser identity and proxy metadata
  // have no meaning to the shared runtime.
  if (protection) {
    return { host: backend.host, origin: backend.origin,
      'content-type': 'application/json', authorization: `Bearer ${protection.service}` };
  }
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

function proxyApi(request, response, backend, pending, protection) {
  let validOrigin = sameOrigin(request);
  if (protection) {
    validOrigin = request.headers.origin === protection.publicOrigin;
  }
  if (!validOrigin) {
    sendJson(response, 403, { ok: false, error: 'Cross-origin editor requests are not allowed' });
    return;
  }
  let transport = http;
  if (backend.protocol === 'https:') {
    transport = https;
  }
  const outgoing = transport.request(new URL('/api', backend), {
    method: 'POST', headers: forwardedHeaders(request.headers, backend, protection),
  });
  pending.add(outgoing);
  outgoing.once('close', () => pending.delete(outgoing));
  outgoing.once('error', () => {
    endWithError(response, 502, new Error('Backend unavailable (connection failed)'));
  });
  outgoing.once('response', incoming => {
    if (protection && [401, 403].includes(incoming.statusCode)) {
      incoming.resume();
      sendJson(response, 502, { ok: false, error: 'Backend authentication configuration failure' });
      return;
    }
    const headers = {};
    for (const [name, value] of Object.entries(incoming.headers)) {
      if ((!protection || ['content-type', 'content-length'].includes(name)) && !hopByHopHeaders.has(name) && !name.startsWith('access-control-')) {
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

export function createDevServer({ root = repositoryRoot, backend = 'http://127.0.0.1:4780',
  mode = 'local', publicOrigin, gatewayCredentialFile, backendCredentialFile } = {}) {
  const address = backendAddress(backend);
  const protection = protectedConfiguration(mode, publicOrigin, gatewayCredentialFile, backendCredentialFile, address);
  const pending = new Set();
  let closing;
  const assets = new Map([
    ['/', [join(root, 'resources/public/lain-editor/index.html'), 'text/html; charset=utf-8']],
    ['/style.css', [join(root, 'resources/public/lain-editor/style.css'), 'text/css; charset=utf-8']],
    ['/compiled/app.js', [join(root, 'target/lain-editor/public/lain-editor/compiled/app.js'), 'text/javascript; charset=utf-8']],
    ['/compiled/app.js.map', [join(root, 'target/lain-editor/public/lain-editor/compiled/app.js.map'), 'application/json; charset=utf-8']],
  ]);
  const server = http.createServer((request, response) => {
    if (protection) {
      response.setHeader('cache-control', 'no-store');
    }
    if (protection && !gatewayAccepted(request, protection)) {
      sendJson(response, 403, { ok: false, error: 'Authenticated gateway required' });
      return;
    }
    const path = request.url.split('?')[0];
    if (['/auth/session', '/auth/complete'].includes(path) && ['GET', 'HEAD'].includes(request.method)) {
      response.setHeader('cache-control', 'no-store');
      if (path === '/auth/session') {
        sendJson(response, 200, { ok: true, result: { authenticated: true } });
      } else {
        response.writeHead(200, { 'content-type': 'text/html; charset=utf-8',
          'content-security-policy': "default-src 'none'; frame-ancestors 'none'" });
        response.end('<!doctype html><title>Signed in</title><h1>Signed in</h1><p>Return to the editor tab and select Resume connection.</p>');
      }
      return;
    }
    if (path === '/api') {
      if (request.method !== 'POST') {
        sendJson(response, 405, { ok: false, error: 'Editor API requires POST' }, { allow: 'POST' });
        return;
      }
      proxyApi(request, response, address, pending, protection);
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
    const application = await startDevServer({ port, host, backend,
      mode: process.env.LAIN_EDITOR_MODE ?? 'local',
      publicOrigin: process.env.LAIN_EDITOR_PUBLIC_ORIGIN,
      gatewayCredentialFile: process.env.LAIN_EDITOR_GATEWAY_CREDENTIAL_FILE,
      backendCredentialFile: process.env.LAIN_EDITOR_BACKEND_CREDENTIAL_FILE });
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
