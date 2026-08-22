import { describe, expect, it } from 'vitest';
import { daylight, mixHex, stableNoise } from './scene';

/**
 * 合成画像を描くための純粋関数だけをここでテストする。
 * canvas への描画そのものは jsdom で動かないので対象外。
 * 代わりに「色が正しく混ざるか」「同じ場所に同じ株が立つか」を固定する。
 */

describe('mixHex', () => {
  it('t=0 なら左の色をそのまま返す', () => {
    expect(mixHex('#102030', '#ffffff', 0)).toBe('rgb(16,32,48)');
  });

  it('t=1 なら右の色をそのまま返す', () => {
    expect(mixHex('#102030', '#ffffff', 1)).toBe('rgb(255,255,255)');
  });

  it('中間では成分ごとに線形補間する', () => {
    expect(mixHex('#000000', '#ffffff', 0.5)).toBe('rgb(128,128,128)');
  });
});

describe('daylight', () => {
  it('夜明け（JST 5 時）は 0 になる', () => {
    expect(daylight(new Date('2026-08-21T20:00:00.000Z'))).toBe(0);
  });

  it('JST 8 時はちょうど中間になる', () => {
    expect(daylight(new Date('2026-08-21T23:00:00.000Z'))).toBeCloseTo(0.5, 5);
  });

  it('日中（JST 11 時以降）は 1 で頭打ちになる', () => {
    expect(daylight(new Date('2026-08-22T05:00:00.000Z'))).toBe(1);
  });

  it('未明（JST 2 時）でも負にならない', () => {
    expect(daylight(new Date('2026-08-21T17:00:00.000Z'))).toBe(0);
  });
});

describe('stableNoise', () => {
  it('同じ種なら毎回同じ値を返す（定点なので株の位置が動いてはいけない）', () => {
    expect(stableNoise(42)).toBe(stableNoise(42));
  });

  it('種が違えば違う値になる', () => {
    expect(stableNoise(1)).not.toBe(stableNoise(2));
  });

  it('0 以上 1 未満に収まる', () => {
    for (let seed = 0; seed < 50; seed += 1) {
      const value = stableNoise(seed);
      expect(value).toBeGreaterThanOrEqual(0);
      expect(value).toBeLessThan(1);
    }
  });
});
