import {
  DriveAuthError,
  DriveRateLimitError,
  PhotoNotFoundError,
} from '../drive/driveClient';
import type { DriveGateway } from '../drive/gateway';
import type { Photo } from '../drive/photo';
import { jstParts } from '../format';
import { renderSceneBlob } from './scene';

/**
 * デモ用の Drive。本番の DriveGateway と同じ口を持つ。
 * これを App に差し込むだけで、画面のコードは 1 行も変えずにデモになる。
 */

/** Q3 暫定と同じ 5 分間隔 */
export const DEMO_CAPTURE_INTERVAL_MIN = 5;
/** 02-google-drive.md 4.2 の固定値（1 端末契約） */
export const DEMO_CAMERA_ID = 'CAM001';
/** JST 2026-08-22 06:00:00 */
export const DEMO_SERIES_START = new Date('2026-08-21T21:00:00.000Z');
export const DEMO_INITIAL_COUNT = 36;

/** その場で起こせる異常系。docs/04-frontend.md 8 節の表に対応している。 */
export type DemoFault = 'none' | 'empty' | 'auth' | 'rateLimit' | 'missingPhoto';

export interface FakeDriveOptions {
  render?: (seq: number, at: Date) => Promise<Blob>;
  getFault?: () => DemoFault;
  initialCount?: number;
}

/** Native と同じ命名規約。人間がデバッグで読む前提なので規約を守る。 */
export function demoFileName(at: Date): string {
  const p = jstParts(at);
  return `${DEMO_CAMERA_ID}_${p.year}${p.month}${p.day}_${p.hour}${p.minute}${p.second}.jpg`;
}

export function demoCapturedAt(seq: number): Date {
  return new Date(DEMO_SERIES_START.getTime() + seq * DEMO_CAPTURE_INTERVAL_MIN * 60_000);
}

export function demoPhoto(seq: number): Photo {
  const capturedAt = demoCapturedAt(seq);
  return {
    id: `demo-${seq}`,
    name: demoFileName(capturedAt),
    mimeType: 'image/jpeg',
    capturedAt,
    // 何枚かは EXIF を落として、フォールバック表示を見せられるようにする（Q6）
    capturedAtSource: seq % 11 === 4 ? 'createdTime' : 'exif',
  };
}

function seqOf(fileId: string): number {
  return Number(fileId.replace(/^demo-/, ''));
}

export function createFakeDrive(options: FakeDriveOptions = {}): DriveGateway {
  const {
    render = renderSceneBlob,
    getFault = () => 'none' as DemoFault,
    initialCount = DEMO_INITIAL_COUNT,
  } = options;

  // 新しい順に持つ。本番の orderBy=createdTime desc と同じ並び
  let photos: Photo[] = Array.from({ length: initialCount }, (_, seq) => demoPhoto(seq)).reverse();
  let nextSeq = initialCount;
  let calls = 0;

  return {
    async listPhotos() {
      const fault = getFault();
      if (fault === 'auth') throw new DriveAuthError();
      if (fault === 'rateLimit') throw new DriveRateLimitError();
      if (fault === 'empty') return [];

      // 2 回目以降は 1 枚増やす。ポーリングで画面が動くことを見せるため
      if (calls > 0) {
        photos = [demoPhoto(nextSeq), ...photos];
        nextSeq += 1;
      }
      calls += 1;

      return [...photos];
    },

    async fetchPhotoBlob(fileId) {
      // 個別ファイルの 404。新しく届いた 1 枚が取れない状況を作り、
      // それでも画面全体が落ちないことを見せる
      if (getFault() === 'missingPhoto' && fileId === photos[0]?.id) {
        throw new PhotoNotFoundError(fileId);
      }

      const seq = seqOf(fileId);
      return render(seq, demoCapturedAt(seq));
    },
  };
}
