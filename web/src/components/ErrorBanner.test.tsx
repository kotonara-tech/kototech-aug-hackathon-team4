import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ErrorBanner } from './ErrorBanner';

describe('ErrorBanner', () => {
  it('メッセージが無いときは何も描画しない', () => {
    const { container } = render(<ErrorBanner message={null} />);

    expect(container).toBeEmptyDOMElement();
  });

  it('メッセージを読み上げ対象として出す', () => {
    render(<ErrorBanner message="Drive API のレート制限に達しました。" />);

    expect(screen.getByRole('status')).toHaveTextContent('Drive API のレート制限に達しました。');
  });
});
