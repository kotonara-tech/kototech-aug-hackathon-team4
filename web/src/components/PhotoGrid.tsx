import type { Photo } from '../drive/photo';
import { formatCapturedAt } from '../format';

interface Props {
  /** 新しい順に並んでいる前提。並べ替えはここでやらない */
  photos: Photo[];
  onSelect: (photo: Photo) => void;
}

/**
 * サムネイル一覧。
 * 0 件のときは空リストではなく説明文を出す（→ docs/04-frontend.md 8 節）。
 */
export function PhotoGrid({ photos, onSelect }: Props) {
  if (photos.length === 0) {
    return (
      <div className="empty">
        <h3>まだ写真がありません</h3>
        <p>
          Android 端末が撮影してアップロードすると、ここに表示されます。
          <br />
          端末側で撮影が開始されているかを確認してください。
        </p>
      </div>
    );
  }

  return (
    <ul className="grid">
      {photos.map((photo, index) => (
        <li key={photo.id}>
          <button
            type="button"
            className={index === 0 ? 'thumb newest' : 'thumb'}
            aria-label={`${photo.name} を拡大表示`}
            onClick={() => onSelect(photo)}
          >
            {photo.objectUrl ? (
              <img src={photo.objectUrl} alt="" loading="lazy" />
            ) : (
              <span className="thumb-placeholder" aria-hidden="true" />
            )}
            {index === 0 && <span className="badge">最新</span>}
            <span className="thumb-time">{formatCapturedAt(photo.capturedAt)}</span>
          </button>
        </li>
      ))}
    </ul>
  );
}
