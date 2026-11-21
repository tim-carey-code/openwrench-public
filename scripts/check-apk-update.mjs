#!/usr/bin/env node
import { statSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';

const updatePackages = new Set([
  'org.openwrench.android', 'org.openwrench.android.usbpreview',
  'org.openwrench.android.livehomepreview', 'org.openwrench.android.workspacepreview',
]);

function execute(command, args) {
  const result = spawnSync(command, args, { encoding: 'utf8', maxBuffer: 4 * 1024 * 1024 });
  if (result.error || result.status !== 0) throw new Error(`${command} failed; verify the APK and SDK tooling.`);
  return result.stdout;
}

export function inspectApk(file, run = execute) {
  if (!statSync(file).isFile()) throw new Error('APK must be a regular file.');
  const signature = run('apksigner', ['verify', '--verbose', '--print-certs', file]);
  const certificates = [...signature.matchAll(/^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})\s*$/gm)]
    .map(match => match[1].toLowerCase());
  if (certificates.length !== 1) throw new Error('Expected one verified signer; rotation/multiple signers need separate review.');
  const badging = run('aapt', ['dump', 'badging', file]);
  const line = badging.split('\n').find(value => value.startsWith('package: ')) ?? '';
  const packageId = /\bname='([^']+)'/.exec(line)?.[1];
  const versionCode = /\bversionCode='([0-9]+)'/.exec(line)?.[1];
  const major = /\bversionCodeMajor='([0-9]+)'/.exec(line)?.[1] ?? '0';
  const minSdk = /^sdkVersion:'([0-9]+)'/m.exec(badging)?.[1];
  if (!packageId || !versionCode || !minSdk || BigInt(major) !== 0n) {
    throw new Error('Missing or unsupported APK package/version/SDK metadata.');
  }
  return { packageId, versionCode, minSdk: Number(minSdk), certificateSha256: certificates[0],
    debuggable: /^application-debuggable\s*$/m.test(badging) };
}

export function checkApkUpdate(reference, candidate, packageId, fingerprint, run = execute, requireDebuggable = false) {
  if (!updatePackages.has(packageId)) throw new Error('Package is not explicitly allowed by this verifier.');
  if (!/^[a-fA-F0-9]{64}$/.test(fingerprint)) throw new Error('Provide the trusted SHA-256 certificate fingerprint (64 hex digits).');
  const previous = inspectApk(reference, run);
  const next = inspectApk(candidate, run);
  for (const apk of [previous, next]) {
    if (apk.packageId !== packageId) throw new Error('Package mismatch; separate packages have separate history.');
    if (apk.certificateSha256 !== fingerprint.toLowerCase()) throw new Error('Signing certificate mismatch. Keep the installed app and recover its original key.');
    if (requireDebuggable && !apk.debuggable) throw new Error('The run-as history check requires debuggable reference and candidate APKs.');
  }
  if (BigInt(next.versionCode) < BigInt(previous.versionCode)) throw new Error('Refusing a versionCode downgrade.');
  if (next.minSdk > previous.minSdk) throw new Error('Minimum Android version increased; review device compatibility separately.');
  return { reference: previous, candidate: next, result: 'APK identity preflight passed; installed-device identity and history preservation remain separate checks.' };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const args = process.argv.slice(2);
    const debug = args.length === 5 && args[4] === '--require-debuggable';
    if (args.length !== 4 && !debug) throw new Error('Usage: node scripts/check-apk-update.mjs <reference.apk> <candidate.apk> <package-id> <trusted-cert-sha256> [--require-debuggable]');
    console.log(JSON.stringify(checkApkUpdate(...args.slice(0, 4), undefined, debug), null, 2));
  } catch (error) {
    console.error(`STOP: ${error.message}`);
    process.exitCode = 1;
  }
}
