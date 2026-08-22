import 'package:farmcamera/capture_naming.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('buildPhotoFileName', () {
    test('AGENTS.md 5.3 の CAM001_yyyyMMdd_HHmmss.jpg 形式になる', () {
      final name = buildPhotoFileName('CAM001', DateTime(2026, 8, 21, 14, 5, 9));
      expect(name, 'CAM001_20260821_140509.jpg');
    });

    test('月日時分秒がゼロ埋めされる（元日 0時0分0秒）', () {
      final name = buildPhotoFileName('CAM001', DateTime(2026, 1, 1, 0, 0, 0));
      expect(name, 'CAM001_20260101_000000.jpg');
    });

    test('二桁の値がゼロ埋めで壊れない（大晦日 23時59分59秒）', () {
      final name = buildPhotoFileName('CAM001', DateTime(2026, 12, 31, 23, 59, 59));
      expect(name, 'CAM001_20261231_235959.jpg');
    });

    test('カメラIDを差し替えられる（将来の複数端末対応の余地）', () {
      final name = buildPhotoFileName('CAM042', DateTime(2026, 8, 21, 14, 5, 9));
      expect(name, 'CAM042_20260821_140509.jpg');
    });

    test('ミリ秒は名前に含めない（秒単位で丸める仕様を固定する）', () {
      final name = buildPhotoFileName('CAM001', DateTime(2026, 8, 21, 14, 5, 9, 750));
      expect(name, 'CAM001_20260821_140509.jpg');
    });
  });
}
