// Called only by the existing guarded, job-owned offline emulator capture.
import assert from 'node:assert/strict';
import { nodes, assertView, workspaceDriver } from './capture-graph-workspace.mjs';

export function assertText(xml, text) {
  const decoded = value => value?.replace(/&(amp|lt|gt|quot|apos);/g, (_, entity) => ({ amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" })[entity]);
  assert(nodes(xml).some(n => decoded(n.text) === text || decoded(n['content-desc']) === text), `Missing visible state: ${text}`);
}
export function assertExitedToSettings(activities) {
  const resumed = activities.split('\n').filter(line => /(?:mResumedActivity|topResumedActivity)/.test(line));
  assert(resumed.some(line => /com\.android\.settings\//.test(line)), 'Root Back did not return to the previously launched system Settings');
  assert(!resumed.some(line => /org\.openwrench\.android\//.test(line)), 'Root Back trapped the user in OpenWrench');
}
export function checkboxState(xml, description) {
  const node = nodes(xml).find(n => n['content-desc'] === description && n.checkable === 'true');
  assert(node && /^(true|false)$/.test(node.checked), `Missing selectable checkbox: ${description}`);
  return node.checked;
}

/** Read the current WM InputMethod window, never historical input-method event records. */
export function currentImeVisibility(windows) {
  const blocks = windows.split(/^  Window #\d+ Window\{/m).slice(1)
    .filter(block => /^[^\n]*\bInputMethod\}:\r?\n/.test(block));
  if (blocks.length !== 1) return null;
  const visible = [...blocks[0].matchAll(/^    isVisible=(true|false)\s*$/gm)];
  const onScreen = [...blocks[0].matchAll(/^    isOnScreen=(true|false)\s*$/gm)];
  if (visible.length !== 1 || onScreen.length !== 1 || visible[0][1] !== onScreen[0][1]) return null;
  return visible[0][1] === 'true';
}

export async function waitForImeVisibility({ readWindows, pause, onSample = () => {} }, expected,
  { attempts = 20, interval = 150 } = {}) {
  for (let index = 0; index < attempts; index++) {
    const windows = readWindows();
    const visible = currentImeVisibility(windows);
    onSample({ index, visible, windows });
    if (visible === expected) return;
    if (index + 1 < attempts) await pause(interval);
  }
  throw new Error(`Current IME window did not become ${expected ? 'visible' : 'hidden'} within bounded polling`);
}

export async function captureSystemBack(env) {
  const { adb, hierarchy, pause, capture, launch, save } = env;
  const driver = workspaceDriver(env);
  const back = async () => { adb(['shell', 'input', 'keyevent', 'KEYCODE_BACK']); await pause(500); };
  const check = async (name, text) => assertText(await capture(`back-${name}`), text);
  const view = async (name, mode) => assertView(await capture(`back-${name}`), mode);
  const results = [];
  assert.equal(adb(['shell', 'getprop', 'ro.kernel.qemu']).trim(), '1', 'Back capture requires the guarded emulator');
  await launch('1280x800', 160, 1);
  await check('root', 'Vehicle diagnosis');
  await driver.tap(/^Live Sensors$/);
  await driver.mode('Graphs'); await driver.mode('Values');
  await back(); await view('values-to-graphs', 'Graphs');
  await back(); await view('graphs-to-dashboard', 'Both');
  await back(); await check('dashboard-to-root', 'Vehicle diagnosis');
  results.push('Actual display-mode/page sequence unwound to original root');

  await driver.tap(/^Live Sensors$/); await driver.mode('Both'); await driver.mode('Both');
  await driver.action(/^Enter full screen$/);
  await view('fullscreen', 'Graphs');
  await driver.tap(/^Engine speed$/);
  await check('inspector', 'Close details');
  await back(); await view('inspector-to-fullscreen', 'Graphs');
  assertText(hierarchy(), 'Exit full screen');
  await back(); await view('fullscreen-to-original-dashboard', 'Both');
  await back(); await check('deduplicated-root', 'Vehicle diagnosis');
  results.push('Inspector precedes fullscreen; fullscreen restores prior mode without phantom history; repeated tabs deduplicated');

  await driver.tap(/^Live Sensors$/);
  await driver.tap(/^Workspace menu and chart controls$/);
  await check('menu', 'Manage live acquisition'); await back(); await view('menu-dismissed', 'Both');
  await driver.tap(/^Configure dashboard PIDs and modules$/);
  const initial = checkboxState(hierarchy(), 'Display PID Engine speed');
  await driver.tap(/^Display PID Engine speed$/);
  assert.notEqual(checkboxState(hierarchy(), 'Display PID Engine speed'), initial);
  await back(); await view('module-draft-cancelled', 'Both');
  await driver.tap(/^Configure dashboard PIDs and modules$/);
  assert.equal(checkboxState(hierarchy(), 'Display PID Engine speed'), initial, 'Back committed a module draft');
  await capture('back-module-selection-preserved');
  const field = nodes(hierarchy()).find(n => n.class === 'android.widget.EditText');
  const b = /^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$/.exec(field?.bounds ?? '')?.slice(1).map(Number);
  assert(b && b[2] > b[0] && b[3] > b[1], 'Module search field is not visible');
  adb(['shell', 'input', 'tap', String(Math.round((b[0] + b[2]) / 2)), String(Math.round((b[1] + b[3]) / 2))]);
  const imeSamples = [];
  const waitIme = (expected, phase) => waitForImeVisibility({
    readWindows: () => adb(['shell', 'dumpsys', 'window']), pause,
    onSample: ({ index, visible, windows }) => {
      imeSamples.push({ phase, index, visible, atMillis: Date.now() });
      save(`back-ime-${phase}-windows.txt`, windows);
      save('back-ime-visibility-samples.json', JSON.stringify(imeSamples, null, 2));
    },
  }, expected);
  await waitIme(true, 'shown');
  save('back-ime-before-input-method.txt', adb(['shell', 'dumpsys', 'input_method']));
  await capture('back-ime-shown');
  await back();
  try { await waitIme(false, 'after-first-back'); }
  finally {
    save('back-ime-after-input-method.txt', adb(['shell', 'dumpsys', 'input_method']));
    save('back-ime-after-windows.txt', adb(['shell', 'dumpsys', 'window']));
    await capture('back-ime-after-first-back');
  }
  await check('keyboard-dismissed-dialog-kept', 'LAYOUT & SIGNALS');
  await back(); await view('dialog-dismissed', 'Both');
  results.push('Menus/dialogs consume Back; unsaved module selection discarded; IME consumes the first Back');

  await driver.tap(/^History$/); await driver.tap(/^Individual drives and scans$/); await driver.tap(/^Scans$/);
  await driver.tap(/^Advanced$/); await driver.tap(/^Open Local drives and scans$/);
  await check('records-revisited', 'History & Reports');
  await back(); await check('records-to-actual-previous-advanced', 'Connection & local data');
  await back(); await check('advanced-to-records', 'History & Reports');
  await back(); await check('records-tabs-to-drives', 'Drive history');
  results.push('Nested record tabs share the actual page history order');

  await launch('1280x800', 160, 1);
  await driver.tap(/^Live Sensors$/); await driver.mode('Graphs');
  await driver.action(/^Pause chart display only/);
  await driver.tap(/^Advanced$/); await driver.tap(/^Open Connection settings$/);
  adb(['shell', 'settings', 'put', 'system', 'user_rotation', '1']); await pause(1400);
  await check('rotated-session', 'Selected source');
  await back(); await check('rotated-advanced', 'Connection & local data');
  await back(); await view('rotated-graphs-history', 'Graphs');
  assertText(hierarchy(), 'Charts paused · values current');
  await back(); await view('rotated-dashboard-history', 'Both');
  adb(['shell', 'settings', 'put', 'system', 'user_rotation', '0']); await pause(1000);
  results.push('Configuration recreation preserves page/mode history and frozen chart state');

  const overlays = adb(['shell', 'cmd', 'overlay', 'list', '--user', '0']);
  const gesture = 'com.android.internal.systemui.navbar.gestural';
  const previous = /^\[x\] (com\.android\.internal\.systemui\.navbar\.\S+)$/m.exec(overlays)?.[1];
  if (overlays.split('\n').some(line => line.trim().endsWith(' ' + gesture))) {
    try {
      adb(['shell', 'cmd', 'overlay', 'enable-exclusive', '--user', '0', '--category', gesture]);
      await launch('1280x800', 160, 1); await driver.tap(/^Live Sensors$/);
      const root = nodes(hierarchy()).filter(n => n.package === 'org.openwrench.android')
        .map(n => /^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$/.exec(n.bounds ?? '')?.slice(1).map(Number))
        .filter(Boolean).sort((a,b) => (b[2]-b[0])*(b[3]-b[1]) - (a[2]-a[0])*(a[3]-a[1]))[0];
      assert(root && root[2] - root[0] > 500, 'Cannot derive edge gesture from observed window');
      const y = String(Math.round((root[1] + root[3]) / 2));
      adb(['shell', 'input', 'swipe', '1', y, String(Math.round(root[2] * .3)), y, '350']);
      await pause(1000); await check('system-edge-gesture', 'Vehicle diagnosis');
      results.push('Injected system edge swipe returns to previous page with gestural navigation enabled');
    } finally {
      if (previous) adb(['shell', 'cmd', 'overlay', 'enable-exclusive', '--user', '0', '--category', previous]);
    }
  } else results.push('UNRUN: system gestural-navigation overlay unavailable on this emulator image');

  // Put a known system Activity underneath the fresh root. No disabled-launcher dependency.
  adb(['shell', 'am', 'start', '-W', '-a', 'android.settings.SETTINGS']);
  await launch('1280x800', 160, 1); await check('fresh-root-before-exit', 'Vehicle diagnosis');
  await back();
  const activities = adb(['shell', 'dumpsys', 'activity', 'activities']);
  save('back-root-exit-activities.txt', activities); assertExitedToSettings(activities);
  results.push('Root Back falls through to Android and returns to system Settings');
  save('system-back-evidence.json', JSON.stringify({ results, limitations: 'Injected emulator events, not physical Meswao or TalkBack qualification. No hardware connection or user history.' }, null, 2));
}
