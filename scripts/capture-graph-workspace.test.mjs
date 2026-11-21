// Validate acceptance predicates; these fixtures are not rendered Android evidence.
import test from 'node:test';
import assert from 'node:assert/strict';
import { assertView, graphLabels, assertReferenceOverview } from './capture-graph-workspace.mjs';
const graph = '<node content-desc="Engine speed: 2 received observations in 60 seconds; gaps remain empty"/>';
const card = '<node content-desc="Engine speed value card"/>';
const fixture = (mode, content) => `<hierarchy><node content-desc="Scrollable ${mode.toLowerCase()}"/>${content}</hierarchy>`;
test('mode checks reject mixed or missing content', () => {
  assertView(fixture('Both', card + graph), 'Both');
  assertView(fixture('Graphs', graph), 'Graphs');
  assertView(fixture('Values', card), 'Values');
  assert.throws(() => assertView(fixture('Values', graph), 'Values'), /must not/);
  assert.throws(() => assertView(fixture('Graphs', card + graph), 'Graphs'), /must not/);
  assert.throws(() => assertView(fixture('Both', graph), 'Both'), /must render/);
  assert.throws(() => assertView('<hierarchy/>', 'Graphs'), /Missing/);
});
test('graph identity excludes value cards and keeps paused samples', () => {
  assert.deepEqual(graphLabels(fixture('Both', card + graph)), ['Engine speed']);
  assert.deepEqual(graphLabels(graph.replace('gaps remain empty', 'gaps remain empty; paused')), ['Engine speed']);
});

test('reference acceptance requires four named roles, supporting panels and multiple plots', () => {
  const xml = '<hierarchy><node content-desc="Reference dashboard grid"/>' +
    ['Engine speed', 'Coolant temperature', 'Vehicle speed', 'Module Voltage'].map(label => `<node content-desc="${label} value card"/>`).join('') +
    ['Signal Integrity', 'Data Quality', 'RPM vs Throttle'].map(label => `<node text="${label}"/>`).join('') +
    graph + graph.replace('Engine speed', 'Coolant temperature') + '</hierarchy>';
  assertReferenceOverview(xml);
  assert.throws(() => assertReferenceOverview(xml.replace('Vehicle speed value card', 'Speed value card')), /reference role/);
  assert.throws(() => assertReferenceOverview(xml.replace('Reference dashboard grid', 'Scrollable both')), /wide reference/);
  assert.throws(() => assertReferenceOverview(xml.replace(graph, '')), /more than one plot/);
});


test('mode driver scrolls observed large-text tabs and requires the requested control', async () => {
  const { workspaceDriver } = await import('./capture-graph-workspace.mjs');
  const scroller = '<node content-desc="Scrollable display modes" scrollable="true" bounds="[0,162][780,262]" />';
  let swipes = 0; let selected = false;
  const driver = workspaceDriver({
    adb: args => { assert.deepEqual(args.slice(0,3), ['shell','input','swipe']); assert(Number(args[3]) > Number(args[5])); swipes++; },
    hierarchy: () => scroller + (swipes ? '<node text="Values" bounds="[200,162][400,262]" />' : ''),
    tapLabel: (xml, pattern) => { if (xml.includes('text="Values"') && pattern.test('Values')) { selected = true; return true; } return false; },
    pause: async () => {}, capture: async () => '',
  });
  await driver.mode('Values');
  assert.equal(swipes, 1); assert(selected);
});

test('mode driver fails after bounded swipes when a tab never becomes reachable', async () => {
  const { workspaceDriver } = await import('./capture-graph-workspace.mjs');
  let swipes = 0;
  const driver = workspaceDriver({ adb: () => { swipes++; },
    hierarchy: () => '<node content-desc="Scrollable display modes" scrollable="true" bounds="[0,162][780,262]" />',
    tapLabel: () => false, pause: async () => {}, capture: async () => '',
  });
  await assert.rejects(driver.mode('Values'), /remains unreachable after bounded scrolling/);
  assert.equal(swipes, 4);
});
