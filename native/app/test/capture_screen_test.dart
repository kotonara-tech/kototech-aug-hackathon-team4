import 'dart:io';

import 'package:farmcamera/auth_gateway.dart';
import 'package:farmcamera/main.dart';
import 'package:farmcamera/photo_source.dart';
import 'package:farmcamera/photo_record.dart';
import 'package:farmcamera/photo_uploader.dart';
import 'package:farmcamera/wake_lock.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

class _FakePhotoSource implements PhotoSource {
  _FakePhotoSource(this.dir);

  final Directory dir;
  bool ready = true;
  String? initError;
  final List<String> captured = <String>[];

  /// `initialize()` が呼ばれた回数。再試行が実際に再初期化を走らせたかを見る。
  int initializeCount = 0;

  /// 次の `initialize()` で権限が許可された状況を再現する。
  /// （端末設定で権限を付け直してからアプリに戻ってきた場合）
  bool recoversOnNextInitialize = false;

  @override
  bool get isReady => ready && initError == null;

  @override
  String? get errorMessage => initError;

  @override
  Future<void> initialize() async {
    initializeCount++;
    if (recoversOnNextInitialize) {
      initError = null;
      recoversOnNextInitialize = false;
    }
  }

  @override
  Future<File> capture(String fileName) async {
    captured.add(fileName);
    final file = File('${dir.path}/$fileName');
    // ウィジェットテストの fakeAsync ゾーンでは実ファイルI/Oの Future が完了せず
    // 撮影が終わらないまま次の tick を握りつぶしてしまうため、同期で書き込む。
    file.writeAsBytesSync(<int>[0xFF, 0xD8]);
    return file;
  }

  @override
  Widget buildPreview() => const Placeholder(key: Key('preview'));

  @override
  void dispose() {}
}

class _FakeAuthGateway implements AuthGateway {
  String? _email;
  bool signInSucceeds = true;
  Object? signInThrows;

  @override
  String? get email => _email;

  @override
  Future<void> initialize() async {}

  @override
  Future<bool> signIn() async {
    if (signInThrows != null) throw signInThrows!;
    if (!signInSucceeds) return false;
    _email = 'farmer@example.com';
    return true;
  }

  @override
  Future<Map<String, String>?> authHeaders() async => <String, String>{};
}

class _FakeWakeLock implements WakeLock {
  final List<bool> calls = <bool>[];
  bool get isEnabled => calls.isNotEmpty && calls.last;

  @override
  Future<void> enable() async => calls.add(true);

  @override
  Future<void> disable() async => calls.add(false);
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
  late _FakePhotoSource source;
  late _FakeAuthGateway auth;
  late _FakeUploader uploader;
  late _FakeWakeLock wakeLock;
  late InMemoryPhotoRecordStore recordStore;

  setUp(() {
    tempDir = Directory.systemTemp.createTempSync('capture_screen_test');
    source = _FakePhotoSource(tempDir);
    auth = _FakeAuthGateway();
    uploader = _FakeUploader();
    wakeLock = _FakeWakeLock();
    recordStore = InMemoryPhotoRecordStore();
  });

  tearDown(() {
    try {
      if (tempDir.existsSync()) tempDir.deleteSync(recursive: true);
    } on FileSystemException {
      // Windows では書き込み直後のファイルが掴まれたままのことがある。
      // 一時ディレクトリなのでOSに任せる。
    }
  });

  Future<void> pumpScreen(WidgetTester tester) async {
    await tester.pumpWidget(
      FarmCameraApp(
        source: source,
        auth: auth,
        uploader: uploader,
        wakeLock: wakeLock,
        recordStore: recordStore,
      ),
    );
    await tester.pumpAndSettle();
  }

  ElevatedButton buttonWithText(WidgetTester tester, String label) {
    return tester.widget<ElevatedButton>(
      find.ancestor(of: find.text(label), matching: find.byType(ElevatedButton)),
    );
  }

  testWidgets('起動するとタイトルとプレビューが表示される', (tester) async {
    await pumpScreen(tester);

    expect(find.text('定点撮影POC'), findsWidgets);
    expect(find.byKey(const Key('preview')), findsOneWidget);
  });

  testWidgets('未サインインでは開始できない（AGENTS.md 5.2-2）', (tester) async {
    await pumpScreen(tester);

    expect(find.text('未サインイン'), findsOneWidget);
    expect(buttonWithText(tester, '開始').onPressed, isNull);
  });

  testWidgets('サインインすると開始できるようになる', (tester) async {
    await pumpScreen(tester);

    await tester.tap(find.text('Googleでサインイン'));
    await tester.pumpAndSettle();

    expect(find.text('サインイン中: farmer@example.com'), findsOneWidget);
    expect(buttonWithText(tester, '開始').onPressed, isNotNull);
  });

  testWidgets('カメラが準備できていなければサインイン済みでも開始できない', (tester) async {
    source.ready = false;
    await pumpScreen(tester);

    await tester.tap(find.text('Googleでサインイン'));
    await tester.pumpAndSettle();

    expect(buttonWithText(tester, '開始').onPressed, isNull);
  });

  testWidgets('カメラ初期化エラーはプレビュー位置に再試行つきで表示される', (tester) async {
    source.initError = 'カメラ権限が許可されていません。';
    await pumpScreen(tester);

    expect(find.text('カメラ権限が許可されていません。'), findsOneWidget);
    expect(find.text('再試行'), findsOneWidget);
    expect(find.byKey(const Key('preview')), findsNothing);
  });

  testWidgets('サインイン失敗はステータスパネルの直近エラーに残る（AGENTS.md 5.1）', (tester) async {
    auth.signInThrows = Exception('network down');
    await pumpScreen(tester);

    await tester.tap(find.text('Googleでサインイン'));
    await tester.pumpAndSettle();

    // SnackBar と違い、時間が経っても消えないことまで確認する。
    await tester.pump(const Duration(seconds: 10));
    expect(find.textContaining('サインインに失敗しました'), findsOneWidget);
  });

  testWidgets('開始すると撮影が走り、ステータスの枚数が更新される', (tester) async {
    await pumpScreen(tester);
    await tester.tap(find.text('Googleでサインイン'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('開始'));
    await tester.pumpAndSettle();

    expect(source.captured, hasLength(1), reason: '開始直後に1枚撮る');
    expect(uploader.uploaded, hasLength(1));
    expect(find.textContaining('撮影枚数: 1'), findsOneWidget);
    expect(find.textContaining('送信枚数: 1'), findsOneWidget);

    // タイマーを止めてから破棄しないと pending timer で失敗する。
    await tester.tap(find.text('停止'));
    await tester.pumpAndSettle();
  });

  testWidgets('撮影間隔ごとに撮影される', (tester) async {
    await pumpScreen(tester);
    await tester.tap(find.text('Googleでサインイン'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('開始'));
    await tester.pumpAndSettle();

    await tester.pump(const Duration(minutes: 1));
    await tester.pumpAndSettle();

    expect(source.captured, hasLength(2));

    await tester.tap(find.text('停止'));
    await tester.pumpAndSettle();
  });

  testWidgets('実行中は開始ボタンと撮影間隔が操作できない（AGENTS.md 5.2-3）', (tester) async {
    await pumpScreen(tester);
    await tester.tap(find.text('Googleでサインイン'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('開始'));
    await tester.pumpAndSettle();

    expect(buttonWithText(tester, '開始').onPressed, isNull);
    expect(buttonWithText(tester, '停止').onPressed, isNotNull);
    expect(
      tester.widget<DropdownButton<Duration>>(find.byType(DropdownButton<Duration>)).onChanged,
      isNull,
      reason: '実行中は撮影間隔を変えられない',
    );

    await tester.tap(find.text('停止'));
    await tester.pumpAndSettle();
  });

  testWidgets('停止すると撮影が止まる', (tester) async {
    await pumpScreen(tester);
    await tester.tap(find.text('Googleでサインイン'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('開始'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('停止'));
    await tester.pumpAndSettle();

    final countAtStop = source.captured.length;
    await tester.pump(const Duration(minutes: 10));
    await tester.pumpAndSettle();

    expect(source.captured, hasLength(countAtStop));
    expect(buttonWithText(tester, '開始').onPressed, isNotNull);
  });

  testWidgets('撮影間隔のセレクタは1分・5分・10分・30分（AGENTS.md 2節-4）', (tester) async {
    await pumpScreen(tester);

    final dropdown =
        tester.widget<DropdownButton<Duration>>(find.byType(DropdownButton<Duration>));
    expect(
      dropdown.items!.map((i) => i.value).toList(),
      <Duration>[
        Duration(minutes: 1),
        Duration(minutes: 5),
        Duration(minutes: 10),
        Duration(minutes: 30),
      ],
    );
    expect(dropdown.value, const Duration(minutes: 1), reason: '既定は1分');
  });

  testWidgets('再試行を押すとカメラを初期化し直し、回復すればプレビューが出る', (tester) async {
    // 権限を一度拒否したあと、端末設定で許可して戻ってきた状況。ここで復帰
    // できないと、アプリを入れ直す以外に手が無くなる。
    source.initError = 'カメラ権限が許可されていません。';
    await pumpScreen(tester);
    expect(find.byKey(const Key('preview')), findsNothing);
    final before = source.initializeCount;

    source.recoversOnNextInitialize = true;
    await tester.tap(find.text('再試行'));
    await tester.pumpAndSettle();

    expect(source.initializeCount, before + 1, reason: '再初期化を実際に呼ぶこと');
    expect(find.byKey(const Key('preview')), findsOneWidget);
    expect(find.text('再試行'), findsNothing, reason: '回復したらエラー表示は消える');
  });

  testWidgets('再試行しても回復しなければエラー表示のまま再試行できる', (tester) async {
    source.initError = 'カメラ権限が許可されていません。';
    await pumpScreen(tester);

    await tester.tap(find.text('再試行'));
    await tester.pumpAndSettle();

    expect(find.text('カメラ権限が許可されていません。'), findsOneWidget);
    expect(find.text('再試行'), findsOneWidget, reason: '何度でも試せること');
  });

  testWidgets('撮影間隔を選ぶと選択が反映される', (tester) async {
    await pumpScreen(tester);

    await tester.tap(find.byType(DropdownButton<Duration>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('10分').last);
    await tester.pumpAndSettle();

    expect(
      tester.widget<DropdownButton<Duration>>(find.byType(DropdownButton<Duration>)).value,
      const Duration(minutes: 10),
    );
  });

  testWidgets('選んだ撮影間隔でタイマーが動く（既定の1分のままにならない）', (tester) async {
    await pumpScreen(tester);
    await tester.tap(find.text('Googleでサインイン'));
    await tester.pumpAndSettle();

    await tester.tap(find.byType(DropdownButton<Duration>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('5分').last);
    await tester.pumpAndSettle();

    await tester.tap(find.text('開始'));
    await tester.pumpAndSettle();
    expect(source.captured, hasLength(1), reason: '開始直後の1枚');

    // 1分では撮らない。ここで2枚目が出るなら選択がタイマーに届いていない。
    await tester.pump(const Duration(minutes: 1));
    await tester.pumpAndSettle();
    expect(source.captured, hasLength(1));

    await tester.pump(const Duration(minutes: 4));
    await tester.pumpAndSettle();
    expect(source.captured, hasLength(2), reason: '5分経過で2枚目');

    await tester.tap(find.text('停止'));
    await tester.pumpAndSettle();
  });

  testWidgets('開始で画面スリープを抑止し、停止で解除する（#6）', (tester) async {
    await pumpScreen(tester);
    await tester.tap(find.text('Googleでサインイン'));
    await tester.pumpAndSettle();
    expect(wakeLock.calls, isEmpty, reason: 'サインインしただけでは抑止しない');

    await tester.tap(find.text('開始'));
    await tester.pumpAndSettle();
    expect(wakeLock.isEnabled, isTrue);

    await tester.tap(find.text('停止'));
    await tester.pumpAndSettle();
    expect(wakeLock.isEnabled, isFalse);
  });

  testWidgets('撮影中に画面を離れても抑止が残らない（#6）', (tester) async {
    await pumpScreen(tester);
    await tester.tap(find.text('Googleでサインイン'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('開始'));
    await tester.pumpAndSettle();
    expect(wakeLock.isEnabled, isTrue);

    // 画面を破棄する（別画面へ遷移・アプリ終了に相当）。
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pumpAndSettle();

    expect(wakeLock.isEnabled, isFalse, reason: '端末が眠れないままになるのを防ぐ');
  });

  testWidgets('撮影前は履歴が空であることが分かる（#4）', (tester) async {
    await pumpScreen(tester);

    expect(find.text('まだ撮影していません'), findsOneWidget);
  });

  testWidgets('撮影すると履歴に圃場ID・撮影日時・送信状態が出る（#4）', (tester) async {
    await pumpScreen(tester);
    await tester.tap(find.text('Googleでサインイン'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('開始'));
    await tester.pumpAndSettle();

    expect(find.text('まだ撮影していません'), findsNothing);
    expect(find.textContaining(source.captured.single), findsOneWidget,
        reason: '撮影したファイル名が履歴に出る');
    expect(find.textContaining('圃場ID: CAM001'), findsOneWidget);
    expect(find.text('送信済み'), findsOneWidget);

    await tester.tap(find.text('停止'));
    await tester.pumpAndSettle();
  });

  testWidgets('送信に失敗した写真は履歴に送信失敗として残る（#4）', (tester) async {
    // 端末にファイルは残っているので、あとから追えないと再送のきっかけを失う。
    uploader.shouldFail = true;
    await pumpScreen(tester);
    await tester.tap(find.text('Googleでサインイン'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('開始'));
    await tester.pumpAndSettle();

    expect(find.text('送信失敗'), findsOneWidget);
    expect(find.text('送信済み'), findsNothing);

    await tester.tap(find.text('停止'));
    await tester.pumpAndSettle();
  });

  testWidgets('前回起動時の履歴が起動時に復元される（#4）', (tester) async {
    await recordStore.save(<PhotoRecord>[
      PhotoRecord(
        fileName: 'CAM001_20260820_090000.jpg',
        fieldId: 'CAM001',
        capturedAt: DateTime(2026, 8, 20, 9),
        state: PhotoSendState.failed,
      ),
    ]);

    await pumpScreen(tester);

    expect(find.textContaining('CAM001_20260820_090000.jpg'), findsOneWidget);
    expect(find.text('送信失敗'), findsOneWidget);
  });
}
