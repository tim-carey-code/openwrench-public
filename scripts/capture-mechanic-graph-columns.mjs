// Only called by the job-owned, package-verified offline emulator workflow.
import assert from 'node:assert/strict';
import { nodes, graphLabels, assertView, workspaceDriver } from './capture-graph-workspace.mjs';
import { captureMechanicWorkspace } from './capture-mechanic-workspace.mjs';

const bounds = node => /^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$/.exec(node?.bounds ?? '')?.slice(1).map(Number);
const plotPattern = /^(.*): (\d+) received observations in (\d+) seconds; gaps remain empty(?:; paused)?$/;
export function exactLabel(label) {
  return new RegExp('^' + label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '$');
}

export function graphGeometry(xml) {
  const all = nodes(xml);
  const viewport = bounds(all.find(n => /^Scrollable (both|graphs)$/.test(n['content-desc'] ?? '')));
  assert(viewport && viewport[2] > viewport[0] && viewport[3] > viewport[1], 'Missing observed graph viewport');
  const plots = all.flatMap(n => {
    const match = plotPattern.exec(n['content-desc'] ?? ''), box = bounds(n);
    return match && box && box[2] > box[0] && box[3] > box[1] && box[1] >= viewport[1] && box[3] <= viewport[3]
      ? [{ label: match[1], observations: Number(match[2]), box }] : [];
  });
  return { viewport, plots };
}
export function assertGraphColumns(xml, columns, expected = ['Engine speed', 'Coolant temperature']) {
  const { viewport, plots } = graphGeometry(xml);
  assert(plots.length >= columns, `Need ${columns} visible real chart canvases`);
  assert.deepEqual(plots.slice(0, columns).map(p => p.label), expected.slice(0, columns), 'Graph order changed');
  const width = viewport[2] - viewport[0];
  for (const p of plots) assert(p.box[0] >= viewport[0] && p.box[2] <= viewport[2], 'Plot extends outside workspace');
  const [a, b] = plots.map(p => p.box);
  if (columns === 2) {
    assert(a[2] <= b[0], 'Paired graphs overlap horizontally or are still stacked');
    assert(Math.abs(a[1] - b[1]) <= 3 && Math.abs(a[3] - b[3]) <= 3, 'First two plots must share a row');
    assert(Math.abs((a[2] - a[0]) - (b[2] - b[0])) <= 3, 'Paired graph widths differ');
    assert(a[2] - a[0] > width * .22 && a[2] - a[0] < width * .5, 'Paired plot is unreadably narrow or still full width');
  } else {
    assert(a[2] - a[0] > width * .55, 'Single-column fallback is not full width');
    assert(plots.slice(1).every(p => p.box[1] >= a[3]), 'Single-column fallback contains a horizontal peer');
  }
  assert(plots.slice(0, columns).every(p => p.observations === 57), 'Gap-bearing synthetic traces must remain intact');
  return { columns, viewport, firstPlots: plots.slice(0, columns) };
}

/** Android XML bounds can already be clipped. Require the configured canvas height,
 * visible title and final time tick. PNGs still require independent pixel inspection. */
export function assertCompleteGraphRows(xml, columns, expected, plotHeightPx) {
  assert(Number.isFinite(plotHeightPx) && plotHeightPx > 0, 'Expected physical canvas height required');
  const result = assertGraphColumns(xml, columns, expected);
  const all = nodes(xml), viewport = result.viewport;
  for (const plot of result.firstPlots) {
    assert(Math.abs(plot.box[3] - plot.box[1] - plotHeightPx) <= 3, plot.label + ': clipped or unexpected canvas height');
    const header = all.map(n => ({ text: n.text, box: bounds(n) })).find(n => n.text === plot.label && n.box &&
      n.box[1] >= viewport[1] && n.box[3] <= plot.box[1] && n.box[0] < plot.box[2] && n.box[2] > plot.box[0]);
    assert(header, plot.label + ': complete visible chart header required');
    const tick = all.map(n => ({ text: n.text, box: bounds(n) })).find(n => n.text === '0 s' && n.box &&
      n.box[1] >= plot.box[3] && n.box[3] <= viewport[3] && n.box[0] >= plot.box[0] && n.box[2] <= plot.box[2] + 3);
    assert(tick, plot.label + ': complete final time tick required');
  }
  return { ...result, expectedCanvasHeightPx: plotHeightPx };
}

export async function captureMechanicGraphColumns(ctx) {
  // Preserve the prior dense overview, split banks, source states and all interaction gates.
  await captureMechanicWorkspace(ctx);
  const d = workspaceDriver(ctx), evidence = [];
  let density = 160, fontScale = 1;
  const back = async () => { ctx.adb(['shell','input','keyevent','KEYCODE_BACK']); await ctx.pause(400); };
  await d.tap(/^Advanced$/); await d.tap(/^Open Connection settings$/);
  await d.tap(/^Mixed availability fixture$/); await d.tap(/^Synthetic chart fixture · offline$/);
  await d.enterSensors(); await d.mode('Both');
  await d.manage(); await d.editorAction(/^Reset module layout$/); await d.tap(/^Apply layout$/);
  async function resize(size, nextDensity, nextFontScale) {
    density = nextDensity; fontScale = nextFontScale;
    ctx.adb(['shell','wm','size',size]); ctx.adb(['shell','wm','density',String(density)]);
    ctx.adb(['shell','settings','put','system','font_scale',String(fontScale)]); await ctx.pause(1300);
    await d.enterSensors();
  }
  async function record(name, columns, expected, heightDp) {
    const xml = await ctx.capture(name);
    const configuredHeight = heightDp ?? (xml.includes('content-desc="Scrollable both"') ? 100 : 180);
    evidence.push({ name, ...assertCompleteGraphRows(xml, columns, expected, configuredHeight * Math.min(2, Math.max(1, fontScale)) * density / 160) });
    return xml;
  }
  async function reachOverviewGraphs(columns) {
    for (let attempt = 0; attempt < 45; attempt++) {
      const { viewport: [l,t,r,b], plots } = graphGeometry(ctx.hierarchy());
      if (plots[0]?.label === 'Engine speed' && plots.length >= columns) {
        try { assertCompleteGraphRows(ctx.hierarchy(), columns, undefined, 100 * Math.min(2, Math.max(1, fontScale)) * density / 160); return; }
        catch (error) { if (!/clipped|complete/.test(error.message)) throw error; }
      }
      assert(!plots.some(p => p.label !== 'Engine speed' && p.label !== 'Coolant temperature'), 'Scrolled beyond the first graph row');
      const x = Math.round((l+r)/2), y = Math.round(t+(b-t)*.8);
      const distance = Math.round(Math.min((b-t)*.16, 100));
      ctx.adb(['shell','input','swipe',String(x),String(y),String(x),String(y-distance),'300']); await ctx.pause(200);
    }
    throw new Error('First overview graph row not reachable by bounded scrolling');
  }
  for (const [size,density,fontScale,name,columns] of [
    ['800x600',160,1,'800',2], ['960x600',160,1,'960',2], ['1024x640',160,1,'1024',2],
    ['1280x800',160,1,'1280',2], ['1536x1044',160,1,'1536',2],
    ['780x1600',320,1,'phone',1], ['780x1600',320,2,'phone-2x',1],
    ['1280x800',160,2,'1280-2x',1], ['1536x1044',160,2,'1536-2x',2],
  ]) {
    await resize(size,density,fontScale); await d.mode('Values'); await d.mode('Graphs');
    assertView(ctx.hierarchy(), 'Graphs');
    await record(`paired-${name}-graphs`,columns);
    // Normal-sized tablets must show the requested arrangement below the dense overview too.
    if (fontScale === 1 && density === 160) {
      await d.mode('Both'); await reachOverviewGraphs(columns);
      await record(`paired-${name}-dashboard`,columns);
    }
  }
  await resize('1280x800',160,1); await d.mode('Values'); await d.mode('Graphs');
  await d.tap(/^Engine speed: 57 received observations in 60 seconds; gaps remain empty$/);
  assert((await ctx.capture('paired-inspector')).includes('Close details'), 'Paired chart inspector did not open');
  await back(); await record('paired-inspector-back',2);
  await d.tap(/^Coolant temperature: 57 received observations in 60 seconds; gaps remain empty$/);
  assert((await ctx.capture('paired-right-inspector')).includes('Close details'), 'Right-column inspection did not open');
  await back(); await record('paired-right-inspector-back',2);
  await d.action(/^Pause chart display only/);
  const paused = await record('paired-paused',2);
  assert(paused.includes('Charts paused') && paused.includes('gaps remain empty; paused'), 'Pause state missing');
  await d.action(/^Resume chart display only/);
  await d.action(/^Expand chart height$/); await record('paired-expanded-full-width',1,undefined,280);
  await d.action(/^Compact chart height$/); await record('paired-compact-restored',2);
  await d.action(/^Enter full screen$/); await record('paired-fullscreen',1);
  await back(); await record('paired-fullscreen-back',2);
  await d.manage(); await d.editorAction(/^Move down Engine speed graph$/); await d.tap(/^Apply layout$/);
  await record('paired-reordered',2,['Coolant temperature','Engine speed']);
  await d.manage(); await d.editorAction(/^Engine speed graph visibility$/); await d.tap(/^Apply layout$/);
  const hidden = await ctx.capture('paired-rpm-hidden');
  assert(!graphLabels(hidden).includes('Engine speed'), 'Hidden graph still shown');
  await d.manage(); await d.editorAction(/^Engine speed graph visibility$/); await back();
  assert(!graphLabels(ctx.hierarchy()).includes('Engine speed'), 'Back applied staged recovery');
  await d.manage(); await d.editorAction(/^Engine speed graph visibility$/); await d.editorAction(/^Engine speed graph pin first$/);
  await d.tap(/^Apply layout$/); await record('paired-rpm-recovered-pinned',2);
  await d.manage(); await d.editorAction(/^Reset module layout$/); await d.tap(/^Apply layout$/);
  await record('paired-reset',2);
  await d.manage(); await d.editorAction(/^Engine speed graph expansion$/); await d.tap(/^Apply layout$/);
  const collapsed = await ctx.capture('paired-collapsed');
  assert(collapsed.includes('Collapsed') && !graphLabels(collapsed).includes('Engine speed'), 'Collapsed graph did not retain its header-only state');
  assert(graphLabels(collapsed).includes('Coolant temperature'), 'Collapsing the left graph hid its right neighbor');
  await d.manage(); await d.editorAction(/^Engine speed graph expansion$/); await d.tap(/^Apply layout$/);
  await record('paired-expanded-module-restored',2);
  // Keep exactly three selected channels via ordinary persisted display checkboxes.
  await d.manage();
  const wanted = new Set(['Display PID Engine speed', 'Display PID Coolant temperature', 'Display PID Vehicle speed']);
  const seen = new Set(); let reachedEnd = false;
  for (let attempt = 0; attempt < 45; attempt++) {
    let xml = ctx.hierarchy();
    const boxes = nodes(xml).filter(n => n.checkable === 'true' && /^Display PID /.test(n['content-desc'] ?? ''));
    for (const n of boxes) {
      const name = n['content-desc']; seen.add(name);
      if ((n.checked === 'true') !== wanted.has(name)) {
        assert(ctx.tapLabel(ctx.hierarchy(), exactLabel(name)), 'Display checkbox vanished');
        await ctx.pause(150);
      }
    }
    xml = ctx.hierarchy();
    if (xml.includes('Pins and ordering apply within the selected module kind.')) { reachedEnd = true; break; }
    const box = bounds(nodes(xml).find(n => n['content-desc'] === 'Module layout editor'));
    assert(box && box[3] > box[1], 'Missing module editor viewport');
    const [l,t,r,b] = box, x = String(Math.round((l+r)/2));
    ctx.adb(['shell','input','swipe',x,String(Math.round(t+(b-t)*.8)),x,String(Math.round(t+(b-t)*.3)),'350']); await ctx.pause(250);
  }
  assert(reachedEnd && [...wanted].every(name => seen.has(name)), 'Could not verify the complete three-channel selection');
  await d.tap(/^Apply layout$/);
  const odd = await ctx.capture('paired-odd-final-row');
  const geometry = assertCompleteGraphRows(odd,2,undefined,180);
  const oddPlots = graphGeometry(odd).plots;
  assert.deepEqual(oddPlots.map(p => p.label), ['Engine speed','Coolant temperature','Vehicle speed'], 'Odd selection lost or added a graph');
  assert(Math.abs((oddPlots[2].box[2]-oddPlots[2].box[0])-(geometry.firstPlots[0].box[2]-geometry.firstPlots[0].box[0])) <= 3,
    'Odd final graph expanded across both columns');
  assert(oddPlots[2].box[1] >= geometry.firstPlots[0].box[3], 'Odd final graph is not on the next row');
  await d.manage(); await d.editorAction(/^Reset module layout$/); await d.tap(/^Apply layout$/);
  await record('paired-final-reset',2);
  ctx.save('paired-graph-evidence.json', JSON.stringify({ cases: evidence,
    scope: 'Configured full canvas height, title/time-tick visibility, paired geometry, synthetic gap-bearing traces and interaction gates; inspect actual PNGs',
    limitations: 'Pixel inspection required; no physical tablet, TalkBack, vehicle capability, migration or retained signing identity claim.' },null,2));
}
