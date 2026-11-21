// Offline Android hierarchy acceptance check. Reads captures only; never contacts a device.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

const decode = value => value.replace(/&quot;/g, '"').replace(/&apos;/g, "'")
  .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&');
const bounds = value => /^\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]$/.exec(value ?? '')?.slice(1).map(Number);

export function checkGraphCapture(xml, { density, fontScale, contentInsetDp = 12 }) {
  assert(density > 0 && Number.isFinite(density) && fontScale > 0 && Number.isFinite(fontScale), 'Valid capture density/font scale required');
  assert(xml.includes('<hierarchy'), 'Android UI hierarchy required');
  const nodes = [...xml.matchAll(/<node\b[^>]*>/g)].map(match =>
    Object.fromEntries([...match[0].matchAll(/([\w:-]+)="([^"]*)"/g)].map(a => [a[1], decode(a[2])])));
  const views = nodes.filter(n => /^Scrollable (graphs|both)( view)?$/.test(n['content-desc'] ?? ''));
  assert.equal(views.length, 1, 'Expected exactly one Graphs/Both viewport');
  const viewport = views[0];
  assert.equal(viewport.scrollable, 'true', 'Select enough graphs to require vertical scrolling');
  const box = bounds(viewport.bounds);
  assert(box && box[2] > box[0] && box[3] > box[1], 'Nonempty viewport required');
  // The accepted canvas fills its card after the fixed value-axis gutter and panel padding.
  // Short windows scroll controls too and have an additional 12dp content inset.
  const inset = (viewport['content-desc'].endsWith(' view') ? 24 : contentInsetDp) * density;
  const left = box[0] + inset + 58 * Math.max(1, fontScale) * density;
  const right = box[2] - inset;
  const plots = nodes.map(n => ({ match: /^(.*): \d+ received observations in \d+ seconds; gaps remain empty(?:; paused)?$/.exec(n['content-desc'] ?? ''), box: bounds(n.bounds) }))
    .filter(n => n.match && n.box && n.box[2] > n.box[0] && n.box[3] > n.box[1]);
  assert(plots.length > 0, 'No visible graph canvases found');
  let bottom = box[1];
  for (const plot of plots) {
    assert(Math.abs(plot.box[0] - left) <= 3 && Math.abs(plot.box[2] - right) <= 3,
      `${plot.match[1]} must span the available width after its axis gutter`);
    assert(plot.box[1] >= bottom - 1 && plot.box[3] <= box[3] + 1, 'Graphs must stack vertically within the viewport');
    bottom = plot.box[3];
  }
  return plots.map(p => p.match[1]);
}

export function checkGraphScroll(captures, options) {
  assert(options.expectedLabels?.length >= 4, 'Use at least four selected graphs to expose a 2x2 regression');
  assert.equal(new Set(options.expectedLabels).size, options.expectedLabels.length, 'Use unique graph labels');
  assert(captures.length >= 2, 'Provide captures before and after scrolling');
  const seen = new Set();
  for (const xml of captures) {
    const labels = checkGraphCapture(xml, options);
    let previous = -1;
    for (const label of labels) {
      const index = options.expectedLabels.indexOf(label);
      assert(index > previous, `Unexpected or reordered graph: ${label}`);
      previous = index;
      seen.add(label);
    }
  }
  assert(options.expectedLabels.every(label => seen.has(label)), 'Scroll captures must reach every selected graph, including the last');
  return [...seen];
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [density, fontScale, labels, ...files] = process.argv.slice(2);
  const seen = checkGraphScroll(files.map(file => readFileSync(file, 'utf8')), {
    density: Number(density), fontScale: Number(fontScale), expectedLabels: JSON.parse(labels ?? 'null'),
  });
  console.log(`PASS: full-width vertical graphs, selection order and scroll reachability: ${seen.join(', ')}`);
}
