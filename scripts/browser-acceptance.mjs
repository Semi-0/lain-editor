import assert from 'node:assert/strict';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const url = process.env.LAIN_EDITOR_URL ?? 'http://127.0.0.1:4781';
const output = resolve(process.env.LAIN_EDITOR_BROWSER_OUTPUT ?? join(root, 'target/browser-acceptance'));
const timeout = Number(process.env.LAIN_EDITOR_BROWSER_TIMEOUT ?? '30000');
await mkdir(output, { recursive: true });
const requiredChecks = [
  'application loads without page errors',
  'editing retains focus and cursor across derived updates',
  'command palette opens and dismisses',
  'run reaches the real configured runtime',
  'topology renders runtime nodes',
  'layout selects display current settings and refine the chosen budget',
  'zoom remains stable across inspection refreshes',
  'pan remains stable across inspection refreshes',
  'node detail opens and closes without changing the camera',
  'fullscreen preserves the mounted graph and camera',
  'desktop switching preserves source and camera',
  'split close and archive reopen preserve the draft',
  'save dialog cancels without writing files',
  'source extension reaches the existing runtime session',
  'legacy example workspace restores through its public file input',
  'multi-node topology keeps exact rendered cards apart',
  'learned no-good values retain recursive structural rendering',
  'persisted stack composition selects the matching option',
  'recorded learning replay scrubs and inspects frozen values',
  'fold cell selection survives a desktop remount',
  'no browser errors were reported',
];
const report = { url, startedAt: new Date().toISOString(), requiredChecks, checks: [], pageErrors: [], consoleErrors: [], passed: false };
let browser;
let context;
let page;

async function check(name, operation) {
  try {
    await operation();
    report.checks.push({ name, outcome: 'passed' });
    console.log(`PASS ${name}`);
  } catch (error) {
    report.checks.push({ name, outcome: 'failed', error: error.message });
    throw error;
  }
}

function cameraNumbers(transform) {
  const values = transform.match(/-?\d+(?:\.\d+)?(?:e[+-]?\d+)?/gi).map(Number);
  assert.equal(values.length, 3, `Unexpected camera transform: ${transform}`);
  return { x: values[0], y: values[1], scale: values[2] };
}

async function probeFullscreenEscape(browser) {
  const context = await browser.newContext();
  try {
    const probe = await context.newPage();
    await probe.setContent('<button id="fullscreen-probe" onclick="document.documentElement.requestFullscreen()">Enter fullscreen</button>');
    await probe.locator('#fullscreen-probe').click();
    await probe.waitForFunction(() => document.fullscreenElement !== null);
    await probe.keyboard.press('Escape');
    await probe.waitForTimeout(300);
    const supported = await probe.evaluate(() => document.fullscreenElement === null);
    if (!supported) {
      await probe.evaluate(() => document.exitFullscreen());
    }
    return supported;
  } finally {
    await context.close();
  }
}

async function paletteCommand(label) {
  await page.keyboard.press('Control+k');
  const palette = page.locator('dialog.command-palette');
  await palette.waitFor({ state: 'visible' });
  await palette.getByRole('searchbox', { name: 'Search commands' }).fill(label);
  await palette.getByRole('button', { name: label, exact: true }).click();
  await palette.waitFor({ state: 'hidden' });
}

async function visualizerSettings() {
  await page.keyboard.press('Control+k');
  const palette = page.locator('dialog.command-palette');
  await palette.waitFor({ state: 'visible' });
  await palette.getByRole('searchbox', { name: 'Search commands' }).fill('Visualizer settings');
  await palette.getByRole('button', { name: 'Visualizer settings…', exact: true }).click();
  await palette.locator('.visualizer-settings').waitFor({ state: 'visible' });
}

try {
  browser = await chromium.launch({ headless: process.env.LAIN_EDITOR_HEADED !== '1' });
  report.browserVersion = browser.version();
  report.nativeFullscreenEscapeSupported = await probeFullscreenEscape(browser);
  context = await browser.newContext({ storageState: process.env.LAIN_EDITOR_BROWSER_STORAGE_STATE, viewport: { width: 1440, height: 1000 } });
  page = await context.newPage();
  page.setDefaultTimeout(timeout);
  page.on('pageerror', error => {
    report.pageErrors.push(error.message);
    console.error(`PAGE ERROR: ${error.stack}`);
  });
  page.on('console', message => {
    if (message.type() === 'error') {
      report.consoleErrors.push(message.text());
    }
  });
  const layoutRequests = [];
  page.on('request', request => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/api') {
      try {
        const command = request.postDataJSON();
        if (command.op === 'layout') {
          layoutRequests.push(command);
        }
      } catch {
        // Non-JSON requests are reported by the application rather than this observer.
      }
    }
  });
  await page.goto(url, { waitUntil: 'networkidle' });
  const code = page.getByRole('textbox', { name: 'Lain code 1', exact: true });
  await check('application loads without page errors', async () => {
    await code.waitFor({ state: 'visible' });
    assert.deepEqual(report.pageErrors, []);
  });
  const source = '(define x 2)\n(define y (+ x 3))\n(display y)';
  await check('editing retains focus and cursor across derived updates', async () => {
    await code.fill(source);
    await code.focus();
    await code.evaluate(element => element.setSelectionRange(4, 4));
    await page.waitForTimeout(2200);
    assert.deepEqual(await code.evaluate(element => ({
      focused: document.activeElement === element,
      start: element.selectionStart, end: element.selectionEnd, value: element.value,
    })), { focused: true, start: 4, end: 4, value: source });
    const selected = await code.evaluate(element => element.closest('.pane').classList.contains('selected'));
    assert.equal(selected, true);
  });
  await check('command palette opens and dismisses', async () => {
    await page.keyboard.press('Control+k');
    await page.locator('dialog.command-palette').waitFor({ state: 'visible' });
    await page.keyboard.press('Escape');
    await page.locator('dialog.command-palette').waitFor({ state: 'hidden' });
    await code.focus();
  });
  await check('run reaches the real configured runtime', async () => {
    const response = page.waitForResponse(async incoming => {
      if (new URL(incoming.url()).pathname !== '/api' || incoming.request().method() !== 'POST') {
        return false;
      }
      return incoming.request().postDataJSON().op === 'run';
    });
    await code.press('Control+Enter');
    const result = await response;
    assert.equal(result.ok(), true);
    const body = await result.json();
    assert.equal(body.ok, true, body.error);
    assert.ok(body.result.generation !== undefined);
    await page.waitForFunction(() => {
      const value = document.querySelector('.pane.selected .value-view');
      return value !== null && value.textContent.includes('5');
    }, null, { timeout });
  });
  const svg = page.locator('.pane.selected .graph-svg').first();
  await check('topology renders runtime nodes', async () => {
    await paletteCommand('Show topology');
    await svg.waitFor({ state: 'visible' });
    await page.waitForFunction(() => {
      const scroller = document.querySelector('.pane.selected .graph-scroller');
      const node = document.querySelector('.pane.selected .graph-node');
      if (scroller === null || node === null || scroller.clientHeight === 0) {
        return false;
      }
      const frame = scroller.getBoundingClientRect();
      const card = node.getBoundingClientRect();
      return card.bottom > frame.top && card.top < frame.bottom && card.right > frame.left && card.left < frame.right;
    }, null, { timeout });
    await page.waitForFunction(() => {
      const graph = document.querySelector('.pane.selected .graph-svg');
      return graph !== null && graph.dataset.layoutPhase === 'settled';
    }, null, { timeout });
  });
  await check('layout selects display current settings and refine the chosen budget', async () => {
    assert.equal(await page.locator('.pane .layout-controls,.pane .value-fold-menu').count(), 0);
    await visualizerSettings();
    const budget = page.getByRole('combobox', { name: 'Layout time budget', exact: true });
    assert.equal(await budget.inputValue(), '1000');
    assert.equal(await budget.locator('option:checked').textContent(), '1 second');
    assert.equal(layoutRequests[0].budgetMs, 1000);
    const before = layoutRequests.length;
    await budget.selectOption('3000');
    assert.equal(await budget.inputValue(), '3000');
    assert.equal(await budget.locator('option:checked').textContent(), '3 seconds');
    await page.waitForTimeout(100);
    assert.equal(layoutRequests.length, before, 'selecting a budget must not compute a new layout');
    const response = page.waitForResponse(incoming => {
      if (new URL(incoming.url()).pathname !== '/api' || incoming.request().method() !== 'POST') {
        return false;
      }
      const command = incoming.request().postDataJSON();
      return command.op === 'layout' && command.refine === true && command.budgetMs === 3000;
    });
    await page.getByRole('button', { name: 'Refine layout', exact: true }).click();
    const result = await response;
    assert.equal(result.ok(), true);
    assert.equal((await result.json()).ok, true);
    report.selects = { defaultBudgetMs: 1000, selectedRefinementBudgetMs: 3000 };
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => document.querySelector('.pane.selected .graph-svg')?.dataset.layoutPhase === 'settled', null, { timeout });
  });
  const camera = svg.locator(':scope > g');
  await check('zoom remains stable across inspection refreshes', async () => {
    const box = await svg.boundingBox();
    assert.ok(box);
    await page.mouse.move(box.x + Math.min(box.width / 2, 240), box.y + Math.min(box.height / 2, 180));
    const before = await camera.getAttribute('transform');
    await page.mouse.wheel(0, 250);
    await page.waitForFunction(previous => {
      const transform = document.querySelector('.pane.selected .graph-svg > g');
      return transform !== null && transform.getAttribute('transform') !== previous;
    }, before);
    const afterZoom = await camera.getAttribute('transform');
    report.camera = { beforeZoom: cameraNumbers(before), afterZoom: cameraNumbers(afterZoom) };
    const layoutsAfterZoom = layoutRequests.length;
    await page.waitForTimeout(2400);
    assert.equal(await camera.getAttribute('transform'), afterZoom);
    assert.equal(layoutRequests.length, layoutsAfterZoom, 'unchanged topology or zoom must not trigger layout');
  });
  await check('pan remains stable across inspection refreshes', async () => {
    const box = await svg.boundingBox();
    assert.ok(box);
    const point = { x: box.x + Math.min(box.width - 20, box.width * 0.8), y: box.y + Math.min(box.height - 20, box.height * 0.8) };
    const before = await camera.getAttribute('transform');
    await page.mouse.move(point.x, point.y);
    await page.mouse.down();
    await page.mouse.move(point.x + 32, point.y + 24, { steps: 4 });
    await page.mouse.up();
    const afterPan = await camera.getAttribute('transform');
    const from = cameraNumbers(before);
    const to = cameraNumbers(afterPan);
    report.camera.beforePan = from;
    report.camera.afterPan = to;
    assert.notEqual(afterPan, before);
    assert.ok(Math.abs(to.x - from.x - 32) < 1, 'pan must follow the horizontal pointer delta');
    assert.ok(Math.abs(to.y - from.y - 24) < 1, 'pan must follow the vertical pointer delta');
    assert.equal(to.scale, from.scale, 'pan must preserve zoom');
    const layoutsAfterPan = layoutRequests.length;
    await page.waitForTimeout(2400);
    assert.equal(await camera.getAttribute('transform'), afterPan);
    assert.equal(layoutRequests.length, layoutsAfterPan, 'pan must not trigger layout');
  });
  const originalPane = await page.locator('.pane.selected').getAttribute('data-pane-id');
  await check('node detail opens and closes without changing the camera', async () => {
    const before = await camera.getAttribute('transform');
    await page.locator('.pane.selected .graph-node').first().click();
    const close = page.getByRole('button', { name: 'Close node view', exact: true });
    await close.waitFor({ state: 'visible' });
    await page.waitForFunction(() => {
      const body = document.querySelector('.pane.selected .node-detail-body');
      return body !== null && !body.textContent.includes('Reading runtime node');
    }, null, { timeout });
    await close.click();
    await close.waitFor({ state: 'hidden' });
    assert.equal(await camera.getAttribute('transform'), before);
  });
  await check('fullscreen preserves the mounted graph and camera', async () => {
    const originalGraph = await svg.elementHandle();
    const before = await camera.getAttribute('transform');
    await paletteCommand('Toggle fullscreen visualizer');
    await page.waitForFunction(() => document.fullscreenElement !== null, null, { timeout });
    assert.equal(await originalGraph.evaluate(element => element.isConnected), true);
    assert.equal(await originalGraph.evaluate(element => element === document.querySelector('.pane.selected .graph-svg')), true);
    await page.keyboard.press('Escape');
    if (report.nativeFullscreenEscapeSupported) {
      await page.waitForFunction(() => document.fullscreenElement === null, null, { timeout });
      report.fullscreen = { exitMethod: 'Escape', escapeOutcome: 'passed' };
    } else {
      report.fullscreen = {
        exitMethod: 'Fullscreen API', escapeOutcome: 'blocked',
        notes: 'Injected Escape does not exit fullscreen in a minimal standalone page in this browser; API exit verifies the editor fullscreenchange lifecycle.',
      };
      await page.evaluate(() => document.exitFullscreen());
      await page.waitForFunction(() => document.fullscreenElement === null, null, { timeout });
    }
    assert.equal(await originalGraph.evaluate(element => element.isConnected), true);
    assert.equal(await camera.getAttribute('transform'), before);
  });
  await check('desktop switching preserves source and camera', async () => {
    const before = await camera.getAttribute('transform');
    const originalSource = await code.inputValue();
    await paletteCommand('New desktop');
    await page.waitForFunction(() => document.querySelectorAll('.desktop-bar button').length === 2);
    report.newDesktopInitialFocus = await page.evaluate(() => document.activeElement?.matches('.pane.selected textarea.code'));
    const newPane = await page.locator('.pane.selected').getAttribute('data-pane-id');
    const newCode = page.locator('.pane.selected textarea.code').first();
    await newCode.fill('(define result 99)');
    await page.locator('.desktop-bar button').nth(0).click();
    await page.waitForFunction(id => document.querySelector('.pane.selected')?.dataset.paneId === id, originalPane);
    assert.equal(await page.locator('.pane.selected textarea.code').first().inputValue(), originalSource);
    await page.locator('.pane.selected .graph-node').first().waitFor({ state: 'visible' });
    assert.equal(await page.locator('.pane.selected .graph-svg > g').first().getAttribute('transform'), before);
    await page.locator('.desktop-bar button').nth(1).click();
    await page.waitForFunction(id => document.querySelector('.pane.selected')?.dataset.paneId === id, newPane);
    assert.equal(await page.locator('.pane.selected textarea.code').first().inputValue(), '(define result 99)');
    await page.locator('.desktop-bar button').nth(0).click();
    await page.waitForFunction(id => document.querySelector('.pane.selected')?.dataset.paneId === id, originalPane);
  });
  await check('split close and archive reopen preserve the draft', async () => {
    await paletteCommand('Split side by side');
    await page.waitForFunction(() => document.querySelectorAll('#workspace .pane').length === 2);
    await page.waitForFunction(() => document.activeElement?.matches('.pane.selected textarea.code'));
    const draft = page.locator('.pane.selected textarea.code').first();
    await draft.fill('archive me');
    const closedId = await page.locator('.pane.selected').getAttribute('data-pane-id');
    await paletteCommand('Close block');
    await page.waitForFunction(() => document.querySelectorAll('#workspace .pane').length === 1);
    await paletteCommand('Reopen closed block: Untitled block (#1)');
    await page.waitForFunction(() => document.querySelectorAll('#workspace .pane').length === 2);
    assert.equal(await page.locator('.pane.selected textarea.code').first().inputValue(), 'archive me');
    assert.notEqual(await page.locator('.pane.selected').getAttribute('data-pane-id'), closedId);
  });
  await check('save dialog cancels without writing files', async () => {
    const writes = [];
    const observe = request => {
      if (request.method() === 'POST' && new URL(request.url()).pathname === '/api') {
        const command = request.postDataJSON();
        if (command.op === 'save-block' || command.op === 'save-workspace') {
          writes.push(command);
        }
      }
    };
    page.on('request', observe);
    await paletteCommand('Save block to file…');
    const dialog = page.getByRole('dialog', { name: 'Save block to file', exact: true });
    await dialog.waitFor({ state: 'visible' });
    await dialog.getByRole('textbox', { name: 'Project-relative file path' }).fill('should-not-be-written.lain');
    await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
    await dialog.waitFor({ state: 'hidden' });
    page.off('request', observe);
    assert.equal(writes.length, 0);
  });
  await check('source extension reaches the existing runtime session', async () => {
    await page.locator(`.pane[data-pane-id="${originalPane}"] textarea.code`).first().click();
    await paletteCommand('Toggle extension editor');
    const extension = '(define next (+ y 1))';
    await page.getByRole('textbox', { name: 'Extension code', exact: true }).fill(extension);
    const response = page.waitForResponse(incoming => {
      if (new URL(incoming.url()).pathname !== '/api' || incoming.request().method() !== 'POST') {
        return false;
      }
      return incoming.request().postDataJSON().op === 'extend';
    });
    await page.getByRole('button', { name: 'Apply extension', exact: true }).click();
    const result = await response;
    assert.equal(result.ok(), true);
    const body = await result.json();
    assert.equal(body.ok, true, body.error);
    await paletteCommand('Toggle display layers');
    const binding = page.getByRole('textbox', { name: 'Output binding', exact: true });
    await binding.fill('next');
    await binding.press('Enter');
    await page.waitForFunction(() => document.querySelector('.pane.selected .value-view')?.textContent.includes('6'));
    assert.equal(await page.getByRole('textbox', { name: 'Extension 1 code', exact: true }).inputValue(), extension);
  });
  await check('legacy example workspace restores through its public file input', async () => {
    const bytes = await readFile(join(root, 'resources/example-workspace.json'));
    const fixture = JSON.parse(bytes.toString('utf8'));
    const response = page.waitForResponse(incoming => {
      if (new URL(incoming.url()).pathname !== '/api' || incoming.request().method() !== 'POST') {
        return false;
      }
      return incoming.request().postDataJSON().op === 'restore';
    });
    await page.locator('#open').setInputFiles({ name: 'example-workspace.json', mimeType: 'application/json', buffer: bytes });
    const result = await response;
    assert.equal(result.ok(), true);
    const body = await result.json();
    assert.equal(body.ok, true, body.error);
    await page.waitForFunction(() => document.querySelectorAll('#workspace .pane').length === 3);
    assert.equal(await page.locator('.pane.selected textarea.code').first().inputValue(), fixture.panes[0].source);
  });
  await check('multi-node topology keeps exact rendered cards apart', async () => {
    await page.waitForFunction(() => {
      const graph = document.querySelector('.pane.selected .graph-svg');
      const scroller = document.querySelector('.pane.selected .graph-scroller');
      return graph !== null && scroller !== null && scroller.clientHeight > 0
        && graph.dataset.layoutPhase === 'settled' && graph.querySelectorAll(':scope > g > .graph-node').length > 1;
    }, null, { timeout });
    const geometry = await page.evaluate(() => {
      const graph = document.querySelector('.pane.selected .graph-svg');
      return {
        transform: graph.querySelector(':scope > g').getAttribute('transform'),
        nodes: [...graph.querySelectorAll(':scope > g > .graph-node')].map(node => {
          const frame = node.querySelector(':scope > .node-frame').getBoundingClientRect();
          return { id: node.dataset.nodeId, left: frame.left, right: frame.right, top: frame.top, bottom: frame.bottom };
        }),
      };
    });
    const scale = cameraNumbers(geometry.transform).scale;
    let minimumGap = Infinity;
    for (let a = 0; a < geometry.nodes.length; a += 1) {
      for (let b = a + 1; b < geometry.nodes.length; b += 1) {
        const first = geometry.nodes[a];
        const second = geometry.nodes[b];
        const gap = Math.max(first.left - second.right, second.left - first.right, first.top - second.bottom, second.top - first.bottom) / scale;
        minimumGap = Math.min(minimumGap, gap);
        assert.ok(gap >= 27.5, `Cards overlap or lose clearance: ${first.id} / ${second.id}: ${gap}`);
      }
    }
    report.clearance = { nodes: geometry.nodes.length, minimumGapWorldUnits: minimumGap };
  });
  await check('learned no-good values retain recursive structural rendering', async () => {
    const bytes = await readFile(join(root, 'test/fixtures/amb-workspace.json'));
    const response = page.waitForResponse(incoming => {
      if (new URL(incoming.url()).pathname !== '/api' || incoming.request().method() !== 'POST') {
        return false;
      }
      return incoming.request().postDataJSON().op === 'restore';
    });
    await page.locator('#open').setInputFiles({ name: 'amb-workspace.json', mimeType: 'application/json', buffer: bytes });
    const result = await response;
    assert.equal(result.ok(), true);
    const body = await result.json();
    assert.equal(body.ok, true, body.error);
    await page.waitForFunction(() => document.querySelector('.pane.selected')?.dataset.paneId === 'amb-search');
    await page.locator('.pane.selected .value-card').first().waitFor({ state: 'visible' });
    await page.waitForFunction(() => {
      const value = document.querySelector('.pane.selected .value-card');
      return value !== null && /no.?good|learn/i.test(value.textContent)
        && value.querySelectorAll('.linked-list,.value-tree-branch').length > 0;
    }, null, { timeout });
    report.learning = await page.locator('.pane.selected .value-card').first().evaluate(element => ({
      text: element.textContent, linkedLists: element.querySelectorAll('.linked-list').length,
      structuralBranches: element.querySelectorAll('.value-tree-branch').length,
    }));
  });
  await check('persisted stack composition selects the matching option', async () => {
    await paletteCommand('Toggle display layers');
    const composition = page.getByRole('combobox', { name: 'Layer composition', exact: true });
    assert.equal(await composition.inputValue(), 'stack');
    assert.equal(await composition.locator('option:checked').textContent(), 'Stack');
    assert.equal(await page.locator('.pane.selected .layer-stage').evaluate(element => element.classList.contains('stack')), true);
    report.selects.persistedLayerMode = await composition.inputValue();
    await paletteCommand('Toggle display layers');
  });
  await check('recorded learning replay scrubs and inspects frozen values', async () => {
    await page.locator('.desktop-bar button').nth(2).click();
    await page.locator('.pane.selected .replay-view').waitFor({ state: 'visible' });
    const slider = page.getByRole('slider', { name: 'Recorded observation', exact: true });
    await page.waitForFunction(() => {
      const slider = document.querySelector('.pane.selected input[aria-label="Recorded observation"]');
      return slider !== null && Number(slider.max) >= 1;
    }, null, { timeout });
    await slider.press('Home');
    await page.waitForFunction(() => document.querySelector('.pane.selected .replay-view')?.dataset.replayIndex === '0');
    assert.equal(await page.getByRole('button', { name: 'Previous observation', exact: true }).isDisabled(), true);
    await page.getByRole('button', { name: 'Next observation', exact: true }).click();
    await page.waitForFunction(() => Number(document.querySelector('.pane.selected .replay-view')?.dataset.replayIndex) > 0);
    await slider.press('End');
    await page.waitForFunction(() => {
      const replay = document.querySelector('.pane.selected .replay-view');
      const slider = document.querySelector('.pane.selected input[aria-label="Recorded observation"]');
      return replay !== null && slider !== null && replay.dataset.replayIndex === slider.max;
    });
    assert.equal(await page.getByRole('button', { name: 'Next observation', exact: true }).isDisabled(), true);
    const liveNodeReads = [];
    const observe = request => {
      if (request.method() === 'POST' && new URL(request.url()).pathname === '/api') {
        const command = request.postDataJSON();
        if (command.op === 'inspect' && command.node !== undefined) {
          liveNodeReads.push(command);
        }
      }
    };
    page.on('request', observe);
    const node = page.locator('.pane.selected .graph-svg .graph-node[data-kind="cell"]').first();
    await node.focus();
    await node.press('Enter');
    await page.locator('.pane.selected .node-value-section[aria-label="strongest"]').waitFor({ state: 'visible' });
    await page.locator('.pane.selected .node-value-section[aria-label="content"]').waitFor({ state: 'visible' });
    assert.equal(liveNodeReads.length, 0, 'recorded detail must not issue live node inspection');
    page.off('request', observe);
    report.replay = {
      lastIndex: Number(await slider.getAttribute('max')),
      inspectedFrozenNode: await node.getAttribute('data-node-id'),
      liveNodeReads: liveNodeReads.length,
    };
    await page.getByRole('button', { name: 'Close node view', exact: true }).click();
  });
  await check('fold cell selection survives a desktop remount', async () => {
    await visualizerSettings();
    const menu = page.locator('.visualizer-settings .value-fold-menu').first();
    if (!(await menu.evaluate(element => element.open))) {
      await menu.locator(':scope > summary').click();
    }
    const select = page.getByRole('combobox', { name: 'Cell value to fold or unfold', exact: true });
    const options = await select.locator('option').evaluateAll(elements => elements.map(element => ({ value: element.value, label: element.textContent })));
    const original = await select.inputValue();
    const alternative = options.find(option => option.value !== original);
    assert.ok(alternative, 'the recorded semantic graph must provide multiple inspectable cells');
    await select.selectOption(alternative.value);
    assert.equal(await select.inputValue(), alternative.value);
    await page.keyboard.press('Escape');
    await page.locator('.desktop-bar button').nth(0).click();
    await page.waitForFunction(() => document.querySelector('.pane.selected')?.dataset.paneId === 'amb-search');
    await page.locator('.desktop-bar button').nth(2).click();
    await page.waitForFunction(() => document.querySelector('.pane.selected')?.dataset.paneId === 'amb-replay');
    await visualizerSettings();
    const restoredMenu = page.locator('.visualizer-settings .value-fold-menu').first();
    if (!(await restoredMenu.evaluate(element => element.open))) {
      await restoredMenu.locator(':scope > summary').click();
    }
    const restored = page.getByRole('combobox', { name: 'Cell value to fold or unfold', exact: true });
    assert.equal(await restored.inputValue(), alternative.value);
    assert.equal(await restored.locator('option:checked').textContent(), alternative.label);
    report.selects.restoredFoldCell = alternative.value;
    await restoredMenu.locator(':scope > summary').click();
    await page.keyboard.press('Escape');
  });
  await check('no browser errors were reported', async () => {
    assert.deepEqual(report.pageErrors, []);
    assert.deepEqual(report.consoleErrors, []);
  });
  await page.screenshot({ path: join(output, 'editor.png'), fullPage: true });
  report.layoutRequests = layoutRequests.length;
  report.passed = true;
} catch (error) {
  report.failure = { message: error.message, stack: error.stack };
  process.exitCode = 1;
  console.error(error.stack);
  if (page !== undefined) {
    try {
      await page.screenshot({ path: join(output, 'failure.png'), fullPage: true });
      await writeFile(join(output, 'failure.html'), await page.content());
    } catch (captureError) {
      report.screenshotError = captureError.message;
    }
  }
} finally {
  for (const name of requiredChecks) {
    if (!report.checks.some(check => check.name === name)) {
      report.checks.push({ name, outcome: 'not-run' });
    }
  }
  report.finishedAt = new Date().toISOString();
  await writeFile(join(output, 'report.json'), `${JSON.stringify(report, null, 2)}\n`);
  if (context !== undefined) {
    await context.close();
  }
  if (browser !== undefined) {
    await browser.close();
  }
}
