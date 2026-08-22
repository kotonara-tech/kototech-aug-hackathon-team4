import { describe, expect, it, vi } from 'vitest';
import {
  createFakeDrive,
  demoCapturedAt,
  demoFileName,
  demoPhoto,
  DEMO_INITIAL_COUNT,
  type DemoFault,
} from './fakeDrive';
import {
  DriveAuthError,
  DriveRateLimitError,
  PhotoNotFoundError,
} from '../drive/driveClient';

/**
 * デモ用の Drive。本番の DriveGateway と同じ口を持ち、
 * 合成データと、その場で起こせる異常系を返す。
 *
 * 期待値は UTC の絶対時刻で書く。開発機のタイムゾーンに依存させない。
 */

const stubRender = () => vi.fn(async () => new Blob(['jpeg']));

describe('demoFileName', () => {
  it('Native と同じ命名規約に従う（CAM001_yyyyMMdd_HHmmss.jpg）', () => {
    // JST 2026-08-22 06:30:00
    expect(demoFileName(new Date('2026-08-21T21:30:00.000Z')))
      .toBe('CAM001_20260822_063000.jpg');
  });
});

describe('demoCapturedAt', () => {
  it('連番 0 はシリーズの開始時刻（JST 06:00）になる', () => {
    expect(demoCapturedAt(0).toISOString()).toBe('2026-08-21T21:00:00.000Z');
  });

  it('連番が 1 進むと撮影間隔ぶん（5 分）進む', () => {
    const delta = demoCapturedAt(1).getTime() - demoCapturedAt(0).getTime();
    expect(delta).toBe(5 * 60 * 1000);
  });
});

describe('demoPhoto', () => {
  it('撮影時刻とファイル名が対応している', () => {
    const photo = demoPhoto(6); // 06:00 + 30 分
    expect(photo.name).toBe('CAM001_20260822_063000.jpg');
    expect(photo.capturedAt.toISOString()).toBe('2026-08-21T21:30:00.000Z');
  });

  it('何枚かは EXIF なし扱いにする（フォールバック表示を見せられるようにする）', () => {
    const sources = Array.from({ length: 22 }, (_, seq) => demoPhoto(seq).capturedAtSource);
    expect(sources).toContain('exif');
    expect(sources).toContain('createdTime');
  });
});

describe('createFakeDrive', () => {
  it('初回の取得で 36 枚を新しい順に返す', async () => {
    const drive = createFakeDrive({ render: stubRender() });

    const photos = await drive.listPhotos('demo');

    expect(photos).toHaveLength(DEMO_INITIAL_COUNT);
    expect(photos[0].capturedAt.getTime()).toBeGreaterThan(photos[1].capturedAt.getTime());
  });

  it('取得のたびに 1 枚増える（ポーリングで画面が動くことを見せるため）', async () => {
    const drive = createFakeDrive({ render: stubRender() });

    const first = await drive.listPhotos('demo');
    const second = await drive.listPhotos('demo');

    expect(second).toHaveLength(first.length + 1);
    expect(second[0].capturedAt.getTime()).toBeGreaterThan(first[0].capturedAt.getTime());
  });

  it('0 件モードでは空を返す', async () => {
    const drive = createFakeDrive({ render: stubRender(), getFault: () => 'empty' });

    await expect(drive.listPhotos('demo')).resolves.toEqual([]);
  });

  it('トークン失効モードでは DriveAuthError を投げる', async () => {
    const drive = createFakeDrive({ render: stubRender(), getFault: () => 'auth' });

    await expect(drive.listPhotos('demo')).rejects.toBeInstanceOf(DriveAuthError);
  });

  it('レート制限モードでは DriveRateLimitError を投げる', async () => {
    const drive = createFakeDrive({ render: stubRender(), getFault: () => 'rateLimit' });

    await expect(drive.listPhotos('demo')).rejects.toBeInstanceOf(DriveRateLimitError);
  });

  it('画像の実体は、渡された描画関数の結果を返す', async () => {
    const render = stubRender();
    const drive = createFakeDrive({ render });

    const blob = await drive.fetchPhotoBlob('demo-0', 'demo');

    expect(render).toHaveBeenCalled();
    expect(blob).toBeInstanceOf(Blob);
  });

  it('1 枚だけ取得失敗モードでは、いちばん新しい写真が PhotoNotFoundError になる', async () => {
    let fault: DemoFault = 'none';
    const drive = createFakeDrive({ render: stubRender(), getFault: () => fault });
    const photos = await drive.listPhotos('demo');

    fault = 'missingPhoto';

    // 新しく届いた 1 枚が取れない状況を作る。残りは取れ続ける
    await expect(drive.fetchPhotoBlob(photos[0].id, 'demo'))
      .rejects.toBeInstanceOf(PhotoNotFoundError);
    await expect(drive.fetchPhotoBlob(photos[1].id, 'demo')).resolves.toBeInstanceOf(Blob);
  });
});
