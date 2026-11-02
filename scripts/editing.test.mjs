import { test } from 'node:test';
import assert from 'node:assert/strict';
import { context, options, matchingBrackets } from '../resources/public/lain-editor/editing.mjs';

test('nested matches work in both directions and on either side of the caret', () => {
  const source = '(+ 1 (* 2 3))';
  assert.deepEqual(matchingBrackets(source, 0), { from: 0, to: 12, matched: true });
  assert.deepEqual(matchingBrackets(source, 1), { from: 0, to: 12, matched: true });
  assert.deepEqual(matchingBrackets(source, 13), { from: 12, to: 0, matched: true });
  assert.deepEqual(matchingBrackets(source, 6), { from: 5, to: 11, matched: true });
});

test('mixed and multiline brackets retain nesting', () => {
  const source = '(network [x]\n  {x (+ x 1)})';
  assert.deepEqual(matchingBrackets(source, 10), { from: 9, to: 11, matched: true });
  assert.deepEqual(matchingBrackets(source, source.length), { from: source.length - 1, to: 0, matched: true });
});

test('unmatched and incorrectly nested delimiters are distinguished', () => {
  assert.deepEqual(matchingBrackets('(display', 1), { from: 0, to: null, matched: false });
  assert.deepEqual(matchingBrackets(')', 1), { from: 0, to: null, matched: false });
  assert.deepEqual(matchingBrackets('([)]', 1), { from: 0, to: 2, matched: false });
  assert.deepEqual(matchingBrackets('(]', 2), { from: 1, to: 0, matched: false });
});

test('strings, escaped strings, comments and character literals are opaque', () => {
  const source = '(list "[ignored]" ; ) ignored\n \\) 1)';
  assert.deepEqual(matchingBrackets(source, 1), { from: 0, to: source.length - 1, matched: true });
  assert.equal(matchingBrackets(source, source.indexOf('[') + 1), null);
  assert.equal(matchingBrackets(source, source.indexOf('; )') + 3), null);
  const character = String.raw`(list \) 1)`;
  assert.equal(matchingBrackets(character, character.indexOf('\\') + 2), null);
  for (const text of ['; dis', '"escaped \\" dis', ':dis', '123', '\\dis']) {
    assert.equal(context(text, text.length), null, text);
  }
});

test('nonempty selections and positions away from brackets clear matching', () => {
  assert.equal(matchingBrackets('(hello)', 3), null);
  assert.equal(matchingBrackets('(hello)', 0, 7), null);
  assert.equal(matchingBrackets('', 0), null);
  assert.equal(context('display', 0, 3), null);
});

test('document completion excludes lexical literals and the current token', () => {
  const source = '(define document-total 9) "document-string" ; document-comment\n\\document-character\n(doc';
  assert.deepEqual(options(source, source.length), [{ label: 'document-total', origin: 'document word' }]);
});

test('environment names replace duplicate document words without evaluation', () => {
  const source = '(define display 1) (dis';
  assert.deepEqual(options(source, source.length, [{ label: 'display', origin: 'environment' }]),
    [{ label: 'display', origin: 'environment' }]);
});

test('completing in the middle replaces the entire symbol', () => {
  assert.deepEqual(context('(display)', 4), { from: 1, to: 8, prefix: 'dis' });
  assert.deepEqual(context('(define x "hello") (', 20), { from: 20, to: 20, prefix: '' });
});
