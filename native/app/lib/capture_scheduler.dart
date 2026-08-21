import 'dart:async';

/// 撮影の周期実行。`Timer` をここに閉じ込め、多重起動の防止と停止を保証する。
///
/// [CaptureSession] がタイマーを持たない代わりに、周期実行の責務はこのクラスが
/// 単独で担う。`Timer` を直接扱う場所を1か所に限定することで、キャンセル漏れ
/// （停止したのに撮影が続く）を起こしにくくする。
class CaptureScheduler {
  Timer? _timer;

  bool get isActive => _timer != null;

  /// [interval] ごとに [onTick] を呼ぶ。すでに動作中なら何もせず false を返す。
  ///
  /// 開始直後にも1回発火させる（`AGENTS.md` 5.2-4 の「開始でタイマー起動」を、
  /// 最初の1枚を待たせずに撮る挙動として実装している）。
  bool start(Duration interval, void Function() onTick) {
    if (_timer != null) return false;
    _timer = Timer.periodic(interval, (_) => onTick());
    onTick();
    return true;
  }

  void stop() {
    _timer?.cancel();
    _timer = null;
  }

  void dispose() => stop();
}
