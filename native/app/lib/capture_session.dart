import 'dart:io';

import 'package:flutter/foundation.dart';

import 'capture_naming.dart';
import 'photo_record.dart';
import 'photo_storage.dart';
import 'photo_uploader.dart';
import 'wake_lock.dart';

/// 1枚撮影して保存済みファイルを返す処理。カメラ実装をここで隠蔽する。
typedef PhotoCapturer = Future<File> Function(String fileName);

/// 選択可能な撮影間隔。`AGENTS.md` 2節-4 が「1分・5分・10分・30分」と定めている。
///
/// 先頭が既定値。UI のセレクタはこのリストから構築する。
/// [CaptureSession] 側は「正の Duration」であれば受け付ける。4値への限定は
/// UI の責務とし、状態機械は不正値（ゼロ・負値）だけを弾く。
const kCaptureIntervals = <Duration>[
  Duration(minutes: 1),
  Duration(minutes: 5),
  Duration(minutes: 10),
  Duration(minutes: 30),
];

/// 定点撮影セッションの状態機械。
///
/// UI・カメラ・認証のいずれにも依存しないので、そのままユニットテストできる。
/// タイマーは保持せず、発火のたびに [captureOnce] を呼んでもらう前提にしている。
/// これは「タイマーを持たないほうがテストしやすい」ためであり、実際の周期実行は
/// UI 層（`main.dart`）が [CaptureScheduler] で駆動する。
class CaptureSession extends ChangeNotifier {
  CaptureSession({
    required this.capturer,
    required this.uploader,
    this.cameraId = 'CAM001',
    this.now = DateTime.now,
    this.wakeLock = const NoopWakeLock(),
    PhotoRecordStore? recordStore,
    this.fileStore = const NoopPhotoFileStore(),
    this.maxRecords = 100,
    Duration initialInterval = const Duration(minutes: 1),
  })  : _interval = initialInterval,
        recordStore = recordStore ?? InMemoryPhotoRecordStore();

  final PhotoCapturer capturer;
  final PhotoUploader uploader;
  final String cameraId;
  final DateTime Function() now;

  /// 撮影中の画面スリープ抑止（#6）。既定は何もしない。
  final WakeLock wakeLock;

  /// 撮影記録の保存先（#4）。既定は永続化しないインメモリ実装。
  final PhotoRecordStore recordStore;

  /// 撮影ファイルの保管（#32）。既定は何も消さない。
  final PhotoFileStore fileStore;

  /// 保持する記録の上限。超過ぶんは古い**送信済み**から捨てる。
  ///
  /// 履歴と実体ファイルで別々の上限を持たせない。ここから落ちた写真は
  /// [fileStore] が実体も消すので、「履歴に無いのにファイルだけ残る」も
  /// 「履歴に失敗と出ているのに実体が無い」も起きない。
  final int maxRecords;

  Duration _interval;
  bool _isRunning = false;
  bool _isCapturing = false;
  bool _isSignedIn = false;
  bool _disposed = false;

  int _capturedCount = 0;
  int _sentCount = 0;
  DateTime? _lastSentAt;
  String? _lastError;
  List<PhotoRecord> _records = <PhotoRecord>[];

  Duration get interval => _interval;
  bool get isRunning => _isRunning;
  int get capturedCount => _capturedCount;
  int get sentCount => _sentCount;
  DateTime? get lastSentAt => _lastSentAt;
  String? get lastError => _lastError;

  /// 撮影記録。新しいものが先頭（#4 の一覧表示用）。
  List<PhotoRecord> get records => List<PhotoRecord>.unmodifiable(_records);

  bool get isSignedIn => _isSignedIn;
  set isSignedIn(bool value) {
    if (_isSignedIn == value) return;
    _isSignedIn = value;
    _notify();
  }

  /// 撮影を開始する。開始できなければ `false`。
  ///
  /// 未サインイン時は送信できないため開始しない（`AGENTS.md` 5.2-2）。
  /// 実行中の再呼び出しも拒否してタイマーの多重起動を防ぐ（同 5.2-3）。
  bool start() {
    if (_isRunning || !_isSignedIn) return false;
    _isRunning = true;
    _lastError = null;
    _notify();
    _enableWakeLock();
    return true;
  }

  void stop() {
    if (!_isRunning) return;
    _isRunning = false;
    _notify();
    _disableWakeLock();
  }

  /// 撮影間隔を変更する。変更できなければ `false`。
  ///
  /// 実行中に間隔が変わると時系列の等間隔性が崩れるため禁止する。
  /// ゼロ・負値はタイマーを暴走させるため、UI を経由しない呼び出しでも弾く。
  bool setInterval(Duration value) {
    if (_isRunning) return false;
    if (value <= Duration.zero) return false;
    _interval = value;
    _notify();
    return true;
  }

  /// 撮影とは無関係な失敗（サインイン失敗など）をステータスパネルへ出す。
  ///
  /// `AGENTS.md` 5.1 が「直近エラー」を下部パネルに表示すると定めているため、
  /// UI 層のエラーもここへ集約して同じ場所に出す。
  void reportError(String message) {
    _lastError = message;
    _notify();
  }

  /// 1回分の撮影と送信。タイマー発火ごとに呼ぶ。
  ///
  /// 停止中は何もしない。**撮影処理が終わっていない場合のみ**スキップし、
  /// 送信中は次の撮影を妨げない。送信は撮影間隔より長くかかりうるため、
  /// ここで待たせると欠測が発生する（`AGENTS.md` 1節の連続撮影が崩れる）。
  Future<void> captureOnce() async {
    if (!_isRunning || _isCapturing) return;

    final at = now();
    final fileName = buildPhotoFileName(cameraId, at);

    final File saved;
    _isCapturing = true;
    try {
      saved = await capturer(fileName);
    } catch (e) {
      _lastError = '撮影に失敗しました: $e';
      _notify();
      return;
    } finally {
      // 撮影さえ終われば次の tick を受け付ける。送信はこの後ろで並行に走る。
      _isCapturing = false;
    }

    _capturedCount++;
    _addRecord(
      PhotoRecord(fileName: fileName, fieldId: cameraId, capturedAt: at),
    );
    _notify();
    await _persistRecords();

    try {
      await uploader.upload(saved);
    } catch (e) {
      // 送信に失敗してもローカルファイルは残す。未送信データを失わないため
      // （risk-assessment.md「追加合意」2）。撮影自体は継続する。
      _lastError = '送信に失敗しました: $e';
      _updateRecord(fileName, PhotoSendState.failed);
      _notify();
      await _persistRecords();
      await _sweepFiles();
      return;
    }

    _sentCount++;
    _lastSentAt = now();
    _lastError = null;
    _updateRecord(fileName, PhotoSendState.sent);
    _notify();
    await _persistRecords();
    await _sweepFiles();
  }

  /// 保存済みの撮影記録を読み直す（#4）。起動時に一度だけ呼ぶ想定。
  Future<void> restoreRecords() async {
    _records = await recordStore.load();
    _notify();
  }

  void _addRecord(PhotoRecord record) {
    _records = <PhotoRecord>[record, ..._records];
    _prune();
  }

  void _updateRecord(String fileName, PhotoSendState state) {
    _records = _records
        .map((r) => r.fileName == fileName ? r.withState(state) : r)
        .toList();
  }

  /// 上限超過ぶんを古い**送信済み**から捨てる。
  ///
  /// 未送信・送信失敗は捨てない。送信できていない写真こそ追跡が要るためで、
  /// ここを落とすと端末に残っているファイルが履歴から消え、再送のきっかけ
  /// （#19）が失われる。そのぶん、送信できない状態が続くと記録は上限を超えて
  /// 増え続ける。これは端末容量より未送信データの保全を優先した結果である
  /// （`risk-assessment.md`「追加合意」2）。容量警告そのものはスコープ外。
  void _prune() {
    var over = _records.length - maxRecords;
    if (over <= 0) return;
    final kept = <PhotoRecord>[];
    for (final r in _records.reversed) {
      if (over > 0 && r.state == PhotoSendState.sent) {
        over--;
        continue;
      }
      kept.add(r);
    }
    _records = kept.reversed.toList();
  }

  /// 履歴の保存に失敗しても撮影・送信は止めない。写真ファイル自体は端末に
  /// あるため、失われるのは一覧表示だけで復旧の余地がある。
  Future<void> _persistRecords() async {
    try {
      await recordStore.save(_records);
    } catch (e) {
      _lastError = '撮影履歴を保存できませんでした: $e';
      _notify();
    }
  }

  /// 履歴に残っている写真だけを端末に残す（#32）。
  ///
  /// 長期保管は Web アプリ側の責務なので、端末は直近ぶんだけを持てばよい。
  /// 削除に失敗しても撮影・送信は止めない。埋まるのは端末容量だけで、
  /// ここで撮影を止めるほうが損失が大きい。
  ///
  /// 送信の成否が確定した**あと**に呼ぶ。送信成功は直近エラーを消すので、
  /// 先に呼ぶと削除の失敗が上書きされて利用者から見えなくなる。
  Future<void> _sweepFiles() async {
    try {
      await fileStore.retainOnly(_records.map((r) => r.fileName).toSet());
    } catch (e) {
      _lastError = '古い写真を削除できませんでした: $e';
      _notify();
    }
  }


  /// dispose 後に飛んでくる非同期の完了通知を握りつぶす。
  ///
  /// 撮影・送信の実行中に画面が破棄されると、完了時に notifyListeners() が
  /// 呼ばれて "A CaptureSession was used after being disposed" になる。
  void _notify() {
    if (_disposed) return;
    notifyListeners();
  }

  @override
  void dispose() {
    // 画面破棄とテストの tearDown など、二重に呼ばれても落ちないようにする。
    if (_disposed) return;
    _disposed = true;
    if (_isRunning) {
      _isRunning = false;
      _disableWakeLock();
    }
    super.dispose();
  }

  /// 画面スリープ抑止の有効化。**撮影の可否は左右しない。**
  ///
  /// 端末やOSによっては抑止できないことがあるが、それで撮影を止めるのは
  /// 過剰。撮影は続けたうえで「画面が消えると止まる」ことだけ利用者へ伝える
  /// （`AGENTS.md` 5.1 の直近エラー）。
  void _enableWakeLock() {
    wakeLock.enable().catchError((Object e) {
      _lastError = 'スリープ抑止を有効にできませんでした: $e';
      _notify();
    });
  }

  /// 解除は失敗しても伝えない。停止・破棄の時点で利用者に打つ手が無く、
  /// 端末側のスリープ設定が効くだけで実害が無いため。
  void _disableWakeLock() {
    wakeLock.disable().catchError((Object _) {});
  }
}
