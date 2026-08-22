import type { Photo } from '../drive/photo';
import { formatCapturedAt } from '../format';

interface Props {
  photo: Photo;
}

/**
 * 最新写真の大表示。
 * 出すのは「撮影時刻」であって、アップロード時刻ではない。
 * EXIF が取れずフォールバックしたときは、それが分かるようにする。
 * → docs/04-frontend.md 9 節
 */
export function LatestPhoto({ photo }: Props) {
  const fellBack = photo.capturedAtSource === 'createdTime';

  return (
    <section className="latest" aria-label="最新の写真">
      {photo.objectUrl ? (
        <img className="latest-image" src={photo.objectUrl} alt="最新の撮影画像" />
      ) : (
        // 実体がまだ取れていなくても、時刻は出す。1 枚のために画面を落とさない
        <div className="latest-image latest-placeholder">画像を取得しています…</div>
      )}

      <div className="latest-meta">
        <span className="captured">{formatCapturedAt(photo.capturedAt)}</span>
        <span className={fellBack ? 'captured-source fallback' : 'captured-source'}>
          {fellBack
            ? 'EXIF なし — アップロード時刻を表示しています'
            : '撮影時刻（EXIF）'}
        </span>
        <span className="file-name">{photo.name}</span>
      </div>
    </section>
  );
}
