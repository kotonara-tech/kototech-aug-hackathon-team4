import 'package:farmcamera/photo_record.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  PhotoRecord record(
    String fileName, {
    PhotoSendState state = PhotoSendState.pending,
    DateTime? at,
  }) {
    return PhotoRecord(
      fileName: fileName,
      fieldId: 'CAM001',
      capturedAt: at ?? DateTime(2026, 8, 21, 14, 5, 9),
      state: state,
    );
  }

  group('PhotoRecord のJSON往復', () {
    test('保存して読み直しても内容が変わらない', () {
      final original = record('CAM001_20260821_140509.jpg',
          state: PhotoSendState.sent);

      final restored = PhotoRecord.fromJson(original.toJson());

      expect(restored.fileName, original.fileName);
      expect(restored.fieldId, original.fieldId);
      expect(restored.capturedAt, original.capturedAt);
      expect(restored.state, original.state);
    });

    test('撮影日時はタイムゾーンを含めて復元される', () {
      // 端末のローカル時刻で記録する。UTC として読み直すと時系列がずれる。
      final original = record('a.jpg', at: DateTime(2026, 1, 1, 0, 30));

      final restored = PhotoRecord.fromJson(original.toJson());

      expect(restored.capturedAt.isUtc, isFalse);
      expect(restored.capturedAt, DateTime(2026, 1, 1, 0, 30));
    });

    test('未知の送信状態は未送信として扱う（前方互換）', () {
      final json = record('a.jpg').toJson();
      json['state'] = 'someFutureState';

      expect(PhotoRecord.fromJson(json).state, PhotoSendState.pending);
    });
  });

  group('SharedPreferencesPhotoRecordStore', () {
    setUp(() => SharedPreferences.setMockInitialValues(<String, Object>{}));

    test('保存した記録を読み直せる', () async {
      final store = SharedPreferencesPhotoRecordStore();
      await store.save(<PhotoRecord>[
        record('b.jpg', state: PhotoSendState.sent),
        record('a.jpg'),
      ]);

      // アプリ再起動を模して別インスタンスから読む。
      final loaded = await SharedPreferencesPhotoRecordStore().load();

      expect(loaded.map((r) => r.fileName), <String>['b.jpg', 'a.jpg']);
      expect(loaded.first.state, PhotoSendState.sent);
    });

    test('未保存なら空リストを返す', () async {
      expect(await SharedPreferencesPhotoRecordStore().load(), isEmpty);
    });

    test('壊れたJSONが入っていても落ちず空リストを返す', () async {
      // 旧バージョンの形式が残っている場合に、アプリが起動不能になるのを防ぐ。
      SharedPreferences.setMockInitialValues(<String, Object>{
        'photo_records': 'not a json array',
      });

      expect(await SharedPreferencesPhotoRecordStore().load(), isEmpty);
    });
  });

  group('InMemoryPhotoRecordStore', () {
    test('保存した内容をそのまま返す', () async {
      final store = InMemoryPhotoRecordStore();
      await store.save(<PhotoRecord>[record('a.jpg')]);

      expect((await store.load()).single.fileName, 'a.jpg');
    });

    test('保存後にリストを変更しても記録は影響を受けない', () async {
      final store = InMemoryPhotoRecordStore();
      final list = <PhotoRecord>[record('a.jpg')];
      await store.save(list);
      list.clear();

      expect(await store.load(), hasLength(1));
    });
  });
}
