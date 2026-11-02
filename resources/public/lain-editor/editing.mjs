// Textarea editing assistance. Completion observes names; it never evaluates code.
const delimiter = /[\s()[\]{}";,\\]/;
const identifier = /^(?!:|[0-9])[^\s()[\]{}";,\\]+$/;

export function scan(source) {
  const tokens = [];
  let index = 0;
  while (index < source.length) {
    const start = index;
    const character = source[index];
    if (character === ';') {
      while (index < source.length && source[index] !== '\n') {
        index += 1;
      }
      tokens.push({ kind: 'comment', start, end: index });
    } else if (character === '"') {
      index += 1;
      let closed = false;
      while (index < source.length && !closed) {
        if (source[index] === '\\') {
          index += 2;
        } else if (source[index] === '"') {
          index += 1;
          closed = true;
        } else {
          index += 1;
        }
      }
      tokens.push({ kind: 'string', start, end: Math.min(index, source.length), closed });
    } else if (character === '\\') {
      index += 2;
      while (index < source.length && !delimiter.test(source[index])) {
        index += 1;
      }
      tokens.push({ kind: 'character', start, end: Math.min(index, source.length) });
    } else if (delimiter.test(character)) {
      index += 1;
    } else {
      while (index < source.length && !delimiter.test(source[index])) {
        index += 1;
      }
      tokens.push({ kind: 'symbol', start, end: index, text: source.slice(start, index) });
    }
  }
  return tokens;
}

export function context(source, start, end = start) {
  if (start !== end) {
    return null;
  }
  const tokens = scan(source);
  if (tokens.some(token => token.kind !== 'symbol' && token.start < start &&
      (start < token.end || (start === token.end &&
        (token.kind === 'comment' || token.kind === 'character' || token.closed === false))))) {
    return null;
  }
  const token = tokens.find(item => item.kind === 'symbol' && item.start <= start && start <= item.end);
  if (!token) {
    return { from: start, to: start, prefix: '' };
  }
  if (!identifier.test(token.text)) {
    return null;
  }
  return { from: token.start, to: token.end, prefix: source.slice(token.start, start) };
}

export function options(source, cursor, environment = []) {
  const selected = context(source, cursor);
  if (!selected) {
    return [];
  }
  const names = new Map();
  for (const token of scan(source)) {
    if (token.kind === 'symbol' && identifier.test(token.text) &&
        !['true', 'false', 'nil'].includes(token.text) &&
        !(token.start === selected.from && token.end === selected.to)) {
      names.set(token.text, { label: token.text, origin: 'document word' });
    }
  }
  for (const item of environment) {
    names.set(item.label, item);
  }
  return [...names.values()].filter(item => item.label.startsWith(selected.prefix))
    .sort((left, right) => left.label.localeCompare(right.label));
}

export function matchingBrackets(source, cursor, selectionEnd = cursor) {
  if (cursor !== selectionEnd) {
    return null;
  }
  const opening = '([{';
  const closing = ')]}';
  const brackets = [];
  const ignored = scan(source).filter(token => token.kind !== 'symbol');
  let ignoredIndex = 0;
  for (let position = 0; position < source.length; position += 1) {
    while (ignoredIndex < ignored.length && ignored[ignoredIndex].end <= position) {
      ignoredIndex += 1;
    }
    const token = ignored[ignoredIndex];
    if (token && token.start <= position && position < token.end) {
      position = token.end - 1;
    } else if (opening.includes(source[position]) || closing.includes(source[position])) {
      brackets.push(position);
    }
  }
  let selected = brackets.indexOf(cursor - 1);
  if (selected === -1) {
    selected = brackets.indexOf(cursor);
  }
  if (selected === -1) {
    return null;
  }
  const from = brackets[selected];
  let direction = 1;
  let starters = opening;
  let finishers = closing;
  if (closing.includes(source[from])) {
    direction = -1;
    starters = closing;
    finishers = opening;
  }
  const expected = [finishers[starters.indexOf(source[from])]];
  for (let index = selected + direction; index >= 0 && index < brackets.length; index += direction) {
    const position = brackets[index];
    const next = source[position];
    if (starters.includes(next)) {
      expected.push(finishers[starters.indexOf(next)]);
    } else if (next !== expected.pop()) {
      return { from, to: position, matched: false };
    } else if (expected.length === 0) {
      return { from, to: position, matched: true };
    }
  }
  return { from, to: null, matched: false };
}

function sourceField(field) {
  return field instanceof HTMLTextAreaElement &&
    field.matches('.pane textarea.code, .pane .extension textarea');
}

function generation(field) {
  return field.closest('.pane')?.dataset.generation;
}

function bracketRectangles(field, positions) {
  const computed = getComputedStyle(field);
  const rectangle = field.getBoundingClientRect();
  const mirror = document.createElement('div');
  mirror.className = 'lain-bracket-mirror';
  mirror.setAttribute('aria-hidden', 'true');
  for (const property of ['fontFamily', 'fontSize', 'fontWeight', 'fontStyle', 'fontVariant',
    'lineHeight', 'letterSpacing', 'wordSpacing', 'textTransform', 'textIndent', 'direction',
    'paddingTop', 'paddingBottom', 'paddingLeft', 'paddingRight', 'tabSize',
    'borderTopWidth', 'borderBottomWidth', 'borderLeftWidth', 'borderRightWidth']) {
    mirror.style[property] = computed[property];
  }
  Object.assign(mirror.style, { position: 'fixed', visibility: 'hidden', pointerEvents: 'none',
    boxSizing: 'border-box', borderStyle: 'solid', whiteSpace: 'pre-wrap', overflowWrap: 'break-word',
    left: `${rectangle.left}px`, top: `${rectangle.top}px`,
    width: `${field.clientWidth + parseFloat(computed.borderLeftWidth) + parseFloat(computed.borderRightWidth)}px` });
  if (field.wrap === 'off') {
    mirror.style.whiteSpace = 'pre';
  }
  const text = document.createTextNode(field.value);
  mirror.append(text);
  document.body.append(mirror);
  try {
    return positions.map(position => {
      const range = document.createRange();
      range.setStart(text, position);
      range.setEnd(text, position + 1);
      const glyph = range.getBoundingClientRect();
      return { left: glyph.left - field.scrollLeft, top: glyph.top - field.scrollTop,
        width: glyph.width, height: glyph.height, position };
    });
  } finally {
    mirror.remove();
  }
}

function installBracketMatching() {
  const overlay = document.createElement('div');
  overlay.className = 'lain-bracket-overlay';
  overlay.setAttribute('aria-hidden', 'true');
  document.body.append(overlay);
  let scheduled = false;
  let observed = null;
  const resize = new ResizeObserver(schedule);

  function update() {
    scheduled = false;
    overlay.replaceChildren();
    overlay.style.display = 'none';
    const field = document.activeElement;
    if (!sourceField(field)) {
      resize.disconnect();
      observed = null;
      return;
    }
    if (observed !== field) {
      resize.disconnect();
      resize.observe(field);
      observed = field;
    }
    const match = matchingBrackets(field.value, field.selectionStart, field.selectionEnd);
    if (!match) {
      return;
    }
    const rectangle = field.getBoundingClientRect();
    const left = rectangle.left + field.clientLeft;
    const top = rectangle.top + field.clientTop;
    Object.assign(overlay.style, { display: 'block', left: `${left}px`, top: `${top}px`,
      width: `${field.clientWidth}px`, height: `${field.clientHeight}px` });
    const positions = [match.from];
    if (match.to !== null) {
      positions.push(match.to);
    }
    for (const glyph of bracketRectangles(field, positions)) {
      const mark = document.createElement('span');
      mark.className = 'lain-matching-bracket';
      mark.dataset.position = String(glyph.position);
      mark.dataset.matched = String(match.matched);
      Object.assign(mark.style, { left: `${glyph.left - left - 1}px`, top: `${glyph.top - top}px`,
        width: `${glyph.width + 2}px`, height: `${glyph.height}px` });
      overlay.append(mark);
    }
  }

  function schedule() {
    if (!scheduled) {
      scheduled = true;
      requestAnimationFrame(update);
    }
  }
  for (const event of ['selectionchange', 'focusin', 'focusout', 'input', 'keyup', 'click']) {
    document.addEventListener(event, schedule);
  }
  window.addEventListener('scroll', schedule, true);
  window.addEventListener('resize', schedule);
  const app = document.getElementById('app');
  if (app) {
    new MutationObserver(schedule).observe(app, { childList: true, subtree: true });
  }
  schedule();
}

export function install() {
  installBracketMatching();
  let popup = null;
  let epoch = 0;
  let request = null;

  function hide() {
    epoch += 1;
    if (request) {
      request.abort();
      request = null;
    }
    if (popup) {
      popup.field.removeAttribute('aria-controls');
      popup.field.removeAttribute('aria-expanded');
      popup.field.removeAttribute('aria-activedescendant');
      popup.node.remove();
      popup = null;
    }
  }

  function validPopup() {
    if (!popup) {
      return false;
    }
    if (!popup.field.isConnected || popup.field.readOnly ||
        generation(popup.field) !== popup.generation) {
      hide();
      return false;
    }
    return true;
  }

  function accept(index) {
    if (!validPopup()) {
      return;
    }
    const item = popup.items[index];
    const field = popup.field;
    const selected = context(field.value, field.selectionStart, field.selectionEnd);
    if (!item || !selected) {
      hide();
      return;
    }
    field.setRangeText(item.label, selected.from, selected.to, 'end');
    hide();
    field.dispatchEvent(new Event('input', { bubbles: true }));
    field.focus();
  }

  function render() {
    if (!validPopup()) {
      return;
    }
    const { field, environment, node } = popup;
    if (!context(field.value, field.selectionStart, field.selectionEnd)) {
      hide();
      return;
    }
    popup.items = options(field.value, field.selectionStart, environment).slice(0, 80);
    popup.selected = Math.min(popup.selected, Math.max(0, popup.items.length - 1));
    node.replaceChildren();
    const status = document.createElement('div');
    status.className = 'lain-completions-status';
    status.setAttribute('role', 'status');
    status.textContent = popup.status;
    node.append(status);
    const list = document.createElement('div');
    list.id = 'lain-completion-options';
    list.setAttribute('role', 'listbox');
    list.setAttribute('aria-label', 'Code completions');
    node.append(list);
    popup.items.forEach((item, index) => {
      const option = document.createElement('div');
      option.id = `lain-completion-${index}`;
      option.setAttribute('role', 'option');
      option.setAttribute('aria-selected', String(index === popup.selected));
      const label = document.createElement('span');
      label.textContent = item.label;
      const origin = document.createElement('small');
      origin.textContent = item.origin;
      option.append(label, origin);
      option.addEventListener('pointerdown', event => {
        event.preventDefault();
        accept(index);
      });
      list.append(option);
    });
    field.setAttribute('aria-controls', list.id);
    field.setAttribute('aria-expanded', 'true');
    if (popup.items.length > 0) {
      field.setAttribute('aria-activedescendant', `lain-completion-${popup.selected}`);
    } else {
      field.removeAttribute('aria-activedescendant');
    }
    const rectangle = field.getBoundingClientRect();
    node.style.left = `${Math.max(8, Math.min(rectangle.left, innerWidth - 380))}px`;
    node.style.top = `${Math.max(8, Math.min(rectangle.bottom, innerHeight - 280))}px`;
    const active = list.children[popup.selected];
    if (active) {
      active.scrollIntoView({ block: 'nearest' });
    }
  }

  async function open(field) {
    hide();
    if (field.readOnly || !context(field.value, field.selectionStart, field.selectionEnd)) {
      return;
    }
    const pane = field.closest('.pane').dataset.paneId;
    const current = generation(field);
    const node = document.createElement('div');
    node.className = 'lain-completions';
    document.body.append(node);
    popup = { field, node, generation: current, environment: [], items: [], selected: 0,
      status: 'Document words · run this pane for environment names' };
    const requestEpoch = epoch;
    if (!current) {
      render();
      return;
    }
    popup.status = 'Reading session names…';
    render();
    const controller = new AbortController();
    request = controller;
    const timeout = setTimeout(() => controller.abort(), 10000);
    try {
      const response = await fetch('/api', {
        method: 'POST', credentials: 'same-origin', signal: controller.signal,
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ op: 'complete', pane, generation: current }),
      });
      if ([401, 403].includes(response.status)) {
        throw new Error('Sign in again to read session names');
      }
      const payload = await response.json();
      if (requestEpoch !== epoch || !validPopup()) {
        return;
      }
      if (!response.ok || !payload.ok || payload.result.generation !== current) {
        throw new Error(payload.error || 'Session changed');
      }
      popup.environment = payload.result.candidates;
      popup.status = 'Session names + document words';
      if (payload.result.truncated) {
        popup.status += ' · environment list truncated';
      }
    } catch (error) {
      if (requestEpoch !== epoch || !validPopup()) {
        return;
      }
      popup.status = `Document words only · ${error.message}`;
    } finally {
      clearTimeout(timeout);
      if (request === controller) {
        request = null;
      }
    }
    render();
  }

  document.addEventListener('keydown', event => {
    if (event.isComposing) {
      return;
    }
    if (event.ctrlKey && event.code === 'Space' && sourceField(event.target)) {
      event.preventDefault();
      event.stopImmediatePropagation();
      void open(event.target);
      return;
    }
    if (!validPopup() || event.target !== popup.field) {
      return;
    }
    if (['ArrowDown', 'ArrowUp'].includes(event.key) && popup.items.length > 0) {
      event.preventDefault();
      event.stopImmediatePropagation();
      let offset = -1;
      if (event.key === 'ArrowDown') {
        offset = 1;
      }
      popup.selected = (popup.selected + offset + popup.items.length) % popup.items.length;
      render();
    } else if (['Enter', 'Tab'].includes(event.key) && popup.items.length > 0 && !event.metaKey && !event.ctrlKey) {
      event.preventDefault();
      event.stopImmediatePropagation();
      accept(popup.selected);
    } else if (event.key === 'Escape') {
      event.preventDefault();
      event.stopImmediatePropagation();
      hide();
    } else if (['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key) || event.metaKey || event.ctrlKey) {
      hide();
    }
  }, true);
  document.addEventListener('input', event => {
    if (popup && event.target === popup.field) {
      popup.selected = 0;
      render();
    }
  });
  document.addEventListener('pointerdown', event => {
    if (popup && !popup.node.contains(event.target)) {
      hide();
    }
  });
  document.addEventListener('focusout', event => {
    if (popup && event.target === popup.field) {
      hide();
    }
  });
  window.addEventListener('scroll', event => {
    if (popup && !popup.node.contains(event.target)) {
      hide();
    }
  }, true);
  window.addEventListener('resize', hide);
  const app = document.getElementById('app');
  if (app) {
    new MutationObserver(validPopup).observe(app, { childList: true, subtree: true,
      attributes: true, attributeFilter: ['data-generation', 'readonly'] });
  }
}

if (typeof document !== 'undefined') {
  install();
}
