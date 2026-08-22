# 05. 実装計画（Kotlin 移行版 / Native 担当）

`01`〜`04` が「**何を**作るか」の正本。この文書は「**どの順で**作るか」を決める。
未決事項は [00-openquestion.md](00-openquestion.md) が正で、ここでは勝手に決めない。

対象は Android（Kotlin）。Web は [04-frontend.md](04-frontend.md) の担当に委ねる。

---

## 1. 何が変わったか

| | 旧（`legacy/native-app`） | 新 |
|---|---|---|
| 言語 | Flutter 3.47 / Dart | **Kotlin** |
| 常駐 | 前面表示 + `wakelock_plus` | **Foreground Service**（`foregroundServiceType="camera"`） |
| 保存先 | マイドライブ直下 `FarmCameraPOC` フォルダ | **`appDataFolder`（フラット配置）** |
| スコープ | `drive.file` | **`drive.appdata`** |
| 認証 | `google_sign_in` | **Credential Manager + `AuthorizationClient`** |
| UI | プレビュー + 間隔セレクタ + 履歴一覧 | **ステータス表示のみの1画面。プレビューなし** |

Flutter をやめる理由は [03 §2](03-native.md) のとおり。`WorkManager` の最小間隔15分では
数分間隔を原理的に満たせず、Foreground Service が要る。それを Flutter からやると結局
Kotlin プラグインを書くことになる。**この判断に異論はない。**

---

## 2. ★ 着手順序に循環がある

[01 §8](01-overview.md) と [CLAUDE.md §8](../CLAUDE.md) は
「**★ Q1 の疎通検証が通るまで Native と Web の実装に人手を割かないこと**」と定めている。

一方 [02 §7](02-google-drive.md) の検証手順は次のようになっている。

| # | 操作 |
|---|---|
| 1 | Playground で `files.list?spaces=appDataFolder` → 空が返る |
| 2 | **Android アプリから 1 枚アップロード** |
| 3 | Playground で再取得 → 1 件見える |
| 4 | `imageMediaMetadata.time` を確認（Q6） |
| 5 | Web SPA から同一アカウントで一覧取得 → 同じファイルが見える |

**手順2に「Android アプリ」が要る。** つまり「検証が通るまで実装しない」を字義どおり守ると
検証を開始できない。

Playground で代替もできない。Playground は**クライアントシークレットを持つクライアント**を
要求するが、**Android 用 OAuth クライアントにシークレットは存在しない**。
Web 用クライアントで書き込んで Web 用クライアントで読んでも、
「同一 GCP プロジェクトの**別クライアント間**で共有されるか」という Q1 の核心は検証できない。

### 解決: 検証専用の最小スパイクを先に作る

**M2（疎通スパイク）を「実装」ではなく「Q1 の検証装置」と位置づける。**

- サインインして `drive.appdata` のトークンを取り、**1枚撮って送るだけ**のアプリ
- 画面はボタン1つとログ表示のみ。Service もスケジューラも持たない
- Q1 が倒れても捨てるのはこのスパイクだけで、M1（純ロジック）は生き残る

**この順序であれば「全部無駄になる」リスクは M2 の範囲に閉じる。**

---

## 3. マイルストーン

```
M0 環境      Gradle が通ること              ← 依存なし。今すぐ着手できる
   │
M1 契約層    純ロジックを TDD で固める        ← 実機・GCP 不要。Q1 が倒れても大半が残る
   │
   ├─ 人間: GCP 設定（#11 を drive.appdata へ改訂）＋ デモ用アカウント・実機（Q11）
   │
M2 疎通スパイク  ★ Q1 / Q6 の検証            ← ここで設計の成否が決まる
   │
M3 常駐化    Foreground Service
   │
M4 画面      Compose のステータス1画面
   │
M5 実機受入  数分間隔での連続動作
```

### M0. 環境スパイク

**目的**: ビルドが通らないという理由で当日詰まらないようにする。

| 項目 | 内容 |
|---|---|
| 成果物 | `native/android/` に空の Kotlin Android プロジェクト |
| 依存 | なし |
| 完了条件 | `gradlew test` と `gradlew assembleDebug` が緑。所要時間を記録する |

**懸念**: 開発機の `JAVA_HOME` は Android Studio 同梱 JBR で **JDK 25**。
AGP / Gradle が JDK 25 を受け付けない可能性がある。受け付けなければ
JDK 17 か 21 を別途入れるか、Gradle の toolchain で固定する。
**先に潰す。** 旧実装では `flutter analyze` の初回48分をここで測って当日の想定を立てた
（`legacy/native-app/BUILD_NOTES.md`）。同じことをやる。

`gradle` は PATH に無いので wrapper を使う。wrapper jar の入手も M0 の範囲。

### M1. 契約層（純ロジック / TDD）

**目的**: 実機も GCP も要らず、**Q1 が倒れても生き残る**部分を先に固める。

[03 §9](03-native.md) の「実機なしでテストできるもの」がそのままスコープ。

| 対象 | 保証すること |
|---|---|
| `PhotoNaming` | ゼロ埋め / 秒精度 / `CAM001_yyyyMMdd_HHmmss.jpg` |
| `CaptureScheduler` | 開始時に即1回発火 / 間隔どおり / 停止後は発火しない / 多重起動を拒否 |
| `CaptureCoordinator` | **送信が撮影間隔より長くても撮影がスキップされない** |
| `CaptureCoordinator` | 送信失敗で撮影を止めない / エラーが状態に載る |
| `AppDataUploader` | ボディに `"parents":["appDataFolder"]` が入る |
| `AppDataUploader` | URL が `/upload/drive/v3/files?uploadType=multipart` |
| `AppDataUploader` | `Authorization: Bearer` が付く / 401・403・5xx が `Result.failure` |
| `CaptureState` | 撮影・送信・失敗で状態が遷移する |

`AppDataUploader` は MockWebServer。`CaptureScheduler` は `kotlinx-coroutines-test` の仮想時間。

**旧 Flutter 実装から持ってくるもの**（コードではなく**保証したい内容とテストケース**）:

| 旧 | 新 | 中身 |
|---|---|---|
| `capture_naming.dart` + テスト3件 | `PhotoNaming` | 命名規約は [02 §4.2](02-google-drive.md) と一致済み。そのまま移せる |
| `capture_scheduler.dart` + テスト5件 | `CaptureScheduler` | 「開始時に即1回発火」「多重 start を拒否」はテストごと移す |
| `capture_session.dart` + テスト | `CaptureCoordinator` | **#17 の退行（送信完了までフラグを保持して撮影がスキップされた）とその再発防止テスト。** [03 §6](03-native.md) が名指しで警告している当のもの |
| `drive_uploader.dart` + テスト16件 | `AppDataUploader` | multipart の組み立て方と 4xx/5xx の扱い。**ただし宛先が変わるのでフォルダ ID 解決は捨てる**（AppData はフラット配置で解決そのものが消える） |

**捨てるもの**: `main.dart`（UI が変わる）、`photo_source.dart`（camera → CameraX）、
`auth_gateway.dart`（google_sign_in → Credential Manager）、
`photo_storage.dart`（後述 §5-1）。

### M2. 疎通スパイク ★

**目的**: [02 §7](02-google-drive.md) の手順2を実行できるようにし、**Q1 と Q6 を潰す。**

| 項目 | 内容 |
|---|---|
| 成果物 | ボタン1つのアプリ。押すと「サインイン → `drive.appdata` 認可 → 1枚撮影 → アップロード → ファイル ID を画面に出す」 |
| 依存 | M1 の `AppDataUploader` / `PhotoNaming`、**人間の GCP 設定**、**デモ用アカウントと実機（Q11）** |
| 完了条件 | 02 §7 の手順1〜4が通る。手順5は Web 担当と合同 |

**ここが倒れたら設計をやり直す。** 倒れ方は「エラーが出ず 0 件」なので、
先に Playground を用意しておくこと（02 §7 の「準備」）。

### M3. 常駐化

Foreground Service（`foregroundServiceType="camera"`）+ 常駐通知。
`POST_NOTIFICATIONS`（API 33+）と `FOREGROUND_SERVICE_CAMERA`（API 34+）の実行時権限。
`PhotoSource` は**プレビューなしで撮影**する（サービスは UI を持たない）。

**EXIF を落とさないこと。** JPEG を再エンコードしない。Q6 の結果がここに効く。

### M4. 画面

[03 §8](03-native.md) のとおり。アカウント / 撮影間隔（表示のみ）/ 開始・停止 /
撮影枚数・送信枚数・最終送信・直近エラー。**プレビューは出さない。**

`MainActivity` は `StateFlow` を購読して描画するだけに保つ。
旧実装は `main.dart` にロジックが集中してウィジェットテストが書きづらくなった。同じことをしない。

### M5. 実機受入

- 数分間隔で連続撮影・連続アップロードされること
- 画面を消しても続くこと（Foreground Service の存在意義）
- **デモ端末の「バッテリー最適化」を対象外に設定する**（Q10。運用で回避すると決めてある）
- Web 側と同一アカウントで、Web に写真が並ぶこと

---

## 4. 人間側にしかできないこと（私は進められない）

| # | 内容 | 対応する未決 |
|---|---|---|
| 1 | GCP プロジェクトの作成、Drive API 有効化、同意画面 | — |
| 2 | **Android 用と Web 用の OAuth クライアントを同じプロジェクトに作る** | 02 §2。**間違えると永久に 0 件** |
| 3 | スコープを両側とも `drive.appdata` にする | 02 §3 |
| 4 | デモ用 Google アカウントのメールアドレス確定・テストユーザー登録 | Q11（Open / ブロッカー） |
| 5 | デモ用 Android 実機の機種と OS バージョン確定 | Q11（Open / ブロッカー） |
| 6 | 撮影間隔の値を決める | Q3（Open / ブロッカー）。暫定5分で進める |
| ~~7~~ | ~~旧実装・旧ドキュメントの処遇~~ | **Q2 は Close。`legacy/` へ退避済み** |
| 8 | OAuth Playground の準備（Web 用クライアントのリダイレクト URI 承認） | 02 §7 |

既存 issue **#11** は旧方針（`drive.file` / マイドライブ）のまま。**2・3 を含む形へ改訂が要る。**

---

## 5. 設計書に確認したいこと

**勝手に決めない**（`00` の運用ルール）ため、判断を仰ぐものをここに挙げる。

### 5-1. 端末に残す JPEG の扱い → **[Q16](00-openquestion.md) として起票済み**

[03 §6](03-native.md)「一時ディレクトリへ保存」と [03 §11](03-native.md)「送信失敗時に
削除しない」の組み合わせが、**観測可能な効果を持たない**という指摘。
読む主体（自動再送・一覧表示）がどこにも無く、`cacheDir` は OS がいつでも消せる。

**議論を継続する項目として [Q16](00-openquestion.md) に移した。** A / B / C の3案も同所。

M1 では**結論を先取りしない。** `CaptureCoordinator` の保存責務を差し替え可能に
しておき（§5-3）、どの案に決まっても変更が1箇所で済む形にする。

> 旧実装では案 C（永続領域＋件数上限）を PR #39 として実装済み。
> **新方針では過剰**と判断し、取り下げる（§6）。

### 5-2. Q5（Drive 側の削除）と 5-1 は別問題

Q5 は `appDataFolder` に溜まる画像の話で、暫定「何もしない」。
5-1 は端末内のファイルの話。**混同しないこと。** 5-1 は Q5 の暫定決着に影響されない。

### 5-3. `PhotoSource.capture()` の戻り値

[03 §7](03-native.md) は `suspend fun capture(): Result<ByteArray>` としているが、
[03 §6](03-native.md) のフローは「3. 端末の一時ディレクトリへ保存」を含む。
**保存の責務が `PhotoSource` と `CaptureCoordinator` のどちらにあるか**が読み取れない。

**提案**: `PhotoSource` はバイト列を返すだけにし、保存は `CaptureCoordinator` が行う。
そうすれば `PhotoSource` の CameraX 実装が薄くなり（＝テストできない範囲が狭くなり）、
保存方針（5-1）を実機なしでテストできる。

---

## 6. 既存の issue / PR の処遇

`legacy/native-app`（Flutter）を前提にした issue が残っている。
**Q2 は Close（`legacy/` へ退避）したので、以下を実行に移す。**

| # | 件名 | 提案 |
|---|---|---|
| **39**(PR) | 写真をアプリ専用領域に保存し上限超過ぶんを削除 | **取り下げ**。5-1 案 C に相当し、新方針では過剰 |
| 32 | 同上の issue | クローズ。決着は [Q16](00-openquestion.md) が引き継ぐ |
| 34 | 送信失敗時の方針を明記し案内を利用者向けにする | **内容は生きる。** [03 §11](03-native.md) に相当。Kotlin 版の issue として立て直す |
| 35 | フォルダ作成の競合を排他化 | **クローズ。** AppData はフラット配置でフォルダ解決が消えるため、問題ごと消滅 |
| 22 | レスポンシブ対応 | クローズ。画面が1画面のステータス表示に変わる |
| 7 | 前面復帰時のカメラセッション再初期化 | クローズ。Foreground Service 化で前提が変わる（Q15 が引き継ぐ） |
| 8 | デバッグ APK ビルドと動作確認 | **M5 として生かす。** 本文を Kotlin / Gradle 前提に改訂 |
| 11 | GCP: Drive API 有効化と OAuth クライアント発行 | **生かす。** `drive.appdata` と「2クライアントを同一プロジェクトに」を追記（§4-2, 4-3） |
| 12 | 実機/エミュレータ接続と環境準備 | 生かす。Q11 と統合 |
| 21 | Web 連携コントラクトの確定 | **クローズ。** [02-google-drive.md](02-google-drive.md) が契約そのものとして確定済み |
| 9 / 10 / 19 | 認証追加 / トーチ / 自動再送キュー | 19 は [01 §5.5](01-overview.md) で明確に不採用。9・10 は future のまま |

**新規に立てる issue**: M0 環境スパイク / M1 の各コンポーネント / M2 疎通スパイク /
M3 常駐化 / M4 画面 / 5-1 の決着。

---

## 7. リスク

| リスク | 影響 | 対処 |
|---|---|---|
| **Q1 が倒れる**（AppData が別クライアント間で共有されない） | 設計ごとやり直し | M2 を最優先。M1 は Q1 非依存に保つ |
| JDK 25 と AGP / Gradle の非互換 | 着手そのものが止まる | M0 で先に潰す |
| 署名 SHA-1 がビルド機依存 | **サインインだけが失敗し、原因に辿り着けない** | ビルド機を固定（Q12 暫定）。SHA-1 を issue #11 に記録 |
| メーカー独自の省電力機能がサービスを殺す | 撮影が静かに止まる | 端末のバッテリー最適化を対象外に（Q10）。M5 のチェック項目に入れる |
| EXIF が落ちる / 返らない | Web の時刻表示が到着時刻になる | 再エンコードしない。Q6 を M2 で確認 |
| `parents` に `appDataFolder` を付け忘れる | **マイドライブを汚した上で Web から見えない** | M1 のテストで固定（[02 §10](02-google-drive.md) 地雷3） |

---

## 8. 私の担当分の着手順（確定）

1. **M0** を始める。Q2 / Q3 / Q11 に依存しない
2. 並行して **M1** を TDD で進める。Q1 が倒れても残る
3. 人間側の §4 が揃い次第 **M2** を作り、02 §7 の検証を実行する
4. 検証が通ってから M3 → M4 → M5

**M2 より先に M3・M4 を作らない。** Q1 が倒れたときの損失を最小にするため。
