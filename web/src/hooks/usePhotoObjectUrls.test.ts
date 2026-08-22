import { describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { usePhotoObjectUrls } from './usePhotoObjectUrls';
import { PhotoNotFoundError } from '../drive/driveClient';
import type { Photo } from '../drive/photo';

/**
 * → docs/04-frontend.md 6.2 / 12 節
 * revokeObjectURL を忘れるとメモリを食い潰す。ポーリングで取り続ける設計なので、
 * 普通の SPA より露呈が早い。ここはテストで固定する。
 *
 * jsdom には URL.createObjectURL が無いので、外から差し替えられる形にしてある。
 */

const photo = (id: string): Photo => ({
  id,
  name: `CAM001_${id}.jpg`,
  mimeType: 'image/jpeg',
  capturedAt: new Date('2026-08-21T21:30:00.000Z'),
  capturedAtSource: 'exif',
});

function deps(over: Partial<Parameters<typeof usePhotoObjectUrls>[2]> = {}) {
  return {
    fetchBlob: vi.fn(async (fileId: string) => new Blob([fileId])),
    createObjectURL: vi.fn((_blob: Blob) => `blob:${Math.random()}`),
    revokeObjectURL: vi.fn(),
    ...over,
  };
}

describe('usePhotoObjectUrls', () => {
  it('実体を取得できた写真に objectUrl を付けて返す', async () => {
    const d = deps();
    const { result } = renderHook(() => usePhotoObjectUrls([photo('a')], 'token', d));

    await waitFor(() => expect(result.current[0]?.objectUrl).toMatch(/^blob:/));
  });

  it('同じ写真を二度取りに行かない（ポーリングのたびに全件落とさない）', async () => {
    const d = deps();
    const photos = [photo('a')];
    const { result, rerender } = renderHook(
      ({ list }) => usePhotoObjectUrls(list, 'token', d),
      { initialProps: { list: photos } },
    );

    await waitFor(() => expect(result.current[0]?.objectUrl).toBeDefined());
    rerender({ list: [...photos] });
    await waitFor(() => expect(d.fetchBlob).toHaveBeenCalledTimes(1));
  });

  it('一覧から消えた写真の objectUrl を revoke する', async () => {
    const d = deps();
    const { result, rerender } = renderHook(
      ({ list }) => usePhotoObjectUrls(list, 'token', d),
      { initialProps: { list: [photo('a'), photo('b')] } },
    );

    await waitFor(() => expect(result.current.every((p) => p.objectUrl)).toBe(true));
    const revoked = result.current.find((p) => p.id === 'b')?.objectUrl;

    rerender({ list: [photo('a')] });

    await waitFor(() => expect(d.revokeObjectURL).toHaveBeenCalledWith(revoked));
  });

  it('1 枚が 404 でも、その写真だけ外して残りを表示し続ける', async () => {
    const d = deps({
      fetchBlob: vi.fn(async (fileId: string) => {
        if (fileId === 'gone') throw new PhotoNotFoundError('gone');
        return new Blob([fileId]);
      }),
    });

    const { result } = renderHook(() =>
      usePhotoObjectUrls([photo('ok'), photo('gone')], 'token', d),
    );

    await waitFor(() => expect(result.current.map((p) => p.id)).toEqual(['ok']));
  });

  it('アンマウント時に取得済みの objectUrl をすべて解放する', async () => {
    const d = deps();
    const { result, unmount } = renderHook(() => usePhotoObjectUrls([photo('a')], 'token', d));

    await waitFor(() => expect(result.current[0]?.objectUrl).toBeDefined());
    unmount();

    expect(d.revokeObjectURL).toHaveBeenCalledTimes(1);
  });
});
