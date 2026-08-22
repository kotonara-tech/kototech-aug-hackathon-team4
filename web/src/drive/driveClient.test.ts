import { describe, expect, it, vi } from 'vitest';
import {
  buildListRequest,
  fetchPhotoBlob,
  listPhotos,
  DriveAuthError,
  DriveRateLimitError,
  PhotoNotFoundError,
} from './driveClient';

/**
 * ここで固定したいのは「0 枚問題」を生む地雷そのもの。
 * → docs/02-google-drive.md 10 節 / docs/04-frontend.md 6.1
 *
 * 外部 API は絶対に叩かない。fetch はフェイクに差し替える。
 */

function fakeFetch(response: {
  status?: number;
  json?: unknown;
  blob?: Blob;
}): typeof fetch {
  const status = response.status ?? 200;
  return vi.fn(async () =>
    new Response(
      response.blob ?? JSON.stringify(response.json ?? { files: [] }),
      { status, headers: { 'Content-Type': 'application/json' } },
    ),
  ) as unknown as typeof fetch;
}

describe('buildListRequest', () => {
  it('spaces=appDataFolder を必ず含む（忘れるとマイドライブを見に行き、静かに 0 件になる）', () => {
    const url = new URL(buildListRequest().url);

    expect(url.searchParams.get('spaces')).toBe('appDataFolder');
  });

  it('fields に createdTime と imageMediaMetadata/time を含む（省略すると時刻が全部不明になる）', () => {
    const fields = new URL(buildListRequest().url).searchParams.get('fields') ?? '';

    expect(fields).toContain('createdTime');
    expect(fields).toContain('imageMediaMetadata/time');
  });

  it('ゴミ箱の中と画像以外を除外する', () => {
    const q = new URL(buildListRequest().url).searchParams.get('q') ?? '';

    expect(q).toContain("mimeType contains 'image/'");
    expect(q).toContain('trashed = false');
  });

  it('新しい順に、最大 100 件だけ取る', () => {
    const params = new URL(buildListRequest().url).searchParams;

    expect(params.get('orderBy')).toBe('createdTime desc');
    expect(params.get('pageSize')).toBe('100');
  });
});

describe('listPhotos', () => {
  it('Authorization: Bearer ヘッダを付けて呼ぶ', async () => {
    const fetchImpl = fakeFetch({ json: { files: [] } });

    await listPhotos('test-token', fetchImpl);

    const [, init] = (fetchImpl as unknown as ReturnType<typeof vi.fn>).mock.calls[0];
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer test-token');
  });

  it('401 は DriveAuthError にする（再ログインへ落とすため。黙って再試行しない）', async () => {
    await expect(listPhotos('expired', fakeFetch({ status: 401 })))
      .rejects.toBeInstanceOf(DriveAuthError);
  });

  it('403（スコープ不足）も DriveAuthError にする', async () => {
    await expect(listPhotos('wrong-scope', fakeFetch({ status: 403 })))
      .rejects.toBeInstanceOf(DriveAuthError);
  });

  it('429 は DriveRateLimitError にする（ポーリングは止めず間隔を延ばす）', async () => {
    await expect(listPhotos('token', fakeFetch({ status: 429 })))
      .rejects.toBeInstanceOf(DriveRateLimitError);
  });

  it('撮影時刻が決められない 1 枚があっても、残りを返して一覧全体を落とさない', async () => {
    const fetchImpl = fakeFetch({
      json: {
        files: [
          { id: 'ok', name: 'CAM001_20260822_063000.jpg', mimeType: 'image/jpeg',
            createdTime: '2026-08-22T09:35:12.000Z' },
          { id: 'broken', name: 'CAM001_20260822_063500.jpg', mimeType: 'image/jpeg' },
        ],
      },
    });

    const photos = await listPhotos('token', fetchImpl);

    expect(photos.map((p) => p.id)).toEqual(['ok']);
  });
});

describe('fetchPhotoBlob', () => {
  it('alt=media を付けて実体を取りに行く', async () => {
    const fetchImpl = fakeFetch({ blob: new Blob(['x'], { type: 'image/jpeg' }) });

    await fetchPhotoBlob('file-1', 'token', fetchImpl);

    const [url] = (fetchImpl as unknown as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(new URL(url as string).searchParams.get('alt')).toBe('media');
  });

  it('個別ファイルの 404 は PhotoNotFoundError にする（画面全体を落とさないため）', async () => {
    await expect(fetchPhotoBlob('gone', 'token', fakeFetch({ status: 404 })))
      .rejects.toBeInstanceOf(PhotoNotFoundError);
  });
});
