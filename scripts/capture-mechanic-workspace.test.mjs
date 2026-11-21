import test from 'node:test';
import assert from 'node:assert/strict';
import { assertMechanicOverview, assertMixedCounts, assertStatusPlacement, visibleMechanicDetailLabels } from './capture-mechanic-workspace.mjs';
const node = text => `<node text="${text}"/>`;
const minimum = '<node content-desc="Diagnostic dashboard"/>'+node('DIAGNOSTIC DASHBOARD')+node('SIMULATED')+'<node text="DATA STATUS" bounds="[20,40][140,60]"/><node content-desc="Scrollable both" bounds="[0,0][1000,800]"/>';
test('mechanic capture requires normal dashboard, simulated source and source-specific receipt labels', () => {
  assertMechanicOverview(minimum+node('Fictional static values · receipt times not supplied'), {sixCards:false});
  assertMechanicOverview(minimum+node('Synthetic timed observations · not vehicle receipts'), {sixCards:false,timed:true});
  assert.throws(()=>assertMechanicOverview(minimum+node('Actual receipts only'),{sixCards:false}));
  assert.throws(()=>assertMechanicOverview(minimum.replace('SIMULATED','LIVE')+node('Fictional static values · receipt times not supplied'),{sixCards:false}));
  assert.throws(()=>assertMechanicOverview(minimum+node('Fictional static values · receipt times not supplied'),{sixCards:true}));
  assert.throws(()=>assertMechanicOverview(minimum+node('Fictional static values · receipt times not supplied')+node('Reference dashboard grid'),{sixCards:false}));
});

test('status capture proves initial placement and exact mixed counts before and after hiding', () => {
  const xml = minimum + node('0/4 selected available') + node('1 stale · 2 unavailable/not sampled · 0 disconnected · 1 read errors');
  assertMixedCounts(xml);
  assert.throws(() => assertMixedCounts(xml.replace('1 stale', '0 stale')));
  assert.throws(() => assertMixedCounts(xml.replace('0/4', '0/0')));
  assert.throws(() => assertStatusPlacement(xml.replace('[20,40][140,60]', '[20,700][140,720]')));
  assert.throws(() => assertStatusPlacement(xml+'<node content-desc="Engine speed value card" bounds="[10,10][200,100]"/>'));
});

test('large-text detail evidence counts only fully visible panel labels', () => {
  const viewport = '<node content-desc="Scrollable both" bounds="[0,200][780,1500]"/>';
  const visible = '<node text="Bank 1" bounds="[20,300][200,350]"/><node text="Air &amp; Engine" bounds="[20,1200][400,1250]"/>';
  const clipped = '<node text="Bank 2" bounds="[20,1490][200,1550]"/><node text="LTFT · %" bounds="[20,100][200,150]"/>';
  assert.deepEqual(visibleMechanicDetailLabels(viewport+visible+clipped), ['Bank 1', 'Air & Engine']);
  assert.throws(() => visibleMechanicDetailLabels(visible), /viewport missing/);
});
