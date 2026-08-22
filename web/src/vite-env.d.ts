/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** OAuth クライアント ID。公開情報なのでバンドルに入ってよい */
  readonly VITE_GOOGLE_CLIENT_ID?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
