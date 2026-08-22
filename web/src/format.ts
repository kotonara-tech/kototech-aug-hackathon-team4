/** 表示用の書式。撮影時刻は JST で見せる（→ docs/04-frontend.md 6.3）。 */

const JST = 'Asia/Tokyo';

/** JST に直した年月日時分秒。デモの合成データもここを通す。 */
export function jstParts(date: Date): Record<string, string> {
  const formatter = new Intl.DateTimeFormat('ja-JP', {
    timeZone: JST,
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit',
    hourCycle: 'h23',
  });

  return Object.fromEntries(
    formatter.formatToParts(date).map((part) => [part.type, part.value]),
  );
}

/** "2026-08-22 06:30:00" */
export function formatCapturedAt(date: Date): string {
  const p = jstParts(date);
  return `${p.year}-${p.month}-${p.day} ${p.hour}:${p.minute}:${p.second}`;
}

/** "06:35:12" */
export function formatClock(date: Date): string {
  const p = jstParts(date);
  return `${p.hour}:${p.minute}:${p.second}`;
}

/** 10 → "10秒", 300 → "5分" */
export function formatInterval(seconds: number): string {
  return seconds < 60 ? `${seconds}秒` : `${seconds / 60}分`;
}
