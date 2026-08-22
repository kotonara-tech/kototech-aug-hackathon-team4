import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { PollControl } from './PollControl';

/** → docs/04-frontend.md 7 節（既定 30 秒・選択肢 10s/30s/1m/5m・下限 10 秒・手動更新は必須） */

function setup(over: Partial<Parameters<typeof PollControl>[0]> = {}) {
  const props = {
    intervalSec: 30,
    onIntervalChange: vi.fn(),
    onRefresh: vi.fn(),
    loading: false,
    lastFetchedAt: new Date('2026-08-21T21:35:12.000Z'), // JST 06:35:12
    ...over,
  };
  render(<PollControl {...props} />);
  return props;
}

describe('PollControl', () => {
  it('更新間隔の選択肢は 10秒 / 30秒 / 1分 / 5分 の 4 つ', () => {
    setup();

    const options = screen.getAllByRole('option').map((o) => o.textContent);
    expect(options).toEqual(['10秒', '30秒', '1分', '5分']);
  });

  it('10 秒より短い間隔は選べない（レート制限に当たるため）', () => {
    setup();

    const values = screen.getAllByRole('option').map((o) => Number(o.getAttribute('value')));
    expect(Math.min(...values)).toBe(10);
  });

  it('間隔を変えると、新しい秒数を通知する', async () => {
    const props = setup();

    await userEvent.selectOptions(screen.getByRole('combobox'), '300');

    expect(props.onIntervalChange).toHaveBeenCalledWith(300);
  });

  it('手動更新ボタンを押すと再取得を要求する（デモで「今すぐ反映」に効く）', async () => {
    const props = setup();

    await userEvent.click(screen.getByRole('button', { name: '今すぐ更新' }));

    expect(props.onRefresh).toHaveBeenCalledTimes(1);
  });

  it('取得中は手動更新ボタンを押せない（前回が終わる前に次を撃たないため）', () => {
    setup({ loading: true });

    expect(screen.getByRole('button', { name: '今すぐ更新' })).toBeDisabled();
  });

  it('最終取得時刻を JST で表示する', () => {
    setup();

    expect(screen.getByText(/06:35:12/)).toBeInTheDocument();
  });
});
