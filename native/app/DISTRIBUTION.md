# 配布手順（実機検証用）

対象: 定点撮影POC Android アプリ / issue #23
作成日: 2026-08-21 / Flutter 3.47.1 (Dart 3.13.1)

## 0. 先に読んでほしい制約

**GCP / OAuth クライアントID（#11）が未完了のあいだ、配布したAPKでは Google サインインと Drive 送信が失敗します。**

| 機能 | #11 未完了時 |
|---|---|
| カメラ権限リクエスト・プレビュー | 動く |
| 撮影・端末内への保存（`CAM001_yyyyMMdd_HHmmss.jpg`） | 動く |
| 撮影間隔の切替（1/5/10/30分）・開始/停止 | 動く |
| 撮影枚数などのステータス表示 | 動く |
| **Googleサインイン** | **失敗**（ステータスパネルの「直近エラー」に表示） |
| **Driveへの送信** | **サインイン必須のため到達しない** |

サインインしないと「開始」ボタンは押せません（`AGENTS.md` 5.2-2）。したがって **#11 が終わるまで、このAPKで確認できるのはプレビューまで**です。撮影の連続動作まで見るには #11 の完了が必要です。

## 1. 配布物

`flutter build` の出力は `build/` 配下にあり `flutter clean` で消えるため、配布用のコピーを `native/app/dist/` に置いています。**このディレクトリは `.gitignore` 済みで、APK はリポジトリにコミットしません。**

| ファイル | サイズ | 用途 |
|---|---|---|
| `dist/farmcamera-release-arm64-v8a.apk` | 17.5 MB | **既定。** 現代の Android 実機はほぼこれ |
| `dist/farmcamera-release-armeabi-v7a.apk` | 14.9 MB | 32bit の古い端末向け |
| `dist/farmcamera-debug-universal.apk` | 171 MB | 障害切り分け用。全ABI同梱・最適化なし |

release と debug の使い分け:

- **release** — 軽量で動作が速い。1分間隔の連続動作デモはこちら
- **debug** — `adb logcat` に Dart 側のログが出る。挙動がおかしいときだけ使う。171MB あるので転送は USB 経由推奨

`AGENTS.md` 1節の完了条件は「デバッグAPKで確認」と書かれているため、**最終確認は debug APK でも行う**こと。

### 端末のABIを確認する

```bash
adb shell getprop ro.product.cpu.abi
# arm64-v8a と出れば arm64 版を入れる
```

## 2. インストール

### 2-1. USB 経由（推奨・#12 の環境が前提）

端末側で「開発者向けオプション」→「USBデバッグ」を有効にしておきます。

```bash
# 接続確認。unauthorized と出たら端末側のダイアログで許可する
adb devices

# インストール（-r は再インストール）
adb install -r native/app/dist/farmcamera-release-arm64-v8a.apk
```

`adb` は `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` にあります。

署名が違う版を上書きしようとすると `INSTALL_FAILED_UPDATE_INCOMPATIBLE` になります。その場合は先に `adb uninstall com.kotonara.farmcamera` してください。

### 2-2. ファイルを直接渡す場合

APK をそのまま端末に送る場合、端末側で **「提供元不明のアプリ」/「不明なアプリのインストール」の許可**が必要です（Android 8 以降は、APKを開くアプリ単位で許可を出します）。

### 2-3. 開発中は `flutter run` が速い

APKを作らず直接流し込めます。ホットリロードとログがそのまま使えるため、実機で調整するあいだはこちらが効率的です。

```bash
flutter devices
flutter run --release   # ログを見たいときは --debug
```

## 3. 初回起動時にやること

1. **カメラ権限のダイアログで「許可」する**
   拒否するとプレビュー位置にエラーと「再試行」ボタンが出ます（`PhotoSource` の初期化失敗）。端末の設定から権限を付け直してから「再試行」を押してください
2. Googleサインイン（#11 完了後）
   OAuth同意画面の**テストユーザーに登録済みのアカウント**でサインインしてください。未登録のアカウントは同意画面で弾かれます
3. 撮影間隔を選び「開始」
   開始した瞬間に1枚目を撮ります（間隔ぶん待ちません）

## 4. 署名について（#24 で追跡）

`android/app/build.gradle.kts` は Flutter の初期テンプレートのままで、**release ビルドも debug キーで署名**しています。実際に配布APKへ載っている証明書を確認済みです。

```
$ apksigner verify --print-certs dist/farmcamera-release-arm64-v8a.apk
Signer #1 certificate DN: C=US, O=Android, CN=Android Debug
Signer #1 certificate SHA-1 digest: df39d502fa77c2c0aaecb92f26892ec8c41a2ebb
```

これは #11 で GCP に登録する SHA-1 と同じ値です。

| 項目 | 値 |
|---|---|
| パッケージ名 | `com.kotonara.farmcamera` |
| SHA-1 | `DF:39:D5:02:FA:77:C2:C0:AA:EC:B9:2F:26:89:2E:C8:C4:1A:2E:BB` |

**重要: ビルドマシンを固定してください。** `~/.android/debug.keystore` は開発マシンごとに自動生成される別物です。別のマシンでビルドすると SHA-1 が変わり、GCP の登録と一致しなくなって **Googleサインインだけが失敗**します。カメラも撮影も動くため原因が見えにくい壊れ方をします。

keystore とパスワードは、このリポジトリが Public であるため**絶対にコミットしません**（`risk-assessment.md` の方針）。

## 5. 配布チャネル

このリポジトリは Public です。GitHub Releases に APK を上げると**誰でもダウンロードできる**状態になります。
当日の受け渡しは USB 直挿しで足りるため、**既定はローカル配布（`dist/` から `adb install`）**とし、公開配布が必要になった時点で判断します。

## 6. 再ビルド手順

```bash
cd native/app
flutter build apk --release --split-per-abi   # 配布用
flutter build apk --debug                     # 切り分け用

cp build/app/outputs/flutter-apk/app-arm64-v8a-release.apk   dist/farmcamera-release-arm64-v8a.apk
cp build/app/outputs/flutter-apk/app-armeabi-v7a-release.apk dist/farmcamera-release-armeabi-v7a.apk
cp build/app/outputs/flutter-apk/app-debug.apk               dist/farmcamera-debug-universal.apk
```

所要時間の目安は `BUILD_NOTES.md` 参照（初回のみ大きく、2回目以降は数十秒）。

## 7. 実機で確認したいこと

実機検証の観点は #8 に集約しています。この手順書の範囲は「端末に入るところまで」です。
なお **画面レイアウトの縦横対応は未実装（#22）**なので、横向きにするとレイアウトが崩れる可能性があります。実機で崩れ方を確認できたら #22 に記録してください。
