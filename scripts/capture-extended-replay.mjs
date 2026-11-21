// Integration-only gate: explicitly synthetic file, disposable verified CI emulator.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { nodes, workspaceDriver, assertView } from './capture-graph-workspace.mjs';

export const FIXTURE_NAME = 'SYNTHETIC-Legacy-inspired-55-channel-5min-drive.json';
export const FIXTURE_SHA = '6cdbe18be974af4b2ab4a5f7f966575e2200628ea04651ee987238a97da3dc6b';
export function pickerOwnsForeground(activity, windows, packageName) {
  return activity.split('\n').some(line => /(?:mResumedActivity|topResumedActivity)/.test(line) && line.includes(packageName + '/')) &&
    windows.split('\n').some(line => line.includes('mCurrentFocus=') && line.includes(packageName + '/'));
}

// Never accept a previous dump after UIAutomator reports a null root during a route transition.
// All calls remain on the already job-owned, offline emulator; failures stay failures.
export function readFreshReplayHierarchy({ adb, onAttempt = () => {}, checkForCrash = () => {} }) {
  assert.equal(adb(['shell', 'getprop', 'ro.kernel.qemu']).trim(), '1', 'Hierarchy capture requires the job emulator');
  const path = '/sdcard/openwrench-replay-hierarchy.xml';
  let lastError = '';
  for (let attempt = 1; attempt <= 4; attempt++) {
    checkForCrash();
    // Removal is intentionally outside the retry catch: a failed removal cannot permit stale XML.
    adb(['shell', 'rm', '-f', path]);
    let dump = '';
    try {
      dump = adb(['shell', 'uiautomator', 'dump', path]);
      const xml = adb(['exec-out', 'cat', path]);
      assert(/<hierarchy\b/.test(xml) && /<node\b/.test(xml) && xml.includes('</hierarchy>'),
        'Fresh UI hierarchy is empty or incomplete');
      onAttempt({ attempt, ok: true, dump });
      return xml;
    } catch (error) {
      lastError = String(error);
      onAttempt({ attempt, ok: false, dump, error: lastError });
    }
    if (attempt < 4) adb(['shell', 'sleep', '0.25']);
  }
  throw new Error('Fresh UI hierarchy unavailable after 4 attempts: ' + lastError);
}

// Provider return precedes asynchronous decoding. Wait for actual imported content,
// not a fixed sleep or a later screenshot; failures and missing evidence still fail.
export async function waitForImportedReplay({ requireApp, hierarchy, pause }) {
  for (let attempt = 0; attempt < 8; attempt++) {
    await requireApp();
    const xml = hierarchy();
    if (xml.includes('Imported for playback only') && xml.includes('Synthetic recording · fictional values')) return xml;
    if (attempt < 7) await pause(250);
  }
  assert.fail('Actual saved-drive importer did not return the recording after 8 fresh hierarchy checks');
}

// This job fixes the viewport at 1280x800, density 160 and fontScale 1.
// Require complete standard-height Graphs canvases, not clipped bounds or card sparklines.
export function assertReplayPlotsVisible(xml) {
  assert(xml.includes('Synthetic replay · Not live'), 'Replay provenance missing');
  assertView(xml, 'Graphs');
  const all = nodes(xml);
  const bounds = node => /^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$/.exec(node?.bounds ?? '')?.slice(1).map(Number);
  const viewport = bounds(all.find(n => n['content-desc'] === 'Scrollable graphs'));
  assert(viewport && viewport[2] > viewport[0] && viewport[3] > viewport[1], 'Missing replay Graphs viewport');
  const plots = all.flatMap(n => {
    const match = /^(.*): (\d+) received observations in \d+ seconds; gaps remain empty(?:; paused)?$/.exec(n['content-desc'] ?? '');
    return match ? [{ label: match[1], observations: Number(match[2]), box: bounds(n) }] : [];
  }).slice(0, 2);
  assert.equal(plots.length, 2, 'Two replay graphs must be visible for gap evidence');
  for (const plot of plots) {
    const b = plot.box;
    assert(b && b[0] >= viewport[0] && b[2] <= viewport[2] && b[2] > b[0] &&
      b[1] >= viewport[1] && b[3] <= viewport[3] && Math.abs(b[3] - b[1] - 180) <= 3,
      'Replay canvas must be fully visible at its configured height');
    assert(plot.observations > 0, 'Replay graph has no received observations');
    assert(all.some(n => { const h = bounds(n); return n.text === plot.label && h &&
      h[1] >= viewport[1] && h[3] <= b[1] && h[0] < b[2] && h[2] > b[0]; }), 'Replay graph header is clipped');
    assert(all.some(n => { const t = bounds(n); return n.text === '0 s' && t &&
      t[1] >= b[3] && t[3] <= viewport[3] && t[0] >= b[0] && t[2] <= b[2] + 3; }), 'Replay time axis is clipped');
  }
  return { viewport, plots };
}


/** Normal-font fixture tiles are 104dp at density160. Pixel review must also confirm contents. */
export function assertDenseReplayValues(xml, expectedPids) {
  assertView(xml, 'Values');
  assert(xml.includes('Synthetic replay · Not live'), 'Replay provenance missing from dense Values');
  const box = node => /^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$/.exec(node?.bounds ?? '')?.slice(1).map(Number);
  const all = nodes(xml), area = box(all.find(n => n['content-desc'] === 'Scrollable values'));
  assert(area && area[2] > area[0] && area[3] > area[1], 'Missing Values viewport');
  const candidates = all.flatMap(n => {
    const match = /^Retained PID (01[0-9A-F]{2}) value tile:/.exec(n['content-desc'] ?? '');
    return match ? [{ pid: match[1], bounds: box(n), description: n['content-desc'] }] : [];
  });
  const full = candidates.filter(({ bounds: b }) => b && b[0] >= area[0] && b[2] <= area[2] &&
    b[1] >= area[1] && b[3] <= area[3] && b[2] - b[0] >= 175 && Math.abs(b[3] - b[1] - 104) <= 1);
  const unique = new Set(full.map(c => c.pid));
  assert(unique.size >= 24, `Only ${unique.size} complete PID values visible; need at least 24 simultaneously`);
  for (const cell of full) {
    assert(expectedPids.has(cell.pid), 'Dense tile invented a channel');
    assert(cell.description.includes('Historical replay, not live'), 'Tile lost replay provenance');
    assert(cell.description.includes(' · '), 'Tile lacks value/unit/status information');
  }
  return { viewport: area, simultaneousCompleteValues: unique.size, values: full };
}

export function replayClock(xml) {
  const match = /(?:Playing|Paused) · (\d+):(\d{2}) \/ 5:00/.exec(xml);
  assert(match, 'Five-minute replay progress unavailable');
  return Number(match[1]) * 60 + Number(match[2]);
}
export function graphPids(xml) {
  return new Set(nodes(xml).flatMap(n => [n.text ?? '', n['content-desc'] ?? ''].flatMap(label => [...label.matchAll(/\b(01[0-9A-F]{2})\b/g)].map(m => m[1]))));
}
export function unavailableArchivePids(xml, pids) {
  const found = new Set();
  for (const node of nodes(xml)) for (const label of [node.text ?? '', node['content-desc'] ?? '']) for (const pid of pids) {
    if (label.includes(pid + ' · ')) {
      assert(label.includes(pid + ' · Retained replay channel · ECU capability unverified · Unavailable'), `Misleading null-only channel: ${pid}`);
      found.add(pid);
    }
  }
  return found;
}
function viewport(xml, label) {
  const n = nodes(xml).find(n => n['content-desc'] === label);
  const b = /^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$/.exec(n?.bounds ?? '')?.slice(1).map(Number);
  assert(b && b[2] > b[0] && b[3] > b[1], `Missing scroll viewport: ${label}`);
  return b;
}

export async function captureExtendedReplay(ctx) {
  const { adb, pause, hierarchy, capture, tapLabel, capturePicker, requireApp, fixturePath, historyDigest, save } = ctx;
  const d = workspaceDriver(ctx);
  assert.equal(adb(['shell', 'getprop', 'ro.kernel.qemu']).trim(), '1', 'Only the job-owned emulator is allowed');
  const bytes = readFileSync(fixturePath);
  assert.equal(createHash('sha256').update(bytes).digest('hex'), FIXTURE_SHA);
  const file = JSON.parse(bytes);
  assert.equal(file.origin, 'SYNTHETIC'); assert.equal(file.review.elapsed_ms, 300000);
  const expected = new Set(file.review.series.map(s => `01${s[0].toString(16).toUpperCase().padStart(2, '0')}`));
  assert.equal(expected.size, 55);
  const before = historyDigest();
  adb(['push', fixturePath, `/sdcard/Download/${FIXTURE_NAME}`]);
  assert.equal(adb(['shell', 'sha256sum', `/sdcard/Download/${FIXTURE_NAME}`]).trim().split(/\s/)[0], FIXTURE_SHA);
  await d.enterSensors();

  // Cancellation must use the same actual provider launch, without injecting results.
  await d.tap(/^Import or replay a saved drive$/);
  await d.tap(/^Import drive \/ diagnosis ZIP$/);
  await capturePicker('replay-picker-cancel');
  adb(['shell', 'input', 'keyevent', 'KEYCODE_BACK']); await pause(600); await requireApp();
  await d.tap(/^Cancel$/);
  assert(!hierarchy().includes('Synthetic replay · Not live'), 'Cancelled import must not start playback');

  await d.tap(/^Import or replay a saved drive$/);
  await d.tap(/^Import drive \/ diagnosis ZIP$/);
  let picker = await capturePicker('replay-picker-open');
  const exactFile = new RegExp(`^${FIXTURE_NAME.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`);
  let chosen = false;
  for (let attempt = 0; attempt < 5; attempt++) {
    if (tapLabel(picker, exactFile)) { chosen = true; break; }
    if (!tapLabel(picker, /^Downloads$/)) assert(tapLabel(picker, /^(Show roots|Navigate up)$/), 'Picker roots are unavailable');
    await pause(600); picker = await capturePicker(`replay-picker-navigation-${attempt}`);
  }
  assert(chosen, 'Exact verified synthetic file was not selectable in DocumentsUI');
  await waitForImportedReplay({ requireApp, hierarchy, pause });
  let imported = await capture('replay-imported-chooser');
  assert(imported.includes('Imported for playback only'), 'Actual saved-drive importer did not return the recording');
  await d.tap(/^Play .*Synthetic recording · fictional values/);
  await d.tap(/^Pause replay$/);
  const paused = await capture('replay-55-paused');
  assert(paused.includes('Synthetic replay · Not live') && paused.includes('Paused ·'));
  replayClock(paused);
  await d.tap(/^Replay options$/); await d.tap(/^Replay details$/);
  const details = await capture('replay-retained-details');
  assert(details.includes('+0…+300000 ms') && details.includes('Synthetic recording'));
  await d.tap(/^Close$/);
  await d.action(/^Choose PID graphs$/);
  let list = hierarchy();
  assert(list.includes('55 retained replay channels · ECU capability unverified'));
  assert(list.includes('55 matches'));
  const seen = new Set();
  const nullPids = new Set(file.review.series.filter(s => s[2].every(p => p[1] === null)).map(s => `01${s[0].toString(16).toUpperCase().padStart(2, '0')}`));
  assert.equal(nullPids.size, 8);
  const unavailableSeen = new Set();
  for (let attempt = 0; attempt < 40; attempt++) {
    list = hierarchy(); graphPids(list).forEach(pid => seen.add(pid));
    unavailableArchivePids(list, nullPids).forEach(pid => unavailableSeen.add(pid));
    if (seen.size === 55) break;
    const [l, t, r, b] = viewport(list, 'Scrollable PID graph choices');
    adb(['shell', 'input', 'swipe', String(Math.round((l + r) / 2)), String(Math.round(t + (b - t) * .8)),
      String(Math.round((l + r) / 2)), String(Math.round(t + (b - t) * .2)), '350']);
    await pause(250);
  }
  assert.deepEqual([...seen].sort(), [...expected].sort(), 'All 55 actual archive channels must remain reachable');
  assert.deepEqual([...unavailableSeen].sort(), [...nullPids].sort(), 'All eight null-only channels must stay unavailable');
  assert(list.includes('55 selected'), 'All 55 retained channels must remain selected');
  await capture('replay-55-picker-last-page');
  await d.tap(/^Done · Keep \d+ selected$/);
  await d.mode('Values');
  const denseValues = assertDenseReplayValues(await capture('replay-55-values'), expected);
  save('replay-24-visible-values.json', JSON.stringify(denseValues, null, 2));
  await d.tap(/^Table view$/);
  assert(!hierarchy().includes(' value tile:'), 'Table view must preserve the original scrollable table');
  await capture('replay-table-values');
  await d.tap(/^Dense values$/);
  assertDenseReplayValues(hierarchy(), expected);
  await d.tap(/^Retained PID 01[0-9A-F]{2} value tile:/);
  const inspected = await capture('replay-dense-inspector');
  assert(inspected.includes('Close details') && inspected.includes('Synthetic replay · Not live'), 'Dense tile must open inspection with replay provenance');
  adb(['shell', 'input', 'keyevent', 'KEYCODE_BACK']); await pause(400);
  assertDenseReplayValues(hierarchy(), expected);
  await d.mode('Both'); await capture('replay-55-dashboard');
  // The dense Dashboard places full charts below the overview. Use the real Graphs tab
  // for visible gap evidence, preserving all 55 channels and the captured overview.
  await d.mode('Graphs');

  // Restart while paused: prove the wall-clock wait cannot advance playback.
  await d.tap(/^Replay options$/); await d.tap(/^Restart replay$/);
  const start = hierarchy(); assert.equal(replayClock(start), 0); assert(start.includes('Paused ·'));
  await pause(2100); assert.equal(replayClock(hierarchy()), 0);
  await d.tap(/^Play replay$/);
  let seconds = 0, current = '';
  for (let attempt = 0; attempt < 20 && seconds < 199; attempt++) {
    await pause(Math.min(20000, Math.max(1000, (199 - seconds) * 1000)));
    current = hierarchy(); seconds = replayClock(current);
  }
  assert(seconds >= 199 && seconds <= 209, `Missed bounded gap recording start: ${seconds}`);
  save('replay-graph-viewport.json', JSON.stringify(assertReplayPlotsVisible(current), null, 2));
  await ctx.recordGapVideo(22); // Actual natural-speed playback; never seek or alter the app clock.
  await d.tap(/^Pause replay$/);
  const recovered = await capture('replay-gap-recovered-paused');
  assert(replayClock(recovered) >= 213 && replayClock(recovered) < 250);
  assertReplayPlotsVisible(recovered);
  await d.mode('Graphs'); await capture('replay-gap-graphs');
  await d.mode('Both');

  await d.tap(/^Play replay$/);
  await ctx.backgroundApp(); await pause(3000);
  adb(['shell', 'am', 'start', '-W', '-n', ctx.component]); await requireApp();
  const resumed = await capture('replay-background-paused'); assert(resumed.includes('Paused ·'));
  const at = replayClock(resumed); await pause(2000); assert.equal(replayClock(hierarchy()), at);
  // Configuration changes keep the loaded archive and paused cursor; no force-stop/reimport.
  await d.mode('Values');
  for (const [name, size, scale] of [['replay-values-phone', '390x844', 1], ['replay-values-large-text', '1280x800', 2]]) {
    adb(['shell', 'wm', 'size', size]);
    adb(['shell', 'settings', 'put', 'system', 'font_scale', String(scale)]);
    await pause(800); await requireApp(); await d.mode('Values');
    const fallback = await capture(name);
    assertView(fallback, 'Values');
    assert(fallback.includes('Synthetic replay · Not live') && fallback.includes('Paused ·'), 'Fallback lost replay/source state');
    assert(!fallback.includes(' value tile:'), 'Narrow/large text must use the readable table');
    assert.equal(replayClock(fallback), at, 'Resize must not advance paused playback');
  }
  adb(['shell', 'wm', 'size', '1280x800']);
  adb(['shell', 'settings', 'put', 'system', 'font_scale', '1']);
  await pause(800); await requireApp(); await d.mode('Values');
  assertDenseReplayValues(await capture('replay-values-restored-tablet'), expected);
  await d.tap(/^Replay options$/); await d.tap(/^Stop replay$/);
  const stopped = await capture('replay-stopped'); assert(!stopped.includes('Synthetic replay · Not live'));
  assert.equal(historyDigest(), before, 'Import/replay must not write or modify saved drive history');
  save('replay-import-evidence.json', JSON.stringify({ fixture: FIXTURE_NAME, sha256: FIXTURE_SHA,
    bytes: bytes.length, channelPids: [...seen].sort(), durationMillis: 300000,
    route: 'Actual Android document picker → importer → explicit Play', source: 'SYNTHETIC; vehicle capability unverified',
    nullOnlyPids: [...unavailableSeen].sort(), denseValues, valuesViewports: ['1280x800@1x-font', '390x844@1x-font', '1280x800@2x-font'], historyUnchanged: true, pauseBackgroundStop: true, gapVideo: 'PENDING manual pixel review of 3:31–3:32 and recovery at 3:33; green job alone does not establish gap rendering',
    loopUi: 'Not exercised; JVM offset/end/loop tests cover the model' }, null, 2));
}
