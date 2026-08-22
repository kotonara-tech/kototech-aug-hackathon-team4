import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import type { GoogleAuth } from './auth/useGoogleAuth';
import { DriveAuthError, DriveRateLimitError } from './drive/driveClient';
import type { DriveGateway } from './drive/gateway';
import type { Photo } from './drive/photo';
import { usePolling } from './hooks/usePolling';
import { usePhotoObjectUrls } from './hooks/usePhotoObjectUrls';
import { ErrorBanner } from './components/ErrorBanner';
import { LatestPhoto } from './components/LatestPhoto';
import { LoginGate } from './components/LoginGate';
import { PhotoGrid } from './components/PhotoGrid';
import { PhotoViewer } from './components/PhotoViewer';
import { DEFAULT_POLL_SEC, POLL_OPTIONS_SEC, PollControl } from './components/PollControl';

interface Notice {
  message: string;
  tone: 'warn' | 'error';
}

export interface AppProps {
  auth: GoogleAuth;
  drive: DriveGateway;
  /** 画面のいちばん上。デモの帯を差し込むために開けてある */
  banner?: ReactNode;
  /** 画面のいちばん下。デモ操作パネルを差し込むために開けてある */
  footer?: ReactNode;
  /** 値が変わると再取得する。デモから「今の状態で取り直せ」と言うための口 */
  refreshSignal?: number;
}

/** レート制限を受けたら 1 段階だけ間隔を延ばす。上限で頭打ち。 */
function longerInterval(current: number): number {
  const index = POLL_OPTIONS_SEC.indexOf(current);
  return POLL_OPTIONS_SEC[Math.min(POLL_OPTIONS_SEC.length - 1, index + 1)] ?? current;
}

/**
 * 画面は 1 枚。ルーティングは無い。
 * ここは組み立てるだけの薄い層に保つこと。→ docs/04-frontend.md 4 節
 *
 * 認証と Drive は受け取る。自分で掴まないので、本番とデモで同じ画面が動く。
 */
export function App({ auth, drive, banner, footer, refreshSignal = 0 }: AppProps) {
  const [photos, setPhotos] = useState<Photo[]>([]);
  const [intervalSec, setIntervalSec] = useState(DEFAULT_POLL_SEC);
  const [lastFetchedAt, setLastFetchedAt] = useState<Date | null>(null);
  const [loading, setLoading] = useState(false);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const objectUrlDeps = useMemo(
    () => ({
      fetchBlob: drive.fetchPhotoBlob,
      createObjectURL: (blob: Blob) => URL.createObjectURL(blob),
      revokeObjectURL: (url: string) => URL.revokeObjectURL(url),
    }),
    [drive],
  );

  const visiblePhotos = usePhotoObjectUrls(photos, auth.accessToken, objectUrlDeps);
  const selected = visiblePhotos.find((photo) => photo.id === selectedId) ?? null;

  const refresh = useCallback(async () => {
    const token = auth.accessToken;
    if (!token) return;

    setLoading(true);
    try {
      setPhotos(await drive.listPhotos(token));
      setLastFetchedAt(new Date());
      setNotice(null);
    } catch (error: unknown) {
      // 401 / 403（スコープ不足）: 更新して復活はしないので、再ログインへ落とす
      if (error instanceof DriveAuthError) {
        auth.signOut();
        setPhotos([]);
        setSelectedId(null);
        setNotice({ message: error.message, tone: 'error' });
        return;
      }

      // 429 / 403（レート制限）: 間隔を延ばして継続する。止めない
      if (error instanceof DriveRateLimitError) {
        setIntervalSec((current) => longerInterval(current));
        setNotice({ message: `${error.message}更新間隔を延ばして継続します。`, tone: 'warn' });
        return;
      }

      // 5xx など: 次のポーリングで自然に回復する。即時リトライしない
      setNotice({
        message: '一時的に取得できませんでした。次の更新で再試行します。',
        tone: 'warn',
      });
    } finally {
      setLoading(false);
    }
  }, [auth, drive]);

  // ログイン直後の初回取得と、外から明示的に要求された取り直し
  useEffect(() => {
    if (auth.accessToken) void refresh();
    // refresh は auth / drive に依存しているので、トークンと合図の変化だけで走らせる
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [auth.accessToken, refreshSignal]);

  usePolling(refresh, intervalSec * 1000, auth.accessToken !== null);

  if (!auth.accessToken) {
    return (
      <div className="app">
        {banner}
        <main className="wrap wrap-gate">
          <ErrorBanner message={notice?.message ?? auth.error} tone={notice?.tone ?? 'error'} />
          <LoginGate onSignIn={auth.signIn} />
          {footer}
        </main>
      </div>
    );
  }

  const hasPhotos = visiblePhotos.length > 0;

  return (
    <div className="app">
      {banner}

      <header className="appbar">
        <div className="appbar-inner">
          <div className="brand">
            <span className="dot" />
            定点観測ビューアー
          </div>
          <span className="grow" />
          <button type="button" onClick={auth.signOut}>
            ログアウト
          </button>
        </div>
      </header>

      <main className="wrap">
        <ErrorBanner message={notice?.message ?? null} tone={notice?.tone ?? 'warn'} />

        <PollControl
          intervalSec={intervalSec}
          onIntervalChange={setIntervalSec}
          onRefresh={() => void refresh()}
          loading={loading}
          lastFetchedAt={lastFetchedAt}
        />

        {/* 1 画面に収める要。ここから下が残りの高さを分け合う。
            伸び縮みするのは写真と一覧だけ、スクロールするのは一覧だけ */}
        <div className={hasPhotos ? 'stage' : 'stage stage-empty'}>
          {hasPhotos && <LatestPhoto photo={visiblePhotos[0]} />}

          <aside className="rail">
            {hasPhotos && (
              <h2 className="section">
                撮影一覧 <span className="section-note">（新しい順・クリックで拡大）</span>
              </h2>
            )}
            <div className="rail-body">
              <PhotoGrid photos={visiblePhotos} onSelect={(photo) => setSelectedId(photo.id)} />
            </div>
          </aside>
        </div>

        {footer}
      </main>

      {selected && (
        <PhotoViewer
          photos={visiblePhotos}
          current={selected}
          onNavigate={(photo) => setSelectedId(photo.id)}
          onClose={() => setSelectedId(null)}
        />
      )}
    </div>
  );
}
