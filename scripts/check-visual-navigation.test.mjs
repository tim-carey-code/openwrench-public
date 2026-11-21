// Contract tests for optional local navigation selectors; synthetic XML is not device evidence.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { tapLabel, assertLargeFontNavigation } from './visual-navigation-assertions.mjs';
const helper = readFileSync(new URL('./visual-navigation-assertions.mjs', import.meta.url), 'utf8');
const app = readFileSync(new URL('../kotlin/androidApp/src/main/kotlin/org/openwrench/frontend/DashboardApp.kt', import.meta.url), 'utf8');
const rail = readFileSync(new URL('../kotlin/androidApp/src/main/kotlin/org/openwrench/frontend/DiagnosticWorkspace.kt', import.meta.url), 'utf8');
const labels = [...helper.match(/const labels = \[([^\]]+)\]/)[1].matchAll(/'([^']+)'/g)].map(m => m[1]);
const calls = [];
const context = { assertLargeFontNavigation, tapLabel: (xml, pattern) => tapLabel(xml, pattern, args => calls.push(args)) };
const fixture = labels.map((label, i) => `<node text="${label}" class="android.widget.TextView" enabled="true" bounds="[${i % 2 * 390},${1200 + Math.floor(i / 2) * 100}][${i % 2 * 390 + 300},${1250 + Math.floor(i / 2) * 100}]"/>`).join('');
test('local helper labels match the actual phone and rail destinations', () => {
  for (const [i, id] of ['DIAGNOSIS', 'DASHBOARD', 'HISTORY', 'ADVANCED'].entries()) {
    assert(app.includes(`${id}("${labels[i]}")`));
    assert(rail.includes(`"${labels[i]}"`));
  }
});
test('large-text checker accepts separate rows and rejects missing destination', () => {
  context.assertLargeFontNavigation(fixture);
  assert.throws(() => context.assertLargeFontNavigation(fixture.replace('Live Sensors', 'old name')), /missing/);
});
test('label tap chooses the observed node and never guesses missing coordinates', () => {
  calls.length = 0;
  assert(context.tapLabel(fixture, /^Live Sensors$/));
  assert.deepEqual(Array.from(calls[0]), ['shell', 'input', 'tap', '540', '1225']);
  assert.equal(context.tapLabel(fixture, /^Absent$/), false);
  assert.equal(calls.length, 1);
});

test('remaining Android workflows check out the reviewed head explicitly', () => {
  const native = readFileSync(new URL('../.github/workflows/android.yml', import.meta.url), 'utf8');
  const replay = readFileSync(new URL('../.github/workflows/android-replay-review.yml', import.meta.url), 'utf8');
  for (const source of [replay, native]) {
    assert(source.includes('ref: ${{ github.event.pull_request.head.sha || github.sha }}'));
    assert(source.includes('persist-credentials: false'));
  }
});
