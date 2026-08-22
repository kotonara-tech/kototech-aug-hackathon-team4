import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { DemoApp } from './DemoApp';
import { DEMO_INITIAL_COUNT } from './fakeDrive';

/**
 * デモ画面。本番の App をそのまま使い、Drive と認証だけを差し替えたもの。
 * ここで固定するのは「本番の画面が本当に動いていること」と
 * 「異常系をその場で見せられること」の 2 つ。
 *
 * 画像の描画は canvas なので jsdom では動かない。描画関数は差し替える。
 */

const stubRender = () => vi.fn(async () => new Blob(['jpeg']));

function thumbnails() {
  return screen.queryAllByRole('button', { name: /を拡大表示$/ });
}

async function signIn() {
  await userEvent.click(screen.getByRole('button', { name: /Google でログイン/ }));
}

beforeEach(() => {
  URL.createObjectURL = vi.fn(() => `blob:${Math.random()}`);
  URL.revokeObjectURL = vi.fn();
});

describe('DemoApp', () => {
  it('合成データであることを、ログイン前から常に明示する', () => {
    render(<DemoApp render={stubRender()} />);

    expect(screen.getByText(/合成データ/)).toBeInTheDocument();
  });

  it('ログインすると、本番と同じ画面に 36 枚が並ぶ', async () => {
    render(<DemoApp render={stubRender()} />);

    await signIn();

    await waitFor(() => expect(thumbnails()).toHaveLength(DEMO_INITIAL_COUNT));
    expect(screen.getByRole('region', { name: '最新の写真' })).toBeInTheDocument();
  });

  it('「新しい写真が届いた」を押すと 1 枚増える', async () => {
    render(<DemoApp render={stubRender()} />);
    await signIn();
    await waitFor(() => expect(thumbnails()).toHaveLength(DEMO_INITIAL_COUNT));

    await userEvent.click(screen.getByRole('button', { name: '新しい写真が届いた' }));

    await waitFor(() => expect(thumbnails()).toHaveLength(DEMO_INITIAL_COUNT + 1));
  });

  it('「写真 0 件にする」を押すと、空リストではなく説明文が出る', async () => {
    render(<DemoApp render={stubRender()} />);
    await signIn();
    await waitFor(() => expect(thumbnails()).toHaveLength(DEMO_INITIAL_COUNT));

    await userEvent.click(screen.getByRole('button', { name: '写真 0 件にする' }));

    expect(await screen.findByText(/まだ写真がありません/)).toBeInTheDocument();
  });

  it('「401（トークン失効）」を押すと、期限切れを伝えて再ログイン導線へ戻る', async () => {
    render(<DemoApp render={stubRender()} />);
    await signIn();
    await waitFor(() => expect(thumbnails()).toHaveLength(DEMO_INITIAL_COUNT));

    await userEvent.click(screen.getByRole('button', { name: /401/ }));

    expect(await screen.findByText(/有効期限が切れました/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Google でログイン/ })).toBeInTheDocument();
  });

  it('401 のあとログインし直すと、また写真が見えるようになる', async () => {
    render(<DemoApp render={stubRender()} />);
    await signIn();
    await waitFor(() => expect(thumbnails()).toHaveLength(DEMO_INITIAL_COUNT));
    await userEvent.click(screen.getByRole('button', { name: /401/ }));
    await screen.findByText(/有効期限が切れました/);

    await signIn();

    await waitFor(() => expect(thumbnails().length).toBeGreaterThan(0));
  });

  it('「429（レート制限）」を押すと、更新間隔を延ばして表示を続ける', async () => {
    render(<DemoApp render={stubRender()} />);
    await signIn();
    await waitFor(() => expect(thumbnails()).toHaveLength(DEMO_INITIAL_COUNT));

    await userEvent.click(screen.getByRole('button', { name: /429/ }));

    expect(await screen.findByRole('status')).toHaveTextContent(/レート制限/);
    // 画面は落ちない
    expect(thumbnails().length).toBeGreaterThan(0);
  });
});
