import { describe, expect, it } from 'vitest';
import { resolveCapturedAt, type DriveFile } from './photo';

/**
 * 撮影時刻の決定は「ネットワークなしでテストできる、かつバグの温床」の代表。
 * → docs/02-google-drive.md 5 節 / docs/04-frontend.md 6.3
 *
 * 期待値は必ず UTC の絶対時刻で書く。ローカルタイムゾーンに依存させると
 * CI や別マシンで落ちる。
 */

const base: DriveFile = {
  id: 'file-1',
  name: 'CAM001_20260822_063000.jpg',
  mimeType: 'image/jpeg',
  // Drive への到着時刻。RFC 3339 の UTC（末尾 Z）
  createdTime: '2026-08-22T09:35:12.000Z',
};

describe('resolveCapturedAt', () => {
  it('EXIF の撮影時刻があれば、createdTime より EXIF を優先する', () => {
    const result = resolveCapturedAt({
      ...base,
      imageMediaMetadata: { time: '2026:08:22 06:30:00' },
    });

    expect(result).not.toBeNull();
    expect(result!.source).toBe('exif');
  });

  it('EXIF の "yyyy:MM:dd HH:mm:ss" を JST として解釈する', () => {
    const result = resolveCapturedAt({
      ...base,
      imageMediaMetadata: { time: '2026:08:22 06:30:00' },
    });

    // JST 2026-08-22 06:30:00 は UTC では前日 21:30:00
    expect(result!.at.toISOString()).toBe('2026-08-21T21:30:00.000Z');
  });

  it('EXIF がなければ createdTime を使い、フォールバックしたことが分かる', () => {
    const result = resolveCapturedAt(base);

    expect(result!.source).toBe('createdTime');
  });

  it('createdTime は UTC（末尾 Z）として解釈する', () => {
    const result = resolveCapturedAt(base);

    expect(result!.at.toISOString()).toBe('2026-08-22T09:35:12.000Z');
  });

  it('EXIF が壊れていても Invalid Date を返さず、createdTime へフォールバックする', () => {
    const result = resolveCapturedAt({
      ...base,
      imageMediaMetadata: { time: 'not-a-timestamp' },
    });

    expect(Number.isNaN(result!.at.getTime())).toBe(false);
    expect(result!.source).toBe('createdTime');
  });

  it('EXIF も createdTime も無い写真は null を返す（呼び出し側が一覧から外せるようにする）', () => {
    const { createdTime: _omitted, ...withoutCreatedTime } = base;

    expect(resolveCapturedAt(withoutCreatedTime as DriveFile)).toBeNull();
  });
});
