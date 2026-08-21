import 'dart:io';

/// 撮影画像の送信先を抽象化する。
///
/// `risk-assessment.md` STEP4 が「送信先未定のためインターフェース化」を
/// 求めているため、Drive 以外（自前バックエンド等）へ差し替えられるようにし、
/// テストではフェイクに置き換えられるようにしている。
abstract class PhotoUploader {
  /// 送信に失敗した場合は例外を投げる。
  ///
  /// 実装は**ローカルファイルを削除してはならない**。未送信データの保護は
  /// `risk-assessment.md`「追加合意」2 の要件。
  Future<void> upload(File file);
}
