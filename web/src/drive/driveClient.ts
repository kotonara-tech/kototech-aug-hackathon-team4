/**
 * Google Drive API v3 を fetch で直接叩く。サーバーもプロキシも挟まない。
 * → docs/02-google-drive.md 6 節 / docs/04-frontend.md 6 節
 *
 * ここで固定しているのは、外すと「エラーも出ずに 0 件が返る」パラメータ。
 */

import { toPhoto, type DriveFile, type Photo } from './photo';

const FILES_ENDPOINT = 'https://www.googleapis.com/drive/v3/files';

/** Q8 暫定: 最新 100 件の 1 ページだけ。ページングはしない */
export const LIST_PAGE_SIZE = 100;

/**
 * fields を省略すると id と name しか返らない。
 * createdTime も imageMediaMetadata も落ちて、時刻がすべて不明になる。
 */
const LIST_FIELDS = 'nextPageToken,files(id,name,mimeType,size,createdTime,imageMediaMetadata/time)';

/** 再ログインへ落とすべきエラー。トークンは更新できないので、黙って再試行しない。 */
export class DriveAuthError extends Error {
  constructor(message = 'ログインの有効期限が切れました。もう一度ログインしてください。') {
    super(message);
    this.name = 'DriveAuthError';
  }
}

/** ポーリング間隔を延ばして継続すべきエラー。止めない。 */
export class DriveRateLimitError extends Error {
  constructor(message = 'Drive API のレート制限に達しました。') {
    super(message);
    this.name = 'DriveRateLimitError';
  }
}

/** 個別ファイルが取れなかっただけ。一覧全体を落とさないこと。 */
export class PhotoNotFoundError extends Error {
  constructor(public readonly fileId: string) {
    super(`画像を取得できませんでした (${fileId})`);
    this.name = 'PhotoNotFoundError';
  }
}

/** それ以外。次のポーリングで自然に回復させる。即時リトライしない。 */
export class DriveError extends Error {
  constructor(public readonly status: number) {
    super(`Drive API がエラーを返しました (HTTP ${status})`);
    this.name = 'DriveError';
  }
}

const RATE_LIMIT_REASONS = new Set([
  'rateLimitExceeded',
  'userRateLimitExceeded',
  'dailyLimitExceeded',
]);

function authHeaders(accessToken: string): Record<string, string> {
  return { Authorization: `Bearer ${accessToken}` };
}

/**
 * 一覧取得のリクエスト URL。
 * spaces=appDataFolder が抜けるとマイドライブを見に行き、静かに 0 件が返る。
 */
export function buildListRequest(): { url: string } {
  const params = new URLSearchParams({
    spaces: 'appDataFolder',
    q: "mimeType contains 'image/' and trashed = false",
    fields: LIST_FIELDS,
    orderBy: 'createdTime desc',
    pageSize: String(LIST_PAGE_SIZE),
  });

  return { url: `${FILES_ENDPOINT}?${params.toString()}` };
}

/**
 * 実体取得のリクエスト URL。
 * alt=media が無いとメタデータの JSON が返ってくる。
 */
export function buildPhotoRequest(fileId: string): { url: string } {
  return { url: `${FILES_ENDPOINT}/${encodeURIComponent(fileId)}?alt=media` };
}

async function readReason(response: Response): Promise<string> {
  try {
    const body = (await response.clone().json()) as {
      error?: { errors?: Array<{ reason?: string }> };
    };
    return body.error?.errors?.[0]?.reason ?? '';
  } catch {
    return '';
  }
}

/** HTTP ステータスを、呼び出し側が「止めるか続けるか」を判断できる型に写す。 */
async function toDriveError(response: Response): Promise<Error> {
  if (response.status === 429) return new DriveRateLimitError();

  if (response.status === 403 && RATE_LIMIT_REASONS.has(await readReason(response))) {
    return new DriveRateLimitError();
  }

  // 401、および 403 のうちスコープ不足。どちらも再ログインでしか直らない
  if (response.status === 401 || response.status === 403) return new DriveAuthError();

  return new DriveError(response.status);
}

/**
 * AppData 領域の画像を新しい順に取る。
 * 撮影時刻を決められなかった写真は、その 1 枚だけ落として残りを返す。
 */
export async function listPhotos(
  accessToken: string,
  fetchImpl: typeof fetch = fetch,
): Promise<Photo[]> {
  const { url } = buildListRequest();
  const response = await fetchImpl(url, { headers: authHeaders(accessToken) });

  if (!response.ok) throw await toDriveError(response);

  const body = (await response.json()) as { files?: DriveFile[] };
  return (body.files ?? [])
    .map(toPhoto)
    .filter((photo): photo is Photo => photo !== null);
}

/**
 * 画像の実体。
 * <img src="https://www.googleapis.com/..."> では表示できない（Authorization が要る）ので、
 * ここで Blob を取って createObjectURL に渡す。revokeObjectURL は呼び出し側の責任。
 */
export async function fetchPhotoBlob(
  fileId: string,
  accessToken: string,
  fetchImpl: typeof fetch = fetch,
): Promise<Blob> {
  const { url } = buildPhotoRequest(fileId);
  const response = await fetchImpl(url, { headers: authHeaders(accessToken) });

  if (response.status === 404) throw new PhotoNotFoundError(fileId);
  if (!response.ok) throw await toDriveError(response);

  return response.blob();
}
