import 'package:google_sign_in/google_sign_in.dart';

/// Google 認証と Drive 認可の抽象。
///
/// `google_sign_in` はプラットフォームチャネルを使うためウィジェットテストで
/// 動かせない。UI から切り離してフェイクに差し替えられるようにする。
abstract class AuthGateway {
  /// 保存済み資格情報での自動サインインを試みる（`AGENTS.md` 5.2-2）。
  Future<void> initialize();

  /// 対話的サインイン。成功したら true。
  Future<bool> signIn();

  /// サインイン中のアカウント。未サインインなら null。
  String? get email;

  /// Drive API 用の認可ヘッダ。取得できなければ null。
  Future<Map<String, String>?> authHeaders();
}

/// `google_sign_in` による実装。
class GoogleAuthGateway implements AuthGateway {
  GoogleAuthGateway({this.scopes = _defaultScopes});

  static const _defaultScopes = <String>[
    'https://www.googleapis.com/auth/drive.file',
  ];

  final List<String> scopes;
  GoogleSignInAccount? _account;

  @override
  String? get email => _account?.email;

  @override
  Future<void> initialize() async {
    await GoogleSignIn.instance.initialize();
    _account = await GoogleSignIn.instance.attemptLightweightAuthentication();
  }

  @override
  Future<bool> signIn() async {
    _account = await GoogleSignIn.instance.authenticate(scopeHint: scopes);
    return _account != null;
  }

  @override
  Future<Map<String, String>?> authHeaders() async {
    final account = _account;
    if (account == null) return null;
    return account.authorizationClient.authorizationHeaders(
      scopes,
      promptIfNecessary: true,
    );
  }
}
