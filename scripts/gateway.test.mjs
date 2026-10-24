// Real Caddy routing against controlled auth/runtime fixtures; not real Authelia acceptance.
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { mkdtemp, readFile, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawn } from 'node:child_process';
import test from 'node:test';
import { startDevServer } from './dev.mjs';

async function listen(server) {
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  return server.address().port;
}
async function close(server) {
  server.closeAllConnections();
  await new Promise(resolve => server.close(resolve));
}

test('Caddy protects website/API, injects gateway only after auth, and fails closed', { skip: !process.env.LAIN_CADDY_BINARY }, async t => {
  const directory = await mkdtemp(join(tmpdir(), 'lain-caddy-test-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  await writeFile(join(directory, 'gateway'), 'g'.repeat(48));
  await writeFile(join(directory, 'backend'), 'b'.repeat(48));
  await writeFile(join(directory, 'gateway-header.caddy'), `header_up X-Lain-Gateway "${'g'.repeat(48)}"\n`);
  let status = 302;
  const auth = http.createServer((request, response) => {
    assert.equal(request.url, '/api/authz/forward-auth');
    response.writeHead(status, { location: 'https://auth.lain.home.arpa/', 'content-type': 'text/plain' });
    response.end('auth fixture');
  });
  const authPort = await listen(auth);
  let authClosed = false;
  t.after(async () => { if (!authClosed) { await close(auth); } });
  let runtimeCalls = 0;
  const runtime = http.createServer((request, response) => {
    runtimeCalls++;
    assert.equal(request.headers.authorization, `Bearer ${'b'.repeat(48)}`);
    request.resume();
    response.end('{"ok":true,"result":{}}');
  });
  const runtimePort = await listen(runtime);
  t.after(() => close(runtime));
  const editor = await startDevServer({ port: 0, mode: 'protected', publicOrigin: 'https://editor.lain.home.arpa',
    backend: `http://127.0.0.1:${runtimePort}`, gatewayCredentialFile: join(directory, 'gateway'), backendCredentialFile: join(directory, 'backend') });
  t.after(() => editor.close());
  const reservation = http.createServer();
  const ingressPort = await listen(reservation);
  await close(reservation);
  let configuration = await readFile(new URL('../deploy/gx10/Caddyfile.in', import.meta.url), 'utf8');
  configuration = configuration.replace('__GX10_ADDRESS__', '127.0.0.1').replace('__CLIENT_SUBNET__', '127.0.0.0/8')
    .replace('https://auth.lain.home.arpa', `http://auth.localhost:${ingressPort}`)
    .replace('https://editor.lain.home.arpa', `http://127.0.0.1:${ingressPort}`)
    .replaceAll('127.0.0.1:9091', `127.0.0.1:${authPort}`)
    .replaceAll('127.0.0.1:4781', new URL(editor.url).host)
    .replaceAll('/etc/lain-editor/caddy/gateway-header.caddy', join(directory, 'gateway-header.caddy'));
  const config = join(directory, 'Caddyfile');
  await writeFile(config, configuration);
  const caddy = spawn(process.env.LAIN_CADDY_BINARY, ['run', '--config', config, '--adapter', 'caddyfile'], { stdio: ['ignore', 'ignore', 'pipe'] });
  let diagnostics = '';
  caddy.stderr.on('data', chunk => { diagnostics += chunk; });
  t.after(async () => {
    if (caddy.exitCode === null) { caddy.kill('SIGTERM'); await once(caddy, 'exit'); }
  });
  const url = `http://127.0.0.1:${ingressPort}`;
  let ready = false;
  for (let attempt = 0; attempt < 100 && !ready; attempt++) {
    try { await fetch(url, { redirect: 'manual' }); ready = true; }
    catch { await new Promise(resolve => setTimeout(resolve, 30)); }
  }
  assert.ok(ready, diagnostics.replaceAll('g'.repeat(48), '[REDACTED]'));
  assert.equal((await fetch(url, { redirect: 'manual' })).status, 302);
  for (const path of ['/api', '/auth/session']) {
    const response = await fetch(`${url}${path}`, { redirect: 'manual', headers: { 'x-lain-gateway': 'forged' } });
    assert.equal(response.status, 401);
    assert.equal(response.headers.get('location'), null);
  }
  assert.equal(runtimeCalls, 0);
  status = 403;
  assert.equal((await fetch(`${url}/api`)).status, 403);
  status = 200;
  assert.equal((await fetch(`${url}/auth/session`)).status, 200);
  const api = await fetch(`${url}/api`, { method: 'POST', headers: { origin: 'https://editor.lain.home.arpa', 'x-lain-gateway': 'forged' }, body: '{}' });
  assert.equal(api.status, 200);
  assert.equal(runtimeCalls, 1);
  await close(auth); authClosed = true;
  assert.equal((await fetch(`${url}/auth/session`)).status, 502);
  assert.equal(runtimeCalls, 1);
});
