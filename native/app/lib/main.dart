import 'package:flutter/material.dart';

import 'auth_gateway.dart';
import 'capture_scheduler.dart';
import 'capture_session.dart';
import 'drive_uploader.dart';
import 'photo_source.dart';
import 'photo_uploader.dart';
import 'wake_lock.dart';

final _intervalLabels = <Duration, String>{
  const Duration(minutes: 1): '1分',
  const Duration(minutes: 5): '5分',
  const Duration(minutes: 10): '10分',
  const Duration(minutes: 30): '30分',
};

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(
    FarmCameraApp(
      source: CameraPhotoSource(),
      auth: GoogleAuthGateway(),
    ),
  );
}

class FarmCameraApp extends StatelessWidget {
  const FarmCameraApp({
    super.key,
    required this.source,
    required this.auth,
    this.uploader,
    this.wakeLock,
  });

  final PhotoSource source;
  final AuthGateway auth;

  /// 省略時は Drive へ送る。ウィジェットテストでフェイクを差し込むための口。
  final PhotoUploader? uploader;

  /// 省略時は端末の画面スリープを抑止する。テストで差し替えるための口。
  final WakeLock? wakeLock;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '定点撮影POC',
      theme: ThemeData(colorSchemeSeed: const Color(0xFF236B50), useMaterial3: true),
      home: CaptureScreen(
        source: source,
        auth: auth,
        uploader: uploader,
        wakeLock: wakeLock,
      ),
    );
  }
}

/// 単一画面（`AGENTS.md` 5.1）。上からプレビュー・操作・ステータスパネル。
///
/// 撮影の判断は [CaptureSession]、周期実行は [CaptureScheduler]、ハードウェアと
/// 認証は [PhotoSource] / [AuthGateway] が担い、この画面は配線と描画に徹する。
class CaptureScreen extends StatefulWidget {
  const CaptureScreen({
    super.key,
    required this.source,
    required this.auth,
    this.uploader,
    this.wakeLock,
  });

  final PhotoSource source;
  final AuthGateway auth;
  final PhotoUploader? uploader;

  /// 省略時は端末の画面スリープを抑止する。テストで差し替えるための口。
  final WakeLock? wakeLock;

  @override
  State<CaptureScreen> createState() => _CaptureScreenState();
}

class _CaptureScreenState extends State<CaptureScreen> {
  late final CaptureSession _session;
  final _scheduler = CaptureScheduler();

  bool _signingIn = false;

  @override
  void initState() {
    super.initState();
    _session = CaptureSession(
      capturer: widget.source.capture,
      uploader: widget.uploader ??
          DriveUploader(authHeadersProvider: widget.auth.authHeaders),
      wakeLock: widget.wakeLock ?? const ScreenWakeLock(),
    );
    _bootstrap();
  }

  Future<void> _bootstrap() async {
    await widget.source.initialize();
    if (mounted) setState(() {});

    await widget.auth.initialize();
    if (!mounted) return;
    setState(() {});
    _session.isSignedIn = widget.auth.email != null;
  }

  Future<void> _signIn() async {
    setState(() => _signingIn = true);
    try {
      final signedIn = await widget.auth.signIn();
      if (!mounted) return;
      _session.isSignedIn = signedIn;
    } catch (e) {
      // AGENTS.md 5.1 の「直近エラー」としてステータスパネルへ出す。
      // SnackBar だと消えた後に失敗を確認できない。
      _session.reportError('サインインに失敗しました: $e');
    } finally {
      if (mounted) setState(() => _signingIn = false);
    }
  }

  void _startCapture() {
    if (!_session.start()) return;
    _scheduler.start(_session.interval, _session.captureOnce);
  }

  void _stopCapture() {
    _scheduler.stop();
    _session.stop();
  }

  @override
  void dispose() {
    _scheduler.dispose();
    widget.source.dispose();
    _session.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('定点撮影POC')),
      body: Column(
        children: [
          Expanded(flex: 3, child: _buildPreview()),
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

  Widget _buildPreview() {
    final error = widget.source.errorMessage;
    if (error != null) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(error, textAlign: TextAlign.center),
              const SizedBox(height: 12),
              ElevatedButton(
                onPressed: () async {
                  await widget.source.initialize();
                  if (mounted) setState(() {});
                },
                child: const Text('再試行'),
              ),
            ],
          ),
        ),
      );
    }
    return widget.source.buildPreview();
  }

  Widget _buildControls() {
    final email = widget.auth.email;
    final canStart = !_session.isRunning && widget.source.isReady && _session.isSignedIn;
    return Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  email == null ? '未サインイン' : 'サインイン中: $email',
                  overflow: TextOverflow.ellipsis,
                ),
              ),
              if (email == null)
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
