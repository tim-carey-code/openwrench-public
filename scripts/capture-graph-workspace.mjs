// Runs only inside the existing verified, job-owned offline Android emulator capture.
// No adapter discovery, connection, drive import, private data or external app targets.
import assert from 'node:assert/strict';
import { checkGraphCapture } from './check-single-column-graphs.mjs';

export function nodes(xml) {
  return [...xml.matchAll(/<node\b[^>]*>/g)].map(match =>
    Object.fromEntries([...match[0].matchAll(/([\w:-]+)="([^"]*)"/g)].map(a => [a[1], a[2]])));
}
export function graphLabels(xml) {
  return nodes(xml).map(n => /^(.*): \d+ received observations in \d+ seconds; gaps remain empty(?:; paused)?$/.exec(n['content-desc'] ?? '')?.[1]).filter(Boolean);
}
export function assertView(xml, mode) {
  assert(xml.includes(`Scrollable ${mode.toLowerCase()}`), `Missing ${mode} viewport`);
  if (mode === 'Values') assert.equal(graphLabels(xml).length, 0, 'Values must not render graph canvases');
  if (mode === 'Graphs') assert(!nodes(xml).some(n => / value card$/.test(n['content-desc'] ?? '')), 'Graphs must not render value cards');
  if (mode === 'Both') assert(nodes(xml).some(n => / value card$/.test(n['content-desc'] ?? '')), 'Both must render compact value cards');
}

export function assertReferenceOverview(xml) {
  const all = nodes(xml);
  assert(all.some(n => n['content-desc'] === 'Reference dashboard grid'), 'Missing wide reference grid');
  for (const role of ['Engine speed', 'Coolant temperature', 'Vehicle speed', 'Module Voltage']) {
    assert(all.some(n => n['content-desc'] === `${role} value card`), `Missing reference role: ${role}`);
  }
  for (const title of ['Signal Integrity', 'Data Quality', 'RPM vs Throttle']) {
    assert(all.some(n => n.text === title), `Missing reference panel: ${title}`);
  }
  assert(graphLabels(xml).length >= 2, 'Overview must expose more than one plot in the initial viewport');
}

export function workspaceDriver({ adb, hierarchy, tapLabel, pause, capture }) {
  async function tap(pattern) {
    let xml = hierarchy();
    if (!nodes(xml).some(n => pattern.test(n.text ?? '') || pattern.test(n['content-desc'] ?? '')) &&
        /Advanced|History|Diagnosis|Session/.test(pattern.source) && tapLabel(xml, /^Workspace menu and chart controls$/)) {
      await pause(300); xml = hierarchy();
    }
    assert(tapLabel(xml, pattern), `Control unavailable: ${pattern}`);
    await pause(400);
  }
  async function action(pattern) {
    if (tapLabel(hierarchy(), pattern)) { await pause(400); return; }
    await tap(/^(Sensor settings and more controls|Workspace menu and chart controls)$/);
    await tap(pattern);
  }
  async function mode(label) {
    const pattern = label === 'Both' ? /^(Both|Dashboard|Dashboard, Both graphs and values)$/ : new RegExp(`^${label}$`);
    let sawScroller = false;
    for (let attempt = 0; attempt < 4; attempt++) {
      const xml = hierarchy();
      if (tapLabel(xml, pattern)) { await pause(400); return; }
      const scroller = nodes(xml).find(n => n['content-desc'] === 'Scrollable display modes' && n.scrollable === 'true');
      if (!scroller) break;
      sawScroller = true;
      const bounds = /^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$/.exec(scroller.bounds ?? '')?.slice(1).map(Number);
      assert(bounds && bounds[2] > bounds[0] && bounds[3] > bounds[1], 'Display-mode scroll viewport is unavailable');
      const [l, t, r, b] = bounds;
      const toStart = label === 'Both' || label === 'Graphs' && attempt < 2;
      const start = l + (r - l) * (toStart ? .2 : .8);
      const end = l + (r - l) * (toStart ? .8 : .2);
      adb(['shell', 'input', 'swipe', String(Math.round(start)), String(Math.round((t + b) / 2)),
        String(Math.round(end)), String(Math.round((t + b) / 2)), '350']);
      await pause(350);
    }
    if (tapLabel(hierarchy(), pattern)) { await pause(400); return; }
    assert(!sawScroller, `Display mode remains unreachable after bounded scrolling: ${label}`);
    await action(/^Display mode:/);
    await tap(pattern);
  }
  async function manage() {
    await tap(/^(Sensor settings and more controls|Workspace menu and chart controls)$/);
    await tap(/^Manage modules$/);
    await tap(/^Graph modules$/);
  }
  async function editorAction(pattern) {
    for (let attempt = 0; attempt < 40; attempt++) {
      const xml = hierarchy();
      if (tapLabel(xml, pattern)) { await pause(300); return; }
      const viewport = nodes(xml).find(n => n['content-desc'] === 'Module layout editor');
      const box = /^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$/.exec(viewport?.bounds ?? '')?.slice(1).map(Number);
      assert(box && box[3] > box[1], 'Missing editor scroll viewport');
      const [l, t, r, b] = box;
      adb(['shell', 'input', 'swipe', String(Math.round((l + r) / 2)), String(Math.round(t + (b - t) * .85)),
        String(Math.round((l + r) / 2)), String(Math.round(t + (b - t) * .2)), '350']);
      await pause(250);
    }
    throw new Error(`Editor control not reachable after bounded scrolling: ${pattern}`);
  }
  async function enterSensors() {
    const xml = hierarchy();
    if (!xml.includes('Live sensor controls') && !xml.includes('Workspace menu and chart controls')) await tap(/^Live Sensors$/);
  }
  return { tap, action, mode, manage, editorAction, enterSensors, capture };
}

export async function captureWorkspace(ctx) {
  const d = workspaceDriver(ctx);
  await d.mode('Both');
  const both = await ctx.capture('m1-tablet-both');
  assertView(both, 'Both');
  assert(both.includes('Reference dashboard grid'));
  assert(graphLabels(both).length >= 2, 'Overview must show multiple plots');
  await d.mode('Graphs');
  const graphs = await ctx.capture('m1-tablet-graphs');
  assertView(graphs, 'Graphs');
  const initialFirst = graphLabels(graphs)[0];
  assert.equal(initialFirst, 'Engine speed');
  await d.tap(/^Engine speed: \d+ received observations in \d+ seconds; gaps remain empty$/);
  assert((await ctx.capture('figma-compact-graph-inspector')).includes('Close details'), 'Compact graph must open signal inspection');
  await d.tap(/^Close details$/);
  assertView(ctx.hierarchy(), 'Graphs');
  await d.action(/^Expand chart height$/);
  const expanded = await ctx.capture('m1-tablet-expanded');
  checkGraphCapture(expanded, { density: 1, fontScale: 1, contentInsetDp: 17 });
  await d.action(/^Compact chart height$/);
  await d.action(/^Pause chart display only/);
  const paused = await ctx.capture('m1-tablet-paused');
  assert(paused.includes('Charts paused'), 'Pause must be visible');
  await d.action(/^Resume chart display only/);
  await d.mode('Values');
  assertView(await ctx.capture('m1-tablet-values'), 'Values');
  await d.mode('Graphs');

  await d.manage();
  await ctx.capture('m2-manage-modules');
  await d.editorAction(/^Engine speed graph visibility$/);
  await d.tap(/^Apply layout$/);
  assert(!graphLabels(await ctx.capture('m2-rpm-hidden')).includes('Engine speed'), 'Hidden graph still rendered');
  await d.manage();
  await ctx.capture('m2-hidden-module-recovery');
  await d.editorAction(/^Engine speed graph visibility$/);
  await d.editorAction(/^Move down Engine speed graph$/);
  await d.tap(/^Apply layout$/);
  const reordered = await ctx.capture('m2-reordered');
  assert(graphLabels(reordered).length > 0 && graphLabels(reordered)[0] !== initialFirst, 'Move down did not change graph order');

  // Back cancels staged edits; it must not exit the underlying workspace.
  await d.manage();
  await d.editorAction(/^Engine speed graph visibility$/);
  ctx.adb(['shell', 'input', 'keyevent', 'KEYCODE_BACK']);
  const cancelled = await ctx.capture('m2-cancelled-with-back');
  assert.deepEqual(graphLabels(cancelled), graphLabels(reordered));
  await d.manage();
  await d.editorAction(/^Engine speed graph visibility$/);
  await d.tap(/^Cancel$/);
  assert.deepEqual(graphLabels(await ctx.capture('m2-cancelled-with-button')), graphLabels(reordered));
  ctx.adb(['shell', 'input', 'keyevent', 'KEYCODE_HOME']);
  ctx.adb(['shell', 'am', 'start', '-W', '-n', 'org.openwrench.android/org.openwrench.frontend.android.MainActivity']);
  await d.enterSensors(); await d.mode('Graphs');
  assert.equal(graphLabels(await ctx.capture('m2-background-return'))[0], graphLabels(reordered)[0]);

  // A window-size configuration change exercises committed-layout recreation.
  ctx.adb(['shell', 'wm', 'size', '800x1280']); await ctx.pause(1200);
  await d.enterSensors(); await d.mode('Graphs');
  assert.equal(graphLabels(await ctx.capture('m2-tablet-portrait-persisted'))[0], graphLabels(reordered)[0]);
  ctx.adb(['shell', 'wm', 'size', '1280x800']); await ctx.pause(1200);
  await d.enterSensors(); await d.mode('Graphs');

  await d.manage();
  await d.editorAction(/^Engine speed graph pin first$/);
  await d.editorAction(/^Engine speed graph expansion$/);
  await d.tap(/^Apply layout$/);
  const collapsed = await ctx.capture('m2-pinned-collapsed');
  assert(collapsed.includes('Collapsed'), 'Collapsed module must retain a visible status');
  assert(!graphLabels(collapsed).includes('Engine speed'));
  await d.manage();
  await d.editorAction(/^Engine speed graph expansion$/);
  await d.editorAction(/^Engine speed graph tall height$/);
  await d.tap(/^Apply layout$/);
  assert.equal(graphLabels(await ctx.capture('m2-pinned-tall'))[0], 'Engine speed');
  await d.manage();
  await d.editorAction(/^Reset module layout$/);
  await d.tap(/^Apply layout$/);
  assert.equal(graphLabels(await ctx.capture('m2-reset'))[0], 'Engine speed');
  // The unified dialog saves PID selection atomically with its layout, unlike quick display shortcuts.
  await d.manage(); await d.editorAction(/^Display PID Engine speed$/); await d.tap(/^Cancel$/);
  assert.equal(graphLabels(await ctx.capture('figma-pid-cancelled'))[0], 'Engine speed');
  await d.manage(); await d.editorAction(/^Display PID Engine speed$/); await d.tap(/^Apply layout$/);
  assert(!graphLabels(await ctx.capture('figma-pid-deselected')).includes('Engine speed'));
  ctx.adb(['shell', 'am', 'force-stop', 'org.openwrench.android']);
  ctx.adb(['shell', 'am', 'start', '-W', '-n', 'org.openwrench.android/org.openwrench.frontend.android.MainActivity']);
  await d.enterSensors(); await d.mode('Graphs');
  assert(!graphLabels(await ctx.capture('figma-pid-relaunch-persisted')).includes('Engine speed'));
  await d.manage(); await d.editorAction(/^Display PID Engine speed$/); await d.tap(/^Apply layout$/);
  assert.equal(graphLabels(await ctx.capture('figma-pid-restored'))[0], 'Engine speed');
  await d.mode('Both');
}

export async function captureWorkspaceSize(ctx, name, density, fontScale) {
  const d = workspaceDriver(ctx);
  await d.enterSensors(); await d.mode('Both');
  assertView(await ctx.capture(`${name}-both`), 'Both');
  await d.mode('Values');
  const values = await ctx.capture(`${name}-values`);
  assertView(values, 'Values');
  if (fontScale >= 2) assert(values.includes('Freshness: Age unknown') && !nodes(values).some(n => n.text === 'FRESHNESS'),
    'Narrow Values must keep freshness on a readable secondary line');
  await d.mode('Graphs');
  checkGraphCapture(await ctx.capture(`${name}-graphs`), { density, fontScale, contentInsetDp: 17 });
  await d.action(/^Enter full screen$/);
  const fullscreen = await ctx.capture(`${name}-fullscreen`);
  assert(fullscreen.includes('Exit full screen'), 'Full-screen exit is unreachable');
  await d.manage();
  await ctx.capture(`${name}-manage-modules`);
  await d.editorAction(/^Move down Engine speed graph$/);
  await ctx.capture(`${name}-manage-scrolled`);
  await d.tap(/^Cancel$/);
  assert.deepEqual(graphLabels(ctx.hierarchy()), graphLabels(fullscreen), 'Cancel must discard the narrow/large-text reorder');
  await d.action(/^Exit full screen$/);
}

// Select the explicit offline fixture via ordinary settings; never inject samples into live data.
export async function captureReferenceFixture(ctx) {
  const d = workspaceDriver(ctx);
  await d.tap(/^Advanced$/);
  await d.tap(/^Open Connection settings$/);
  await d.tap(/^Complete simulator fixture$/);
  await d.tap(/^Synthetic chart fixture · offline$/);
  await d.enterSensors(); await d.mode('Both');
  await d.manage(); await d.editorAction(/^Reset module layout$/); await d.tap(/^Apply layout$/);
  const populated = await ctx.capture('reference-tablet-synthetic');
  assert(populated.includes('DEMO') && populated.includes('Synthetic observations'), 'Synthetic fixture must be clearly labeled');
  assert(graphLabels(populated).length >= 2);
  assert(populated.includes('57 received observations'), 'Populated traces with gaps were not exercised');
  assert(populated.includes('Temperatures: 171 received observations'), 'Three real synthetic temperature histories must share one plot');
  const displayed = nodes(populated).map(n => n.text);
  for (const value of ['2,346', '91', '14.1']) assert(displayed.includes(value), `Readable KPI value missing: ${value}`);
  await d.mode('Values');
  assertView(await ctx.capture('reference-tablet-synthetic-values'), 'Values');
  await d.mode('Both');
  for (const size of ['800x600', '960x600', '1024x640', '1170x776']) {
    ctx.adb(['shell', 'wm', 'size', size]); await ctx.pause(1200);
    await d.enterSensors(); await d.mode('Both');
    const tablet = await ctx.capture(`reference-${size}-synthetic`);
    assert(tablet.includes('Reference dashboard grid'), 'Ordinary landscape tablet must retain dashboard composition');
  }
  ctx.adb(['shell', 'wm', 'size', '640x640']); await ctx.pause(1200);
  await d.enterSensors(); await d.mode('Values');
  assertView(await ctx.capture('figma-values-640-boundary'), 'Values');
  ctx.adb(['shell', 'wm', 'size', '1536x1044']); await ctx.pause(1200);
  await d.enterSensors(); await d.mode('Both');
  const reference = await ctx.capture('reference-1536-synthetic');
  assertReferenceOverview(reference.replace('Module voltage value card', 'Module Voltage value card'));
  await d.manage(); await d.tap(/^Value cards$/);
  await d.editorAction(/^Mass air flow value card pin first$/); await d.tap(/^Apply layout$/);
  const pinnedCards = await ctx.capture('reference-nonrole-card-pinned');
  assert.equal(nodes(pinnedCards).filter(n => / value card$/.test(n['content-desc'] ?? ''))[0]?.['content-desc'], 'Mass air flow value card');
  await d.manage(); await d.editorAction(/^Reset module layout$/); await d.tap(/^Apply layout$/);
  await d.action(/^Pause chart display only/);
  await ctx.capture('reference-1536-paused');
  await d.action(/^Resume chart display only/);
  await d.mode('Graphs');
  await d.action(/^Expand chart height$/);
  checkGraphCapture(await ctx.capture('reference-populated-graphs'), { density: 1, fontScale: 1, contentInsetDp: 17 });
  await d.action(/^Compact chart height$/);
  await d.mode('Both');
  ctx.adb(['shell', 'wm', 'size', '1280x800']); await ctx.pause(1200);
  await d.enterSensors();
}
