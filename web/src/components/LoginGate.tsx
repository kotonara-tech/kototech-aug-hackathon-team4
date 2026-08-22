interface Props {
  onSignIn: () => void;
}

/**
 * 未ログイン時はこれだけを出す。
 * アカウントがずれていると、実装が正しくても 0 枚になる。
 * → docs/02-google-drive.md 8 節
 */
export function LoginGate({ onSignIn }: Props) {
  return (
    <div className="gate">
      <h1>定点観測ビューアー</h1>
      <p>
        Android 端末が Google Drive のアプリ専用領域へアップロードした画像を表示します。
        撮影に使ったものと<strong>同じ Google アカウント</strong>でログインしてください。
      </p>
      <button type="button" className="primary" onClick={onSignIn}>
        Google でログイン
      </button>
    </div>
  );
}
