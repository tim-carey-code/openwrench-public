// Optional local capture helpers. Synthetic unit tests are not Android visual evidence.
export function tapLabel(xml, pattern, adb) {
  for (const match of xml.matchAll(/<node\b[^>]*>/g)) {
    const attrs = Object.fromEntries([...match[0].matchAll(/([\w:-]+)="([^"]*)"/g)].map(item => [item[1], item[2]]));
    if (!pattern.test(attrs.text ?? '') && !pattern.test(attrs['content-desc'] ?? '')) continue;
    const bounds = /^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$/.exec(attrs.bounds ?? '');
    if (!bounds || attrs.enabled === 'false') continue;
    const [left, top, right, bottom] = bounds.slice(1).map(Number);
    if (right <= left || bottom <= top) continue;
    adb(['shell', 'input', 'tap', String(Math.floor((left + right) / 2)), String(Math.floor((top + bottom) / 2))]);
    return true;
  }
  return false;
}

export function assertLargeFontNavigation(xml) {
  const labels = ['Diagnosis', 'Live Sensors', 'History', 'Advanced'];
  const nodes = [...xml.matchAll(/<node\b[^>]*>/g)].map(match =>
    Object.fromEntries([...match[0].matchAll(/([\w:-]+)="([^"]*)"/g)].map(item => [item[1], item[2]])));
  const workspaceMenu = nodes.find(n => n['content-desc'] === 'Workspace menu and chart controls');
  if (workspaceMenu) {
    const b = /^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$/.exec(workspaceMenu.bounds ?? '')?.slice(1).map(Number);
    if (!b || b[0] < 0 || b[2] > 780 || b[3] > 1592 || b[2] - b[0] < 96 || b[3] - b[1] < 96)
      throw new Error('Workspace menu is clipped or below 48dp');
    return;
  }
  const boxes = labels.map(label => {
    const node = nodes.filter(n => n.text === label && n.class === 'android.widget.TextView').at(-1);
    const box = /^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$/.exec(node?.bounds ?? '')?.slice(1).map(Number);
    if (!box || box[2] <= box[0] || box[3] <= box[1] || box[0] < 0 || box[2] > 780 || box[3] > 1592)
      throw new Error(`Large-font navigation label missing or touching screen edge: ${label}`);
    return box;
  });
  // At 390dp / 2x text, all four full labels must occupy two complete rows.
  if (boxes[0][1] !== boxes[1][1] || boxes[2][1] !== boxes[3][1] || boxes[2][1] <= boxes[0][3])
    throw new Error('Large-font navigation did not use two complete, non-overlapping rows');
  if (boxes[0][2] > boxes[1][0] || boxes[2][2] > boxes[3][0])
    throw new Error('Large-font navigation labels overlap');
}
