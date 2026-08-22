import 'dart:io';

import 'package:path_provider/path_provider.dart';

/// 撮影ファイルの保存先を返す（無ければ作る）。
///
/// 一時ディレクトリ（`getTemporaryDirectory`）は Android の `cacheDir` に対応し、
/// **OS がストレージ逼迫時に予告なく消せる**。「直近ぶんだけ持ち、超過ぶんは
/// アプリが消す」という保管方針（`AGENTS.md` 5.4）は、消える時期をアプリが
/// 決められて初めて成立するので、消えない領域に置く。
Future<Directory> resolvePhotoDirectory() async {
  final base = await getApplicationDocumentsDirectory();
  return Directory('${base.path}/photos').create(recursive: true);
}

/// 端末に残す写真ファイルの管理（#32）。
///
/// 長期保管は Web アプリ側の責務で、端末は直近ぶんだけを持つ。撮影は1分間隔で
/// 続くため、消す責任をどこかに置かないと端末が埋まる。
abstract class PhotoFileStore {
  /// [keep] に無いファイルを保存先から削除する。
  Future<void> retainOnly(Set<String> keep);
}

/// 保存先ディレクトリを走査して削除する実装。
class DirectoryPhotoFileStore implements PhotoFileStore {
  DirectoryPhotoFileStore(this.directory);

  /// 保存先の解決。実機では [resolvePhotoDirectory]、テストでは一時領域を渡す。
  final Future<Directory> Function() directory;

  @override
  Future<void> retainOnly(Set<String> keep) async {
    final dir = await directory();
    if (!await dir.exists()) return;
    await for (final entity in dir.list()) {
      // ファイルだけを対象にする。将来サブディレクトリを置いても巻き込まない。
      if (entity is! File) continue;
      if (keep.contains(entity.uri.pathSegments.last)) continue;
      await entity.delete();
    }
  }
}

/// 何も消さない実装。既定値とテスト用。
class NoopPhotoFileStore implements PhotoFileStore {
  const NoopPhotoFileStore();

  @override
  Future<void> retainOnly(Set<String> keep) async {}
}
