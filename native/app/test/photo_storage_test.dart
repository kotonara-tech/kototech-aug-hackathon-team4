import 'dart:io';

import 'package:farmcamera/photo_storage.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  late Directory dir;
  late DirectoryPhotoFileStore store;

  setUp(() {
    dir = Directory.systemTemp.createTempSync('farmcamera_storage_test');
    store = DirectoryPhotoFileStore(() async => dir);
  });

  tearDown(() {
    try {
      if (dir.existsSync()) dir.deleteSync(recursive: true);
    } on FileSystemException {
      // Windows では書き込み直後に消せないことがある。一時領域なのでOSに任せる。
    }
  });

  File touch(String name) =>
      File('${dir.path}/$name')..writeAsBytesSync(<int>[0xFF, 0xD8, 0xFF]);

  Set<String> namesInDir() => dir
      .listSync()
      .whereType<File>()
      .map((f) => f.uri.pathSegments.last)
      .toSet();

  test('保持対象に無いファイルを削除する', () async {
    touch('CAM001_20260821_140509.jpg');
    touch('CAM001_20260821_140609.jpg');

    await store.retainOnly(<String>{'CAM001_20260821_140609.jpg'});

    expect(namesInDir(), <String>{'CAM001_20260821_140609.jpg'});
  });

  test('保持対象のファイルは残す', () async {
    touch('CAM001_20260821_140509.jpg');

    await store.retainOnly(<String>{'CAM001_20260821_140509.jpg'});

    expect(namesInDir(), <String>{'CAM001_20260821_140509.jpg'});
  });

  test('保持対象が空なら全て消える（履歴を失ったときの意図した挙動）', () async {
    // 履歴が読めなければ、その写真はもうアプリのどこからも辿れない。
    // 消さずに残すと端末を圧迫し続けるだけなので、消すのが正しい。
    touch('CAM001_20260821_140509.jpg');

    await store.retainOnly(const <String>{});

    expect(namesInDir(), isEmpty);
  });

  test('保存先ディレクトリがまだ無くても例外を投げない', () async {
    dir.deleteSync(recursive: true);

    await expectLater(store.retainOnly(const <String>{}), completes);
  });

  test('ディレクトリは削除しない（ファイルだけを対象にする）', () async {
    Directory('${dir.path}/keep_me').createSync();
    touch('CAM001_20260821_140509.jpg');

    await store.retainOnly(const <String>{});

    expect(Directory('${dir.path}/keep_me').existsSync(), isTrue);
    expect(namesInDir(), isEmpty);
  });

  test('何もしない実装は削除しない（既定値・テスト用）', () async {
    touch('CAM001_20260821_140509.jpg');

    await const NoopPhotoFileStore().retainOnly(const <String>{});

    expect(namesInDir(), hasLength(1));
  });
}
