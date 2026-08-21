import 'package:wakelock_plus/wakelock_plus.dart';

/// 画面スリープ抑止の抽象。
///
/// 抑止の実現方法は OS ごとに違う（Android は `FLAG_KEEP_SCREEN_ON`、
/// iOS は `UIApplication.isIdleTimerDisabled`）。将来 iOS へ展開しても
/// 呼び出し側を変えずに済むよう、OS 依存部分をこの薄いアダプタへ閉じ込める。
///
/// テストではプラットフォームチャネルを叩けないため、差し替え可能にしている。
abstract class WakeLock {
  /// 画面が消灯しないようにする。
  Future<void> enable();

  /// 端末の通常のスリープ設定へ戻す。
  Future<void> disable();
}

/// 何もしない実装。テストと、抑止が不要な場面の既定値。
class NoopWakeLock implements WakeLock {
  const NoopWakeLock();

  @override
  Future<void> enable() async {}

  @override
  Future<void> disable() async {}
}

/// `wakelock_plus` による実装。
class ScreenWakeLock implements WakeLock {
  const ScreenWakeLock();

  @override
  Future<void> enable() => WakelockPlus.enable();

  @override
  Future<void> disable() => WakelockPlus.disable();
}
