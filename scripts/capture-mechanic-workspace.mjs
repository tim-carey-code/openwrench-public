// Invoked only by the guarded job-owned offline emulator workflow, never a user device.
import assert from 'node:assert/strict';
import { nodes, graphLabels, assertView, workspaceDriver } from './capture-graph-workspace.mjs';
import { checkboxState } from './capture-system-back.mjs';

const textOf = xml => nodes(xml).map(n => (n.text ?? '').replaceAll('&amp;', '&'));
const boundsOf = node => /^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$/.exec(node?.bounds ?? '')?.slice(1).map(Number);
// Require actual visible panel labels after bounded scrolling.
export function visibleMechanicDetailLabels(xml) {
  const all = nodes(xml);
  const viewport = boundsOf(all.find(n => n['content-desc'] === 'Scrollable both'));
  assert(viewport && viewport[2] > viewport[0] && viewport[3] > viewport[1], 'Mechanic detail viewport missing');
  const required = new Set(['Bank 1', 'Bank 2', 'STFT · %', 'LTFT · %', 'Air & Engine', 'Faults · Last Diagnostic Scan']);
  return all.filter(n => required.has((n.text ?? '').replaceAll('&amp;', '&'))).filter(n => {
    const b = boundsOf(n);
    return b && b[2] > b[0] && b[3] > b[1] && b[0] >= viewport[0] && b[2] <= viewport[2]
      && b[1] >= viewport[1] && b[3] <= viewport[3];
  }).map(n => n.text.replaceAll('&amp;', '&'));
}

export function assertStatusPlacement(xml) {
  const all = nodes(xml);
  const status = boundsOf(all.find(n => n.text === 'DATA STATUS'));
  const viewport = boundsOf(all.find(n => n['content-desc'] === 'Scrollable both'));
  assert(status && viewport && status[2] > status[0] && status[3] > status[1], 'Data status lacks observed visible bounds');
  assert(status[0] >= viewport[0] && status[2] <= viewport[2] && status[1] >= viewport[1] && status[3] <= viewport[3], 'Data status is clipped/outside the initial viewport');
  assert(status[1] <= viewport[1] + (viewport[3]-viewport[1])*.35, 'Data status is too far below the first-glance region');
  const content = all.filter(n => / value card$|received observations/.test(n['content-desc'] ?? '')).map(boundsOf).filter(b => b && b[3] > b[1]);
  assert(content.every(b => status[3] <= b[1]), 'Data status must appear above value cards and plots');
}
export function assertMixedCounts(xml) {
  assertStatusPlacement(xml);
  const texts = textOf(xml);
  assert(texts.includes('0/4 selected available'), 'Mixed fixture must retain all four selected failed channels');
  assert(texts.includes('1 stale · 2 unavailable/not sampled · 0 disconnected · 1 read errors'), 'Incorrect mixed fixture counts');
}
export function assertMechanicOverview(xml, { sixCards = true, timed = false } = {}) {
  const texts = textOf(xml);
  assert(xml.includes('Diagnostic dashboard'), 'Missing diagnostic dashboard');
  assert(texts.includes('DIAGNOSTIC DASHBOARD') && texts.includes('SIMULATED'), 'Exact simulated-source and dashboard identity must be visible');
  assertStatusPlacement(xml);
  if (sixCards) {
    const cards = nodes(xml).filter(n => / value card$/.test(n['content-desc'] ?? ''));
    assert.equal(cards.length, 6, 'Initial landscape viewport must expose six cards');
    for (const label of ['Bank 1', 'Bank 2', 'STFT · %', 'LTFT · %', 'Air & Engine', 'Faults · Last Diagnostic Scan'])
      assert(texts.includes(label), `Missing mechanic panel/field: ${label}`);
    assert(xml.includes('RPM display gauge; 0 to 8000 rpm; not a vehicle redline'), 'Missing honest compact RPM gauge');
  }
  assert(xml.includes(timed ? 'Synthetic timed observations · not vehicle receipts' : 'Fictional static values · receipt times not supplied'),
    'Incorrect data-provenance statement');
  assert(!xml.includes('Reference dashboard grid'), 'Regular dashboard must not render the old overview');
}

export async function captureMechanicWorkspace(ctx) {
  const d = workspaceDriver(ctx);
  const back = async () => { ctx.adb(['shell', 'input', 'keyevent', 'KEYCODE_BACK']); await ctx.pause(400); };
  async function scrollWorkspace(toStart = false) {
    const viewport = nodes(ctx.hierarchy()).find(n => /^Scrollable (both|graphs|values)$/.test(n['content-desc'] ?? ''));
    const b = /^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$/.exec(viewport?.bounds ?? '')?.slice(1).map(Number);
    assert(b && b[3] > b[1], 'Workspace scrolling bounds unavailable');
    const [l,t,r,bottom] = b, y1 = t+(bottom-t)*.82, y2 = t+(bottom-t)*.18;
    ctx.adb(['shell','input','swipe',String(Math.round((l+r)/2)),String(Math.round(toStart ? y2 : y1)),
      String(Math.round((l+r)/2)),String(Math.round(toStart ? y1 : y2)),'400']); await ctx.pause(300);
  }
  async function scenario(current, next) {
    await d.tap(/^Advanced$/); await d.tap(/^Open Connection settings$/);
    await d.tap(current); await d.tap(next); await d.enterSensors(); await d.mode('Both');
    await d.manage(); await d.editorAction(/^Reset module layout$/); await d.tap(/^Apply layout$/);
  }
  await ctx.launch('1536x1044',160,1); await d.enterSensors(); await d.mode('Both');
  assertMechanicOverview(await ctx.capture('mechanic-1536-static'));
  await scenario(/^Complete simulator fixture$/, /^Synthetic chart fixture · offline$/);
  assertMechanicOverview(await ctx.capture('mechanic-1536-timed'), { timed: true });
  // The trim fixture includes a measured zero in Bank 1 and different Bank 2 values.
  let texts = textOf(ctx.hierarchy());
  for (const value of ['0', '2.3', '-1.6', '0.8', '2,345.6']) assert(texts.includes(value), `Missing independent fixture value: ${value}`);
  await scrollWorkspace();
  const plots = await ctx.capture('mechanic-broad-graphs');
  assert(graphLabels(plots).length > 0, 'Scrolling must reach real plots');
  assert(plots.includes('57 received observations'), 'Gap-bearing synthetic history not exercised');
  await d.mode('Values'); assertView(await ctx.capture('mechanic-values'), 'Values');
  await d.mode('Graphs'); assertView(await ctx.capture('mechanic-graphs'), 'Graphs');
  await d.action(/^Enter full screen$/); assertView(await ctx.capture('mechanic-fullscreen'), 'Graphs');
  await back(); assertView(ctx.hierarchy(), 'Graphs');
  await d.mode('Both');
  await d.tap(/^Configure dashboard PIDs and modules$/);
  const before = checkboxState(ctx.hierarchy(), 'Display PID Engine speed');
  await d.tap(/^Display PID Engine speed$/); await back();
  await d.tap(/^Configure dashboard PIDs and modules$/);
  assert.equal(checkboxState(ctx.hierarchy(), 'Display PID Engine speed'), before, 'Back saved a staged selection');
  await d.tap(/^Cancel$/);
  await d.manage(); await d.tap(/^Value cards$/);
  await d.editorAction(/^Mass air flow value card pin first$/); await d.tap(/^Apply layout$/);
  const pinned = await ctx.capture('mechanic-pinned-maf');
  assert.equal(nodes(pinned).find(n => / value card$/.test(n['content-desc'] ?? ''))?.['content-desc'], 'Mass air flow value card');
  await d.manage(); await d.editorAction(/^Reset module layout$/); await d.tap(/^Apply layout$/);
  await d.action(/^Pause chart display only/); await ctx.capture('mechanic-paused');
  await d.action(/^Resume chart display only/);
  for (const [size,density,fontScale,name] of [
    ['1280x800',160,1,'tablet-1280'], ['800x600',160,1,'tablet-800'],
    ['960x600',160,1,'tablet-960'], ['1024x640',160,1,'tablet-1024'],
    ['780x1600',320,1,'phone'], ['780x1600',320,2,'phone-2x'],
  ]) {
    // Keep the same retained Activity, exercising configuration changes without losing selection.
    ctx.adb(['shell','wm','size',size]); ctx.adb(['shell','wm','density',String(density)]);
    ctx.adb(['shell','settings','put','system','font_scale',String(fontScale)]); await ctx.pause(1300);
    await d.enterSensors(); await d.mode('Both');
    const xml = await ctx.capture(`mechanic-${name}`);
    assertMechanicOverview(xml, { sixCards: name === 'tablet-1280', timed: true });
    if (name.startsWith('phone')) {
      await scrollWorkspace(); const scrolled = await ctx.capture(`mechanic-${name}-scrolled`);
      if (name === 'phone-2x') {
        const required = ['Bank 1', 'Bank 2', 'STFT · %', 'LTFT · %', 'Air & Engine', 'Faults · Last Diagnostic Scan'];
        const observed = new Set(visibleMechanicDetailLabels(scrolled));
        for (let attempt = 0; attempt < 12 && required.some(label => !observed.has(label)); attempt++) {
          await scrollWorkspace();
          const detail = await ctx.capture(`mechanic-phone-2x-details-${attempt + 1}`);
          for (const label of visibleMechanicDetailLabels(detail)) observed.add(label);
        }
        assert(required.every(label => observed.has(label)), `Large-text panels not visibly reached: ${required.filter(label => !observed.has(label)).join(', ')}`);
        ctx.save('mechanic-large-text-panel-evidence.json', JSON.stringify({ observed: [...observed],
          method: 'Complete label bounds inside the observed scroll viewport; inspect paired PNGs for bank values and readability' }, null, 2));
      }
      await d.mode('Values'); assertView(await ctx.capture(`mechanic-${name}-values`), 'Values');
      await d.mode('Graphs'); assertView(await ctx.capture(`mechanic-${name}-graphs`), 'Graphs');
      await d.mode('Both');
    }
  }
  ctx.adb(['shell','wm','size','1536x1044']); ctx.adb(['shell','wm','density','160']);
  ctx.adb(['shell','settings','put','system','font_scale','1']); await ctx.pause(1300);
  await scenario(/^Synthetic chart fixture · offline$/, /^Mixed availability fixture$/);
  const mixed = await ctx.capture('mechanic-mixed-availability');
  assertMixedCounts(mixed);
  await d.manage(); await d.editorAction(/^Hide unavailable PIDs$/); await d.tap(/^Apply layout$/);
  const hidden = await ctx.capture('mechanic-hide-unavailable-summary');
  assertMixedCounts(hidden);
  assert(hidden.includes('counts include hidden selected channels'), 'Hide must not conceal data-status denominator');
  ctx.save('mechanic-capture-evidence.json', JSON.stringify({
    source: 'Only explicit offline fictional fixtures; no adapter or private data',
    completed: ['six-card overview', 'bank-specific zero/trim values', 'synthetic trace gaps', 'Graphs/Values/fullscreen',
      'module cancellation/pin/reset', 'chart pause', 'retained tablet/phone/large-text resize', 'mixed/hidden data summary'],
    limitations: 'Pixel inspection remains required. No physical tablet, TalkBack, vehicle capability, installed history or signing continuity claim.',
  }, null, 2));
}
