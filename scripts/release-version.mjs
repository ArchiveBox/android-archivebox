#!/usr/bin/env node
import {execFileSync} from 'node:child_process';
import {readFileSync, appendFileSync} from 'node:fs';

const git = (...args) => execFileSync('git', args, {encoding: 'utf8'}).trim();
const properties = Object.fromEntries(readFileSync('version.properties', 'utf8').split('\n')
  .filter(line => line.includes('=') && !line.startsWith('#')).map(line => line.split('=').map(part => part.trim())));
const parse = value => {
  const match = /^v?(\d+)\.(\d+)\.(\d+)$/.exec(value);
  if (!match) throw new Error(`Invalid release version: ${value}`);
  const parts = match.slice(1).map(Number);
  if (parts[1] > 999 || parts[2] > 999) throw new Error('Minor and patch versions must be below 1000');
  return parts;
};
const compare = (a, b) => a[0] - b[0] || a[1] - b[1] || a[2] - b[2];
const tags = git('tag', '--list', 'v*').split('\n').filter(tag => /^v\d+\.\d+\.\d+$/.test(tag));
const head = git('rev-parse', 'HEAD');
const previous = tags.filter(tag => git('rev-list', '-n', '1', tag) === head).sort((a,b) => compare(parse(b),parse(a)))[0];
const isMarketingFile = file => file === 'README.md' || file === '.github/workflows/pages.yml' || file.startsWith('docs/') || file.startsWith('.github/pages/');
let version;
if (process.env.GITHUB_EVENT_NAME === 'workflow_dispatch') {
  const released = git('tag', '--merged', 'HEAD', '--list', 'v*').split('\n')
    .filter(tag => /^v\d+\.\d+\.\d+$/.test(tag)).sort((a,b) => compare(parse(b),parse(a)))[0];
  if (!released) throw new Error('Server compatibility captures require an existing Android release');
  const changes = git('diff', '--name-only', released, 'HEAD').split('\n').filter(Boolean);
  if (changes.some(file => !isMarketingFile(file))) throw new Error('Unreleased Android app changes require the normal push release before server compatibility capture');
  version = parse(released);
}
else if (previous) version = parse(previous);
else {
  const base = parse(properties.versionName);
  const latest = tags.map(parse).sort(compare).at(-1);
  if (!latest || compare(base, latest) > 0) version = base;
  else {
    version = [...latest];
    version[2]++;
    if (version[2] > 999) { version[2] = 0; version[1]++; }
    if (version[1] > 999) { version[1] = 0; version[0]++; }
  }
}
const name = version.join('.');
const code = version[0] * 1_000_000 + version[1] * 1_000 + version[2];
if (code < 1 || code > 2_100_000_000) throw new Error('Version code exceeds Android publishing limits');
const values = {version: name, code: String(code), tag: `v${name}`, sha: head};
for (const [key, value] of Object.entries(values)) {
  console.log(`${key}=${value}`);
  if (process.env.GITHUB_OUTPUT) appendFileSync(process.env.GITHUB_OUTPUT, `${key}=${value}\n`);
}
