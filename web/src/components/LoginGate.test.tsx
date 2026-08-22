import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { LoginGate } from './LoginGate';

/** → docs/04-frontend.md 9 節「未ログイン時は LoginGate のみを出す」 */

describe('LoginGate', () => {
  it('撮影に使ったものと同じアカウントでログインするよう促す', () => {
    render(<LoginGate onSignIn={vi.fn()} />);

    expect(screen.getByText(/同じ Google アカウント/)).toBeInTheDocument();
  });

  it('ログインボタンを押すと、ログインを要求する', async () => {
    const onSignIn = vi.fn();
    render(<LoginGate onSignIn={onSignIn} />);

    await userEvent.click(screen.getByRole('button', { name: /ログイン/ }));

    expect(onSignIn).toHaveBeenCalledTimes(1);
  });
});
