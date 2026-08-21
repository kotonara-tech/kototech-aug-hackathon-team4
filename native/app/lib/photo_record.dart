import 'dart:convert';

import 'package:shared_preferences/shared_preferences.dart';

/// 1枚の写真の送信状態。
enum PhotoSendState {
  /// 撮影済み・未送信。端末内にだけ存在する。
  pending,

  /// Drive へ送信済み。
  sent,

  /// 送信を試みて失敗した。ファイルは端末に残っている（`AGENTS.md` 5.2-5）。
  failed,
}

/// 撮影1枚ぶんのメタデータ（`risk-assessment.md` 実装タスク3 / #4）。
///
/// 位置情報は MVP では取得せず、**圃場ID・撮影日時**で写真を管理する方針。
class PhotoRecord {
  const PhotoRecord({
    required this.fileName,
    required this.fieldId,
    required this.capturedAt,
    this.state = PhotoSendState.pending,
  });

  factory PhotoRecord.fromJson(Map<String, dynamic> json) {
    return PhotoRecord(
      fileName: json['fileName'] as String,
      fieldId: json['fieldId'] as String,
      capturedAt: DateTime.parse(json['capturedAt'] as String),
      state: _stateFromName(json['state'] as String?),
    );
  }

  final String fileName;

  /// 圃場ID。MVP では端末IDのプレースホルダー `CAM001` を入れる
  /// （`AGENTS.md` 5.3。実際の圃場との紐付けは #21 で決める）。
  final String fieldId;

  final DateTime capturedAt;
  final PhotoSendState state;

  /// 送信状態だけを差し替えた複製を返す。
  ///
  /// 汎用の copyWith にしないのは、使われない分岐（テストされない防御コード）
  /// を残さないため。差し替えたいのは今のところ送信状態だけ。
  PhotoRecord withState(PhotoSendState state) {
    return PhotoRecord(
      fileName: fileName,
      fieldId: fieldId,
      capturedAt: capturedAt,
      state: state,
    );
  }

  Map<String, dynamic> toJson() => <String, dynamic>{
        'fileName': fileName,
        'fieldId': fieldId,
        // toIso8601String はローカル時刻ならオフセットを付けない。
        // 読み戻しもローカルとして解釈されるので往復で一致する。
        'capturedAt': capturedAt.toIso8601String(),
        'state': state.name,
      };

  /// 未知の名前は「未送信」として扱う。将来状態が増えた版で書かれたデータを
  /// 読んでも、アプリが起動不能にならないようにするため。
  static PhotoSendState _stateFromName(String? name) {
    for (final s in PhotoSendState.values) {
      if (s.name == name) return s;
    }
    return PhotoSendState.pending;
  }
}

/// 撮影記録の永続化先。テストで差し替えられるように抽象化する。
abstract class PhotoRecordStore {
  Future<List<PhotoRecord>> load();
  Future<void> save(List<PhotoRecord> records);
}

/// 端末内（SharedPreferences）へJSON配列として保存する実装。
class SharedPreferencesPhotoRecordStore implements PhotoRecordStore {
  static const _prefsKey = 'photo_records';

  @override
  Future<List<PhotoRecord>> load() async {
    final prefs = await SharedPreferences.getInstance();
    final raw = prefs.getString(_prefsKey);
    if (raw == null) return <PhotoRecord>[];
    try {
      final list = jsonDecode(raw) as List<dynamic>;
      return list
          .map((e) => PhotoRecord.fromJson(e as Map<String, dynamic>))
          .toList();
    } catch (_) {
      // 旧形式や破損データで起動不能になるより、履歴を失うほうがまし。
      // 写真ファイル自体は端末に残っているため復旧の余地がある。
      return <PhotoRecord>[];
    }
  }

  @override
  Future<void> save(List<PhotoRecord> records) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(
      _prefsKey,
      jsonEncode(records.map((r) => r.toJson()).toList()),
    );
  }
}

/// テストと既定値のための実装。永続化しない。
class InMemoryPhotoRecordStore implements PhotoRecordStore {
  List<PhotoRecord> _records = <PhotoRecord>[];

  @override
  Future<List<PhotoRecord>> load() async => List<PhotoRecord>.of(_records);

  @override
  Future<void> save(List<PhotoRecord> records) async {
    // 呼び出し側がリストを変更しても記録が壊れないようコピーを持つ。
    _records = List<PhotoRecord>.of(records);
  }
}
