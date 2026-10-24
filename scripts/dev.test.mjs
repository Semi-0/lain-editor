import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { startDevServer } from './dev.mjs';

async function fixture(t) {
  const root = await mkdtemp(join(tmpdir(), 'lain-dev-test-'));
  await mkdir(join(root, 'resources/public/lain-editor'), { recursive: true });
  await mkdir(join(root, 'target/lain-editor/public/lain-editor/compiled'), { recursive: true });
  await writeFile(join(root, 'resources/public/lain-editor/index.html'), '<title>Lain fixture</title>');
  await writeFile(join(root, 'resources/public/lain-editor/style.css'), '.pane{}');
  await writeFile(join(root, 'target/lain-editor/public/lain-editor/compiled/app.js'), 'console.log("fixture")');
  await writeFile(join(root, 'target/lain-editor/public/lain-editor/compiled/app.js.map'), '{}');
  t.after(() => rm(root, { recursive: true, force: true }));
  return root;
}

async function backend(t, handler) {
  const server = http.createServer(handler);
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(async () => {
    const closed = new Promise(resolve => server.close(resolve));
    server.closeAllConnections();
    await closed;
  });
  return `http://127.0.0.1:${server.address().port}`;
}

async function frontend(t, options = {}) {
  const application = await startDevServer({ root: await fixture(t), port: 0, ...options });
  t.after(() => application.close());
  return application;
}

function rawRequest(url, path, options = {}) {
  return new Promise((accept, reject) => {
    const request = http.request(url, { path, ...options }, response => {
      let body = '';
      response.setEncoding('utf8');
      response.on('data', chunk => { body += chunk; });
      response.on('end', () => accept({ status: response.statusCode, headers: response.headers, body }));
    });
    request.on('error', reject);
    request.end(options.body);
  });
}

test('serves only declared editor assets and rejects traversal', async t => {
  const application = await frontend(t);
  for (const [path, contentType] of [['/', 'text/html'], ['/style.css', 'text/css'], ['/compiled/app.js', 'text/javascript'], ['/compiled/app.js.map', 'application/json']]) {
    const response = await fetch(`${application.url}${path}`);
    assert.equal(response.status, 200);
    assert.ok(response.headers.get('content-type').startsWith(contentType));
  }
  assert.equal((await fetch(`${application.url}/style.css`, { method: 'HEAD' })).status, 200);
  for (const path of ['/../deps.edn', '/%2e%2e/deps.edn', '/compiled/../style.css', '/unknown']) {
    assert.equal((await rawRequest(application.url, path)).status, 404);
  }
  assert.equal((await fetch(`${application.url}/style.css`, { method: 'POST' })).status, 405);
});

test('proxies JSON unchanged and rewrites host/origin only after origin validation', async t => {
  const received = [];
  const address = await backend(t, async (request, response) => {
    let body = '';
    for await (const chunk of request) { body += chunk; }
    received.push({ method: request.method, path: request.url, headers: request.headers, body });
    response.writeHead(201, { 'content-type': 'application/json', 'x-runtime': 'fixture', 'access-control-allow-origin': '*' });
    response.end(JSON.stringify({ ok: true, result: { generation: 7 } }));
  });
  const application = await frontend(t, { backend: address });
  const body = '{"op":"run","pane":"a","source":"(+ 2 3)"}';
  const response = await fetch(`${application.url}/api`, {
    method: 'POST', headers: { origin: application.url, 'content-type': 'application/json' }, body,
  });
  assert.equal(response.status, 201);
  assert.deepEqual(await response.json(), { ok: true, result: { generation: 7 } });
  assert.equal(response.headers.get('x-runtime'), 'fixture');
  assert.equal(response.headers.get('access-control-allow-origin'), null);
  assert.equal(received.length, 1);
  assert.equal(received[0].body, body);
  assert.equal(received[0].method, 'POST');
  assert.equal(received[0].path, '/api');
  assert.equal(received[0].headers.host, new URL(address).host);
  assert.equal(received[0].headers.origin, address);
  const blocked = await fetch(`${application.url}/api`, {
    method: 'POST', headers: { origin: 'https://unrelated.example', 'content-type': 'application/json' }, body,
  });
  assert.equal(blocked.status, 403);
  assert.equal((await blocked.json()).ok, false);
  assert.equal(received.length, 1);
  assert.equal((await fetch(`${application.url}/api`, { method: 'OPTIONS' })).status, 405);
});

test('unavailable backend is an explicit 502 and never a fake runtime success', async t => {
  const temporary = http.createServer();
  temporary.listen(0, '127.0.0.1');
  await once(temporary, 'listening');
  const port = temporary.address().port;
  await new Promise(resolve => temporary.close(resolve));
  const application = await frontend(t, { backend: `http://127.0.0.1:${port}` });
  const response = await fetch(`${application.url}/api`, { method: 'POST', body: '{}' });
  assert.equal(response.status, 502);
  const body = await response.json();
  assert.equal(body.ok, false);
  assert.match(body.error, /Backend unavailable/);
});

test('shutdown cancels an in-flight proxy request', async t => {
  let accepted;
  const requestArrived = new Promise(resolve => { accepted = resolve; });
  const address = await backend(t, request => { request.resume(); accepted(); });
  const application = await startDevServer({ root: await fixture(t), port: 0, backend: address });
  const pending = fetch(`${application.url}/api`, { method: 'POST', body: '{}' }).catch(error => error);
  await requestArrived;
  await application.close();
  await application.close();
  const result = await pending;
  assert.ok(result instanceof Error);
});

test('configuration rejects non-loopback listeners and invalid backend origins', async () => {
  await assert.rejects(startDevServer({ host: '0.0.0.0', port: 0 }), /loopback/);
  await assert.rejects(startDevServer({ port: -1 }), /integer/);
  await assert.rejects(startDevServer({ backend: 'file:///tmp/backend' }), /HTTP/);
  await assert.rejects(startDevServer({ backend: 'http://127.0.0.1/private' }), /origin/);
});
