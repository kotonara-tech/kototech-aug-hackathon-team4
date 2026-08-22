import { useCallback, useMemo, useRef, useState } from 'react';

/**
 * Google Identity Services のトークンクライアントの薄いラッパ。
 * → docs/04-frontend.md 5 節
 *
 *  - SPA なのでクライアントシークレットを使えず、リフレッシュトークンは取れない
 *  - アクセストークンは約 1 時間で失効する。更新はしない。失効したら再ログイン
 *  - トークンはメモリのみに置く（Q7 暫定）。localStorage にも sessionStorage にも書かない
 */

/** ★ これ以外のスコープでは AppData 領域は見えない（drive.readonly でも drive でも届かない） */
const SCOPE = 'https://www.googleapis.com/auth/drive.appdata';

export interface GoogleAuth {
  accessToken: string | null;
  signIn: () => void;
  signOut: () => void;
  error: string | null;
}

export function useGoogleAuth(): GoogleAuth {
  const [accessToken, setAccessToken] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const clientRef = useRef<google.accounts.oauth2.TokenClient | null>(null);

  const signIn = useCallback(() => {
    const clientId = import.meta.env.VITE_GOOGLE_CLIENT_ID;
    if (!clientId) {
      setError('VITE_GOOGLE_CLIENT_ID が設定されていません。.env.local を確認してください。');
      return;
    }

    if (typeof google === 'undefined' || !google.accounts?.oauth2) {
      setError('Google のログイン用スクリプトを読み込めませんでした。通信状況を確認してください。');
      return;
    }

    clientRef.current ??= google.accounts.oauth2.initTokenClient({
      client_id: clientId,
      scope: SCOPE,
      callback: (response) => {
        if (!response.access_token) {
          setError('ログインに失敗しました。もう一度お試しください。');
          return;
        }
        setError(null);
        setAccessToken(response.access_token);
      },
      error_callback: () => {
        setError('ログインがキャンセルされました。');
      },
    });

    clientRef.current.requestAccessToken();
  }, []);

  const signOut = useCallback(() => {
    setAccessToken(null);
  }, []);

  return useMemo(
    () => ({ accessToken, signIn, signOut, error }),
    [accessToken, signIn, signOut, error],
  );
}
