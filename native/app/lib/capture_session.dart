import 'dart:io';

import 'package:flutter/foundation.dart';

import 'capture_naming.dart';
import 'photo_uploader.dart';

/// 1枚撮影して保存済みファイルを返す処理。カメラ実装をここで隠蔽する。
typedef PhotoCapturer = Future<File> Function(String fileName);

/// 定点撮影セッションの状態機械。
///
/// UI・カメラ・認証のいずれにも依存しないので、そのままユニットテストできる。
/// タイマーは保持せず、発火のたびに [captureOnce] を呼んでもらう前提にしている。
/// これは「タイマーを持たないほうがテストしやすい」ためであり、実際の周期実行は
/// UI 層（`main.dart`）が `Timer.periodic` で駆動する。
class CaptureSession extends ChangeNotifier {
  CaptureSession({
    required PhotoCapturer capturer,
    required PhotoUploader uploader,
    String cameraId = 'CAM001',
    DateTime Function() now = DateTime.now,
    Duration interval = const Duration(minutes: 1),
  })  : _capturer = capturer,
        _uploader = uploader,
        _cameraId = cameraId,
        _now = now,
        _interval = interval;

  final PhotoCapturer _capturer;
  final PhotoUploader _uploader;
  final String _cameraId;
  final DateTime Function() _now;

  Duration _interval;
  bool _isRunning = false;
  bool _isCapturing = false;
  bool _isSignedIn = false;

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
    notifyListeners();
  }

  /// 撮影を開始する。開始できなければ `false`。
  ///
  /// 未サインイン時は送信できないため開始しない（`AGENTS.md` 5.2-2）。
  /// 実行中の再呼び出しも拒否してタイマーの多重起動を防ぐ（同 5.2-3）。
  bool start() {
    if (_isRunning || !_isSignedIn) return false;
    _isRunning = true;
    _lastError = null;
    notifyListeners();
    return true;
  }

  void stop() {
    if (!_isRunning) return;
    _isRunning = false;
    notifyListeners();
  }

  /// 撮影間隔を変更する。実行中は変更できず `false` を返す。
  ///
  /// 実行中に間隔が変わると時系列の等間隔性が崩れるため禁止している。
  bool setInterval(Duration value) {
    if (_isRunning) return false;
    _interval = value;
    notifyListeners();
    return true;
  }

  /// 1回分の撮影と送信。タイマー発火ごとに呼ぶ。
  ///
  /// 停止中は何もしない。前回の撮影が終わっていない場合もスキップする
  /// （撮影が撮影間隔より長引いたときに多重実行しないため）。
  Future<void> captureOnce() async {
    if (!_isRunning || _isCapturing) return;
    _isCapturing = true;
    try {
      final File saved;
      try {
        saved = await _capturer(buildPhotoFileName(_cameraId, _now()));
      } catch (e) {
        _lastError = '撮影に失敗しました: $e';
        notifyListeners();
        return;
      }

      _capturedCount++;
      notifyListeners();

      try {
        await _uploader.upload(saved);
      } catch (e) {
        // 送信に失敗してもローカルファイルは残す。未送信データを失わないため
        // （risk-assessment.md「追加合意」2）。撮影自体は継続する。
        _lastError = '送信に失敗しました: $e';
        notifyListeners();
        return;
      }

      _sentCount++;
      _lastSentAt = _now();
      _lastError = null;
      notifyListeners();
    } finally {
      _isCapturing = false;
    }
  }
}

/// 選択可能な撮影間隔。`AGENTS.md` 2節-4 が「1分・5分・10分・30分」と定めている。
///
/// 先頭が既定値。UI のセレクタはこのリストから構築し、`CaptureSession` 側は
/// 任意の Duration を受け付ける（間隔の制限は入力側の責務とする）。
const kCaptureIntervals = <Duration>[
  Duration(minutes: 1),
  Duration(minutes: 5),
  Duration(minutes: 10),
  Duration(minutes: 30),
];
