import test from 'node:test';
import assert from 'node:assert/strict';
import { assertGraphColumns, graphGeometry, exactLabel, assertCompleteGraphRows } from './capture-mechanic-graph-columns.mjs';
const viewport = '<node content-desc="Scrollable graphs" bounds="[80,150][1280,800]"/>';
const plot = (label, box, count=57) => `<node content-desc="${label}: ${count} received observations in 60 seconds; gaps remain empty" bounds="${box}"/>`;
const pair = viewport+plot('Engine speed','[160,250][660,450]')+plot('Coolant temperature','[760,250][1260,450]');
test('paired evidence checks actual geometry, order and synthetic gaps',()=>{
  assertGraphColumns(pair,2);
  assert.throws(()=>assertGraphColumns(pair.replace('[760,250][1260,450]','[160,500][660,700]'),2));
  assert.throws(()=>assertGraphColumns(pair.replace('[760,250][1260,450]','[650,250][1150,450]'),2));
  assert.throws(()=>assertGraphColumns(pair.replace('Coolant temperature','Vehicle speed'),2));
  assert.throws(()=>assertGraphColumns(pair.replace('57 received','61 received'),2));
  assert.throws(()=>assertGraphColumns(pair.replace('[760,250][1260,450]','[760,250][1400,450]'),2));
});
test('one-column evidence requires a wide real canvas and no horizontal peer',()=>{
  const single = viewport+plot('Engine speed','[160,250][1260,600]');
  assertGraphColumns(single,1);
  assert.throws(()=>assertGraphColumns(pair,1));
  assert.throws(()=>assertGraphColumns(viewport,1));
  assert.throws(()=>assertGraphColumns(single+plot('Coolant temperature','[760,250][1260,450]'),1));
});
test('clipped or absent canvases cannot masquerade as visible layout evidence',()=>{
  assert.equal(graphGeometry(viewport+plot('Engine speed','[160,100][660,450]')).plots.length,0);
  assert.equal(graphGeometry(viewport+plot('Engine speed','[160,250][660,850]')).plots.length,0);
  assert.equal(graphGeometry(viewport+plot('Engine speed','[160,250][160,450]')).plots.length,0);
  assert.throws(()=>graphGeometry(plot('Engine speed','[160,250][660,450]')));
});


test('complete-row gate rejects clipped XML slivers and missing headers or time axes',()=>{
  const header = '<node text="Engine speed" bounds="[100,200][300,225]"/><node text="Coolant temperature" bounds="[710,200][1000,225]"/>';
  const ticks = '<node text="0 s" bounds="[640,454][660,468]"/><node text="0 s" bounds="[1240,454][1260,468]"/>';
  const full = pair + header + ticks;
  assertCompleteGraphRows(full, 2, undefined, 200);
  for (const height of [6,9,99])
    assert.throws(()=>assertCompleteGraphRows(full.replaceAll('250]', String(450-height)+']'),2,undefined,200), /clipped/);
  assert.throws(()=>assertCompleteGraphRows(pair+ticks,2,undefined,200),/header/);
  assert.throws(()=>assertCompleteGraphRows(pair+header,2,undefined,200),/time tick/);
  assert.throws(()=>assertCompleteGraphRows(full,2,undefined,undefined),/height required/);
});

test('literal labels preserve parentheses, brackets and regex punctuation',()=>{
  for (const label of ['Display PID A/F (Bank 1)', 'Sensor [1].* + $? ^ {2} |', String.raw`Path \ sensor`]) {
    const pattern = exactLabel(label);
    assert(pattern.test(label), label);
    assert(!pattern.test('prefix '+label));
    assert(!pattern.test(label+' suffix'));
  }
  assert(!exactLabel('Sensor [1]').test('Sensor 1'));
  assert(!exactLabel('A.F').test('ABF'));
});
