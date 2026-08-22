/// 撮影ファイル名の生成。
///
/// `AGENTS.md` 5.3 で定めた `CAM001_yyyyMMdd_HHmmss.jpg` 形式に揃える。
/// 秒までしか持たないため同一秒の撮影は同名になるが、撮影間隔は最短でも
/// 1分（`AGENTS.md` 2節）なので MVP では衝突しない。
String buildPhotoFileName(String cameraId, DateTime at) {
  String pad2(int n) => n.toString().padLeft(2, '0');
  final date = '${at.year}${pad2(at.month)}${pad2(at.day)}';
  final time = '${pad2(at.hour)}${pad2(at.minute)}${pad2(at.second)}';
  return '${cameraId}_${date}_$time.jpg';
}
