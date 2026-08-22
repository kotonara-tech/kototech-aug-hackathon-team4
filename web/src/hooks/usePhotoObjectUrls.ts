import { useEffect, useMemo, useRef, useState } from 'react';
import { fetchPhotoBlob, PhotoNotFoundError } from '../drive/driveClient';
import type { Photo } from '../drive/photo';

/**
 * 画像の実体を取って objectUrl を付ける。
 * → docs/04-frontend.md 6.2 / 12 節
 *
 *  - <img src="https://www.googleapis.com/..."> では表示できない（Authorization が要る）
 *  - revokeObjectURL を忘れるとメモリを食い潰す。ポーリングで取り続けるので露呈が早い
 *  - 個別ファイルの 404 は、その 1 枚だけ一覧から外す。画面全体を落とさない
 *
 * jsdom には URL.createObjectURL が無いため、依存を外から差し替えられる形にしてある。
 */
export interface ObjectUrlDeps {
  fetchBlob: (fileId: string, accessToken: string) => Promise<Blob>;
  createObjectURL: (blob: Blob) => string;
  revokeObjectURL: (url: string) => void;
}

const browserDeps: ObjectUrlDeps = {
  fetchBlob: (fileId, accessToken) => fetchPhotoBlob(fileId, accessToken),
  createObjectURL: (blob) => URL.createObjectURL(blob),
  revokeObjectURL: (url) => URL.revokeObjectURL(url),
};

export function usePhotoObjectUrls(
  photos: Photo[],
  accessToken: string | null,
  deps: ObjectUrlDeps = browserDeps,
): Photo[] {
  const [urls, setUrls] = useState<Record<string, string>>({});
  const [missing, setMissing] = useState<ReadonlySet<string>>(() => new Set<string>());

  // 最新の値をエフェクト内から読むための逃がし先。
  // これらを依存配列に入れると、取得のたびにエフェクトが回り直す。
  const urlsRef = useRef(urls);
  urlsRef.current = urls;
  const depsRef = useRef(deps);
  depsRef.current = deps;

  const inFlight = useRef<Set<string>>(new Set());
  const mounted = useRef(true);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  // 未取得のぶんだけ取りに行く
  useEffect(() => {
    if (!accessToken) return;

    for (const photo of photos) {
      if (urlsRef.current[photo.id]) continue;
      if (inFlight.current.has(photo.id)) continue;
      if (missing.has(photo.id)) continue;

      inFlight.current.add(photo.id);

      void depsRef.current
        .fetchBlob(photo.id, accessToken)
        .then((blob) => {
          const url = depsRef.current.createObjectURL(blob);
          if (!mounted.current) {
            depsRef.current.revokeObjectURL(url);
            return;
          }
          setUrls((prev) => ({ ...prev, [photo.id]: url }));
        })
        .catch((error: unknown) => {
          if (!mounted.current) return;
          if (error instanceof PhotoNotFoundError) {
            setMissing((prev) => new Set(prev).add(photo.id));
          }
          // それ以外は次のポーリングでまた試す
        })
        .finally(() => {
          inFlight.current.delete(photo.id);
        });
    }
  }, [photos, accessToken, missing]);

  // 一覧から消えた写真の URL を解放する
  useEffect(() => {
    const alive = new Set(photos.map((photo) => photo.id));
    const stale = Object.entries(urlsRef.current).filter(([id]) => !alive.has(id));
    if (stale.length === 0) return;

    for (const [, url] of stale) depsRef.current.revokeObjectURL(url);
    setUrls((prev) => {
      const next = { ...prev };
      for (const [id] of stale) delete next[id];
      return next;
    });
  }, [photos]);

  // アンマウント時に全部解放する
  useEffect(
    () => () => {
      for (const url of Object.values(urlsRef.current)) {
        depsRef.current.revokeObjectURL(url);
      }
    },
    [],
  );

  return useMemo(
    () =>
      photos
        .filter((photo) => !missing.has(photo.id))
        .map((photo) => (urls[photo.id] ? { ...photo, objectUrl: urls[photo.id] } : photo)),
    [photos, urls, missing],
  );
}
