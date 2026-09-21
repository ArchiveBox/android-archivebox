#!/usr/bin/env node
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const repo = 'https://github.com/ArchiveBox/android-archivebox';
const option = (name, fallback) => {
  const index = process.argv.indexOf(name);
  if (index >= 0 && (!process.argv[index + 1] || process.argv[index + 1].startsWith('--'))) throw new Error(`Missing value for ${name}`);
  return index < 0 ? fallback : process.argv[index + 1];
};
const input = path.resolve(root, option('--screenshots-dir', process.env.SCREENSHOTS_DIR || 'docs/screenshots'));
const output = path.resolve(root, option('--output', '_site'));
const canonical = new URL(process.env.SITE_URL || 'https://android.archivebox.io/');
if (!canonical.pathname.endsWith('/')) canonical.pathname += '/';
const base = `/${option('--baseurl', canonical.pathname).replace(/^\/+|\/+$/g, '')}/`.replace('//', '/');
const allowMissing = process.argv.includes('--allow-missing-screenshots') && !process.argv.includes('--require-screenshots') && !process.env.CI;
const required = ['onboarding', 'setup-docker', 'dark-mode', 'tablet', 'connections', 'discovery', 'library', 'search', 'snapshot', 'add', 'tags', 'share', 'share-saved', 'activity', 'settings', 'server-browser', 'home', 'crawls', 'scheduled-crawls', 'archive-results', 'server-tags', 'ai-agent', 'users', 'personas', 'api-keys', 'webhooks', 'processes', 'machines', 'network-interfaces', 'binaries', 'plugins', 'workers', 'logs', 'widget'];
const escape = value => String(value).replace(/[&<>"']/g, character => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[character]));

async function loadCaptures(captureRun) {
  let manifest;
  try { manifest = JSON.parse(await fs.readFile(path.join(input, 'manifest.json'), 'utf8')); }
  catch (error) { if (error.code === 'ENOENT' && (allowMissing || (captureRun?.pending === true && !process.argv.includes('--require-screenshots')))) return null; throw error; }
  if (manifest.schemaVersion !== 1 || !/^[a-f0-9]{40}$/.test(manifest.commit) || !manifest.appVersion || !manifest.device || !Number.isFinite(Date.parse(manifest.generatedAt)) || !Array.isArray(manifest.screenshots)) throw new Error('Screenshot manifest is missing capture provenance');
  const expectedCommit = captureRun?.commit || process.env.GITHUB_SHA;
  if (expectedCommit && manifest.commit !== expectedCommit) throw new Error('Screenshots do not match the current commit');
  if (captureRun?.source === 'checked-in-local') {
    const checkedIn = await fs.readFile(path.join(root, 'docs/screenshots/manifest.json'));
    const restored = await fs.readFile(path.join(input, 'manifest.json'));
    if (manifest.workflowRun || captureRun.runURL || !checkedIn.equals(restored) || createHash('sha256').update(restored).digest('hex') !== captureRun.manifestSHA256) throw new Error('Local screenshots do not match their checked-in capture provenance');
  } else if (captureRun && (captureRun.pending || manifest.workflowRun?.url !== captureRun.runURL)) throw new Error('Screenshots do not match the verified capture run');
  if (process.env.RELEASE_VERSION && manifest.appVersion !== process.env.RELEASE_VERSION) throw new Error('Screenshots do not match the release version');
  if (manifest.workflowRun?.url && !/^https:\/\/github\.com\/ArchiveBox\/android-archivebox\/actions\/runs\/\d+$/.test(manifest.workflowRun.url)) throw new Error('Unexpected capture workflow URL');
  const ids = new Set();
  for (const capture of manifest.screenshots) {
    if (!/^[a-z0-9-]+$/.test(capture.id) || ids.has(capture.id) || capture.file !== `${capture.id}.png` || !capture.title || !capture.description) throw new Error('Invalid or duplicate screenshot entry');
    ids.add(capture.id);
    const png = await fs.readFile(path.join(input, capture.file));
    if (png.length < 24 || png.subarray(0, 8).toString('hex') !== '89504e470d0a1a0a' || png.readUInt32BE(16) !== capture.width || png.readUInt32BE(20) !== capture.height || capture.width < 320 || capture.height < 320 || createHash('sha256').update(png).digest('hex') !== capture.sha256) throw new Error(`Invalid screenshot dimensions or digest: ${capture.file}`);
  }
  // Restored artifacts already passed the complete contract at their original
  // revision. New screens must not block marketing edits using that gallery.
  const requiredForCapture = captureRun && !process.argv.includes('--require-screenshots')
    ? (manifest.requiredScreenshots || ['onboarding', 'home', 'share']) : required;
  if (!Array.isArray(requiredForCapture) || !requiredForCapture.length) throw new Error('Missing capture coverage contract');
  for (const id of requiredForCapture) if (!ids.has(id)) throw new Error(`Missing required screenshot: ${id}`);
  return manifest;
}

async function main() {
  if (output === root || root.startsWith(output + path.sep) || output === input || input.startsWith(output + path.sep) || ['docs', 'app', 'scripts', 'gradle', '.git'].some(directory => output === path.join(root, directory) || output.startsWith(path.join(root, directory) + path.sep))) throw new Error('Choose a separate build output directory');
  const captureRunPath = option('--capture-run', null);
  const captureRun = captureRunPath ? JSON.parse(await fs.readFile(path.resolve(root, captureRunPath), 'utf8')) : null;
  if (captureRun && captureRun.pending !== true && (!/^[a-f0-9]{40}$/.test(captureRun.commit) || (captureRun.source === 'checked-in-local' ? !/^[a-f0-9]{64}$/.test(captureRun.manifestSHA256) || Boolean(captureRun.runURL) : !/^https:\/\/github\.com\/ArchiveBox\/android-archivebox\/actions\/runs\/[1-9]\d*$/.test(captureRun.runURL)))) throw new Error('Invalid restored capture run metadata');
  const manifest = await loadCaptures(captureRun);
  const displayOrder = ['home', 'share', 'share-saved', 'search', 'library', 'snapshot', 'add', 'tags', 'connections', 'discovery', 'onboarding', 'setup-docker', 'widget', 'dark-mode', 'tablet', 'activity', 'server-browser', 'crawls', 'scheduled-crawls', 'archive-results', 'server-tags', 'ai-agent', 'users', 'personas', 'api-keys', 'webhooks', 'processes', 'machines', 'network-interfaces', 'binaries', 'plugins', 'workers', 'logs', 'settings'];
  const captures = [...(manifest?.screenshots || [])].sort((left, right) => {
    const rank = id => displayOrder.includes(id) ? displayOrder.indexOf(id) : displayOrder.length;
    return rank(left.id) - rank(right.id);
  });
  const revision = execFileSync('git', ['rev-parse', 'HEAD'], {cwd: root, encoding: 'utf8'}).trim();
  const [header, footer, landing] = await Promise.all(['header.html', 'footer.html', 'index.html'].map(file => fs.readFile(path.join(root, 'docs', file), 'utf8')));
  const description = 'Save the web you want to keep. Share links with tags, search your archive, and connect to your own ArchiveBox server from Android.';
  const page = (title, content, route = '') => `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="theme-color" content="#9b2854"><title>${escape(title)}</title><meta name="description" content="${description}"><link rel="canonical" href="${canonical}${route}">${route ? '' : `<link rel="alternate" hreflang="en" href="${canonical}"><link rel="alternate" hreflang="es" href="${canonical}es/"><link rel="alternate" hreflang="fr" href="${canonical}fr/"><link rel="alternate" hreflang="zh" href="${canonical}zh/"><link rel="alternate" hreflang="ru" href="${canonical}ru/"><link rel="alternate" hreflang="ar" href="${canonical}ar/"><link rel="alternate" hreflang="x-default" href="${canonical}">`}<meta name="robots" content="index,follow,max-image-preview:large"><meta property="og:type" content="website"><meta property="og:site_name" content="ArchiveBox"><meta property="og:locale" content="en_US"><meta property="og:title" content="${escape(title)}"><meta property="og:description" content="${description}"><meta property="og:url" content="${canonical}${route}"><meta property="og:image" content="${canonical}assets/social-card.png"><meta property="og:image:type" content="image/png"><meta property="og:image:width" content="1200"><meta property="og:image:height" content="630"><meta property="og:image:alt" content="ArchiveBox — a home for the web you want to keep"><meta name="twitter:card" content="summary_large_image"><meta name="twitter:title" content="${escape(title)}"><meta name="twitter:description" content="${description}"><meta name="twitter:image" content="${canonical}assets/social-card.png"><link rel="icon" href="${base}assets/favicon.ico"><link rel="apple-touch-icon" href="${base}assets/apple-touch-icon.png"><link rel="stylesheet" href="${base}style.css?v=${revision}"></head><body><a class="skip-link" href="#content">Skip to content</a>${header}<main id="content">${content}</main>${footer}${route ? '' : `<script src="${base}language.js" defer></script>`}</body></html>`.replaceAll('__BASE__', base);
  const screenshotURL = capture => `${base}screenshots/${escape(capture.file)}?v=${capture.sha256.slice(0, 12)}`;
  const phone = id => {
    const capture = captures.find(item => item.id === id);
    if (!capture) return `<div class="capture-placeholder"><img src="${base}assets/icon.png" width="64" height="64" alt=""><strong>Your archive.<br>Made for Android.</strong><p>Real app screenshots will appear here after the first successful release capture.</p></div>`;
    return `<figure class="phone-capture"><a href="${base}screenshots/#${capture.id}"><img src="${screenshotURL(capture)}" width="${capture.width}" height="${capture.height}" alt="${escape(capture.title)}"></a><figcaption>${escape(capture.title)} · Android</figcaption></figure>`;
  };
  let gallery = '<section class="gallery-header"><p class="eyebrow">YOUR ARCHIVE, AROUND EVERY CORNER.</p><h1>Take a look around.</h1><p class="lead">From your first connection to your next saved page. Explore sharing, search, your home-screen widget, and the tools that keep your collection organized.</p></section>';
  if (manifest) {
    gallery += `<p class="provenance">App ${escape(manifest.appVersion)} · ${escape(manifest.device)} · <a href="${repo}/commit/${manifest.commit}">${manifest.commit.slice(0, 12)}</a> · <time datetime="${escape(manifest.generatedAt)}">${escape(manifest.generatedAt)}</time>${manifest.workflowRun?.url ? ` · <a href="${manifest.workflowRun.url}">Capture run ↗</a>` : ' · Local capture'} · <a href="manifest.json">Capture manifest</a>${manifest.backend ? `<br>Backend: ${escape(manifest.backend)}` : ''}</p>`;
    gallery += `<ul class="capture-index">${captures.map(capture => `<li><a href="#${capture.id}">${escape(capture.title)}</a></li>`).join('')}</ul>`;
    gallery += `<div class="gallery-grid">${captures.map(capture => `<article class="capture" id="${capture.id}"><h2>${escape(capture.title)}</h2><p>${escape(capture.description)}</p><figure><a href="${screenshotURL(capture)}"><img src="${screenshotURL(capture)}" width="${capture.width}" height="${capture.height}" alt="${escape(capture.title)} — ${escape(capture.description)}" loading="lazy"></a><figcaption>${capture.width} × ${capture.height} · <a href="${screenshotURL(capture)}">View full image ↗</a></figcaption></figure></article>`).join('')}</div>`;
  } else gallery += `<p class="empty">The screenshot gallery will be available with the first release. <a href="${repo}/actions">View build progress ↗</a></p>`;
  gallery += '<section class="coverage"><h2>A gallery that follows the app</h2><p>The gallery updates with each release, covering setup, saving, search, your home-screen widget, and every collection and administration page. See the capture manifest above for the app version and device shown.</p></section>';
  await fs.rm(output, {recursive: true, force: true});
  await fs.mkdir(path.join(output, 'screenshots'), {recursive: true});
  for (const file of ['assets', 'style.css']) await fs.cp(path.join(root, 'docs', file), path.join(output, file), {recursive: true});
  try { await fs.copyFile(path.join(root, 'docs', 'language.js'), path.join(output, 'language.js')); }
  catch (error) { if (error.code !== 'ENOENT') throw error; }
  if (manifest) {
    await fs.copyFile(path.join(input, 'manifest.json'), path.join(output, 'screenshots', 'manifest.json'));
    for (const capture of captures) await fs.copyFile(path.join(input, capture.file), path.join(output, 'screenshots', capture.file));
  }
  await fs.writeFile(path.join(output, 'index.html'), page('ArchiveBox for Android · Your web, preserved.', landing.replace('__HERO_SCREENSHOT__', phone('home')).replace('__SHARE_SCREENSHOT__', phone('share'))));
  await fs.writeFile(path.join(output, 'screenshots', 'index.html'), page('Screenshots · ArchiveBox for Android', gallery, 'screenshots/'));
  await fs.writeFile(path.join(output, '.nojekyll'), '');
  await fs.writeFile(path.join(output, 'CNAME'), canonical.hostname + '\n');
  await fs.writeFile(path.join(output, 'sitemap.xml'), `<?xml version="1.0" encoding="UTF-8"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9"><url><loc>${escape(canonical)}</loc></url><url><loc>${escape(canonical)}es/</loc></url><url><loc>${escape(canonical)}fr/</loc></url><url><loc>${escape(canonical)}zh/</loc></url><url><loc>${escape(canonical)}ru/</loc></url><url><loc>${escape(canonical)}ar/</loc></url><url><loc>${escape(canonical)}screenshots/</loc></url></urlset>\n`);
  await fs.writeFile(path.join(output, 'build.json'), JSON.stringify({revision, captureRevision: manifest?.commit || null, generatedAt: new Date().toISOString(), screenshots: captures.length, appVersion: manifest?.appVersion || null}, null, 2) + '\n');
  execFileSync('uv', ['run', '--no-project', 'python', path.join(root, '.github/pages/site.py'), 'render', output, '--baseurl', base], { cwd: root, stdio: 'inherit' });
  for (const locale of ['es', 'fr', 'zh', 'ru', 'ar']) {
    try {
      await fs.mkdir(path.join(output, locale), {recursive: true});
      await fs.copyFile(path.join(root, 'docs', locale, 'index.html'), path.join(output, locale, 'index.html'));
    } catch (error) { if (error.code !== 'ENOENT') throw error; }
  }
  console.log(`Built ${output}: ${captures.length} real screenshots${manifest ? '' : ' (gallery pending)'}`);
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });
