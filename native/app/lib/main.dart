import 'dart:async';
import 'dart:io';

import 'package:camera/camera.dart';
import 'package:flutter/material.dart';
import 'package:google_sign_in/google_sign_in.dart';
import 'package:path_provider/path_provider.dart';
import 'package:permission_handler/permission_handler.dart';

import 'drive_uploader.dart';

const _driveScopes = <String>['https://www.googleapis.com/auth/drive.file'];
const _cameraId = 'CAM001';

final _intervalOptions = <Duration, String>{
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

class CaptureScreen extends StatefulWidget {
  const CaptureScreen({super.key});

  @override
  State<CaptureScreen> createState() => _CaptureScreenState();
}

class _CaptureScreenState extends State<CaptureScreen> {
  final _driveUploader = DriveUploader();

  CameraController? _cameraController;
  String? _cameraError;

  GoogleSignInAccount? _account;
  bool _signingIn = false;

  Duration _interval = _intervalOptions.keys.first;
  bool _isRunning = false;
  Timer? _timer;

  int _capturedCount = 0;
  int _sentCount = 0;
  DateTime? _lastSentAt;
  String? _lastError;

  @override
  void initState() {
    super.initState();
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
  }

  Future<void> _signIn() async {
    setState(() => _signingIn = true);
    try {
      final account = await GoogleSignIn.instance.authenticate(scopeHint: _driveScopes);
      setState(() => _account = account);
    } catch (e) {
      setState(() => _lastError = 'サインインに失敗しました: $e');
    } finally {
      if (mounted) setState(() => _signingIn = false);
    }
  }

  void _startCapture() {
    if (_isRunning || _cameraController == null || _account == null) return;
    setState(() {
      _isRunning = true;
      _lastError = null;
    });
    _timer = Timer.periodic(_interval, (_) => _captureAndUpload());
    unawaited(_captureAndUpload());
  }

  void _stopCapture() {
    _timer?.cancel();
    _timer = null;
    setState(() => _isRunning = false);
  }

  Future<void> _captureAndUpload() async {
    final controller = _cameraController;
    if (controller == null || !controller.value.isInitialized) return;
    if (controller.value.isTakingPicture) return;

    try {
      final picture = await controller.takePicture();
      final now = DateTime.now();
      final fileName = '${_cameraId}_${_formatTimestamp(now)}.jpg';
      final tempDir = await getTemporaryDirectory();
      final savedFile = await File(picture.path).copy('${tempDir.path}/$fileName');

      setState(() => _capturedCount++);

      await _uploadFile(savedFile);
    } catch (e) {
      setState(() => _lastError = '撮影に失敗しました: $e');
    }
  }

  Future<void> _uploadFile(File file) async {
    final account = _account;
    if (account == null) {
      setState(() => _lastError = 'Googleアカウント未サインインのため送信できません。');
      return;
    }
    try {
      final headers = await account.authorizationClient.authorizationHeaders(
        _driveScopes,
        promptIfNecessary: true,
      );
      if (headers == null) {
        setState(() => _lastError = 'Drive認可を取得できませんでした。');
        return;
      }
      await _driveUploader.uploadFile(file, headers);
      setState(() {
        _sentCount++;
        _lastSentAt = DateTime.now();
        _lastError = null;
      });
    } catch (e) {
      // 送信失敗時もファイルは端末に残したままにする（自動削除しない）。
      setState(() => _lastError = '送信に失敗しました: $e');
    }
  }

  String _formatTimestamp(DateTime t) {
    String pad2(int n) => n.toString().padLeft(2, '0');
    return '${t.year}${pad2(t.month)}${pad2(t.day)}_${pad2(t.hour)}${pad2(t.minute)}${pad2(t.second)}';
  }

  @override
  void dispose() {
    _timer?.cancel();
    _cameraController?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('定点撮影POC')),
      body: Column(
        children: [
          Expanded(flex: 3, child: _buildCameraPreview()),
          Expanded(flex: 2, child: _buildControls()),
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
                  child: Text(_signingIn ? '接続中…' : 'Googleでサインイン'),
                ),
            ],
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              const Text('撮影間隔:'),
              const SizedBox(width: 8),
              DropdownButton<Duration>(
                value: _interval,
                items: _intervalOptions.entries
                    .map((e) => DropdownMenuItem(value: e.key, child: Text(e.value)))
                    .toList(),
                onChanged: _isRunning
                    ? null
                    : (v) {
                        if (v != null) setState(() => _interval = v);
                      },
              ),
              const Spacer(),
              ElevatedButton(
                onPressed: _isRunning || _cameraController == null || _account == null
                    ? null
                    : _startCapture,
                child: const Text('開始'),
              ),
              const SizedBox(width: 8),
              ElevatedButton(
                onPressed: _isRunning ? _stopCapture : null,
                child: const Text('停止'),
              ),
            ],
          ),
          const SizedBox(height: 12),
          Text('撮影枚数: $_capturedCount　送信枚数: $_sentCount'),
          Text('最終送信: ${_lastSentAt?.toString() ?? '-'}'),
          if (_lastError != null)
            Text(_lastError!, style: const TextStyle(color: Colors.red)),
        ],
      ),
    );
  }
}
