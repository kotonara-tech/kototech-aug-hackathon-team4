package com.kotonara.farmcamera.presentation

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.kotonara.farmcamera.domain.CaptureIntervalOption
import com.kotonara.farmcamera.domain.CaptureState
import com.kotonara.farmcamera.domain.PhotoUploadStatus
import com.kotonara.farmcamera.domain.TorchSettings
import java.io.File
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun CaptureScreen(
    state: CaptureState,
    signInState: SignInState,
    selectedInterval: CaptureIntervalOption,
    galleryPhotos: List<File>,
    photoUploadStates: Map<String, PhotoUploadStatus>,
    torchSettings: TorchSettings,
    isNetworkAvailable: Boolean,
    onIntervalSelected: (CaptureIntervalOption) -> Unit,
    onSignIn: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onUploadSaved: () -> Unit,
    onTorchSettingsChanged: (TorchSettings) -> Unit,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column {
            Spacer(modifier = Modifier.height(24.dp))
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("撮影") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("画像 (${galleryPhotos.size}/100)") })
            }
            if (selectedTab == 0) {
                CaptureStatusPage(
                    state = state,
                    signInState = signInState,
                    selectedInterval = selectedInterval,
                    isNetworkAvailable = isNetworkAvailable,
                    torchSettings = torchSettings,
                    onIntervalSelected = onIntervalSelected,
                    onSignIn = onSignIn,
                    onStart = onStart,
                    onStop = onStop,
                    onUploadSaved = onUploadSaved,
                    onTorchSettingsChanged = onTorchSettingsChanged,
                )
            } else {
                CaptureGalleryPage(galleryPhotos, photoUploadStates)
            }
        }
    }
}

@Composable
private fun CaptureStatusPage(
    state: CaptureState,
    signInState: SignInState,
    selectedInterval: CaptureIntervalOption,
    isNetworkAvailable: Boolean,
    torchSettings: TorchSettings,
    onIntervalSelected: (CaptureIntervalOption) -> Unit,
    onSignIn: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onUploadSaved: () -> Unit,
    onTorchSettingsChanged: (TorchSettings) -> Unit,
) {
    val signInText =
        when (signInState) {
            SignInState.NotSignedIn -> "未認証"
            SignInState.SigningIn -> "認証中"
            SignInState.SignedIn -> "認証済み"
            is SignInState.Error -> "認証エラー: ${signInState.message}"
        }
    val canStart = signInState is SignInState.SignedIn && !state.isRunning

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Text("定点撮影", style = MaterialTheme.typography.headlineMedium)
            NetworkStatusBadge(isNetworkAvailable, Modifier.align(Alignment.TopEnd))
        }
        Text("Google アカウント: $signInText")
        Button(onClick = onSignIn, enabled = signInState !is SignInState.SigningIn) {
            Text(if (signInState is SignInState.SignedIn) "再認証" else "サインイン")
        }
        Text("撮影間隔")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CaptureIntervalOption.entries.forEach { option ->
                FilterChip(
                    selected = selectedInterval == option,
                    onClick = { onIntervalSelected(option) },
                    enabled = !state.isRunning,
                    label = { Text(option.label) },
                )
            }
        }
        if (selectedInterval != CaptureIntervalOption.NORMAL_5_MINUTES) {
            Text("デモ用の短い間隔です", color = MaterialTheme.colorScheme.primary)
        }
        CaptureActivityCard(state.isRunning, selectedInterval)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onStart, enabled = canStart) { Text("開始") }
            OutlinedButton(onClick = onStop, enabled = state.isRunning) { Text("停止") }
        }
        TorchControlCard(torchSettings, onTorchSettingsChanged)
        OutlinedButton(onClick = onUploadSaved, enabled = signInState is SignInState.SignedIn) {
            Text("保存画像を送信")
        }
        StatusRow("撮影枚数", state.capturedCount.toString())
        StatusRow("送信枚数", state.uploadedCount.toString())
        StatusRow("最終送信", state.lastUploadedAt?.toJstTime() ?: "なし")
        StatusRow("直近エラー", state.lastError ?: "なし")
    }
}

@Composable
private fun TorchControlCard(
    settings: TorchSettings,
    onSettingsChanged: (TorchSettings) -> Unit,
) {
    var startText by remember(settings.startTime) { mutableStateOf(settings.startTime.format(TIME_FORMATTER)) }
    var endText by remember(settings.endTime) { mutableStateOf(settings.endTime.format(TIME_FORMATTER)) }
    val startTime = startText.toLocalTimeOrNull()
    val endTime = endText.toLocalTimeOrNull()

    Surface(
        color = if (settings.isTorchOn) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("ライト", style = MaterialTheme.typography.titleLarge)
            Text(if (settings.isTorchOn) "現在: 点灯中" else "現在: 消灯中")
            settings.lastError?.let { Text("ライトエラー: $it", color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val manualOnSelected = !settings.automaticEnabled && settings.manualEnabled
                val manualOffSelected = !settings.automaticEnabled && !settings.manualEnabled
                Button(
                    onClick = { onSettingsChanged(settings.copy(manualEnabled = true, automaticEnabled = false)) },
                    enabled = !settings.automaticEnabled,
                    colors = manualChoiceColors(manualOnSelected),
                ) { Text("ON") }
                Button(
                    onClick = { onSettingsChanged(settings.copy(manualEnabled = false, automaticEnabled = false)) },
                    enabled = !settings.automaticEnabled,
                    colors = manualChoiceColors(manualOffSelected),
                ) { Text("OFF") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("時刻で自動点灯", modifier = Modifier.weight(1f))
                Switch(
                    checked = settings.automaticEnabled,
                    onCheckedChange = { enabled -> onSettingsChanged(settings.copy(automaticEnabled = enabled)) },
                )
            }
            if (settings.automaticEnabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = startText,
                        onValueChange = { value ->
                            startText = value
                            value.toLocalTimeOrNull()?.let { onSettingsChanged(settings.copy(startTime = it)) }
                        },
                        label = { Text("開始 (HH:mm)") },
                        isError = startTime == null,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = endText,
                        onValueChange = { value ->
                            endText = value
                            value.toLocalTimeOrNull()?.let { onSettingsChanged(settings.copy(endTime = it)) }
                        },
                        label = { Text("終了 (HH:mm)") },
                        isError = endTime == null,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                }
                Text("日付をまたぐ時間帯にも対応します（例: 18:00〜06:00）")
            }
        }
    }
}

@Composable
private fun manualChoiceColors(selected: Boolean) =
    ButtonDefaults.buttonColors(
        containerColor = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
        disabledContainerColor = Color.Transparent,
        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
    )

private fun String.toLocalTimeOrNull(): LocalTime? =
    runCatching { LocalTime.parse(this, TIME_FORMATTER) }
        .getOrNull()

private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun NetworkStatusBadge(
    isNetworkAvailable: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color =
            if (isNetworkAvailable) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = if (isNetworkAvailable) "ネットワーク: オンライン" else "ネットワーク: オフライン",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            color =
                if (isNetworkAvailable) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onErrorContainer
                },
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun CaptureActivityCard(
    isRunning: Boolean,
    interval: CaptureIntervalOption,
) {
    Surface(
        color = if (isRunning) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(if (isRunning) "撮影中" else "撮影停止中", style = MaterialTheme.typography.headlineSmall)
            if (isRunning) Text("${interval.label} 間隔でバックグラウンド撮影・送信しています")
        }
    }
}

@Composable
private fun CaptureGalleryPage(
    photos: List<File>,
    photoUploadStates: Map<String, PhotoUploadStatus>,
) {
    var selectedPhoto by remember { mutableStateOf<File?>(null) }
    if (photos.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("撮影済みの画像はありません")
            Text("撮影すると直近100枚までここに表示されます")
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(photos, key = { it.absolutePath }) { photo ->
            val bitmap =
                remember(photo.absolutePath, photo.lastModified()) { BitmapFactory.decodeFile(photo.path) }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "撮影済み画像 ${photo.name}",
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clickable { selectedPhoto = photo },
                )
            }
        }
    }
    selectedPhoto?.let { photo ->
        val uploadStatus = photoUploadStates[photo.name]
        val bitmap = remember(photo.absolutePath, photo.lastModified()) { BitmapFactory.decodeFile(photo.path) }
        if (bitmap != null) {
            Dialog(onDismissRequest = { selectedPhoto = null }) {
                var scale by remember(photo.absolutePath) { mutableStateOf(1f) }
                var offsetX by remember(photo.absolutePath) { mutableStateOf(0f) }
                var offsetY by remember(photo.absolutePath) { mutableStateOf(0f) }
                val transformState =
                    rememberTransformableState { zoomChange, offsetChange, _ ->
                        scale = (scale * zoomChange).coerceIn(1f, 5f)
                        if (scale == 1f) {
                            offsetX = 0f
                            offsetY = 0f
                        } else {
                            offsetX += offsetChange.x
                            offsetY += offsetChange.y
                        }
                    }
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "拡大した撮影済み画像 ${photo.name}",
                            contentScale = ContentScale.Fit,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(16.dp)
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                        translationX = offsetX
                                        translationY = offsetY
                                    }.transformable(transformState),
                        )
                        UploadStatusBadge(
                            status = uploadStatus,
                            modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
                        )
                        OutlinedButton(
                            onClick = { selectedPhoto = null },
                            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                        ) {
                            Text("戻る")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UploadStatusBadge(
    status: PhotoUploadStatus?,
    modifier: Modifier = Modifier,
) {
    val (label, color, contentColor) =
        when (status) {
            PhotoUploadStatus.UPLOADED ->
                Triple(
                    "Drive: アップ済み",
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.onPrimaryContainer,
                )
            PhotoUploadStatus.FAILED ->
                Triple(
                    "Drive: 送信失敗（再送待ち）",
                    MaterialTheme.colorScheme.errorContainer,
                    MaterialTheme.colorScheme.onErrorContainer,
                )
            PhotoUploadStatus.PENDING ->
                Triple(
                    "Drive: アップ未",
                    MaterialTheme.colorScheme.surfaceVariant,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
            null ->
                Triple(
                    "Drive: 未送信（確認待ち）",
                    MaterialTheme.colorScheme.surfaceVariant,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
    Surface(modifier = modifier, color = color, shape = MaterialTheme.shapes.small) {
        Text(label, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = contentColor)
    }
}

@Composable
private fun StatusRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text(value)
    }
}

internal fun Instant.toJstTime(): String = atZone(ZoneId.of("Asia/Tokyo")).format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"))
