// Deterministic browser checks with a simulated gateway/API, not GX10 acceptance.
import assert from 'node:assert/strict';
import { chromium } from 'playwright';
import { startDevServer } from './dev.mjs';

const application = await startDevServer({ port: 0 });
let browser;
try {
  browser = await chromium.launch({ headless: true });
  for (const trigger of ['run', 'workspace-poll']) {
    const context = await browser.newContext({ acceptDownloads: true });
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    let expired = false;
    let expirePoll = false;
    let mutations = 0;
    let polls = 0;
    let sessionChecks = 0;
    let forcedStatus;
    let sessionStatus = 200;
    await page.route('**/api', async route => {
      const command = route.request().postDataJSON();
      if (command.op === 'workspace-poll') { polls++; }
      if (command.op === 'run') { mutations++; }
      if (forcedStatus !== undefined) {
        await route.fulfill({ status: forcedStatus, body: 'Forbidden' });
      } else if ((trigger === 'run' && command.op === 'run') || (expirePoll && command.op === 'workspace-poll')) {
        expired = true;
        await route.fulfill({ status: 401, body: 'Session expired' });
      } else {
        await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ ok: true, result: command.op === 'files/write' ? { path: command.path, source: command.source, version: 'saved', exists: true } : {} }) });
      }
    });
    await page.route('**/auth/session', async route => {
      sessionChecks++;
      if (sessionStatus !== 200) {
        await route.fulfill({ status: sessionStatus, body: 'Connection rejected' });
        return;
      }
      forcedStatus = undefined;
      expired = false;
      expirePoll = false;
      await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ ok: true, result: { authenticated: true } }) });
    });
    await page.goto(application.url);
    // The editor's source textarea is identified by its pane, independent of CSS classes.
    const editor = page.locator('.pane textarea').first();
    await editor.waitFor();
    await editor.fill('(define draft 123)');
    if (trigger === 'run') {
      await editor.press('Control+Enter');
      const save = page.getByRole('dialog', { name: 'Save before Run', exact: true });
      await save.getByRole('textbox', { name: 'Workspace-relative file path' }).fill('auth-draft.lain');
      await save.getByRole('button', { name: 'Save file', exact: true }).click();
    } else {
      expirePoll = true;
    }
    await page.getByText('Session expired', { exact: true }).waitFor();
    assert.ok(expired);
    assert.equal(await editor.inputValue(), '(define draft 123)');
    const pausedPolls = polls;
    await page.waitForTimeout(2200);
    assert.equal(polls, pausedPolls, 'Polling pauses after expiry');
    await editor.fill('(define draft 456)');
    const downloadReady = page.waitForEvent('download');
    await page.getByRole('button', { name: 'Download workspace', exact: true }).click();
    const download = await downloadReady;
    const stream = await download.createReadStream();
    let body = '';
    for await (const chunk of stream) { body += chunk; }
    assert.ok(JSON.parse(body).panes.some(pane => pane.source === '(define draft 456)'));
    const popupReady = page.waitForEvent('popup');
    await page.getByRole('link', { name: 'Sign in', exact: true }).click();
    const popup = await popupReady;
    await popup.waitForLoadState();
    assert.equal(new URL(popup.url()).pathname, '/auth/complete');
    assert.equal(await popup.locator('#app, script').count(), 0);
    assert.match(await popup.textContent('body'), /Resume connection/);
    await popup.close();
    const beforeResume = mutations;
    await page.getByRole('button', { name: 'Resume connection', exact: true }).click();
    await page.locator('.connection-notice').waitFor({ state: 'hidden' });
    await page.waitForTimeout(1200);
    assert.equal(sessionChecks, 1);
    assert.equal(mutations, beforeResume, 'Interrupted mutations are never replayed');
    assert.equal(await editor.inputValue(), '(define draft 456)');
    forcedStatus = 403;
    await page.getByText('Access forbidden', { exact: true }).waitFor();
    assert.equal(await editor.inputValue(), '(define draft 456)');
    sessionStatus = 502;
    await page.getByRole('button', { name: 'Resume connection', exact: true }).click();
    await page.getByText('Backend unavailable or authentication configuration failure (502)', { exact: true }).waitFor();
    assert.equal(await page.getByText('Session expired', { exact: true }).count(), 0);
    sessionStatus = 401;
    await page.getByRole('button', { name: 'Resume connection', exact: true }).click();
    await page.getByText('Session expired', { exact: true }).waitFor();
    assert.equal(mutations, beforeResume);
    assert.deepEqual(errors, []);
    console.log(`PASS ${trigger} expiry: pause, draft editing/export, sign-in confirmation, explicit resume, no replay`);
    await context.close();
  }
} finally {
  if (browser) { await browser.close(); }
  await application.close();
}
