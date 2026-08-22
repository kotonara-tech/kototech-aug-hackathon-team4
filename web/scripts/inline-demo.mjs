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
  const css = await readFile(assetPath(href), 'utf8');
  // 置換は必ず関数で渡すこと。文字列で渡すと中の $& や $1 が置換パターンとして解釈される
  html = html.replace(tag, () => `<style>\n${css}\n</style>`);
  inlined.push(href);
}

// <script type="module" src="/demo.js"></script> → <script>...</script>
// type="module" は file:// から読めないので、IIFE を素の script として埋める
for (const tag of html.match(/<script\b[^>]*\bsrc="[^"]+"[^>]*><\/script>/g) ?? []) {
  const src = tag.match(/src="([^"]+)"/)?.[1];
  if (!src) continue;
  const js = safeForInlineScript(await readFile(assetPath(src), 'utf8'));

  // 元は type="module" で、常に DOM の構築後に走っていた。
  // 素の script は書かれた位置で即座に走るため、head に残すと #root がまだ無い。
  // 取り除いて </body> の直前へ置き直す。
  html = html.replace(tag, '');
  // 置換は必ず関数で渡すこと。React のバンドルに含まれる $& が置換パターンとして解釈される
  html = html.replace('</body>', () => `<script>\n${js}\n</script>\n</body>`);
  inlined.push(src);
}

await writeFile(ENTRY, html, 'utf8');

const kb = (Buffer.byteLength(html, 'utf8') / 1024).toFixed(1);
console.log(`inline-demo: ${inlined.join(', ') || '(なし)'} -> ${ENTRY} (${kb} kB)`);

// 取り込んだはずの参照が本文のどこかに残っていたら失敗とみなす。
// 骨組みだけを見る判定だと、置換で JS の中へ紛れ込んだ <script src> を見逃す。
for (const reference of inlined) {
  if (html.includes(`"${reference}"`)) {
    console.error(`inline-demo: ${reference} への参照が残っています。file:// で開くと壊れます`);
    process.exit(1);
  }
}

// 閉じタグは 1 つだけであるべき。JS 側の </script は safeForInlineScript が潰しているので、
// ここが 2 以上なら埋め込みが途中で HTML を閉じている。
// （開きタグは JS の文字列リテラルにも現れるので数えない）
const closers = (html.match(/<\/script/g) ?? []).length;
if (closers !== inlined.filter((reference) => reference.endsWith('.js')).length) {
  console.error(`inline-demo: 埋め込んだ JS が途中で閉じています（</script が ${closers} 個）`);
  process.exit(1);
}
