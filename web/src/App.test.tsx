import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import { App } from './App';
import type { GoogleAuth } from './auth/useGoogleAuth';
import { DriveAuthError } from './drive/driveClient';
import type { DriveGateway } from './drive/gateway';
import type { Photo } from './drive/photo';

/**
 * App.tsx は組み立てるだけの薄い層。ここで固定するのは
 * 「401 を受けたらポーリングを止めて再ログイン導線へ戻す」という
 * いちばん壊れてはいけない一本だけ。
 * → docs/04-frontend.md 8 節
 *
 * 認証も Drive も props で受け取るので、モジュールのモックは要らない。
 */

function fakeAuth(over: Partial<GoogleAuth> = {}): GoogleAuth {
  return {
    accessToken: null,
    signIn: vi.fn(),
    signOut: vi.fn(),
    error: null,
    ...over,
  };
}

function fakeDrive(over: Partial<DriveGateway> = {}): DriveGateway {
  return {
    listPhotos: vi.fn(async () => []),
    fetchPhotoBlob: vi.fn(async () => new Blob(['x'])),
    ...over,
  };
}

const photo = (id: string): Photo => ({
  id,
  name: `CAM001_${id}.jpg`,
  mimeType: 'image/jpeg',
  capturedAt: new Date('2026-08-21T21:30:00.000Z'), // JST 2026-08-22 06:30:00
  capturedAtSource: 'exif',
});

beforeEach(() => {
  URL.createObjectURL = vi.fn(() => 'blob:stub');
  URL.revokeObjectURL = vi.fn();
});

describe('App', () => {
  it('未ログインのときはログイン導線だけを出し、Drive を叩かない', () => {
    const drive = fakeDrive();
    render(<App auth={fakeAuth()} drive={drive} />);

    expect(screen.getByRole('button', { name: /ログイン/ })).toBeInTheDocument();
    expect(drive.listPhotos).not.toHaveBeenCalled();
  });

  it('ログイン後は取得した写真の最新を大きく表示する', async () => {
    const drive = fakeDrive({ listPhotos: vi.fn(async () => [photo('a')]) });
    render(<App auth={fakeAuth({ accessToken: 'token' })} drive={drive} />);

    const latest = await screen.findByRole('region', { name: '最新の写真' });
    await waitFor(() =>
      expect(within(latest).getByText('2026-08-22 06:30:00')).toBeInTheDocument(),
    );
  });

  it('401 を受けたらログアウトして、期限切れを伝えて再ログイン導線へ戻す', async () => {
    const auth = fakeAuth({ accessToken: 'expired' });
    const drive = fakeDrive({
      listPhotos: vi.fn(async () => {
        throw new DriveAuthError();
      }),
    });
    render(<App auth={auth} drive={drive} />);

    await waitFor(() => expect(auth.signOut).toHaveBeenCalledTimes(1));
    expect(await screen.findByText(/有効期限が切れました/)).toBeInTheDocument();
  });

  it('写真が 0 件のときは、空リストではなく説明文を出す', async () => {
    render(<App auth={fakeAuth({ accessToken: 'token' })} drive={fakeDrive()} />);

    expect(await screen.findByText(/まだ写真がありません/)).toBeInTheDocument();
  });

  it('refreshSignal が変わると、その場で取り直す（デモ操作から使う口）', async () => {
    const drive = fakeDrive();
    const auth = fakeAuth({ accessToken: 'token' });
    const { rerender } = render(<App auth={auth} drive={drive} refreshSignal={0} />);

    await waitFor(() => expect(drive.listPhotos).toHaveBeenCalledTimes(1));

    rerender(<App auth={auth} drive={drive} refreshSignal={1} />);

    await waitFor(() => expect(drive.listPhotos).toHaveBeenCalledTimes(2));
  });
});
