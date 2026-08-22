import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { usePolling } from './usePolling';

/**
 * → docs/04-frontend.md 7 節
 * 「前回のリクエストが終わる前に次を撃たない」がここの肝。
 * 撃ってしまうと、遅い回線でリクエストが雪だるま式に増える。
 */

describe('usePolling', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('指定した間隔どおりに発火する', async () => {
    const task = vi.fn(async () => {});
    renderHook(() => usePolling(task, 30_000, true));

    await act(async () => {
      await vi.advanceTimersByTimeAsync(90_000);
    });

    expect(task).toHaveBeenCalledTimes(3);
  });

  it('enabled が false のあいだは発火しない（401 のときに止めるため）', async () => {
    const task = vi.fn(async () => {});
    renderHook(() => usePolling(task, 30_000, false));

    await act(async () => {
      await vi.advanceTimersByTimeAsync(120_000);
    });

    expect(task).not.toHaveBeenCalled();
  });

  it('前回のリクエストが終わるまで次を撃たない', async () => {
    let release: (() => void) | undefined;
    const task = vi.fn(
      () => new Promise<void>((resolve) => { release = resolve; }),
    );
    renderHook(() => usePolling(task, 10_000, true));

    // 1 回目が終わらないまま 5 周期ぶん時間を進める
    await act(async () => {
      await vi.advanceTimersByTimeAsync(50_000);
    });
    expect(task).toHaveBeenCalledTimes(1);

    // 1 回目を終わらせれば、次の周期で 2 回目が動く
    await act(async () => {
      release?.();
      await vi.advanceTimersByTimeAsync(10_000);
    });
    expect(task).toHaveBeenCalledTimes(2);
  });

  it('タスクが例外を投げてもポーリングを止めない（5xx で画面が死なないようにする）', async () => {
    const task = vi.fn(async () => {
      throw new Error('boom');
    });
    renderHook(() => usePolling(task, 10_000, true));

    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_000);
    });

    expect(task).toHaveBeenCalledTimes(3);
  });

  it('アンマウントするとタイマーを解放する', async () => {
    const task = vi.fn(async () => {});
    const { unmount } = renderHook(() => usePolling(task, 10_000, true));

    unmount();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(50_000);
    });

    expect(task).not.toHaveBeenCalled();
  });
});
