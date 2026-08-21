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
  /// [existingFolderId] が null なら「フォルダ未作成」として振る舞う。
  MockClient buildClient({
    String? existingFolderId,
    String createdFolderId = 'created-folder-id',
    int uploadStatus = 200,
  }) {
    return MockClient((request) async {
      requests.add(request);
      final isUpload = request.url.path.startsWith('/upload/');

      if (isUpload) {
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
      return http.Response(jsonEncode({'id': createdFolderId}), 200);
    });
  }

  DriveUploader buildUploader(
    MockClient client, {
    Map<String, String>? headers = _authHeaders,
  }) {
    return DriveUploader(
      authHeadersProvider: () async => headers,
      httpClient: client,
    );
  }

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
      requests.every((r) => r.url.path.startsWith('/upload/')),
      isTrue,
      reason: '2回目はアップロードだけを行うべき',
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
}
