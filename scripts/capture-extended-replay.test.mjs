import test from 'node:test';
import assert from 'node:assert/strict';
import { replayClock, graphPids, unavailableArchivePids, pickerOwnsForeground, readFreshReplayHierarchy, assertReplayPlotsVisible, waitForImportedReplay, assertDenseReplayValues } from './capture-extended-replay.mjs';

test('parses merged replay clock and rejects an unknown duration', () => {
  assert.equal(replayClock('<node text="Synthetic replay · Not live&#10;Paused · 3:33 / 5:00"/>'), 213);
  assert.throws(() => replayClock('<node text="Playing · 0:30 / 4:00"/>'), /Five-minute/);
});
test('recognizes null channel metadata inside merged row text and accessibility descriptions', () => {
  const xml = '<node text="Hybrid battery remaining life&#10;015B · Retained replay channel · ECU capability unverified · Unavailable&#10;On"/>' +
    '<node content-desc="Diesel dosing&#10;01A5 · Retained replay channel · ECU capability unverified · Unavailable"/>';
  assert.deepEqual([...unavailableArchivePids(xml, new Set(['015B', '01A5']))].sort(), ['015B', '01A5']);
  assert.deepEqual([...graphPids(xml)].sort(), ['015B', '01A5']);
  assert.throws(() => unavailableArchivePids('<node text="015B · supported · Value supplied"/>', new Set(['015B'])), /Misleading/);
});

test('picker requires both resumed activity and current focus from the complete window dump', () => {
  const activity = 'topResumedActivity=ActivityRecord{988c304 u0 com.android.documentsui/.picker.PickActivity t9}';
  const window = 'mCurrentFocus=Window{b6955c9 u0 com.android.documentsui/com.android.documentsui.picker.PickActivity}';
  assert(pickerOwnsForeground(activity, window, 'com.android.documentsui'));
  assert(!pickerOwnsForeground(activity, 'WINDOW MANAGER WINDOWS\npackage=com.android.documentsui', 'com.android.documentsui'));
  assert(!pickerOwnsForeground(activity, window.replaceAll('com.android.documentsui', 'org.openwrench.android'), 'com.android.documentsui'));
  assert(!pickerOwnsForeground('', window, 'com.android.documentsui'));
});


test('fresh replay hierarchy removes stale XML and retries a null-root dump', () => {
  let file = '<hierarchy><node text="Old diagnosis"/></hierarchy>', dumps = 0;
  const calls = [], evidence = [];
  const fresh = '<hierarchy><node text="Import or replay a saved drive"/></hierarchy>';
  const adb = args => {
    calls.push(args);
    if (args[1] === 'getprop') return '1\n';
    if (args[1] === 'rm') { file = null; return ''; }
    if (args[1] === 'uiautomator') { if (++dumps === 2) file = fresh; return dumps === 1 ? 'ERROR: null root node' : 'Dump complete'; }
    if (args[0] === 'exec-out') { if (file === null) throw new Error('No fresh file'); return file; }
    if (args[1] === 'sleep') return '';
    throw new Error('Unexpected command');
  };
  assert.equal(readFreshReplayHierarchy({ adb, onAttempt: x => evidence.push(x) }), fresh);
  assert.deepEqual(evidence.map(x => x.ok), [false, true]);
  assert.equal(calls.filter(x => x[1] === 'rm').length, 2);
  assert.equal(calls.filter(x => x[1] === 'sleep').length, 1);
});

test('fresh replay hierarchy fails after bounded empty dumps and rejects a non-emulator', () => {
  let dumps = 0, waits = 0;
  const adb = args => {
    if (args[1] === 'getprop') return '1';
    if (args[1] === 'uiautomator') dumps++;
    if (args[1] === 'sleep') waits++;
    return args[0] === 'exec-out' ? '<hierarchy></hierarchy>' : '';
  };
  assert.throws(() => readFreshReplayHierarchy({ adb }), /after 4 attempts/);
  assert.equal(dumps, 4); assert.equal(waits, 3);
  assert.throws(() => readFreshReplayHierarchy({ adb: () => '0' }), /job emulator/);
});

test('fresh hierarchy never retries removal failure or suppresses an app crash', () => {
  let dump = false;
  const adb = args => {
    if (args[1] === 'getprop') return '1';
    if (args[1] === 'rm') throw new Error('Removal failed');
    dump = true; return '';
  };
  assert.throws(() => readFreshReplayHierarchy({ adb }), /Removal failed/);
  assert(!dump);
  assert.throws(() => readFreshReplayHierarchy({ adb, checkForCrash: () => { throw new Error('App crashed'); } }), /App crashed/);
});

test('replay workflow uses fresh hierarchy capture with retained crash and picker gates', async () => {
  const { readFileSync } = await import('node:fs');
  const workflow = readFileSync(new URL('../.github/workflows/android-replay-review.yml', import.meta.url), 'utf8');
  assert(workflow.includes('const xml = readFreshReplayHierarchy({ adb, checkForCrash,'));
  assert(workflow.includes("save('last-successful-hierarchy.xml', xml)"));
  assert(workflow.includes("save('final-screen.png'"));
  assert(workflow.includes("save('hierarchy-attempts.json'"));
  assert(workflow.includes("throw new Error('Resolved document picker did not own the foreground')"));
  assert(!workflow.includes("cat', '/sdcard/openwrench-visual.xml'"));
});


function replayGraphs(height = 180, options = {}) {
  return '<hierarchy><node text="Synthetic replay · Not live"/><node content-desc="Scrollable graphs" bounds="[80,150][1280,800]"/>' +
    ['Engine speed', 'Coolant temperature'].map((label, i) => {
      const left = 100 + i * 590, right = left + 550, bottom = 210 + height;
      return '<node text="' + label + '" bounds="[' + left + ',170][' + right + ',200]"/>' +
        '<node content-desc="' + label + ': ' + (options.empty ? 0 : 58) + ' received observations in 60 seconds; gaps remain empty; paused" bounds="[' + left + ',210][' + right + ',' + bottom + ']"/>' +
        (options.noTick ? '' : '<node text="0 s" bounds="[' + (right - 30) + ',' + (bottom + 1) + '][' + right + ',' + (bottom + 19) + ']"/>');
    }).join('') + '</hierarchy>';
}
test('replay gap gate accepts full actual plots without a fixed demo count', () => {
  const evidence = assertReplayPlotsVisible(replayGraphs());
  assert.equal(evidence.plots.length, 2);
  assert.equal(evidence.plots[0].observations, 58);
});
test('replay gap gate rejects below-fold/clipped plots, missing axes and empty traces', () => {
  for (const height of [6, 9, 100]) assert.throws(() => assertReplayPlotsVisible(replayGraphs(height)), /fully visible/);
  assert.throws(() => assertReplayPlotsVisible(replayGraphs(180, { noTick: true })), /time axis/);
  assert.throws(() => assertReplayPlotsVisible(replayGraphs(180, { empty: true })), /no received/);
  assert.throws(() => assertReplayPlotsVisible(replayGraphs().replace('Scrollable graphs', 'Scrollable both')), /Graphs viewport/);
});


test('import wait observes asynchronous provider result instead of a fixed delay', async () => {
  let checks = 0, pauses = 0, foreground = 0;
  const ready = '<hierarchy><node text="Imported for playback only"/><node text="Play Synthetic recording · fictional values"/></hierarchy>';
  const result = await waitForImportedReplay({
    requireApp: async () => { foreground++; },
    hierarchy: () => ++checks < 3 ? '<hierarchy><node text="Loop a previous drive"/></hierarchy>' : ready,
    pause: async ms => { assert.equal(ms, 250); pauses++; },
  });
  assert.equal(result, ready); assert.equal(checks, 3); assert.equal(pauses, 2); assert.equal(foreground, 3);
});
test('import wait is bounded and does not accept only a partial marker', async () => {
  let checks = 0, pauses = 0;
  await assert.rejects(waitForImportedReplay({
    requireApp: async () => {},
    hierarchy: () => { checks++; return '<hierarchy><node text="Imported for playback only"/></hierarchy>'; },
    pause: async () => { pauses++; },
  }), /after 8 fresh hierarchy checks/);
  assert.equal(checks, 8); assert.equal(pauses, 7);
});
test('import wait propagates lost foreground and hierarchy errors without hiding them', async () => {
  await assert.rejects(waitForImportedReplay({ requireApp: async () => { throw new Error('lost foreground'); },
    hierarchy: () => assert.fail('must not read'), pause: async () => {} }), /lost foreground/);
  await assert.rejects(waitForImportedReplay({ requireApp: async () => {},
    hierarchy: () => { throw new Error('fresh dump failed'); }, pause: async () => {} }), /fresh dump failed/);
});


function denseValuesXml(count = 24, clipped = false, duplicate = false) {
  return '<hierarchy><node text="Synthetic replay · Not live"/><node content-desc="Scrollable values" bounds="[72,170][1280,800]"/>' +
    Array.from({ length: count }, (_, i) => {
      const pid = '01' + (duplicate ? 4 : i + 4).toString(16).toUpperCase().padStart(2, '0');
      const l = 82 + i % 6 * 195, t = 230 + Math.floor(i / 6) * 110;
      return '<node content-desc="Retained PID ' + pid + ' value tile: Label · 0 unit · Available. Historical replay, not live." bounds="[' + l + ',' + t + '][' + (l + 189) + ',' + (t + (clipped ? 9 : 104)) + ']"/>';
    }).join('') + '</hierarchy>';
}
const denseExpected = new Set(Array.from({ length: 55 }, (_, i) => '01' + (i + 4).toString(16).toUpperCase().padStart(2, '0')));
test('dense replay evidence requires twenty-four complete distinct visible values', () => {
  assert.equal(assertDenseReplayValues(denseValuesXml(), denseExpected).simultaneousCompleteValues, 24);
  assert.throws(() => assertDenseReplayValues(denseValuesXml(23), denseExpected), /need at least 24/);
  assert.throws(() => assertDenseReplayValues(denseValuesXml(24, true), denseExpected), /need at least 24/);
  assert.throws(() => assertDenseReplayValues(denseValuesXml().replaceAll(',334]', ',350]'), denseExpected), /need at least 24/);
  assert.throws(() => assertDenseReplayValues(denseValuesXml(24, false, true), denseExpected), /need at least 24/);
  assert.throws(() => assertDenseReplayValues(denseValuesXml().replaceAll('Historical replay, not live', 'Live'), denseExpected), /provenance/);
  assert.throws(() => assertDenseReplayValues(denseValuesXml(), new Set()), /invented a channel/);
});
