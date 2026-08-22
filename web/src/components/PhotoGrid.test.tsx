import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { PhotoGrid } from './PhotoGrid';
import type { Photo } from '../drive/photo';

/** → docs/04-frontend.md 8 節「写真 0 件」/ 9 節「クリックで拡大表示」 */

const photo = (id: string, iso: string): Photo => ({
  id,
  name: `CAM001_${id}.jpg`,
  mimeType: 'image/jpeg',
  capturedAt: new Date(iso),
  capturedAtSource: 'exif',
});

describe('PhotoGrid', () => {
  it('写真が 0 件のときは、空リストではなく説明文を出す', () => {
    render(<PhotoGrid photos={[]} onSelect={vi.fn()} />);

    expect(screen.getByText(/まだ写真がありません/)).toBeInTheDocument();
  });

  it('渡された写真を新しい順のまま並べる', () => {
    render(
      <PhotoGrid
        photos={[
          photo('a', '2026-08-22T00:40:00.000Z'),
          photo('b', '2026-08-22T00:35:00.000Z'),
        ]}
        onSelect={vi.fn()}
      />,
    );

    const items = screen.getAllByRole('button');
    expect(items).toHaveLength(2);
    expect(items[0]).toHaveAccessibleName(/CAM001_a/);
  });

  it('サムネイルをクリックすると、その写真を拡大表示するよう通知する', async () => {
    const onSelect = vi.fn();
    const target = photo('a', '2026-08-22T00:40:00.000Z');
    render(<PhotoGrid photos={[target]} onSelect={onSelect} />);

    await userEvent.click(screen.getByRole('button'));

    expect(onSelect).toHaveBeenCalledWith(target);
  });
});
