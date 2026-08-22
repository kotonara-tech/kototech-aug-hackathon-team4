import { jstParts } from '../format';

/**
 * デモ用の合成画像を描く。
 *
 * Drive にも通信にも依存させないため、圃場の風景をその場で canvas に描く。
 * 定点観測なので「株の位置は毎回同じ」「時刻とともに光だけが変わる」ことが要件。
 *
 * 純粋関数（mixHex / daylight / stableNoise）だけをテストしている。
 * canvas への描画は jsdom で動かないため、テスト対象外。
 */

export const SCENE_WIDTH = 800;
export const SCENE_HEIGHT = 450;

const CAMERA_LABEL = 'CAM001';

function clamp(value: number, min: number, max: number): number {
  return value < min ? min : value > max ? max : value;
}

function channels(hex: string): [number, number, number] {
  return [
    parseInt(hex.slice(1, 3), 16),
    parseInt(hex.slice(3, 5), 16),
    parseInt(hex.slice(5, 7), 16),
  ];
}

/** 2 色を成分ごとに線形補間して rgb() 文字列にする。 */
export function mixHex(from: string, to: string, t: number): string {
  const a = channels(from);
  const b = channels(to);
  const at = (i: number) => Math.round(a[i] + (b[i] - a[i]) * t);
  return `rgb(${at(0)},${at(1)},${at(2)})`;
}

/**
 * 日射の強さ。JST 5 時を 0、11 時を 1 として頭打ちにする。
 * デモのシリーズは早朝 3 時間ぶんなので、この範囲だけを表現できれば足りる。
 */
export function daylight(at: Date): number {
  const p = jstParts(at);
  const hours = Number(p.hour) + Number(p.minute) / 60;
  return clamp((hours - 5) / 6, 0, 1);
}

/**
 * 種から決まる 0〜1 の値。乱数ではない。
 * 定点である以上、株の位置が撮影ごとに動いてはいけないため。
 */
export function stableNoise(seed: number): number {
  const x = Math.sin(seed * 12.9898) * 43758.5453;
  return x - Math.floor(x);
}

function drawPlant(
  g: CanvasRenderingContext2D,
  x: number,
  y: number,
  scale: number,
  day: number,
  sway: number,
): void {
  const dark = mixHex('#1d3320', '#4c8f3a', day);
  const light = mixHex('#2b4a2b', '#8ac95c', day);

  g.save();
  g.translate(x, y);
  g.scale(scale, scale);

  g.strokeStyle = dark;
  g.lineWidth = 2.6;
  g.lineCap = 'round';
  g.beginPath();
  g.moveTo(0, 0);
  g.quadraticCurveTo(sway * 0.5, -17, sway, -33);
  g.stroke();

  for (let k = 0; k < 5; k += 1) {
    const angle = -Math.PI / 2 + (k - 2) * 0.58 + sway * 0.02;
    const length = 21 - Math.abs(k - 2) * 3.2;
    g.fillStyle = k % 2 ? light : dark;
    g.beginPath();
    g.ellipse(
      Math.cos(angle) * length * 0.5,
      -19 + Math.sin(angle) * length * 0.5,
      length * 0.55,
      5.4,
      angle,
      0,
      Math.PI * 2,
    );
    g.fill();
  }

  g.restore();
}

/** 1 枚ぶんの風景を描く。seq は撮影の連番（雲の流れと生育に効く）。 */
export function drawScene(g: CanvasRenderingContext2D, seq: number, at: Date): void {
  const day = daylight(at);
  const horizon = SCENE_HEIGHT * 0.62;

  // 空
  const sky = g.createLinearGradient(0, 0, 0, horizon);
  sky.addColorStop(0, mixHex('#16233f', '#3f86cf', day));
  sky.addColorStop(0.55, mixHex('#6d4f6b', '#96c9ee', day));
  sky.addColorStop(1, mixHex('#e0895a', '#dceffa', day));
  g.fillStyle = sky;
  g.fillRect(0, 0, SCENE_WIDTH, horizon);

  // 太陽
  const sunX = SCENE_WIDTH * (0.16 + day * 0.62);
  const sunY = horizon - day * horizon * 0.72;
  const glow = g.createRadialGradient(sunX, sunY, 2, sunX, sunY, 120);
  glow.addColorStop(0, `rgba(255,240,200,${0.55 + day * 0.35})`);
  glow.addColorStop(1, 'rgba(255,220,160,0)');
  g.fillStyle = glow;
  g.beginPath();
  g.arc(sunX, sunY, 120, 0, Math.PI * 2);
  g.fill();
  g.fillStyle = mixHex('#ffbe7a', '#fffbe8', day);
  g.beginPath();
  g.arc(sunX, sunY, 15, 0, Math.PI * 2);
  g.fill();

  // 雲。定点なので、ゆっくりとだけ流す
  g.globalAlpha = 0.3 + day * 0.22;
  for (let c = 0; c < 5; c += 1) {
    const cx = ((stableNoise(c * 7 + 3) * SCENE_WIDTH) + seq * 1.1) % (SCENE_WIDTH + 200) - 100;
    const cy = 24 + stableNoise(c * 13 + 5) * horizon * 0.42;
    const cw = 46 + stableNoise(c * 3 + 11) * 62;
    g.fillStyle = mixHex('#8c93a8', '#ffffff', day);
    for (let p = 0; p < 4; p += 1) {
      g.beginPath();
      g.ellipse(cx + p * cw * 0.42, cy + stableNoise(c * 31 + p) * 7, cw * 0.5, cw * 0.22, 0, 0, Math.PI * 2);
      g.fill();
    }
  }
  g.globalAlpha = 1;

  // 遠景の山
  g.fillStyle = mixHex('#1a2430', '#7d94a6', day);
  g.beginPath();
  g.moveTo(0, horizon);
  for (let mx = 0; mx <= SCENE_WIDTH; mx += 40) {
    g.lineTo(mx, horizon - 26 - stableNoise(mx * 0.11) * 34);
  }
  g.lineTo(SCENE_WIDTH, horizon);
  g.closePath();
  g.fill();

  // 地面
  const ground = g.createLinearGradient(0, horizon, 0, SCENE_HEIGHT);
  ground.addColorStop(0, mixHex('#2c3324', '#6f7d43', day));
  ground.addColorStop(1, mixHex('#1d1913', '#4b3d29', day));
  g.fillStyle = ground;
  g.fillRect(0, horizon, SCENE_WIDTH, SCENE_HEIGHT - horizon);

  // 畝
  g.strokeStyle = `rgba(0,0,0,${0.26 - day * 0.1})`;
  g.lineWidth = 1.4;
  for (let u = -3; u <= 3; u += 1) {
    g.beginPath();
    g.moveTo(SCENE_WIDTH / 2 + u * 22, horizon + 2);
    g.lineTo(SCENE_WIDTH / 2 + u * 240, SCENE_HEIGHT);
    g.stroke();
  }

  // 作物。手前ほど大きく、連番とともにわずかに伸びる
  const growth = seq * 0.0016;
  for (let row = 0; row < 5; row += 1) {
    const f = (row + 1) / 5;
    const rowY = horizon + Math.pow(f, 1.7) * (SCENE_HEIGHT - horizon) * 0.97;
    const scale = (0.3 + Math.pow(f, 1.5) * 1.25) * (1 + growth);
    const count = 12 - row * 2;
    for (let i = 0; i < count; i += 1) {
      const x = ((i + 0.5) / count) * SCENE_WIDTH + (stableNoise(row * 100 + i) - 0.5) * 26;
      const sway = Math.sin(seq * 0.6 + row + i * 0.4) * 3.2;
      drawPlant(g, x, rowY, scale, day, sway);
    }
  }

  // 周辺減光
  const vignette = g.createRadialGradient(
    SCENE_WIDTH / 2, SCENE_HEIGHT / 2, SCENE_HEIGHT * 0.35,
    SCENE_WIDTH / 2, SCENE_HEIGHT / 2, SCENE_HEIGHT * 0.92,
  );
  vignette.addColorStop(0, 'rgba(0,0,0,0)');
  vignette.addColorStop(1, 'rgba(0,0,0,0.34)');
  g.fillStyle = vignette;
  g.fillRect(0, 0, SCENE_WIDTH, SCENE_HEIGHT);

  // 焼き込み文字
  const p = jstParts(at);
  const stamp = `${p.year}-${p.month}-${p.day} ${p.hour}:${p.minute}:${p.second}`;
  g.font = 'bold 15px ui-monospace, Consolas, monospace';
  g.textBaseline = 'alphabetic';
  g.fillStyle = 'rgba(0,0,0,.55)';
  g.fillRect(12, 12, 74, 25);
  g.fillStyle = '#ffffff';
  g.fillText(CAMERA_LABEL, 20, 29);
  const width = g.measureText(stamp).width;
  g.fillStyle = 'rgba(0,0,0,.55)';
  g.fillRect(SCENE_WIDTH - width - 26, SCENE_HEIGHT - 37, width + 14, 25);
  g.fillStyle = '#ffffff';
  g.fillText(stamp, SCENE_WIDTH - width - 19, SCENE_HEIGHT - 20);
}

/** 1 枚ぶんの JPEG。本番の fetchPhotoBlob と同じく Blob を返す。 */
export function renderSceneBlob(seq: number, at: Date): Promise<Blob> {
  const canvas = document.createElement('canvas');
  canvas.width = SCENE_WIDTH;
  canvas.height = SCENE_HEIGHT;

  const context = canvas.getContext('2d');
  if (!context) return Promise.reject(new Error('canvas 2d コンテキストを取得できませんでした'));

  drawScene(context, seq, at);

  return new Promise((resolve, reject) => {
    canvas.toBlob(
      (blob) => (blob ? resolve(blob) : reject(new Error('画像の生成に失敗しました'))),
      'image/jpeg',
      0.78,
    );
  });
}
