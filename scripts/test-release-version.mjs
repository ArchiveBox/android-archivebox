#!/usr/bin/env node
// Exercise actual git history, including reruns and patch rollover.
import {execFileSync} from 'node:child_process';
import {mkdtempSync, writeFileSync, rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join, resolve} from 'node:path';
import assert from 'node:assert/strict';
const script = resolve('scripts/release-version.mjs');
const directory = mkdtempSync(join(tmpdir(), 'archivebox-version-'));
const git = (...args) => execFileSync('git', args, {cwd: directory, stdio: 'pipe'});
const run = (event = 'push') => Object.fromEntries(execFileSync(process.execPath, [script], {
  cwd: directory, encoding: 'utf8', env: {...process.env, GITHUB_EVENT_NAME: event, GITHUB_OUTPUT: ''},
}).trim().split('\n').map(line => line.split('=')));
try {
  git('init', '-b', 'main');
  git('config', 'user.name', 'ArchiveBox version test');
  git('config', 'user.email', 'version-test@example.invalid');
  writeFileSync(join(directory, 'version.properties'), 'versionName=0.1.0\nversionCode=1000\n');
  git('add', '.'); git('commit', '-m', 'Initial version');
  assert.equal(run().version, '0.1.0'); assert.equal(run().code, '1000');
  git('tag', 'v0.1.0');
  assert.equal(run().version, '0.1.0', 'Rerunning the tagged commit must not bump');
  writeFileSync(join(directory, 'README.md'), 'Marketing copy\n');
  git('add', '.'); git('commit', '-m', 'Update marketing copy');
  assert.equal(run('workflow_dispatch').version, '0.1.0', 'Server capture reuses the release after marketing-only commits');
  git('commit', '--allow-empty', '-m', 'App change');
  assert.equal(run().version, '0.1.1'); assert.equal(run().code, '1001');
  writeFileSync(join(directory, 'version.properties'), 'versionName=0.1.0\nversionCode=1000\n# runtime change\n');
  git('add', '.'); git('commit', '-m', 'Change runtime input');
  assert.throws(() => run('workflow_dispatch'), /Unreleased Android app changes/);
  git('tag', 'v0.1.999');
  git('commit', '--allow-empty', '-m', 'Next app change');
  assert.equal(run().version, '0.2.0'); assert.equal(run().code, '2000');
  writeFileSync(join(directory, 'version.properties'), 'versionName=1.0.0\nversionCode=1000000\n');
  assert.equal(run().version, '1.0.0'); assert.equal(run().code, '1000000');
  console.log('Release versions verified with real git tags and commits.');
} finally {
  rmSync(directory, {recursive: true, force: true});
}
