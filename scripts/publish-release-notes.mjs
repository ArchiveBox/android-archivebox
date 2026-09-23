import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';

const repo = process.env.GITHUB_REPOSITORY;
const tag = process.env.RELEASE_TAG;
const sha = process.env.GITHUB_SHA;
if (repo !== 'ArchiveBox/android-archivebox' || !/^v\d+\.\d+\.\d+$/.test(tag || '') || !/^[0-9a-f]{40}$/.test(sha || '')) throw new Error('Invalid release context');
const gh = (...args) => execFileSync('gh', args, { encoding: 'utf8' }).trim();
const releases = JSON.parse(gh('api', `repos/${repo}/releases?per_page=100`));
const previous = releases.filter(r => !r.draft && !r.prerelease && /^v\d+\.\d+\.\d+$/.test(r.tag_name) && r.tag_name !== tag)
  .sort((a, b) => b.created_at.localeCompare(a.created_at))[0]?.tag_name;
const generatorArgs = ['api', `repos/${repo}/releases/generate-notes`, '-f', `tag_name=${tag}`, '-f', `target_commitish=${sha}`];
if (previous) generatorArgs.push('-f', `previous_tag_name=${previous}`);
const generated = JSON.parse(gh(...generatorArgs)).body;
const block = generated.match(/^## (?:New )?Contributors\n[\s\S]*?(?=^## |\*\*Full Changelog|$(?![\s\S]))/m)?.[0] || '';
const contributorLines = block.split('\n').filter(line => !/@pirate\b|Nick Sweeting/i.test(line));
const contributors = contributorLines.slice(1).some(line => line.trim()) ? contributorLines.join('\n').trim() : '';
const compare = previous ? `[\`${previous}...${tag}\`](https://github.com/${repo}/compare/${previous}...${tag})` : `[\`${tag}\`](https://github.com/${repo}/commits/${tag})`;
const range = previous ? `${previous}..${sha}` : sha;
const commits = execFileSync('git', ['log', '--reverse', '--format=%H%x09%s', range], { encoding: 'utf8' }).trim().split('\n').filter(Boolean)
  .map(line => { const [id, subject] = line.split('\t'); return `- [${id.slice(0, 7)}](https://github.com/${repo}/commit/${id}) ${subject}`; }).join('\n');
let notes = `## Install\n\n- 📱 **Android 9+:** Download \`ArchiveBox-Android.apk\` from the Assets section below and install it. If Android asks, allow your browser to install apps. Connect the app to your ArchiveBox server with its URL and API key.\n\nIf you run into a problem, please [🐛 report it here](https://github.com/${repo}/issues).\n\n[💻 Screenshots](https://android.archivebox.io/screenshots/) · [📖 Documentation](https://docs.archivebox.io) · [💬 \`@ArchiveBoxApp\`](https://x.com/ArchiveBoxApp)\n\n**Full Changelog:** ${compare}\n\n## All changes\n\n${commits}\n\nSource: [\`${sha.slice(0, 7)}\`](https://github.com/${repo}/commit/${sha})${contributors ? `\n\n${contributors}` : ''}\n`;
notes = notes.replace(`If you run into a problem, please [🐛 report it here](https://github.com/${repo}/issues).\n\n`, '');
notes = notes.replace(`[💻 Screenshots](https://android.archivebox.io/screenshots/) · [📖 Documentation](https://docs.archivebox.io) · [💬 \`@ArchiveBoxApp\`](https://x.com/ArchiveBoxApp)`, `[💻 Screenshots](https://android.archivebox.io/screenshots/) · [📖 Documentation](https://docs.archivebox.io/) · [💬 \`@ArchiveBoxApp\`](https://x.com/ArchiveBoxApp) · [🐞 Report a bug](https://github.com/${repo}/issues?q=sort%3Aupdated-desc+is%3Aissue+state%3Aopen)`);
mkdirSync('artifacts', { recursive: true });
writeFileSync('artifacts/release-notes.md', notes);
try { gh('release', 'view', tag, '--repo', repo); }
catch { gh('release', 'create', tag, '--repo', repo, '--target', sha, '--title', tag, '--notes-file', 'artifacts/release-notes.md', '--draft'); }
