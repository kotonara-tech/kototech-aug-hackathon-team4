import { useEffect, useRef } from 'react';

/**
 * 間隔可変のポーリング。setInterval 1 つ。
 * → docs/04-frontend.md 7 節
 *
 * 守ること:
 *  - 前回のリクエストが終わる前に次を撃たない（遅い回線でリクエストが雪だるまになる）
 *  - タスクが失敗してもループを止めない（止めるのは 401 のときだけ。enabled で外から止める）
 *  - アンマウントでタイマーを解放する
 *
 * マウント直後には撃たない。初回取得はログイン直後に呼び出し側が明示的に行う。
 */
export function usePolling(
  task: () => Promise<void>,
  intervalMs: number,
  enabled: boolean,
): void {
  // task の同一性が変わるたびにタイマーを張り直すと、間隔が永久にリセットされる。
  // ref に逃がして、依存は間隔と有効フラグだけにする。
  const taskRef = useRef(task);
  taskRef.current = task;

  useEffect(() => {
    if (!enabled) return;

    let running = false;

    const timer = setInterval(() => {
      if (running) return;
      running = true;

      void (async () => {
        try {
          await taskRef.current();
        } catch {
          // 次の周期に任せる。ここで即時リトライしない
        } finally {
          running = false;
        }
      })();
    }, intervalMs);

    return () => clearInterval(timer);
  }, [intervalMs, enabled]);
}
