import test from 'node:test';
import assert from 'node:assert/strict';
import { assertText, assertExitedToSettings, checkboxState, currentImeVisibility, waitForImeVisibility } from './capture-system-back.mjs';

test('Back assertions require the actual destination and preserve checkbox state', () => {
  assertText('<node text="Vehicle diagnosis"/>', 'Vehicle diagnosis');
  assertText('<node text="History &amp; Reports"/>', 'History & Reports');
  assert.throws(() => assertText('<node text="Diagnosis"/>', 'Vehicle diagnosis'));
  assert.equal(checkboxState('<node content-desc="Display PID Engine speed" checkable="true" checked="false"/>', 'Display PID Engine speed'), 'false');
  assert.throws(() => checkboxState('<node content-desc="Display PID Engine speed"/>', 'Display PID Engine speed'));
});
test('root-exit gate rejects stale/background Settings and resumed app', () => {
  assertExitedToSettings('topResumedActivity=ActivityRecord{com.android.settings/.Settings}');
  assert.throws(() => assertExitedToSettings('mResumedActivity=ActivityRecord{org.openwrench.android/.MainActivity}\nHist #1: com.android.settings/.Settings'));
  assert.throws(() => assertExitedToSettings('mResumedActivity=ActivityRecord{com.android.settings/.Settings}\ntopResumedActivity=ActivityRecord{org.openwrench.android/.MainActivity}'));
  assert.throws(() => assertExitedToSettings('mResumedActivity=null'));
});

const imeDump = (visible, onScreen = visible) => `WINDOW MANAGER WINDOWS
  Window #3 Window{abc u0 InputMethod}:
    mOwnerUid=10001 package=com.android.inputmethod.latin
    isOnScreen=${onScreen}
    isVisible=${visible}
  Window #4 Window{def u0 org.openwrench.android/MainActivity}:
    isOnScreen=true
    isVisible=true
`;
test('IME parser ignores historical true/false fields and reads only the current exact WM window', () => {
  assert.equal(currentImeVisibility('mInputShown=true\nHistory: mIsInputViewShown=true\n'+imeDump(false)), false);
  assert.equal(currentImeVisibility('History: mInputShown=false\n'+imeDump(true)), true);
  assert.equal(currentImeVisibility('History: mInputShown=true'), null);
  assert.equal(currentImeVisibility(imeDump(true,false)), null);
  assert.equal(currentImeVisibility(imeDump(false)+imeDump(true)), null);
  assert.equal(currentImeVisibility(imeDump(false).replace('    isVisible=false','Historical isVisible=false')), null);
  assert.equal(currentImeVisibility(imeDump(false).replace('u0 InputMethod}', 'u0 InputMethodDialog}')), null);
});
test('IME poll tolerates animation delivery, records every observed state and fails closed on unknown', async () => {
  const values = [imeDump(true), imeDump(true,false), imeDump(false)];
  const samples = []; let pauses = 0;
  await waitForImeVisibility({readWindows:()=>values.shift(),pause:async()=>pauses++,onSample:s=>samples.push(s.visible)},false);
  assert.deepEqual(samples,[true,null,false]); assert.equal(pauses,2);
  await assert.rejects(waitForImeVisibility({readWindows:()=> 'History: mInputShown=false',pause:async()=>{}},false,{attempts:3,interval:0}),/bounded polling/);
  await assert.rejects(waitForImeVisibility({readWindows:()=>imeDump(true),pause:async()=>{}},false,{attempts:3,interval:0}),/bounded polling/);
});
