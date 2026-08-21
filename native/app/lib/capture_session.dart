import 'dart:io';

import 'package:flutter/foundation.dart';

import 'capture_naming.dart';
import 'photo_uploader.dart';

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
    Duration initialInterval = const Duration(minutes: 1),
  }) : _interval = initialInterval;

  final PhotoCapturer capturer;
  final PhotoUploader uploader;
  final String cameraId;
  final DateTime Function() now;

  Duration _interval;
  bool _isRunning = false;
  bool _isCapturing = false;
  bool _isSignedIn = false;
  bool _disposed = false;

  int _capturedCount = 0;
  int _sentCount = 0;
  DateTime? _lastSentAt;
  String? _lastError;

  Duration get interval => _interval;
  bool get isRunning => _isRunning;
  int get capturedCount => _capturedCount;
  int get sentCount => _sentCount;
  DateTime? get lastSentAt => _lastSentAt;
  String? get lastError => _lastError;

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
    return true;
  }

  void stop() {
    if (!_isRunning) return;
    _isRunning = false;
    _notify();
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

    final File saved;
    _isCapturing = true;
    try {
      saved = await capturer(buildPhotoFileName(cameraId, now()));
    } catch (e) {
      _lastError = '撮影に失敗しました: $e';
      _notify();
      return;
    } finally {
      // 撮影さえ終われば次の tick を受け付ける。送信はこの後ろで並行に走る。
      _isCapturing = false;
    }

    _capturedCount++;
    _notify();

    try {
      await uploader.upload(saved);
    } catch (e) {
      // 送信に失敗してもローカルファイルは残す。未送信データを失わないため
      // （risk-assessment.md「追加合意」2）。撮影自体は継続する。
      _lastError = '送信に失敗しました: $e';
      _notify();
      return;
    }

    _sentCount++;
    _lastSentAt = now();
    _lastError = null;
    _notify();
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
    super.dispose();
  }
}
