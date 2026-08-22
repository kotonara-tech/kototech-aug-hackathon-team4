import 'package:fake_async/fake_async.dart';
import 'package:farmcamera/capture_scheduler.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('開始した時点で1回発火する（最初の1枚を撮影間隔ぶん待たせない）', () {
    fakeAsync((async) {
      final scheduler = CaptureScheduler();
      var ticks = 0;

      scheduler.start(const Duration(minutes: 1), () => ticks++);

      expect(ticks, 1);
      scheduler.dispose();
    });
  });

  test('撮影間隔ごとに発火する', () {
    fakeAsync((async) {
      final scheduler = CaptureScheduler();
      var ticks = 0;

      scheduler.start(const Duration(minutes: 1), () => ticks++);
      async.elapse(const Duration(minutes: 3));

      expect(ticks, 4, reason: '開始時の1回 + 3分ぶんの3回');
      scheduler.dispose();
    });
  });

  test('動作中の start は拒否し、タイマーを多重起動しない（AGENTS.md 5.2-3）', () {
    fakeAsync((async) {
      final scheduler = CaptureScheduler();
      var ticks = 0;

      expect(scheduler.start(const Duration(minutes: 1), () => ticks++), isTrue);
      expect(
        scheduler.start(const Duration(minutes: 1), () => ticks++),
        isFalse,
        reason: '2回目の start は拒否されるべき',
      );
      async.elapse(const Duration(minutes: 2));

      expect(ticks, 3, reason: '多重起動していれば 3 を超える');
      scheduler.dispose();
    });
  });

  test('stop 後は発火しない', () {
    fakeAsync((async) {
      final scheduler = CaptureScheduler();
      var ticks = 0;

      scheduler.start(const Duration(minutes: 1), () => ticks++);
      async.elapse(const Duration(minutes: 1));
      scheduler.stop();
      async.elapse(const Duration(minutes: 10));

      expect(ticks, 2, reason: '停止後のタイマー発火は起きてはいけない');
      expect(scheduler.isActive, isFalse);
    });
  });

  test('stop したあと再度 start できる', () {
    fakeAsync((async) {
      final scheduler = CaptureScheduler();
      var ticks = 0;

      scheduler.start(const Duration(minutes: 1), () => ticks++);
      scheduler.stop();

      expect(scheduler.start(const Duration(minutes: 1), () => ticks++), isTrue);
      expect(ticks, 2);
      scheduler.dispose();
    });
  });
}
