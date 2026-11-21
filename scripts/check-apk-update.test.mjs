import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, readFileSync, mkdirSync, symlinkSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { checkApkUpdate, inspectApk } from './check-apk-update.mjs';

const pkg = 'org.openwrench.android.livehomepreview';
const cert = 'a'.repeat(64);
const other = 'b'.repeat(64);
const base = { packageId: pkg, versionCode: '7', minSdk: 24, cert, debuggable: true };
function setup(t) {
  const dir = mkdtempSync(join(tmpdir(), 'ow-apk-check-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const reference = join(dir, 'reference.apk'), candidate = join(dir, 'candidate.apk');
  writeFileSync(reference, '{}'); writeFileSync(candidate, '{}');
  return { dir, reference, candidate };
}
function fakeRun(previous, next, reference) {
  return (command, args) => {
    const data = args.at(-1) === reference ? previous : next;
    if (command === 'apksigner') {
      if (data.invalidSignature) throw new Error('bad signature');
      return (data.signers ?? [data.cert]).map((value, i) => `Signer #${i + 1} certificate SHA-256 digest: ${value}`).join('\n');
    }
    assert.equal(command, 'aapt');
    return data.badging ?? `package: name='${data.packageId}' versionCode='${data.versionCode}' versionCodeMajor='${data.major ?? 0}'\nsdkVersion:'${data.minSdk}'\n${data.debuggable ? 'application-debuggable\n' : ''}`;
  };
}
function check(t, previous = base, next = base, expectedPackage = pkg, expectedCert = cert, debug = false) {
  const { reference, candidate } = setup(t);
  return checkApkUpdate(reference, candidate, expectedPackage, expectedCert, fakeRun(previous, next, reference), debug);
}

test('matching signer/package and equal or increased version pass', t => {
  assert.equal(check(t).candidate.versionCode, '7');
  assert.equal(check(t, base, { ...base, versionCode: '8' }).candidate.versionCode, '8');
});
test('wrong reference or candidate certificate fails', t => {
  assert.throws(() => check(t, { ...base, cert: other }), /certificate mismatch/);
  assert.throws(() => check(t, base, { ...base, cert: other }), /certificate mismatch/);
});
test('wrong reference or candidate package fails', t => {
  assert.throws(() => check(t, { ...base, packageId: 'org.openwrench.android' }), /Package mismatch/);
  assert.throws(() => check(t, base, { ...base, packageId: 'org.openwrench.android' }), /Package mismatch/);
});
test('unsigned or invalid signatures fail before metadata acceptance', t => {
  assert.throws(() => check(t, base, { ...base, invalidSignature: true }), /bad signature/);
});
test('version downgrade and increased minimum Android fail', t => {
  assert.throws(() => check(t, base, { ...base, versionCode: '6' }), /downgrade/);
  assert.throws(() => check(t, base, { ...base, minSdk: 25 }), /Minimum Android/);
});
test('large version values compare exactly and versionCodeMajor is rejected', t => {
  assert.throws(() => check(t, { ...base, versionCode: '9007199254740993' }, { ...base, versionCode: '9007199254740992' }), /downgrade/);
  assert.throws(() => check(t, base, { ...base, major: 1 }), /unsupported/);
});
test('missing metadata and multiple signers fail closed', t => {
  assert.throws(() => check(t, base, { ...base, badging: '' }), /metadata/);
  assert.throws(() => check(t, base, { ...base, signers: [cert, other] }), /one verified signer/);
});
test('unknown package or malformed trusted fingerprint rejected', t => {
  assert.throws(() => check(t, base, base, 'arbitrary.package'), /not explicitly allowed/);
  assert.throws(() => check(t, base, base, pkg, 'bad'), /64 hex/);
});
test('nonexistent APK rejected before tools', t => {
  const { dir } = setup(t);
  assert.throws(() => inspectApk(join(dir, 'absent.apk'), () => assert.fail()), /ENOENT/);
});
test('history mode requires debuggable APKs before installation', t => {
  assert.throws(() => check(t, base, { ...base, debuggable: false }, pkg, cert, true), /debuggable/);
  assert.throws(() => check(t, { ...base, debuggable: false }, base, pkg, cert, true), /debuggable/);
});

// These executables replace every Android tool. No real device/tool discovery occurs.
const fakeTool = String.raw`#!/usr/bin/env node
const fs = require('node:fs'), path = require('node:path'), cp = require('node:child_process'), crypto = require('node:crypto');
const command = path.basename(process.argv[1]), args = process.argv.slice(2), env = process.env;
const config = JSON.parse(fs.readFileSync(env.FAKE_CONFIG));
const updated = fs.existsSync(env.FAKE_UPDATED);
fs.appendFileSync(env.FAKE_LOG, JSON.stringify({ command, args }) + '\n');
if (command === 'sha256sum') {
  if ((config.hashFailure === 'before' && !updated || config.hashFailure === 'after' && updated) && args[0].includes(config.hashFailureFile ?? 'scan-history')) process.exit(1);
  const bytes = fs.readFileSync(args[0]);
  console.log(crypto.createHash('sha256').update(bytes).digest('hex') + '  ' + args[0]);
  process.exit(0);
}
if (command === 'apksigner' || command === 'aapt') {
  const m = JSON.parse(fs.readFileSync(args.at(-1)));
  if (command === 'apksigner') {
    if (m.invalidSignature) process.exit(1);
    console.log('Signer #1 certificate SHA-256 digest: ' + m.cert);
  } else {
    console.log("package: name='" + m.packageId + "' versionCode='" + m.versionCode + "'\nsdkVersion:'" + m.minSdk + "'");
    if (m.debuggable) console.log('application-debuggable');
  }
  process.exit(0);
}
if (command !== 'adb') process.exit(80);
if (args[0] === 'devices') {
  console.log('List of devices attached\ntablet\tdevice' + (config.multiple ? '\nsecond\tdevice' : ''));
  process.exit(0);
}
if (args[0] !== '-s' || args[1] !== 'tablet') process.exit(81);
const action = args[2];
if (action === 'shell' && args[3] === 'pm') {
  if (!config.absent) console.log('package:/data/app/base.apk');
  if (config.split) console.log('package:/data/app/split.apk');
} else if (action === 'pull') {
  fs.writeFileSync(args[4], JSON.stringify(config.installed));
} else if (action === 'install') {
  if (args.length !== 5 || args[3] !== '-r') process.exit(82);
  fs.writeFileSync(env.FAKE_UPDATED, 'installed');
  if (config.changedHistory) fs.writeFileSync(path.join(env.FAKE_HISTORY, 'files/scan-history-v1.json'), 'changed');
  if (config.changedInvestigationHistory) fs.writeFileSync(path.join(env.FAKE_HISTORY, 'files/investigations-v1/synthetic.json'), 'changed investigation');
} else if (action === 'shell' && args[3].startsWith('run-as ')) {
  const marker = " sh -c '";
  const script = args[3].slice(args[3].indexOf(marker) + marker.length, -1);
  const result = cp.spawnSync('sh', ['-c', script], { cwd: env.FAKE_HISTORY, env, encoding: 'utf8' });
  process.stdout.write(result.stdout || ''); process.stderr.write(result.stderr || '');
  process.exit(result.status ?? 1);
} else process.exit(83);
`;
function runUpgrade(t, changes = {}, input = 'UPDATE\n') {
  const { dir, reference, candidate } = setup(t);
  const bin = join(dir, 'bin'); mkdirSync(bin);
  const tool = join(bin, 'fake.cjs'); writeFileSync(tool, fakeTool, { mode: 0o755 });
  for (const name of ['adb', 'aapt', 'apksigner', 'sha256sum']) symlinkSync(tool, join(bin, name));
  const config = { installed: base, ...changes };
  const configPath = join(dir, 'config.json'); writeFileSync(configPath, JSON.stringify(config));
  writeFileSync(reference, JSON.stringify(changes.reference ?? base));
  writeFileSync(candidate, JSON.stringify(changes.candidate ?? base));
  const history = join(dir, 'history'); mkdirSync(join(history, 'files'), { recursive: true });
  if (!changes.emptyHistory) {
    writeFileSync(join(history, 'files/scan-history-v1.json'), 'original scan');
    writeFileSync(join(history, 'files/diagnostic-cases-v1.json'), 'original cases');
    mkdirSync(join(history, 'files/investigations-v1'));
    writeFileSync(join(history, 'files/investigations-v1/synthetic.json'), 'synthetic investigation with retained audit and evidence');
  }
  const log = join(dir, 'commands.jsonl'); writeFileSync(log, '');
  const result = spawnSync('bash', [resolve('scripts/verify-inplace-apk-upgrade.sh'), candidate, reference, pkg, cert], {
    input, encoding: 'utf8', env: { ...process.env, PATH: `${bin}:${process.env.PATH}`, FAKE_CONFIG: configPath,
      FAKE_HISTORY: history, FAKE_LOG: log, FAKE_UPDATED: join(dir, 'updated') },
  });
  const commands = readFileSync(log, 'utf8').trim().split('\n').filter(Boolean).map(JSON.parse);
  const installs = commands.filter(c => c.command === 'adb' && c.args[2] === 'install');
  return { ...result, commands, installs, candidate };
}

test('unsigned candidate fails before any adb call', t => {
  const r = runUpgrade(t, { candidate: { ...base, invalidSignature: true } });
  assert.notEqual(r.status, 0); assert.equal(r.commands.filter(c => c.command === 'adb').length, 0);
});
test('nondebug candidate fails before any adb call', t => {
  const r = runUpgrade(t, { candidate: { ...base, debuggable: false } });
  assert.notEqual(r.status, 0); assert.equal(r.commands.filter(c => c.command === 'adb').length, 0);
});
test('installed certificate and version checked independently of reference', t => {
  for (const installed of [{ ...base, cert: other }, { ...base, versionCode: '8' }]) {
    const r = runUpgrade(t, { installed }); assert.notEqual(r.status, 0); assert.equal(r.installs.length, 0);
  }
});
test('ambiguous devices and absent/split installations stop', t => {
  for (const config of [{ multiple: true }, { absent: true }, { split: true }]) {
    const r = runUpgrade(t, config); assert.notEqual(r.status, 0); assert.equal(r.installs.length, 0);
  }
});
test('owner cancellation never installs', t => {
  const r = runUpgrade(t, {}, 'no\n'); assert.notEqual(r.status, 0); assert.equal(r.installs.length, 0);
});
test('empty history never installs', t => {
  const r = runUpgrade(t, { emptyHistory: true }); assert.notEqual(r.status, 0); assert.equal(r.installs.length, 0);
});
test('successful synthetic update uses only install -r and preserves hashes', t => {
  const r = runUpgrade(t); assert.equal(r.status, 0, r.stdout + r.stderr);
  assert.equal(r.installs.length, 1); assert.deepEqual(r.installs[0].args, ['-s', 'tablet', 'install', '-r', r.candidate]);
  assert.match(r.stdout, /history bytes match/);
  assert.equal(r.commands.some(c => c.command === 'adb' && c.args.some(a => /uninstall|clear|force-stop|start-activity/.test(a))), false);
});
test('changed history fails after synthetic install', t => {
  const r = runUpgrade(t, { changedHistory: true }); assert.notEqual(r.status, 0); assert.equal(r.installs.length, 1);
});
test('individual hash failure before install cannot be hidden by later files', t => {
  const r = runUpgrade(t, { hashFailure: 'before' }); assert.notEqual(r.status, 0); assert.equal(r.installs.length, 0);
});
test('individual hash failure after install cannot yield a preservation pass', t => {
  const r = runUpgrade(t, { hashFailure: 'after' }); assert.notEqual(r.status, 0); assert.equal(r.installs.length, 1);
  assert.doesNotMatch(r.stdout, /PASS: APK identity/);
});

test('workspace preview update accepts only matching identity and increasing version', t => {
  const packageId = 'org.openwrench.android.workspacepreview';
  const previous = { ...base, packageId };
  const next = { ...previous, versionCode: '8' };
  assert.equal(check(t, previous, next, packageId).candidate.versionCode, '8');
  assert.throws(() => check(t, previous, { ...next, cert: other }, packageId), /certificate mismatch/);
  assert.throws(() => check(t, previous, { ...next, packageId: pkg }, packageId), /Package mismatch/);
  assert.throws(() => check(t, previous, { ...next, versionCode: '6' }, packageId), /downgrade/);
});

test('synthetic update inventories investigation journals before and after replacement', t => {
  const r = runUpgrade(t);
  assert.equal(r.status, 0, r.stdout + r.stderr);
  const hashes = r.commands.filter(c => c.command === 'sha256sum' && c.args[0] === 'files/investigations-v1/synthetic.json');
  assert.equal(hashes.length, 2);
});
test('changed investigation bytes fail preservation even when all other history is unchanged', t => {
  const r = runUpgrade(t, { changedInvestigationHistory: true });
  assert.notEqual(r.status, 0); assert.equal(r.installs.length, 1);
  assert.match(r.stdout + r.stderr, /History bytes changed/);
  assert.doesNotMatch(r.stdout, /PASS: APK identity/);
});
test('investigation hash failures fail closed before or after synthetic replacement', t => {
  for (const phase of ['before', 'after']) {
    const r = runUpgrade(t, { hashFailure: phase, hashFailureFile: 'investigations-v1/' });
    assert.notEqual(r.status, 0); assert.equal(r.installs.length, phase === 'before' ? 0 : 1);
    assert.doesNotMatch(r.stdout, /PASS: APK identity/);
  }
});
