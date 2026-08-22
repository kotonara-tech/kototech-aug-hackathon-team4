import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { PhotoViewer } from './PhotoViewer';
import type { Photo } from '../drive/photo';

/** → docs/04-frontend.md 9 節「クリックで拡大表示」 */

const photo = (id: string, iso: string): Photo => ({
  id,
  name: `CAM001_${id}.jpg`,
  mimeType: 'image/jpeg',
  capturedAt: new Date(iso),
  capturedAtSource: 'exif',
  objectUrl: `blob:${id}`,
});

// 新しい順
const photos = [
  photo('new', '2026-08-21T21:40:00.000Z'),
  photo('mid', '2026-08-21T21:35:00.000Z'),
  photo('old', '2026-08-21T21:30:00.000Z'),
];

describe('PhotoViewer', () => {
  it('選ばれた写真の撮影時刻を表示する', () => {
    render(<PhotoViewer photos={photos} current={photos[1]} onNavigate={vi.fn()} onClose={vi.fn()} />);

    expect(screen.getByText('2026-08-22 06:35:00')).toBeInTheDocument();
  });

  it('「古い」を押すと 1 枚古い写真へ移る', async () => {
    const onNavigate = vi.fn();
    render(<PhotoViewer photos={photos} current={photos[1]} onNavigate={onNavigate} onClose={vi.fn()} />);

    await userEvent.click(screen.getByRole('button', { name: /古い/ }));

    expect(onNavigate).toHaveBeenCalledWith(photos[2]);
  });

  it('「新しい」を押すと 1 枚新しい写真へ移る', async () => {
    const onNavigate = vi.fn();
    render(<PhotoViewer photos={photos} current={photos[1]} onNavigate={onNavigate} onClose={vi.fn()} />);

    await userEvent.click(screen.getByRole('button', { name: /新しい/ }));

    expect(onNavigate).toHaveBeenCalledWith(photos[0]);
  });

  it('いちばん新しい写真では「新しい」を押せない', () => {
    render(<PhotoViewer photos={photos} current={photos[0]} onNavigate={vi.fn()} onClose={vi.fn()} />);

    expect(screen.getByRole('button', { name: /新しい/ })).toBeDisabled();
  });

  it('Esc で閉じる', async () => {
    const onClose = vi.fn();
    render(<PhotoViewer photos={photos} current={photos[0]} onNavigate={vi.fn()} onClose={onClose} />);

    await userEvent.keyboard('{Escape}');

    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
