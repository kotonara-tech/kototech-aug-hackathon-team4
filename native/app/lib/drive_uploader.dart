import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

import 'photo_uploader.dart';

/// Drive API 用の認可ヘッダを供給する。
///
/// `google_sign_in` への依存を [DriveUploader] から切り離すための注入点。
/// 認可が取れない場合は `null` を返す。
typedef AuthHeadersProvider = Future<Map<String, String>?> Function();

/// サインイン中のアカウントを識別する文字列を供給する。
///
/// フォルダIDのキャッシュをアカウント単位に分けるために使う。未サインインなら
/// `null` を返す。
typedef AccountIdProvider = String? Function();

/// Uploads captured photos to a dedicated "FarmCameraPOC" folder in the
/// signed-in user's Google Drive, using the `drive.file` scope so the app
/// only ever sees files/folders it created itself.
class DriveUploader implements PhotoUploader {
  DriveUploader({
    required this.authHeadersProvider,
    required this.accountIdProvider,
    this.folderName = 'FarmCameraPOC',
    http.Client? httpClient,
  }) : _http = httpClient ?? http.Client();

  final AuthHeadersProvider authHeadersProvider;
  final AccountIdProvider accountIdProvider;
  final String folderName;
  final http.Client _http;

  /// フォルダIDのキャッシュキー。
  ///
  /// `drive.file` スコープが見せるのは「このアプリがこのアカウントで作った
  /// ファイル」だけなので、アカウントが変われば同じIDには手が届かない。
  /// 単一キーで共有すると、アカウントを切り替えた瞬間から 404 が出続ける。
  static String _folderIdPrefsKey(String accountId) => 'driveFolderId:$accountId';

  @override
  Future<void> upload(File file) async {
    final authHeaders = await authHeadersProvider();
    if (authHeaders == null) {
      throw Exception('Drive認可を取得できませんでした。');
    }
    final accountId = accountIdProvider();
    if (accountId == null) {
      throw Exception('サインイン中のアカウントを特定できませんでした。');
    }

    final bytes = await file.readAsBytes();
    final fileName = file.uri.pathSegments.last;

    var folderId = await _ensureFolderId(authHeaders, accountId);
    var response = await _postMultipart(authHeaders, folderId, fileName, bytes);

    // 403/404 は「キャッシュしたフォルダにもう手が届かない」を意味する。
    // 利用者が Drive 上でフォルダを消した場合などに、キャッシュを信じ続けると
    // 再インストールするまで送信が通らなくなる。一度だけ捨ててやり直す。
    if (_isFolderUnreachable(response.statusCode)) {
      await _discardFolderId(accountId);
      folderId = await _ensureFolderId(authHeaders, accountId);
      response = await _postMultipart(authHeaders, folderId, fileName, bytes);
    }

    if (response.statusCode != 200 && response.statusCode != 201) {
      throw Exception(
        'Drive upload failed (${response.statusCode}): ${response.body}',
      );
    }
  }

  /// やり直す価値があるステータスか。
  ///
  /// 際限なくやり直すと1分間隔の撮影に追いつけなくなるため、判定はここに限り、
  /// 再試行は [upload] の中で1回だけに閉じる。
  static bool _isFolderUnreachable(int statusCode) =>
      statusCode == 403 || statusCode == 404;

  Future<http.Response> _postMultipart(
    Map<String, String> authHeaders,
    String folderId,
    String fileName,
    Uint8List bytes,
  ) {
    final boundary = 'farmcamera-${DateTime.now().microsecondsSinceEpoch}';
    final metadata = jsonEncode({
      'name': fileName,
      'parents': [folderId],
    });

    final body = BytesBuilder();
    void addText(String text) => body.add(utf8.encode(text));
    addText('--$boundary\r\n');
    addText('Content-Type: application/json; charset=UTF-8\r\n\r\n');
    addText('$metadata\r\n');
    addText('--$boundary\r\n');
    addText('Content-Type: image/jpeg\r\n\r\n');
    body.add(bytes);
    addText('\r\n--$boundary--');

    return _http.post(
      Uri.parse(
        'https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart',
      ),
      headers: {
        ...authHeaders,
        'Content-Type': 'multipart/related; boundary=$boundary',
      },
      body: body.toBytes(),
    );
  }

  Future<void> _discardFolderId(String accountId) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_folderIdPrefsKey(accountId));
  }

  Future<String> _ensureFolderId(
    Map<String, String> authHeaders,
    String accountId,
  ) async {
    final prefs = await SharedPreferences.getInstance();
    final key = _folderIdPrefsKey(accountId);
    final cached = prefs.getString(key);
    if (cached != null) return cached;

    final searchUri = Uri.https(
      'www.googleapis.com',
      '/drive/v3/files',
      {
        'q':
            "mimeType='application/vnd.google-apps.folder' and name='$folderName' and trashed=false",
        'spaces': 'drive',
        'fields': 'files(id,name)',
      },
    );
    final searchRes = await _http.get(searchUri, headers: authHeaders);
    if (searchRes.statusCode == 200) {
      final data = jsonDecode(searchRes.body) as Map<String, dynamic>;
      final files = data['files'] as List<dynamic>;
      if (files.isNotEmpty) {
        final id = files.first['id'] as String;
        await prefs.setString(key, id);
        return id;
      }
    }

    final createRes = await _http.post(
      Uri.parse('https://www.googleapis.com/drive/v3/files'),
      headers: {...authHeaders, 'Content-Type': 'application/json'},
      body: jsonEncode({
        'name': folderName,
        'mimeType': 'application/vnd.google-apps.folder',
      }),
    );
    if (createRes.statusCode != 200 && createRes.statusCode != 201) {
      throw Exception(
        'Drive folder creation failed (${createRes.statusCode}): ${createRes.body}',
      );
    }
    final id = (jsonDecode(createRes.body) as Map<String, dynamic>)['id'] as String;
    await prefs.setString(key, id);
    return id;
  }
}
