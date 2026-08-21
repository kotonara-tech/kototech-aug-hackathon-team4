import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

/// Uploads captured photos to a dedicated "FarmCameraPOC" folder in the
/// signed-in user's Google Drive, using the `drive.file` scope so the app
/// only ever sees files/folders it created itself.
class DriveUploader {
  DriveUploader({this.folderName = 'FarmCameraPOC'});

  final String folderName;
  static const _folderIdPrefsKey = 'driveFolderId';

  Future<void> uploadFile(File file, Map<String, String> authHeaders) async {
    final folderId = await _ensureFolderId(authHeaders);
    final bytes = await file.readAsBytes();
    final fileName = file.uri.pathSegments.last;

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

    final response = await http.post(
      Uri.parse(
        'https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart',
      ),
      headers: {
        ...authHeaders,
        'Content-Type': 'multipart/related; boundary=$boundary',
      },
      body: body.toBytes(),
    );

    if (response.statusCode != 200 && response.statusCode != 201) {
      throw Exception(
        'Drive upload failed (${response.statusCode}): ${response.body}',
      );
    }
  }

  Future<String> _ensureFolderId(Map<String, String> authHeaders) async {
    final prefs = await SharedPreferences.getInstance();
    final cached = prefs.getString(_folderIdPrefsKey);
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
    final searchRes = await http.get(searchUri, headers: authHeaders);
    if (searchRes.statusCode == 200) {
      final data = jsonDecode(searchRes.body) as Map<String, dynamic>;
      final files = data['files'] as List<dynamic>;
      if (files.isNotEmpty) {
        final id = files.first['id'] as String;
        await prefs.setString(_folderIdPrefsKey, id);
        return id;
      }
    }

    final createRes = await http.post(
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
    await prefs.setString(_folderIdPrefsKey, id);
    return id;
  }
}
