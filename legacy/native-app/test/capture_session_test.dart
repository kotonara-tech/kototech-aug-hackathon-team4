import 'dart:async';
import 'dart:io';

import 'package:farmcamera/capture_session.dart';
import 'package:farmcamera/photo_record.dart';
import 'package:farmcamera/photo_storage.dart';
import 'package:farmcamera/photo_uploader.dart';
import 'package:farmcamera/wake_lock.dart';
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

  /// 撮影処理が終わった時点で complete する。実ファイルI/Oの完了を
  /// 固定時間の待機に頼らず待つために使う。
  Completer<void>? finished;

  Future<File> call(String fileName) async {
    requestedNames.add(fileName);
    if (gate != null) await gate!.future;
    if (shouldFail) throw Exception('camera busy');
    final file = File('${dir.path}/$fileName');
    await file.writeAsBytes(<int>[0xFF, 0xD8, 0xFF]); // JPEG SOI 相当のダミー
    finished?.complete();
    return file;
  }
}

class _FakeUploader implements PhotoUploader {
  final List<File> uploaded = <File>[];
  bool shouldFail = false;

  /// 非 null の間は送信が完了しない。送信が撮影間隔より長引く状況を再現する。
  Completer<void>? gate;

  @override
  Future<void> upload(File file) async {
    if (gate != null) await gate!.future;
    if (shouldFail) throw Exception('network down');
    uploaded.add(file);
  }
}

/// 履歴の保存に必ず失敗するフェイク。端末の空き容量不足などを模す。
class _ThrowingRecordStore implements PhotoRecordStore {
  @override
  Future<List<PhotoRecord>> load() async => <PhotoRecord>[];

  @override
  Future<void> save(List<PhotoRecord> records) async =>
      throw Exception('disk full');
}

/// 削除指示を記録するだけのフェイク。実体は消さない。
class _RecordingPhotoFileStore implements PhotoFileStore {
  final List<Set<String>> calls = <Set<String>>[];

  @override
  Future<void> retainOnly(Set<String> keep) async => calls.add(keep);
}

/// 削除に必ず失敗するフェイク。権限不足や読み取り専用領域を模す。
class _ThrowingPhotoFileStore implements PhotoFileStore {
  @override
  Future<void> retainOnly(Set<String> keep) async =>
      throw Exception('permission denied');
}

class _FakeWakeLock implements WakeLock {
  /// 呼び出しの履歴。true=有効化 / false=解除。順序も検証したいので配列で持つ。
  final List<bool> calls = <bool>[];

  bool failOnEnable = false;

  bool get isEnabled => calls.isNotEmpty && calls.last;

  @override
  Future<void> enable() async {
    if (failOnEnable) throw Exception('wakelock unavailable');
    calls.add(true);
  }

  @override
  Future<void> disable() async => calls.add(false);
}

void main() {
  late Directory tempDir;
  late _FakeCapturer capturer;
  late _FakeUploader uploader;
  late _FakeWakeLock wakeLock;
  late InMemoryPhotoRecordStore recordStore;
  late DateTime now;
  late CaptureSession session;

  setUp(() {
    tempDir = Directory.systemTemp.createTempSync('farmcamera_test');
    capturer = _FakeCapturer(tempDir);
    uploader = _FakeUploader();
    wakeLock = _FakeWakeLock();
    recordStore = InMemoryPhotoRecordStore();
    now = DateTime(2026, 8, 21, 14, 5, 9);
    session = CaptureSession(
      capturer: capturer.call,
      uploader: uploader,
      wakeLock: wakeLock,
      recordStore: recordStore,
      now: () => now,
    );
  });

  tearDown(() {
    session.dispose();
    try {
      if (tempDir.existsSync()) tempDir.deleteSync(recursive: true);
    } on FileSystemException {
      // 実行中の撮影がまだ書き込み中の場合、Windows では削除できない。
      // 一時ディレクトリなのでOSに任せる。
    }
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

  group('送信が長引いても撮影を止めない（AGENTS.md 1節の連続撮影）', () {
    test('送信中でも次の tick で撮影できる', () async {
      final uploadGate = Completer<void>();
      uploader.gate = uploadGate;
      capturer.finished = Completer<void>();
      session.isSignedIn = true;
      session.start();

      // 1枚目: 撮影は完了するが送信は uploadGate で止まったまま。
      final first = session.captureOnce();
      await capturer.finished!.future;
      await Future<void>.delayed(Duration.zero);
      expect(session.capturedCount, 1, reason: '撮影自体は完了しているべき');
      capturer.finished = null;

      // 送信が終わらないうちに次の tick が来る。
      uploader.gate = null;
      now = DateTime(2026, 8, 21, 14, 6, 9);
      await session.captureOnce();

      expect(
        session.capturedCount,
        2,
        reason: '送信の遅さで撮影をスキップしてはいけない（欠測になる）',
      );
      expect(capturer.requestedNames, <String>[
        'CAM001_20260821_140509.jpg',
        'CAM001_20260821_140609.jpg',
      ]);

      uploadGate.complete();
      await first;
      expect(session.sentCount, 2, reason: '遅れていた1枚目も最終的に送信される');
    });
  });

  group('停止・破棄と実行中処理の競合', () {
    test('撮影中に stop されても、その1枚の送信は完了させる', () async {
      final gate = Completer<void>();
      capturer.gate = gate;
      session.isSignedIn = true;
      session.start();

      final inFlight = session.captureOnce();
      session.stop();
      gate.complete();
      await inFlight;

      expect(session.isRunning, isFalse);
      expect(session.capturedCount, 1, reason: '撮り終えた写真を捨てない');
      expect(session.sentCount, 1);
    });

    test('停止後は次の tick で撮影しない', () async {
      session.isSignedIn = true;
      session.start();
      session.stop();
      await session.captureOnce();

      expect(capturer.requestedNames, isEmpty);
    });

    test('dispose 後に実行中の撮影が完了しても通知しない', () async {
      final gate = Completer<void>();
      capturer.gate = gate;
      session.isSignedIn = true;
      session.start();

      final inFlight = session.captureOnce();
      session.dispose();
      gate.complete();

      // dispose 済みの ChangeNotifier へ通知すると FlutterError で落ちる。
      await expectLater(inFlight, completes);
    });
  });

  group('setInterval の入力不変条件', () {
    test('ゼロは拒否する（タイマーが暴走する）', () {
      expect(session.setInterval(Duration.zero), isFalse);
      expect(session.interval, const Duration(minutes: 1));
    });

    test('負値は拒否する', () {
      expect(session.setInterval(const Duration(minutes: -1)), isFalse);
      expect(session.interval, const Duration(minutes: 1));
    });

    test('仕様外でも正の値なら受け付ける（4値への限定はUIの責務）', () {
      expect(session.setInterval(const Duration(seconds: 10)), isTrue);
      expect(session.interval, const Duration(seconds: 10));
    });
  });

  group('撮影以外のエラー表示（AGENTS.md 5.1 の直近エラー）', () {
    test('reportError でステータスに残る', () {
      session.reportError('サインインに失敗しました: test');

      expect(session.lastError, 'サインインに失敗しました: test');
    });

    test('start すると直近エラーはクリアされる', () {
      session.reportError('サインインに失敗しました: test');
      session.isSignedIn = true;
      session.start();

      expect(session.lastError, isNull);
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

  group('撮影中の画面スリープ抑止（#6）', () {
    /// 抑止の有効化は非同期なので、start() 直後のマイクロタスクを流す。
    Future<void> settle() => Future<void>.delayed(Duration.zero);

    test('開始すると抑止が有効になる', () async {
      session.isSignedIn = true;
      session.start();
      await settle();

      expect(wakeLock.isEnabled, isTrue);
    });

    test('停止すると解除される（通常のスリープ設定に戻る）', () async {
      session.isSignedIn = true;
      session.start();
      await settle();
      session.stop();
      await settle();

      expect(wakeLock.isEnabled, isFalse);
      expect(wakeLock.calls, <bool>[true, false], reason: '有効化→解除の順で1回ずつ');
    });

    test('開始できなかったときは抑止しない（未サインイン）', () async {
      expect(session.start(), isFalse);
      await settle();

      expect(wakeLock.calls, isEmpty, reason: '撮影していないのに画面を点けたままにしない');
    });

    test('実行中の再 start では抑止を重ねがけしない', () async {
      session.isSignedIn = true;
      session.start();
      await settle();
      session.start();
      await settle();

      expect(wakeLock.calls, <bool>[true]);
    });

    test('停止済みの stop では解除を繰り返さない', () async {
      session.stop();
      await settle();

      expect(wakeLock.calls, isEmpty);
    });

    test('dispose すると解除される（画面を離れて抑止が残らない）', () async {
      session.isSignedIn = true;
      session.start();
      await settle();

      session.dispose();
      await settle();

      expect(wakeLock.isEnabled, isFalse, reason: '破棄後に端末が眠れないままになるのを防ぐ');
    });

    test('抑止に失敗しても撮影は開始でき、エラーはステータスに残る', () async {
      // 端末やOSによっては抑止できないことがある。撮影自体は続けたいが、
      // 「画面が消えると止まる」ことは利用者に伝わる必要がある。
      wakeLock.failOnEnable = true;
      session.isSignedIn = true;

      expect(session.start(), isTrue);
      await settle();

      expect(session.isRunning, isTrue, reason: '抑止の失敗で撮影を止めない');
      expect(session.lastError, isNotNull);
      expect(session.lastError, contains('スリープ'));
    });
  });

  group('撮影記録の保存（#4 / STEP3）', () {
    test('撮影すると圃場ID・撮影日時つきで記録される', () async {
      session.isSignedIn = true;
      session.start();
      await session.captureOnce();

      final r = session.records.single;
      expect(r.fileName, 'CAM001_20260821_140509.jpg');
      expect(r.fieldId, 'CAM001');
      expect(r.capturedAt, now);
    });

    test('送信に成功すると送信済みになる', () async {
      session.isSignedIn = true;
      session.start();
      await session.captureOnce();

      expect(session.records.single.state, PhotoSendState.sent);
    });

    test('送信に失敗すると送信失敗として残る', () async {
      // 端末にファイルは残っているので、履歴から失敗を追える必要がある。
      uploader.shouldFail = true;
      session.isSignedIn = true;
      session.start();
      await session.captureOnce();

      expect(session.records.single.state, PhotoSendState.failed);
    });

    test('撮影自体に失敗したら記録しない', () async {
      capturer.shouldFail = true;
      session.isSignedIn = true;
      session.start();
      await session.captureOnce();

      expect(session.records, isEmpty, reason: '存在しない写真を履歴に出さない');
    });

    test('新しい記録が先頭に来る', () async {
      session.isSignedIn = true;
      session.start();
      await session.captureOnce();
      now = DateTime(2026, 8, 21, 14, 6, 9);
      await session.captureOnce();

      expect(session.records.map((r) => r.fileName), <String>[
        'CAM001_20260821_140609.jpg',
        'CAM001_20260821_140509.jpg',
      ]);
    });

    test('記録は永続化され、次回起動時に復元できる', () async {
      session.isSignedIn = true;
      session.start();
      await session.captureOnce();

      // アプリ再起動を模して、同じ保存先から別セッションを作る。
      final revived = CaptureSession(
        capturer: capturer.call,
        uploader: uploader,
        recordStore: recordStore,
        now: () => now,
      );
      addTearDown(revived.dispose);
      await revived.restoreRecords();

      expect(revived.records.single.fileName, 'CAM001_20260821_140509.jpg');
      expect(revived.records.single.state, PhotoSendState.sent);
    });

    test('上限を超えたら古い送信済みから捨てる', () async {
      final limited = CaptureSession(
        capturer: capturer.call,
        uploader: uploader,
        recordStore: recordStore,
        now: () => now,
        maxRecords: 2,
      );
      addTearDown(limited.dispose);
      limited.isSignedIn = true;
      limited.start();

      for (var i = 0; i < 3; i++) {
        now = DateTime(2026, 8, 21, 14, 5 + i, 9);
        await limited.captureOnce();
      }

      expect(limited.records, hasLength(2));
      expect(limited.records.map((r) => r.fileName), <String>[
        'CAM001_20260821_140709.jpg',
        'CAM001_20260821_140609.jpg',
      ]);
    });

    test('上限を超えても未送信・送信失敗は捨てない', () async {
      // 送信できていない写真こそ追跡が要る。ここを落とすと、端末に残っている
      // ファイルが履歴から消えて再送のきっかけが失われる（#19）。
      final limited = CaptureSession(
        capturer: capturer.call,
        uploader: uploader,
        recordStore: recordStore,
        now: () => now,
        maxRecords: 2,
      );
      addTearDown(limited.dispose);
      limited.isSignedIn = true;
      limited.start();

      uploader.shouldFail = true;
      await limited.captureOnce();

      uploader.shouldFail = false;
      for (var i = 1; i < 4; i++) {
        now = DateTime(2026, 8, 21, 14, 5 + i, 9);
        await limited.captureOnce();
      }

      final failed = limited.records
          .where((r) => r.state == PhotoSendState.failed)
          .toList();
      expect(failed, hasLength(1), reason: '送信失敗の記録は上限を超えても残す');
      expect(failed.single.fileName, 'CAM001_20260821_140509.jpg');
    });
  });

  test('履歴の保存に失敗しても撮影・送信は続き、エラーはステータスに残る', () async {
    // 写真ファイル自体は端末にあるので、失われるのは一覧表示だけ。
    // ここで撮影を止めるほうが損失が大きい。
    final session = CaptureSession(
      capturer: capturer.call,
      uploader: uploader,
      recordStore: _ThrowingRecordStore(),
      now: () => now,
    );
    addTearDown(session.dispose);
    session.isSignedIn = true;
    session.start();

    await session.captureOnce();

    expect(session.capturedCount, 1);
    expect(session.sentCount, 1, reason: '履歴の失敗で送信まで止めない');
    expect(session.lastError, contains('撮影履歴'));
  });

  group('端末に残す写真の上限（#32）', () {
    /// 実体ファイルまで見たいテスト用。撮影先と削除対象を同じ場所に揃える。
    CaptureSession buildSession({
      required PhotoFileStore fileStore,
      int maxRecords = 100,
    }) {
      final s = CaptureSession(
        capturer: capturer.call,
        uploader: uploader,
        recordStore: recordStore,
        fileStore: fileStore,
        maxRecords: maxRecords,
        now: () => now,
      );
      addTearDown(s.dispose);
      s.isSignedIn = true;
      s.start();
      return s;
    }

    bool existsOnDisk(String fileName) =>
        File('${tempDir.path}/$fileName').existsSync();

    test('撮影のたびに、履歴に残っているぶんだけを残すよう指示する', () async {
      final store = _RecordingPhotoFileStore();
      final s = buildSession(fileStore: store);

      await s.captureOnce();

      expect(store.calls.last, <String>{'CAM001_20260821_140509.jpg'});
    });

    test('上限を超えて古くなった送信済みの写真は実体も消える', () async {
      // 長期保管は Web アプリ側の責務。端末は直近ぶんだけを持つ（AGENTS.md 5.4）。
      final s = buildSession(
        fileStore: DirectoryPhotoFileStore(() async => tempDir),
        maxRecords: 2,
      );

      await s.captureOnce();
      now = DateTime(2026, 8, 21, 14, 6, 9);
      await s.captureOnce();
      now = DateTime(2026, 8, 21, 14, 7, 9);
      await s.captureOnce();

      expect(existsOnDisk('CAM001_20260821_140509.jpg'), isFalse,
          reason: '履歴から落ちた写真の実体を残すと端末を圧迫し続ける');
      expect(existsOnDisk('CAM001_20260821_140609.jpg'), isTrue);
      expect(existsOnDisk('CAM001_20260821_140709.jpg'), isTrue);
    });

    test('送信に失敗した写真は上限を超えても実体を消さない', () async {
      // 履歴が「送信失敗」を出しているのに実体が無い状態を作らない。
      final s = buildSession(
        fileStore: DirectoryPhotoFileStore(() async => tempDir),
        maxRecords: 1,
      );

      uploader.shouldFail = true;
      await s.captureOnce();
      uploader.shouldFail = false;
      now = DateTime(2026, 8, 21, 14, 6, 9);
      await s.captureOnce();
      now = DateTime(2026, 8, 21, 14, 7, 9);
      await s.captureOnce();

      expect(existsOnDisk('CAM001_20260821_140509.jpg'), isTrue,
          reason: '送信できていない写真こそ残す（risk-assessment.md 追加合意2）');
      expect(existsOnDisk('CAM001_20260821_140609.jpg'), isFalse);
    });

    test('履歴に無い写真（前回の残骸）も掃除される', () async {
      File('${tempDir.path}/CAM001_20260101_000000.jpg')
          .writeAsBytesSync(<int>[0xFF, 0xD8, 0xFF]);
      final s = buildSession(
        fileStore: DirectoryPhotoFileStore(() async => tempDir),
      );

      await s.captureOnce();

      expect(existsOnDisk('CAM001_20260101_000000.jpg'), isFalse);
      expect(existsOnDisk('CAM001_20260821_140509.jpg'), isTrue);
    });

    test('削除に失敗しても撮影・送信は続き、エラーはステータスに残る', () async {
      final s = buildSession(fileStore: _ThrowingPhotoFileStore());

      await s.captureOnce();

      expect(s.capturedCount, 1);
      expect(s.sentCount, 1, reason: '削除の失敗で送信まで止めない');
      expect(s.lastError, contains('古い写真'));
    });

    test('既定では何も消さない（明示的に渡したときだけ削除する）', () async {
      File('${tempDir.path}/CAM001_20260101_000000.jpg')
          .writeAsBytesSync(<int>[0xFF, 0xD8, 0xFF]);
      session.isSignedIn = true;
      session.start();

      await session.captureOnce();

      expect(existsOnDisk('CAM001_20260101_000000.jpg'), isTrue);
    });
  });
}
