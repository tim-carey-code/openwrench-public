// Synthetic hierarchy fixtures test the acceptance checker, not Android rendering.
import test from 'node:test';
import assert from 'node:assert/strict';
import { checkGraphCapture, checkGraphScroll } from './check-single-column-graphs.mjs';

const expectedLabels = ['Engine speed', 'Coolant', 'Load', 'Throttle'];
const box = (l, t, r, b) => `[${Math.round(l)},${Math.round(t)}][${Math.round(r)},${Math.round(b)}]`;
function capture({ width = 1280, density = 1, fontScale = 1, short = false, mode = 'graphs', labels = expectedLabels.slice(0, 2), grid = false, narrow = false, scrollable = true } = {}) {
  const inset = (short ? 24 : 12) * density;
  const left = inset + 58 * Math.max(1, fontScale) * density;
  const right = width * density - inset;
  return `<hierarchy><node content-desc="Scrollable ${mode}${short ? ' view' : ''}" scrollable="${scrollable}" bounds="${box(0, 0, width * density, 600)}">` + labels.map((label, i) => {
    const row = grid ? Math.floor(i / 2) : i;
    const l = grid && i % 2 ? width * density / 2 + left : left;
    const r = grid && !(i % 2) || narrow ? width * density / 2 - inset : right;
    return `<node content-desc="${label}: 0 received observations in 60 seconds; gaps remain empty" bounds="${box(l, row * 130 + 10, r, row * 130 + 110)}"/>`;
  }).join('') + '</node></hierarchy>';
}
for (const variant of [
  { name: 'phone', width: 390, density: 2 },
  { name: 'tablet', width: 1280 },
  { name: 'landscape and full screen', width: 900, short: true },
  { name: 'large text Both mode', width: 390, density: 2, fontScale: 2, short: true, mode: 'both' },
]) test(`${variant.name}: full width, ordered and last graph reachable`, () => {
  const options = { density: 1, fontScale: 1, ...variant, expectedLabels };
  assert.deepEqual(checkGraphScroll([capture(options), capture({ ...options, labels: expectedLabels.slice(2) })], options), expectedLabels);
});
test('rejects former 2x2 tablet layout', () => assert.throws(() => checkGraphCapture(capture({ grid: true, labels: expectedLabels }), { density: 1, fontScale: 1 }), /available width/));
test('rejects a half-width orphan even when only one graph is visible', () => assert.throws(() => checkGraphCapture(capture({ narrow: true, labels: expectedLabels.slice(0, 1) }), { density: 1, fontScale: 1 }), /available width/));
test('rejects non-scrollable long selection', () => assert.throws(() => checkGraphCapture(capture({ scrollable: false }), { density: 1, fontScale: 1 }), /scrolling/));
test('rejects missing last selected graph', () => assert.throws(() => checkGraphScroll([capture(), capture()], { density: 1, fontScale: 1, expectedLabels }), /every selected/));
test('rejects reordered selected graphs', () => assert.throws(() => checkGraphScroll([capture({ labels: ['Coolant', 'Engine speed'] }), capture({ labels: expectedLabels.slice(2) })], { density: 1, fontScale: 1, expectedLabels }), /reordered/));
test('does not accept an empty or unrelated UI as layout evidence', () => assert.throws(() => checkGraphCapture('<hierarchy/>', { density: 1, fontScale: 1 }), /viewport/));
