import 'dart:async';
import 'dart:io';

import 'package:camera/camera.dart';
import 'package:flutter/material.dart';
import 'package:google_sign_in/google_sign_in.dart';
import 'package:path_provider/path_provider.dart';
import 'package:permission_handler/permission_handler.dart';

import 'capture_session.dart';
import 'drive_uploader.dart';

const _driveScopes = <String>['https://www.googleapis.com/auth/drive.file'];

// Duration は primitive equality を持たないため const マップのキーにできない。
final _intervalLabels = <Duration, String>{
  Duration(minutes: 1): '1分',
  Duration(minutes: 5): '5分',
  Duration(minutes: 10): '10分',
  Duration(minutes: 30): '30分',
};

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const FarmCameraApp());
}

class FarmCameraApp extends StatelessWidget {
  const FarmCameraApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '定点撮影POC',
      theme: ThemeData(colorSchemeSeed: const Color(0xFF236B50), useMaterial3: true),
      home: const CaptureScreen(),
    );
  }
}

/// カメラ・認証という「実機がないと動かない部分」を担い、撮影の判断そのものは
/// [CaptureSession] に委ねる薄い UI 層。周期実行の [Timer] もここが持つ。
class CaptureScreen extends StatefulWidget {
  const CaptureScreen({super.key});

  @override
  State<CaptureScreen> createState() => _CaptureScreenState();
}

class _CaptureScreenState extends State<CaptureScreen> {
  late final CaptureSession _session;

  CameraController? _cameraController;
  String? _cameraError;

  GoogleSignInAccount? _account;
  bool _signingIn = false;
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    _session = CaptureSession(
      capturer: _capturePhoto,
      uploader: DriveUploader(authHeadersProvider: _driveAuthHeaders),
    );
    _bootstrap();
  }

  Future<void> _bootstrap() async {
    await _initCamera();
    await _initGoogleSignIn();
  }

  Future<void> _initCamera() async {
    final status = await Permission.camera.request();
    if (!status.isGranted) {
      setState(() => _cameraError = 'カメラ権限が許可されていません。');
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
      if (!mounted) return;
      setState(() {
        _cameraController = controller;
        _cameraError = null;
      });
    } catch (e) {
      setState(() => _cameraError = 'カメラの初期化に失敗しました: $e');
    }
  }

  Future<void> _initGoogleSignIn() async {
    await GoogleSignIn.instance.initialize();
    final account = await GoogleSignIn.instance.attemptLightweightAuthentication();
    if (!mounted) return;
    setState(() => _account = account);
    _session.isSignedIn = account != null;
  }

  Future<void> _signIn() async {
    setState(() => _signingIn = true);
    try {
      final account = await GoogleSignIn.instance.authenticate(scopeHint: _driveScopes);
      if (!mounted) return;
      setState(() => _account = account);
      _session.isSignedIn = true;
    } catch (e) {
      _showError('サインインに失敗しました: $e');
    } finally {
      if (mounted) setState(() => _signingIn = false);
    }
  }

  Future<Map<String, String>?> _driveAuthHeaders() async {
    final account = _account;
    if (account == null) return null;
    return account.authorizationClient.authorizationHeaders(
      _driveScopes,
      promptIfNecessary: true,
    );
  }

  /// [CaptureSession] から呼ばれる撮影処理。指定された名前で端末内へ保存する。
  Future<File> _capturePhoto(String fileName) async {
    final controller = _cameraController;
    if (controller == null || !controller.value.isInitialized) {
      throw StateError('カメラが初期化されていません。');
    }
    final picture = await controller.takePicture();
    final tempDir = await getTemporaryDirectory();
    return File(picture.path).copy('${tempDir.path}/$fileName');
  }

  void _startCapture() {
    if (!_session.start()) return;
    _timer = Timer.periodic(_session.interval, (_) => _session.captureOnce());
    unawaited(_session.captureOnce());
  }

  void _stopCapture() {
    _timer?.cancel();
    _timer = null;
    _session.stop();
  }

  void _showError(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
  }

  @override
  void dispose() {
    _timer?.cancel();
    _cameraController?.dispose();
    _session.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('定点撮影POC')),
      body: Column(
        children: [
          Expanded(flex: 3, child: _buildCameraPreview()),
          Expanded(
            flex: 2,
            child: ListenableBuilder(
              listenable: _session,
              builder: (context, _) => _buildControls(),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildCameraPreview() {
    if (_cameraError != null) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(_cameraError!, textAlign: TextAlign.center),
              const SizedBox(height: 12),
              ElevatedButton(onPressed: _initCamera, child: const Text('再試行')),
            ],
          ),
        ),
      );
    }
    final controller = _cameraController;
    if (controller == null || !controller.value.isInitialized) {
      return const Center(child: CircularProgressIndicator());
    }
    return CameraPreview(controller);
  }

  Widget _buildControls() {
    final canStart =
        !_session.isRunning && _cameraController != null && _session.isSignedIn;
    return Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  _account == null ? '未サインイン' : 'サインイン中: ${_account!.email}',
                  overflow: TextOverflow.ellipsis,
                ),
              ),
              if (_account == null)
                ElevatedButton(
                  onPressed: _signingIn ? null : _signIn,
                  child: Text(_signingIn ? '接続中' : 'Googleでサインイン'),
                ),
            ],
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              const Text('撮影間隔:'),
              const SizedBox(width: 8),
              DropdownButton<Duration>(
                value: _session.interval,
                items: kCaptureIntervals
                    .map((d) =>
                        DropdownMenuItem(value: d, child: Text(_intervalLabels[d]!)))
                    .toList(),
                onChanged: _session.isRunning
                    ? null
                    : (v) {
                        if (v != null) _session.setInterval(v);
                      },
              ),
              const Spacer(),
              ElevatedButton(
                onPressed: canStart ? _startCapture : null,
                child: const Text('開始'),
              ),
              const SizedBox(width: 8),
              ElevatedButton(
                onPressed: _session.isRunning ? _stopCapture : null,
                child: const Text('停止'),
              ),
            ],
          ),
          const SizedBox(height: 12),
          Text('撮影枚数: ${_session.capturedCount}  送信枚数: ${_session.sentCount}'),
          Text('最終送信: ${_session.lastSentAt?.toString() ?? '-'}'),
          if (_session.lastError != null)
            Text(_session.lastError!, style: const TextStyle(color: Colors.red)),
        ],
      ),
    );
  }
}
