import { useEffect } from 'react';
import type { Photo } from '../drive/photo';
import { formatCapturedAt } from '../format';

interface Props {
  /** 新しい順。index 0 がいちばん新しい */
  photos: Photo[];
  current: Photo;
  onNavigate: (photo: Photo) => void;
  onClose: () => void;
}

/** 拡大表示。ルーティングは無いので、ただの重ね表示。 */
export function PhotoViewer({ photos, current, onNavigate, onClose }: Props) {
  const index = photos.findIndex((photo) => photo.id === current.id);
  const newer = index > 0 ? photos[index - 1] : null;
  const older = index >= 0 && index < photos.length - 1 ? photos[index + 1] : null;

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
      if (event.key === 'ArrowRight' && newer) onNavigate(newer);
      if (event.key === 'ArrowLeft' && older) onNavigate(older);
    };

    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [newer, older, onNavigate, onClose]);

  const fellBack = current.capturedAtSource === 'createdTime';

  return (
    <div className="viewer" role="dialog" aria-modal="true" aria-label="撮影画像の拡大表示">
      <div className="viewer-top">
        <span className="captured">{formatCapturedAt(current.capturedAt)}</span>
        {fellBack && (
          <span className="captured-source fallback">
            EXIF なし — アップロード時刻を表示しています
          </span>
        )}
        <span className="file-name">{current.name}</span>
        <span className="grow" />
        <button type="button" onClick={onClose}>
          閉じる（Esc）
        </button>
      </div>

      <div className="viewer-body">
        {current.objectUrl ? (
          <img src={current.objectUrl} alt={current.name} />
        ) : (
          <div className="latest-placeholder">画像を取得しています…</div>
        )}
      </div>

      <div className="viewer-bottom">
        <button type="button" disabled={!older} onClick={() => older && onNavigate(older)}>
          ← 古い
        </button>
        <span>
          {index + 1} / {photos.length}
        </span>
        <button type="button" disabled={!newer} onClick={() => newer && onNavigate(newer)}>
          新しい →
        </button>
      </div>
    </div>
  );
}
