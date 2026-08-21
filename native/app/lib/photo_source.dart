import 'dart:io';

import 'package:camera/camera.dart';
import 'package:flutter/material.dart';
import 'package:path_provider/path_provider.dart';
import 'package:permission_handler/permission_handler.dart';

/// 撮影ハードウェアの抽象。
///
/// カメラ権限・プレビュー・撮影という「実機がないと動かない部分」をここへ
/// 隔離し、UI とセッションを実機非依存に保つ。ウィジェットテストではフェイクを
/// 差し込んで画面の状態遷移だけを検証できる。
abstract class PhotoSource {
  /// 権限要求とカメラ初期化。失敗しても例外は投げず [errorMessage] に残す。
  Future<void> initialize();

  /// 撮影可能な状態か。false の間は撮影を開始させない。
  bool get isReady;

  /// 直近の初期化エラー。正常時は null。
  String? get errorMessage;

  /// [fileName] の名前で端末内へ保存し、そのファイルを返す。
  Future<File> capture(String fileName);

  /// プレビュー表示。準備できていない場合の表示も実装側が返す。
  Widget buildPreview();

  void dispose();
}

/// `camera` + `permission_handler` による実装。
class CameraPhotoSource implements PhotoSource {
  CameraController? _controller;
  String? _errorMessage;

  @override
  bool get isReady => _controller?.value.isInitialized ?? false;

  @override
  String? get errorMessage => _errorMessage;

  @override
  Future<void> initialize() async {
    final status = await Permission.camera.request();
    if (!status.isGranted) {
      _errorMessage = 'カメラ権限が許可されていません。';
      return;
    }
    try {
      final cameras = await availableCameras();
      final backCamera = cameras.firstWhere(
        (c) => c.lensDirection == CameraLensDirection.back,
        orElse: () => cameras.first,
      );
      final controller = CameraController(
        backCamera,
        ResolutionPreset.medium,
        enableAudio: false,
      );
      await controller.initialize();
      _controller = controller;
      _errorMessage = null;
    } catch (e) {
      _errorMessage = 'カメラの初期化に失敗しました: $e';
    }
  }

  @override
  Future<File> capture(String fileName) async {
    final controller = _controller;
    if (controller == null || !controller.value.isInitialized) {
      throw StateError('カメラが初期化されていません。');
    }
    final picture = await controller.takePicture();
    final tempDir = await getTemporaryDirectory();
    return File(picture.path).copy('${tempDir.path}/$fileName');
  }

  @override
  Widget buildPreview() {
    final controller = _controller;
    if (controller == null || !controller.value.isInitialized) {
      return const Center(child: CircularProgressIndicator());
    }
    return CameraPreview(controller);
  }

  @override
  void dispose() {
    _controller?.dispose();
    _controller = null;
  }
}
