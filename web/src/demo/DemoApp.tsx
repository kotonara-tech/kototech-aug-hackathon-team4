import { useMemo, useRef, useState } from 'react';
import { App } from '../App';
import type { GoogleAuth } from '../auth/useGoogleAuth';
import { createFakeDrive, type DemoFault } from './fakeDrive';
import { renderSceneBlob } from './scene';
import './demo.css';

/**
 * デモ画面。
 *
 * 本番の App をそのまま描画し、Drive と認証だけを差し替えている。
 * 画面のコードはデモ専用に持たない。ここが分かれると、
 * 「デモでは動いたのに本番で動かない」が起きる。
 *
 * → 通信も Google アカウントも要らないので、当日の回線と担当 A の進捗から独立して見せられる。
 */

interface Props {
  /** 画像の描画。テストでは canvas を使わないものに差し替える */
  render?: (seq: number, at: Date) => Promise<Blob>;
}

const FAULT_BUTTONS: Array<{ fault: DemoFault; label: string; hint: string }> = [
  { fault: 'empty', label: '写真 0 件にする', hint: '空リストではなく説明文を出す' },
  { fault: 'auth', label: '401（トークン失効）', hint: 'ポーリングを止めて再ログインへ戻す' },
  { fault: 'rateLimit', label: '429（レート制限）', hint: '間隔を延ばして継続する。止めない' },
  { fault: 'missingPhoto', label: '404（1 枚だけ取得失敗）', hint: 'その 1 枚だけ外して表示を続ける' },
];

export function DemoApp({ render = renderSceneBlob }: Props) {
  const [accessToken, setAccessToken] = useState<string | null>(null);
  const [fault, setFault] = useState<DemoFault>('none');
  const [refreshSignal, setRefreshSignal] = useState(0);

  // fakeDrive は React の外から現在の状態を読む必要がある
  const faultRef = useRef<DemoFault>('none');

  const drive = useMemo(
    () => createFakeDrive({ render, getFault: () => faultRef.current }),
    [render],
  );

  const advance = () => setRefreshSignal((n) => n + 1);

  const applyFault = (next: DemoFault) => {
    faultRef.current = next;
    setFault(next);
    advance();
  };

  const auth = useMemo<GoogleAuth>(
    () => ({
      accessToken,
      signIn: () => {
        // 401 を見せたあとにログインし直したら、また見えるようにする
        faultRef.current = 'none';
        setFault('none');
        setAccessToken('demo-access-token');
      },
      signOut: () => setAccessToken(null),
      error: null,
    }),
    [accessToken],
  );

  return (
    <App
      auth={auth}
      drive={drive}
      refreshSignal={refreshSignal}
      banner={
        <div className="demo-flag">
          <div className="demo-flag-inner">
            <strong>デモモード</strong>
            <span>
              Google Drive には接続していません。表示中の画像はブラウザ内で生成した合成データです。
            </span>
          </div>
        </div>
      }
      footer={
        <div className="demo-controls">
          <h3>デモ操作（本番のビルドには含まれません）</h3>

          <div className="demo-row">
            <button type="button" onClick={advance}>
              新しい写真が届いた
            </button>

            {FAULT_BUTTONS.map((item) => (
              <button
                key={item.fault}
                type="button"
                title={item.hint}
                aria-pressed={fault === item.fault}
                className={fault === item.fault ? 'active' : undefined}
                onClick={() => applyFault(fault === item.fault ? 'none' : item.fault)}
              >
                {fault === item.fault && item.fault === 'empty'
                  ? '写真ありに戻す'
                  : item.label}
              </button>
            ))}

            <button type="button" onClick={() => applyFault('none')}>
              リセット
            </button>
          </div>

          <p>
            docs/04-frontend.md 8 節「エラーハンドリング」の挙動を、その場で見せるためのボタンです。
            表示中のコンポーネントは本番と同一で、差し替えているのは Drive と認証だけです。
          </p>
        </div>
      }
    />
  );
}
