// Uses only the caller's verified, job-owned offline emulator.
import assert from 'node:assert/strict';
import { nodes, workspaceDriver } from './capture-graph-workspace.mjs';
const bounds = n => /^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$/.exec(n?.bounds ?? '')?.slice(1).map(Number);
const pages = ['Diagnosis', 'Live Sensors', 'History', 'Advanced'];
export function assertNavigation(xml, { width, selected, previous, headerBrand = false, expanded = false }) {
  const all = nodes(xml);
  const rails = all.filter(n => n['content-desc'] === 'Workspace navigation');
  assert.equal(rails.length, width ? 1 : 0, 'Unexpected navigation rail visibility');
  const box = width ? bounds(rails[0]) : null;
  if (width) {
    assert(box && box[2] - box[0] === width && box[3] > box[1], 'Wrong measured rail width');
    if (previous) assert.deepEqual(box, previous, 'Navigation moved after switching pages');
    const toggle = all.find(n => n['content-desc'] === (expanded ? 'Collapse sidebar' : 'Expand sidebar'));
    const tb = bounds(toggle);
    // Compose can export the accessible name on a child of the clickable node.
    // Require the same complete bounds, just as for selected destination tabs.
    const toggleControl = toggle && all.find(n => n.bounds === toggle.bounds && n.clickable === 'true' && n.focusable === 'true');
    assert(toggleControl && tb && tb[2] - tb[0] >= 48 && tb[3] - tb[1] >= 48,
      'Missing accessible 48dp sidebar toggle');
    for (const label of pages) {
      const tab = all.find(n => n['content-desc'] === label);
      assert(tab, `Missing tab: ${label}`);
      const control = all.find(n => n.bounds === tab.bounds && n.focusable === 'true');
      assert(control && control.selected === String(label === selected), `Wrong selected tab: ${label}`);
      const b = bounds(tab);
      assert(b && b[0] >= box[0] && b[2] <= box[2] && b[1] >= box[1] && b[3] <= box[3], 'Tab outside rail');
      assert(b[2] - b[0] >= 48 && b[3] - b[1] >= 48, 'Tab below 48dp');
      // Only inspect labels inside the rail; page headings can have the same text.
      const visible = all.find(n => n.text === label && bounds(n)?.[2] <= box[2]);
      assert.equal(Boolean(visible), expanded, `Wrong rail label policy: ${label}`);
      if (visible) {
        const v = bounds(visible);
        assert(v[0] >= b[0] && v[1] >= b[1] && v[2] <= b[2] && v[3] <= b[3], 'Label outside tab');
      }
    }
  }
  assert.equal(all.filter(n => n['content-desc'] === 'OpenWrench').length, width || headerBrand ? 1 : 0,
    'Brand must appear once in the rail or Live Sensors phone header');
  return box;
}
export const navigationCases = [
  ['1280x800', 1, 80, 'wide'], ['1000x800', 1, 80, 'wide-boundary'],
  ['999x800', 1, 80, 'compact-boundary'], ['700x800', 1, 80, 'rail-boundary'],
  ['699x800', 1, 0, 'below-rail'], ['1280x1000', 1.5, 80, 'large-text'],
  ['780x1600', 1, 80, 'portrait'], ['390x800', 1, 0, 'phone'],
];
export async function captureNavigationConsistency(ctx, cases = navigationCases) {
  const d = workspaceDriver(ctx);
  const back = async () => { ctx.adb(['shell', 'input', 'keyevent', 'KEYCODE_BACK']); await ctx.pause(400); };
  for (const [size, fontScale, railWidth, name] of cases) {
    await ctx.launch(size, 160, fontScale);
    for (const expanded of railWidth ? [false, true] : [false]) {
      if (expanded) await d.tap(/^Expand sidebar$/);
      const width = railWidth ? (expanded ? 224 * fontScale : railWidth) : 0;
      const modeName = expanded ? 'expanded' : 'collapsed';
      const check = async (suffix, selected, previous) => assertNavigation(
        await ctx.capture(`navigation-${name}-${modeName}-${suffix}`),
        { width, selected, previous, expanded, headerBrand: selected === 'Live Sensors' });
      let previous;
      for (const page of pages) {
        await d.tap(new RegExp(`^${page}$`));
        previous = await check(page.replaceAll(' ', '-').toLowerCase(), page, previous);
      }
      // Deeper flows in both sidebar modes, and unchanged phone navigation.
      if (!['wide', 'large-text', 'phone'].includes(name)) continue;
      await d.tap(/^Open Connection settings$/);
      await check('nested', 'Advanced', previous);
      await back(); await check('nested-back', 'Advanced', previous);
      await back(); await check('page-back', 'History', previous);
      await d.tap(/^Live Sensors$/);
      for (const mode of ['Both', 'Graphs', 'Values']) {
        await d.mode(mode);
        await check(mode.toLowerCase(), 'Live Sensors', previous);
      }
      await d.action(/^Enter full screen$/);
      assertNavigation(await ctx.capture(`navigation-${name}-${modeName}-fullscreen`), { width: 0 });
      await back(); await check('restored', 'Live Sensors', previous);
      if (name === 'wide') {
        // Actual orientation/configuration recreation, without force-stopping the app.
        ctx.adb(['shell', 'settings', 'put', 'system', 'user_rotation', '1']);
        await ctx.pause(1000);
        const rotated = await check('rotated', 'Live Sensors');
        assert(rotated[3] - rotated[1] > previous[3] - previous[1], 'Rotation did not produce a portrait viewport');
        ctx.adb(['shell', 'settings', 'put', 'system', 'user_rotation', '0']);
        await ctx.pause(1000);
        await check('rotation-restored', 'Live Sensors', previous);
        // Hide the rail in a narrow window, then restore the same chosen mode.
        ctx.adb(['shell', 'wm', 'size', '390x800']); await ctx.pause(1000);
        assertNavigation(await ctx.capture(`navigation-narrow-${modeName}`), { width: 0, headerBrand: true });
        ctx.adb(['shell', 'wm', 'size', size]); await ctx.pause(1000);
        await check('width-restored', 'Live Sensors', previous);
      }
    }
  }
  // Toggling is not a navigation visit: Back goes straight to the previous destination.
  await ctx.launch('1280x800', 160, 1);
  await d.tap(/^History$/); await d.tap(/^Expand sidebar$/); await back();
  assertNavigation(await ctx.capture('navigation-toggle-back-expanded'), { width: 224, expanded: true, selected: 'Diagnosis' });
  await d.tap(/^History$/); await d.tap(/^Collapse sidebar$/); await back();
  assertNavigation(await ctx.capture('navigation-toggle-back-collapsed'), { width: 80, selected: 'Diagnosis' });
}
