import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { LatestPhoto } from './LatestPhoto';
import type { Photo } from '../drive/photo';

/**
 * → docs/04-frontend.md 9 節
 * 「撮影時刻として表示する（アップロード時刻ではない）」
 * 「EXIF が無くフォールバックした場合は、その旨が分かる表示にする」
 */

const photo = (over: Partial<Photo> = {}): Photo => ({
  id: 'p1',
  name: 'CAM001_20260822_063000.jpg',
  mimeType: 'image/jpeg',
  capturedAt: new Date('2026-08-21T21:30:00.000Z'), // JST 2026-08-22 06:30:00
  capturedAtSource: 'exif',
  objectUrl: 'blob:fake',
  ...over,
});

describe('LatestPhoto', () => {
  it('撮影時刻を JST で表示する', () => {
    render(<LatestPhoto photo={photo()} />);

    expect(screen.getByText('2026-08-22 06:30:00')).toBeInTheDocument();
  });

  it('EXIF から時刻を取れた場合は、フォールバックの警告を出さない', () => {
    render(<LatestPhoto photo={photo()} />);

    expect(screen.queryByText(/アップロード時刻/)).not.toBeInTheDocument();
  });

  it('EXIF が無くフォールバックした場合は、その旨が分かる表示にする', () => {
    render(<LatestPhoto photo={photo({ capturedAtSource: 'createdTime' })} />);

    expect(screen.getByText(/アップロード時刻/)).toBeInTheDocument();
  });

  it('ファイル名を表示する（デバッグで人間が読むため）', () => {
    render(<LatestPhoto photo={photo()} />);

    expect(screen.getByText('CAM001_20260822_063000.jpg')).toBeInTheDocument();
  });

  it('実体をまだ取得できていない場合でも、撮影時刻は表示して画面を落とさない', () => {
    const { objectUrl: _pending, ...withoutUrl } = photo();
    render(<LatestPhoto photo={withoutUrl as Photo} />);

    expect(screen.getByText('2026-08-22 06:30:00')).toBeInTheDocument();
  });
});
