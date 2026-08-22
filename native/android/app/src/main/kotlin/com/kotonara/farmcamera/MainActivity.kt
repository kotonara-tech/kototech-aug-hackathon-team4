package com.kotonara.farmcamera

import android.Manifest
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.kotonara.farmcamera.data.CapturePhotoGallery
import com.kotonara.farmcamera.data.CapturePhotoUploadStates
import com.kotonara.farmcamera.data.CredentialAuthGateway
import com.kotonara.farmcamera.data.TorchSettingsRepository
import com.kotonara.farmcamera.domain.AuthGateway
import com.kotonara.farmcamera.domain.CaptureIntervalOption
import com.kotonara.farmcamera.domain.TorchSettings
import com.kotonara.farmcamera.presentation.CaptureScreen
import com.kotonara.farmcamera.presentation.CaptureService
import com.kotonara.farmcamera.presentation.CaptureStartGate
import com.kotonara.farmcamera.presentation.CaptureStatusRepository
import com.kotonara.farmcamera.presentation.CaptureViewModel
import com.kotonara.farmcamera.presentation.SignInState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var authGateway: AuthGateway
    private lateinit var viewModel: CaptureViewModel
    private val signInState = MutableStateFlow<SignInState>(SignInState.NotSignedIn)
    private val selectedInterval = MutableStateFlow(CaptureIntervalOption.NORMAL_5_MINUTES)
    private val isNetworkAvailable = MutableStateFlow(false)
    private var pendingCameraPermission: CompletableDeferred<Boolean>? = null
    private var pendingNotificationPermission: CompletableDeferred<Boolean>? = null
    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refreshNetworkStatus()

            override fun onLost(network: Network) = refreshNetworkStatus()

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) = refreshNetworkStatus()
        }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pendingCameraPermission?.complete(granted)
            pendingCameraPermission = null
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pendingNotificationPermission?.complete(granted)
            pendingNotificationPermission = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerNetworkMonitor()
        authGateway = CredentialAuthGateway(this)
        CapturePhotoGallery.load(this)
        CapturePhotoUploadStates.load(this)
        TorchSettingsRepository.load(this)
        if (getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE).getBoolean(KEY_DRIVE_AUTHORIZED, false)) {
            signInState.value = SignInState.SignedIn
        }
        restoreAuthentication()
        viewModel =
            CaptureViewModel(
                state = CaptureStatusRepository.state,
                startCapture = ::startCaptureService,
                stopCapture = { CaptureService.stop(this) },
            )

        setContent {
            MaterialTheme {
                val captureState = viewModel.state.collectAsState().value
                val authentication = signInState.collectAsState().value
                val interval = selectedInterval.collectAsState().value
                val galleryPhotos = CapturePhotoGallery.photos.collectAsState().value
                val photoUploadStates = CapturePhotoUploadStates.states.collectAsState().value
                val torchSettings = TorchSettingsRepository.settings.collectAsState().value
                val networkAvailable = isNetworkAvailable.collectAsState().value
                CaptureScreen(
                    state = captureState,
                    signInState = authentication,
                    selectedInterval = interval,
                    galleryPhotos = galleryPhotos,
                    photoUploadStates = photoUploadStates,
                    torchSettings = torchSettings,
                    isNetworkAvailable = networkAvailable,
                    onIntervalSelected = { selectedInterval.value = it },
                    onSignIn = ::signIn,
                    onStart = viewModel::start,
                    onStop = viewModel::stop,
                    onUploadSaved = ::uploadSavedPhotos,
                    onTorchSettingsChanged = ::updateTorchSettings,
                )
            }
        }
    }

    override fun onDestroy() {
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        super.onDestroy()
    }

    private fun registerNetworkMonitor() {
        getSystemService(ConnectivityManager::class.java).also { connectivityManager ->
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            isNetworkAvailable.value = connectivityManager.hasValidatedNetwork()
        }
    }

    private fun refreshNetworkStatus() {
        isNetworkAvailable.value = getSystemService(ConnectivityManager::class.java).hasValidatedNetwork()
    }

    private fun signIn() {
        if (signInState.value is SignInState.SigningIn) return
        lifecycleScope.launch {
            signInState.value = SignInState.SigningIn
            authGateway.signIn().fold(
                onSuccess = {
                    authGateway.accessToken().fold(
                        onSuccess = {
                            getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
                                .edit()
                                .putBoolean(KEY_DRIVE_AUTHORIZED, true)
                                .apply()
                            signInState.value = SignInState.SignedIn
                        },
                        onFailure = { markAuthenticationFailed(it) },
                    )
                },
                onFailure = ::markAuthenticationFailed,
            )
        }
    }

    private fun restoreAuthentication() {
        if (signInState.value is SignInState.SignedIn) return
        lifecycleScope.launch {
            authGateway.restoreAccessToken().onSuccess {
                getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_DRIVE_AUTHORIZED, true)
                    .apply()
                signInState.value = SignInState.SignedIn
            }
        }
    }

    private fun markAuthenticationFailed(failure: Throwable) {
        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DRIVE_AUTHORIZED, false)
            .apply()
        signInState.value = SignInState.Error(failure.userMessage())
    }

    private fun startCaptureService() {
        if (signInState.value !is SignInState.SignedIn) return
        lifecycleScope.launch {
            if (!awaitCameraPermission() || !awaitNotificationPermission()) return@launch
            CaptureService.start(this@MainActivity, selectedInterval.value)
        }
    }

    private fun uploadSavedPhotos() {
        if (signInState.value is SignInState.SignedIn) CaptureService.uploadSaved(this)
    }

    private fun updateTorchSettings(settings: TorchSettings) {
        lifecycleScope.launch {
            if (!awaitCameraPermission()) return@launch
            TorchSettingsRepository.update(settings)
            CaptureService.refreshTorch(this@MainActivity)
        }
    }

    private suspend fun awaitCameraPermission(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            return true
        }
        return CompletableDeferred<Boolean>()
            .also {
                pendingCameraPermission = it
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }.await()
    }

    private suspend fun awaitNotificationPermission(): Boolean {
        if (!CaptureStartGate.requiresNotificationPermission(Build.VERSION.SDK_INT) ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }
        return CompletableDeferred<Boolean>()
            .also {
                pendingNotificationPermission = it
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }.await()
    }
}

private fun Throwable.userMessage(): String = message ?: "サインインまたは Drive の認可に失敗しました"

private fun ConnectivityManager.hasValidatedNetwork(): Boolean =
    getNetworkCapabilities(activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

private const val PREFERENCES_NAME = "farm_camera"
private const val KEY_DRIVE_AUTHORIZED = "drive_authorized"
