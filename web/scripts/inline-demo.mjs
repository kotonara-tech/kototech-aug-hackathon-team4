import { readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';

/**
 * dist-demo/demo.html から外部参照を消して、1 枚で完結する HTML にする。
 *
 * これをやらないと file:// で開いたときに JS も CSS も読み込めない。
 * 「ダブルクリックすれば動く」を維持するための後処理。
 */

const OUT_DIR = 'dist-demo';
const ENTRY = join(OUT_DIR, 'demo.html');

function assetPath(reference) {
  return join(OUT_DIR, reference.replace(/^\.?\//, ''));
}

/** 埋め込む JS の中に </script> があると HTML が途中で閉じてしまう */
function safeForInlineScript(js) {
  return js.replace(/<\/script/gi, '<\\/script');
}

let html = await readFile(ENTRY, 'utf8');
const inlined = [];

// <link rel="stylesheet" href="/demo.css"> → <style>...</style>
for (const tag of html.match(/<link\b[^>]*rel="stylesheet"[^>]*>/g) ?? []) {
  const href = tag.match(/href="([^"]+)"/)?.[1];
  if (!href) continue;
  html = html.replace(tag, `<style>\n${await readFile(assetPath(href), 'utf8')}\n</style>`);
  inlined.push(href);
}

// <script type="module" src="/demo.js"></script> → <script>...</script>
// type="module" は file:// から読めないので、IIFE を素の script として埋める
for (const tag of html.match(/<script\b[^>]*\bsrc="[^"]+"[^>]*><\/script>/g) ?? []) {
  const src = tag.match(/src="([^"]+)"/)?.[1];
  if (!src) continue;
  const js = safeForInlineScript(await readFile(assetPath(src), 'utf8'));
  html = html.replace(tag, `<script>\n${js}\n</script>`);
  inlined.push(src);
}

await writeFile(ENTRY, html, 'utf8');

const kb = (Buffer.byteLength(html, 'utf8') / 1024).toFixed(1);
console.log(`inline-demo: ${inlined.join(', ') || '(なし)'} -> ${ENTRY} (${kb} kB)`);

// 中身を抜いた骨組みだけで判定する。埋め込んだ JS 本文に反応しないようにするため
const skeleton = html
  .replace(/<script\b[^>]*>[\s\S]*?<\/script>/g, '<script></script>')
  .replace(/<style\b[^>]*>[\s\S]*?<\/style>/g, '<style></style>');

if (/<script\b[^>]*\bsrc=/.test(skeleton) || /<link\b[^>]*rel="stylesheet"/.test(skeleton)) {
  console.error('inline-demo: 外部参照が残っています。file:// で開くと壊れます');
  process.exit(1);
}
