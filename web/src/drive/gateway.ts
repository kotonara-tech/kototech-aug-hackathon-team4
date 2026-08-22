import { fetchPhotoBlob, listPhotos } from './driveClient';
import type { Photo } from './photo';

/**
 * 画面が Drive に求めることの全部。これ以上は要らない。
 *
 * 本番は Google Drive、デモは合成データと、この 1 つの口を差し替えるだけで入れ替わる。
 * 画面のコードを二重に持たないための境界。
 */
export interface DriveGateway {
  listPhotos: (accessToken: string) => Promise<Photo[]>;
  fetchPhotoBlob: (fileId: string, accessToken: string) => Promise<Blob>;
}

export const googleDrive: DriveGateway = {
  listPhotos: (accessToken) => listPhotos(accessToken),
  fetchPhotoBlob: (fileId, accessToken) => fetchPhotoBlob(fileId, accessToken),
};
