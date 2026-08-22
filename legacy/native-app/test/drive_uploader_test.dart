import 'dart:convert';
import 'dart:io';

import 'package:farmcamera/drive_uploader.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';

const _authHeaders = <String, String>{'Authorization': 'Bearer test-token'};

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late Directory tempDir;
  late File photo;
  late List<http.Request> requests;

  setUp(() async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    tempDir = Directory.systemTemp.createTempSync('drive_uploader_test');
    photo = File('${tempDir.path}/CAM001_20260821_140509.jpg')
      ..writeAsBytesSync(<int>[0xFF, 0xD8, 0xFF, 0xE0]);
    requests = <http.Request>[];
  });

  tearDown(() {
    if (tempDir.existsSync()) tempDir.deleteSync(recursive: true);
  });

  /// Drive API のフェイク。
  ///
  /// - [existingFolderId] が null なら「フォルダ未作成」として振る舞う
  /// - [createdFolderIds] は作成APIが返すIDを先頭から順に払い出す（尽きたら最後の値）
  /// - [goneFolderIds] に含まれるフォルダを親に指定したアップロードは [goneStatus] を
  ///   返す。利用者が Drive 上でフォルダを消した状況を再現する。可変 Set を渡せば
  ///   「1回目は成功、そのあと消された」を表現できる
  MockClient buildClient({
    String? existingFolderId,
    List<String> createdFolderIds = const <String>['created-folder-id'],
    int uploadStatus = 200,
    int createStatus = 200,
    Set<String>? goneFolderIds,
    int goneStatus = 404,
  }) {
    final gone = goneFolderIds ?? <String>{};
    var createCount = 0;
    return MockClient((request) async {
      requests.add(request);
      final isUpload = request.url.path.startsWith('/upload/');

      if (isUpload) {
        final body = utf8.decode(request.bodyBytes, allowMalformed: true);
        if (gone.any((id) => body.contains('"parents":["$id"]'))) {
          return http.Response('{"error":{"code":$goneStatus}}', goneStatus);
        }
        return http.Response('{"id":"file-1"}', uploadStatus);
      }
      if (request.method == 'GET') {
        final files = existingFolderId == null
            ? <Map<String, String>>[]
            : <Map<String, String>>[
                {'id': existingFolderId, 'name': 'FarmCameraPOC'}
              ];
        return http.Response(jsonEncode({'files': files}), 200);
      }
      // フォルダ作成
      final index = createCount < createdFolderIds.length
          ? createCount
          : createdFolderIds.length - 1;
      createCount++;
      return http.Response(jsonEncode({'id': createdFolderIds[index]}), createStatus);
    });
  }

  DriveUploader buildUploader(
    MockClient client, {
    Map<String, String>? headers = _authHeaders,
    String? accountId = 'tester@example.com',
  }) {
    return DriveUploader(
      authHeadersProvider: () async => headers,
      accountIdProvider: () => accountId,
      httpClient: client,
    );
  }

  /// 直近のアップロードリクエストが指定した親フォルダへ送られたか。
  void expectUploadedTo(String folderId) {
    final uploads = requests.where((r) => r.url.path.startsWith('/upload/'));
    expect(
      utf8.decode(uploads.last.bodyBytes, allowMalformed: true),
      contains('"parents":["$folderId"]'),
    );
  }

  Iterable<http.Request> uploadsOf() =>
      requests.where((r) => r.url.path.startsWith('/upload/'));
  Iterable<http.Request> createsOf() => requests.where(
        (r) => r.method == 'POST' && !r.url.path.startsWith('/upload/'),
      );

  test('フォルダが無ければ FarmCameraPOC を作成し、その配下へ送信する（AGENTS.md 5.3）', () async {
    await buildUploader(buildClient()).upload(photo);

    final create = requests.firstWhere(
      (r) => r.method == 'POST' && !r.url.path.startsWith('/upload/'),
    );
    final createBody = jsonDecode(create.body) as Map<String, dynamic>;
    expect(createBody['name'], 'FarmCameraPOC');
    expect(createBody['mimeType'], 'application/vnd.google-apps.folder');

    final upload = requests.firstWhere((r) => r.url.path.startsWith('/upload/'));
    expect(upload.url.queryParameters['uploadType'], 'multipart');
    final uploadBody = utf8.decode(upload.bodyBytes, allowMalformed: true);
    expect(uploadBody, contains('"parents":["created-folder-id"]'));
    expect(uploadBody, contains('"name":"CAM001_20260821_140509.jpg"'));
  });

  test('既存フォルダが見つかれば作成APIを呼ばない', () async {
    await buildUploader(buildClient(existingFolderId: 'existing-id')).upload(photo);

    final creates = requests.where(
      (r) => r.method == 'POST' && !r.url.path.startsWith('/upload/'),
    );
    expect(creates, isEmpty, reason: '既存フォルダがあるのに作成してはいけない');

    final upload = requests.firstWhere((r) => r.url.path.startsWith('/upload/'));
    expect(
      utf8.decode(upload.bodyBytes, allowMalformed: true),
      contains('"parents":["existing-id"]'),
    );
  });

  test('フォルダIDをキャッシュし、2回目は検索・作成をやり直さない', () async {
    final uploader = buildUploader(buildClient());
    await uploader.upload(photo);
    requests.clear();

    await uploader.upload(photo);

    expect(
      requests.where((r) => r.url.path.startsWith('/upload/')),
      hasLength(1),
      reason: '2回目もアップロード自体は必ず行う',
    );
    expect(
      requests.where((r) => !r.url.path.startsWith('/upload/')),
      isEmpty,
      reason: '2回目は検索も作成もしない',
    );
  });

  test('フォルダIDは SharedPreferences に永続化される（別インスタンスでも再利用）', () async {
    await buildUploader(buildClient()).upload(photo);
    requests.clear();

    // アプリ再起動を模して別インスタンスを作る。インメモリキャッシュ実装なら
    // ここで検索・作成が走ってしまう。
    await buildUploader(buildClient()).upload(photo);

    expect(
      requests.where((r) => !r.url.path.startsWith('/upload/')),
      isEmpty,
      reason: 'キャッシュが永続化されていれば検索・作成は不要',
    );
    expect(
      requests.where((r) => r.url.path.startsWith('/upload/')),
      hasLength(1),
    );
  });

  test('認可ヘッダを取得できなければ送信を試みずに例外を投げる', () async {
    final uploader = buildUploader(buildClient(), headers: null);

    await expectLater(uploader.upload(photo), throwsA(isA<Exception>()));
    expect(requests, isEmpty, reason: '認可が無い状態でAPIを叩いてはいけない');
  });

  test('アップロードが失敗ステータスなら例外を投げる（呼び出し側がファイルを残せるように）', () async {
    final uploader = buildUploader(buildClient(uploadStatus: 403));

    await expectLater(uploader.upload(photo), throwsA(isA<Exception>()));
    expect(photo.existsSync(), isTrue, reason: 'Uploader はローカルファイルを消さない');
  });

  test('認可ヘッダをすべてのリクエストに付与する', () async {
    await buildUploader(buildClient()).upload(photo);

    for (final r in requests) {
      expect(r.headers['Authorization'], 'Bearer test-token');
    }
  });

  test('フォルダ作成に失敗したらアップロードを試みずに例外を投げる', () async {
    // 圃場は電波が悪い（risk-assessment.md 反対2）。初回送信でフォルダ作成が
    // 失敗する状況は現実に起きる。ここで例外を投げずに進むと、親フォルダが
    // 決まらないまま送信して静かに失われる。
    final uploader = buildUploader(buildClient(createStatus: 500));

    await expectLater(uploader.upload(photo), throwsA(isA<Exception>()));
    expect(
      requests.where((r) => r.url.path.startsWith('/upload/')),
      isEmpty,
      reason: '親フォルダが確定していないのに送信してはいけない',
    );
    expect(photo.existsSync(), isTrue, reason: '失敗時もローカルファイルは残す');
  });

  test('フォルダ作成に失敗したらIDをキャッシュせず、次回やり直す', () async {
    final failing = buildUploader(buildClient(createStatus: 500));
    await expectLater(failing.upload(photo), throwsA(isA<Exception>()));
    requests.clear();

    // 通信が回復した状況。壊れた値をキャッシュしていれば作成をやり直さない。
    await buildUploader(buildClient()).upload(photo);

    final creates = requests.where(
      (r) => r.method == 'POST' && !r.url.path.startsWith('/upload/'),
    );
    expect(creates, hasLength(1), reason: '失敗を引きずらずフォルダ作成をやり直すこと');
    expect(
      utf8.decode(
        requests.firstWhere((r) => r.url.path.startsWith('/upload/')).bodyBytes,
        allowMalformed: true,
      ),
      contains('"parents":["created-folder-id"]'),
    );
  });

  // --- #33: キャッシュしたフォルダIDが無効になった場合 ---------------------

  test('キャッシュ済みフォルダが消えていたら（404）解決し直して同じ送信の中で再送する', () async {
    // 利用者が Drive 上で FarmCameraPOC をゴミ箱へ入れた状況。
    // キャッシュを信じ続けると再インストールするまで送信が通らなくなる。
    final gone = <String>{};
    final uploader = buildUploader(
      buildClient(createdFolderIds: ['folder-1', 'folder-2'], goneFolderIds: gone),
    );
    await uploader.upload(photo);
    gone.add('folder-1');
    requests.clear();

    await uploader.upload(photo); // 例外を投げずに完了すること

    expect(uploadsOf(), hasLength(2), reason: '404の1回と、やり直しの1回');
    expect(createsOf(), hasLength(1), reason: 'キャッシュを捨てて作り直す');
    expectUploadedTo('folder-2');
  });

  test('403 でも同じようにキャッシュを捨ててやり直す', () async {
    final gone = <String>{};
    final uploader = buildUploader(
      buildClient(
        createdFolderIds: ['folder-1', 'folder-2'],
        goneFolderIds: gone,
        goneStatus: 403,
      ),
    );
    await uploader.upload(photo);
    gone.add('folder-1');
    requests.clear();

    await uploader.upload(photo);

    expectUploadedTo('folder-2');
  });

  test('やり直しは1回だけ。2回目も失敗するなら例外を投げる', () async {
    // 作り直しても同じIDが返る＝原因がフォルダ以外にある状況。
    // 際限なくやり直すと1分間隔の撮影に追いつけなくなる。
    final uploader = buildUploader(
      buildClient(createdFolderIds: ['folder-1'], goneFolderIds: {'folder-1'}),
    );

    await expectLater(uploader.upload(photo), throwsA(isA<Exception>()));

    expect(uploadsOf(), hasLength(2), reason: '初回とやり直しの計2回で打ち切る');
    expect(photo.existsSync(), isTrue, reason: '失敗時もローカルファイルは残す');
  });

  test('やり直して成功したフォルダIDが次回に引き継がれる', () async {
    final gone = <String>{};
    final uploader = buildUploader(
      buildClient(createdFolderIds: ['folder-1', 'folder-2'], goneFolderIds: gone),
    );
    await uploader.upload(photo);
    gone.add('folder-1');
    await uploader.upload(photo);
    requests.clear();

    await uploader.upload(photo);

    expect(createsOf(), isEmpty, reason: 'やり直しの結果もキャッシュされること');
    expect(uploadsOf(), hasLength(1), reason: '3回目は一発で通る');
    expectUploadedTo('folder-2');
  });

  test('別アカウントでサインインしたら前アカウントのフォルダIDを使わない', () async {
    // drive.file スコープでは他アカウントのファイルは見えないため、
    // 前のIDを送り続けると404が出続ける。キャッシュはアカウント単位で持つ。
    await buildUploader(
      buildClient(createdFolderIds: ['folder-a']),
      accountId: 'a@example.com',
    ).upload(photo);
    requests.clear();

    await buildUploader(
      buildClient(createdFolderIds: ['folder-b']),
      accountId: 'b@example.com',
    ).upload(photo);

    expect(createsOf(), hasLength(1), reason: '別アカウントでは自分のフォルダを作る');
    expectUploadedTo('folder-b');
  });

  test('同じアカウントに戻ればキャッシュを再利用する', () async {
    await buildUploader(
      buildClient(createdFolderIds: ['folder-a']),
      accountId: 'a@example.com',
    ).upload(photo);
    await buildUploader(
      buildClient(createdFolderIds: ['folder-b']),
      accountId: 'b@example.com',
    ).upload(photo);
    requests.clear();

    await buildUploader(buildClient(), accountId: 'a@example.com').upload(photo);

    expect(createsOf(), isEmpty, reason: 'アカウントAのIDは消さずに保持しておくこと');
    expectUploadedTo('folder-a');
  });

  test('アカウントを識別できなければ送信を試みずに例外を投げる', () async {
    // 識別子が無いままキャッシュすると全アカウントで同じ鍵を共有してしまう。
    final uploader = buildUploader(buildClient(), accountId: null);

    await expectLater(uploader.upload(photo), throwsA(isA<Exception>()));
    expect(requests, isEmpty);
  });
}
