import 'dart:async';
import 'dart:io';

import 'package:farmcamera/capture_session.dart';
import 'package:farmcamera/photo_uploader.dart';
import 'package:flutter_test/flutter_test.dart';

/// 撮影したことにして、実ファイルを1つ作るフェイク。
/// 「送信に失敗してもローカルファイルを消さない」を実ファイルで検証するため、
/// メモリ上のダミーではなく本物の File を作る。
class _FakeCapturer {
  _FakeCapturer(this.dir);

  final Directory dir;
  final List<String> requestedNames = <String>[];
  bool shouldFail = false;

  /// 非 null の間は撮影が完了しない。撮影中に次の tick が来る状況を
  /// 実行タイミングに依存せず再現するためのゲート。
  Completer<void>? gate;

  Future<File> call(String fileName) async {
    requestedNames.add(fileName);
    if (gate != null) await gate!.future;
    if (shouldFail) throw Exception('camera busy');
    final file = File('${dir.path}/$fileName');
    await file.writeAsBytes(<int>[0xFF, 0xD8, 0xFF]); // JPEG SOI 相当のダミー
    return file;
  }
}

class _FakeUploader implements PhotoUploader {
  final List<File> uploaded = <File>[];
  bool shouldFail = false;

  @override
  Future<void> upload(File file) async {
    if (shouldFail) throw Exception('network down');
    uploaded.add(file);
  }
}

void main() {
  late Directory tempDir;
  late _FakeCapturer capturer;
  late _FakeUploader uploader;
  late DateTime now;
  late CaptureSession session;

  setUp(() {
    tempDir = Directory.systemTemp.createTempSync('farmcamera_test');
    capturer = _FakeCapturer(tempDir);
    uploader = _FakeUploader();
    now = DateTime(2026, 8, 21, 14, 5, 9);
    session = CaptureSession(
      capturer: capturer.call,
      uploader: uploader,
      now: () => now,
    );
  });

  tearDown(() {
    session.dispose();
    if (tempDir.existsSync()) tempDir.deleteSync(recursive: true);
  });

  group('撮影間隔の選択肢（AGENTS.md 2節-4）', () {
    test('1分・5分・10分・30分の4種類だけを提供する', () {
      expect(kCaptureIntervals, <Duration>[
        Duration(minutes: 1),
        Duration(minutes: 5),
        Duration(minutes: 10),
        Duration(minutes: 30),
      ]);
    });

    test('既定値は1分（先頭要素）', () {
      expect(session.interval, const Duration(minutes: 1));
      expect(kCaptureIntervals.first, const Duration(minutes: 1));
    });
  });

  group('開始・停止', () {
    test('未サインインでは開始できない（AGENTS.md 5.2-2）', () {
      expect(session.isSignedIn, isFalse);
      expect(session.start(), isFalse);
      expect(session.isRunning, isFalse);
    });

    test('サインイン済みなら開始できる', () {
      session.isSignedIn = true;
      expect(session.start(), isTrue);
      expect(session.isRunning, isTrue);
    });

    test('実行中に再度 start しても二重起動しない（AGENTS.md 5.2-3）', () {
      session.isSignedIn = true;
      expect(session.start(), isTrue);
      expect(session.start(), isFalse, reason: '2回目の start は拒否されるべき');
      expect(session.isRunning, isTrue);
    });

    test('stop すると停止し、再度 start できる', () {
      session.isSignedIn = true;
      session.start();
      session.stop();
      expect(session.isRunning, isFalse);
      expect(session.start(), isTrue);
    });
  });

  group('撮影間隔の変更', () {
    test('停止中は変更できる', () {
      expect(session.setInterval(const Duration(minutes: 10)), isTrue);
      expect(session.interval, const Duration(minutes: 10));
    });

    test('実行中は変更できない（誤設定で時系列が壊れるのを防ぐ）', () {
      session.isSignedIn = true;
      session.setInterval(const Duration(minutes: 5));
      session.start();

      expect(session.setInterval(const Duration(minutes: 30)), isFalse);
      expect(session.interval, const Duration(minutes: 5), reason: '実行中の変更は無視される');
    });
  });

  group('撮影と送信', () {
    test('撮影ファイル名に現在時刻が使われる（AGENTS.md 5.3）', () async {
      session.isSignedIn = true;
      session.start();
      await session.captureOnce();

      expect(capturer.requestedNames, <String>['CAM001_20260821_140509.jpg']);
    });

    test('撮影したファイルそのものを送信する', () async {
      session.isSignedIn = true;
      session.start();
      await session.captureOnce();

      expect(uploader.uploaded, hasLength(1));
      expect(
        uploader.uploaded.single.path,
        '${tempDir.path}/CAM001_20260821_140509.jpg',
        reason: '保存したファイルと送信したファイルが一致すること',
      );
    });

    test('送信成功で撮影枚数・送信枚数・最終送信時刻が更新される', () async {
      session.isSignedIn = true;
      session.start();
      await session.captureOnce();

      expect(session.capturedCount, 1);
      expect(session.sentCount, 1);
      expect(session.lastSentAt, now);
      expect(session.lastError, isNull);
    });

    test('停止中は撮影が発火しない', () async {
      await session.captureOnce();

      expect(session.capturedCount, 0);
      expect(capturer.requestedNames, isEmpty);
    });

    test('送信に失敗してもローカルファイルを削除しない（AGENTS.md 5.2-5 の非破壊要件）', () async {
      uploader.shouldFail = true;
      session.isSignedIn = true;
      session.start();
      await session.captureOnce();

      final saved = File('${tempDir.path}/CAM001_20260821_140509.jpg');
      expect(saved.existsSync(), isTrue, reason: '送信失敗時にファイルを消してはいけない');
      expect(session.capturedCount, 1, reason: '撮影自体は成功している');
      expect(session.sentCount, 0);
      expect(session.lastError, isNotNull);
    });

    test('送信に失敗しても撮影は継続できる（1枚の失敗でセッションを止めない）', () async {
      uploader.shouldFail = true;
      session.isSignedIn = true;
      session.start();
      await session.captureOnce();

      uploader.shouldFail = false;
      now = DateTime(2026, 8, 21, 14, 6, 9);
      await session.captureOnce();

      expect(session.isRunning, isTrue);
      expect(session.capturedCount, 2);
      expect(session.sentCount, 1);
      expect(session.lastError, isNull, reason: '成功したら直近エラーはクリアされる');
    });

    test('撮影自体に失敗したらエラーを記録し、送信は試みない', () async {
      capturer.shouldFail = true;
      session.isSignedIn = true;
      session.start();
      await session.captureOnce();

      expect(session.capturedCount, 0);
      expect(uploader.uploaded, isEmpty);
      expect(session.lastError, isNotNull);
    });

    test('前の撮影が終わる前に次のtickが来ても二重に撮影・送信しない', () async {
      final gate = Completer<void>();
      capturer.gate = gate;
      session.isSignedIn = true;
      session.start();

      final first = session.captureOnce();
      // 1枚目がゲートで止まっている＝撮影中に、次の tick が発火した状況。
      final second = session.captureOnce();
      gate.complete();
      await Future.wait(<Future<void>>[first, second]);

      expect(capturer.requestedNames, hasLength(1), reason: 'カメラを2回叩いてはいけない');
      expect(uploader.uploaded, hasLength(1), reason: '2回送信してはいけない');
      expect(session.capturedCount, 1);
      expect(session.sentCount, 1);
    });

    test('撮影中が解けた後は次の撮影ができる', () async {
      final gate = Completer<void>();
      capturer.gate = gate;
      session.isSignedIn = true;
      session.start();

      final first = session.captureOnce();
      gate.complete();
      await first;

      capturer.gate = null;
      now = DateTime(2026, 8, 21, 14, 6, 9);
      await session.captureOnce();

      expect(capturer.requestedNames, <String>[
        'CAM001_20260821_140509.jpg',
        'CAM001_20260821_140609.jpg',
      ]);
    });
  });

  test('状態が変わるとリスナーに通知される（UIが購読できる）', () async {
    var notified = 0;
    session.addListener(() => notified++);

    session.isSignedIn = true;
    session.start();
    await session.captureOnce();

    expect(notified, greaterThan(0));
  });
}
