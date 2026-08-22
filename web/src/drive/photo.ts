/**
 * Drive のファイルメタデータから「撮影時刻」を決めるところ。
 *
 * 副作用がないので、ネットワークなしでテストできる。
 * そして 0 枚問題の次に多いバグがここに出る。
 * → docs/02-google-drive.md 5 節 / docs/04-frontend.md 6.3
 */

/** files.list が fields 指定で返してくるぶんだけ。 */
export interface DriveFile {
  id: string;
  name: string;
  mimeType: string;
  size?: string;
  /** RFC 3339 の UTC（末尾 Z）。Drive への到着時刻であって撮影時刻ではない */
  createdTime?: string;
  imageMediaMetadata?: {
    /** EXIF 生の形式 "yyyy:MM:dd HH:mm:ss"。タイムゾーンを持たない */
    time?: string;
  };
}

export type CapturedAtSource = 'exif' | 'createdTime';

export interface CapturedAt {
  at: Date;
  source: CapturedAtSource;
}

/** 画面が扱う写真。Drive の生データではなく、時刻を解決したあとの形。 */
export interface Photo {
  id: string;
  name: string;
  mimeType: string;
  capturedAt: Date;
  capturedAtSource: CapturedAtSource;
  /** fetch → Blob → createObjectURL したもの。取得前は undefined */
  objectUrl?: string;
}

const EXIF_PATTERN = /^(\d{4}):(\d{2}):(\d{2})[ T](\d{2}):(\d{2}):(\d{2})$/;

/**
 * 圃場の端末は JST で動いている前提。
 * 海外運用の話が出たら、ここが最初に壊れる。
 */
const JST_OFFSET_MS = 9 * 60 * 60 * 1000;

/**
 * EXIF の "yyyy:MM:dd HH:mm:ss" を JST として解釈する。
 * new Date("2026:08:22 06:30:00") は Invalid Date になるので、自前で分解する。
 */
export function parseExifAsJst(raw: string): Date | null {
  const matched = EXIF_PATTERN.exec(raw.trim());
  if (!matched) return null;

  const [, year, month, day, hour, minute, second] = matched;
  const asIfUtc = Date.UTC(
    Number(year), Number(month) - 1, Number(day),
    Number(hour), Number(minute), Number(second),
  );
  const date = new Date(asIfUtc - JST_OFFSET_MS);
  return Number.isNaN(date.getTime()) ? null : date;
}

/** createdTime は RFC 3339 の UTC。EXIF と同じパーサに通さないこと。 */
function parseCreatedTime(raw: string | undefined): Date | null {
  if (!raw) return null;
  const date = new Date(raw);
  return Number.isNaN(date.getTime()) ? null : date;
}

/**
 * EXIF を優先し、無ければ createdTime へ落ちる。
 * どちらも読めなければ null。呼び出し側はその 1 枚だけ一覧から外すこと。
 * 画面全体を落としてはいけない。
 */
export function resolveCapturedAt(file: DriveFile): CapturedAt | null {
  const exif = file.imageMediaMetadata?.time;
  if (exif) {
    const at = parseExifAsJst(exif);
    if (at) return { at, source: 'exif' };
  }

  const at = parseCreatedTime(file.createdTime);
  return at ? { at, source: 'createdTime' } : null;
}

/** 時刻を解決できなかった写真は null を返す（＝一覧に出さない）。 */
export function toPhoto(file: DriveFile): Photo | null {
  const resolved = resolveCapturedAt(file);
  if (!resolved) return null;

  return {
    id: file.id,
    name: file.name,
    mimeType: file.mimeType,
    capturedAt: resolved.at,
    capturedAtSource: resolved.source,
  };
}
