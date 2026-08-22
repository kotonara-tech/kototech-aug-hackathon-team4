import { formatClock, formatInterval } from '../format';

/**
 * Q4 暫定: 既定 30 秒、選択肢 10s/30s/1m/5m、下限 10 秒。
 * 下限を外すとレート制限に当たる。ここ以外に値を書かないこと。
 */
export const POLL_OPTIONS_SEC = [10, 30, 60, 300];
export const DEFAULT_POLL_SEC = 30;

interface Props {
  intervalSec: number;
  onIntervalChange: (seconds: number) => void;
  onRefresh: () => void;
  loading: boolean;
  lastFetchedAt: Date | null;
}

/** → docs/04-frontend.md 7 節。手動更新ボタンは必ず置くこと。 */
export function PollControl({
  intervalSec,
  onIntervalChange,
  onRefresh,
  loading,
  lastFetchedAt,
}: Props) {
  return (
    <div className="toolbar">
      <label className="field">
        更新間隔{' '}
        <select
          value={intervalSec}
          onChange={(event) => onIntervalChange(Number(event.target.value))}
        >
          {POLL_OPTIONS_SEC.map((seconds) => (
            <option key={seconds} value={seconds}>
              {formatInterval(seconds)}
            </option>
          ))}
        </select>
      </label>

      <button type="button" className="primary" onClick={onRefresh} disabled={loading}>
        今すぐ更新
      </button>

      <span className="stamp">
        {loading ? '取得中…' : `最終取得 ${lastFetchedAt ? formatClock(lastFetchedAt) : '—'}`}
      </span>
    </div>
  );
}
