interface Props {
  message: string | null;
  /** 'error' は再ログインが要る致命的なもの。'warn' は継続できるもの */
  tone?: 'warn' | 'error';
}

/** → docs/04-frontend.md 8 節 */
export function ErrorBanner({ message, tone = 'warn' }: Props) {
  if (!message) return null;

  return (
    <div className={`banner banner-${tone}`} role="status">
      {message}
    </div>
  );
}
