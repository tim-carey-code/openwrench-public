import test from 'node:test';
import assert from 'node:assert/strict';
import { assertNavigation } from './capture-navigation-consistency.mjs';
function fixture(width = 80, selected = 'Diagnosis') {
  const node = a => `<node ${Object.entries(a).map(([k,v]) => `${k}="${v}"`).join(' ')} />`;
  return '<hierarchy>' + node({'content-desc':'Workspace navigation', bounds:`[0,24][${width},768]`}) +
    node({'content-desc':'OpenWrench'}) +
    node({'content-desc':width > 80 ? 'Collapse sidebar' : 'Expand sidebar', clickable:'true', focusable:'true', bounds:`[12,30][${width-12},86]`}) + ['Diagnosis','Live Sensors','History','Advanced'].map((label,i) => {
      const bounds = `[12,${100+i*60}][${width-12},${156+i*60}]`;
      return node({'content-desc':label,bounds}) + node({bounds,focusable:'true',selected:String(label===selected)}) +
        (width>80 ? node({text:label,bounds}) : '');
    }).join('') + '</hierarchy>';
}
test('both sidebar modes retain bounds, labels and selection across all pages', () => {
  for (const width of [80,224,336]) {
    let previous;
    for (const selected of ['Diagnosis','Live Sensors','History','Advanced'])
      previous = assertNavigation(fixture(width,selected), {width,selected,previous,expanded:width>80});
  }
});
test('detects the original Live Sensors width regression and displaced bounds', () => {
  const previous = assertNavigation(fixture(), {width:80,selected:'Diagnosis'});
  assert.throws(() => assertNavigation(fixture(200,'Live Sensors'), {width:80,selected:'Live Sensors',previous}), /width/);
  assert.throws(() => assertNavigation(fixture().replace('[0,24][80,768]','[1,24][81,768]'), {width:80,selected:'Diagnosis',previous}), /moved/);
});
test('rejects duplicate branding, visible labels or missing accessible names and stale selection', () => {
  const options = {width:80,selected:'Diagnosis'};
  assert.throws(() => assertNavigation(fixture().replace('</hierarchy>','<node content-desc="OpenWrench" /></hierarchy>'),options), /Brand/);
  assert.throws(() => assertNavigation(fixture().replace('</hierarchy>','<node text="Live Sensors" bounds="[12,160][68,216]" /></hierarchy>'),options), /label/);
  assert.throws(() => assertNavigation(fixture().replace('content-desc="Live Sensors"','content-desc=""'),options), /Missing tab/);
  assert.throws(() => assertNavigation(fixture(80,'History'),options), /selected/);
});
test('phone header and explicit full screen have distinct branding rules', () => {
  assertNavigation('<hierarchy><node content-desc="OpenWrench" /></hierarchy>', {width:0,headerBrand:true});
  assertNavigation('<hierarchy/>', {width:0});
  assert.throws(() => assertNavigation(fixture(), {width:0}), /visibility/);
});

test('expanded mode requires every label including Live Sensors and a usable toggle', () => {
  const options = {width:224,selected:'Diagnosis',expanded:true};
  assert.throws(() => assertNavigation(fixture(224).replace('text="Live Sensors"','text=""'),options), /label/);
  assert.throws(() => assertNavigation(fixture(224).replace('content-desc="Collapse sidebar"','content-desc=""'),options), /toggle/);
  assert.throws(() => assertNavigation(fixture(224).replace('[12,30][212,86]','[12,30][212,60]'),options), /toggle/);
  assert.throws(() => assertNavigation(fixture(224).replaceAll('[12,100][212,156]','[12,100][212,130]'),options), /48dp/);
});

test('expanded Compose toggle can export its accessible name separately from the click target', () => {
  const original = fixture(224);
  const split = original.replace('content-desc="Collapse sidebar" clickable="true" focusable="true" bounds="[12,30][212,86]"',
    'clickable="true" focusable="true" bounds="[12,30][212,86]"') +
    '<node content-desc="Collapse sidebar" clickable="false" focusable="false" bounds="[12,30][212,86]" />';
  assertNavigation(split, {width:224,selected:'Diagnosis',expanded:true});
  assert.throws(() => assertNavigation(split.replace('clickable="true"','clickable="false"'),
    {width:224,selected:'Diagnosis',expanded:true}), /toggle/);
  assert.throws(() => assertNavigation(split.replace('focusable="true"','focusable="false"'),
    {width:224,selected:'Diagnosis',expanded:true}), /toggle/);
});
