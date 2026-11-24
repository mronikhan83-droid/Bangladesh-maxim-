package io.github.aedev.flow.ui.screens.settings.downloads

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.aedev.flow.R

private enum class OfflinePage { MAIN, PICKER }

private val StackWidth = 84.dp
private val StackHeight = 120.dp
private val StackStep = 40.dp

/**
 * Pre-download Shorts to watch without a connection: what is on the device, Wi-Fi only, how many
 * to keep (20, 50 or 100), and clearing them. Opens from Settings → Downloads → Offline videos.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OfflineVideosSheet(
    onDismiss: () -> Unit,
    viewModel: OfflineVideosViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val wifiOnly by viewModel.wifiOnly.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(OfflinePage.MAIN) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (page) {
                OfflinePage.MAIN -> {
                    OfflineMain(
                        state = state,
                        wifiOnly = wifiOnly,
                        onWifiOnly = viewModel::setWifiOnly,
                        onPickCount = { page = OfflinePage.PICKER },
                        onClear = { confirmClear = true },
                        onClose = onDismiss,
                    )
                }

                OfflinePage.PICKER -> {
                    OfflinePicker(
                        state = state,
                        onSelect = viewModel::select,
                        onDownload = {
                            viewModel.download()
                            page = OfflinePage.MAIN
                        },
                        onBack = { page = OfflinePage.MAIN },
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.offline_videos_clear_title)) },
            text = { Text(stringResource(R.string.offline_videos_clear_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        viewModel.clear()
                    },
                ) { Text(stringResource(R.string.offline_videos_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun OfflineMain(
    state: OfflineVideosUiState,
    wifiOnly: Boolean,
    onWifiOnly: (Boolean) -> Unit,
    onPickCount: () -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val rowColors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    val countText = pluralStringResource(R.plurals.offline_videos_count, state.count, state.count)

    Box(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.offline_videos_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.align(Alignment.Center),
        )
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterEnd)) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.offline_videos_close))
        }
    }

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ThumbnailStack(state.thumbnails)
            Text(stringResource(R.string.offline_videos_downloaded), style = MaterialTheme.typography.titleMedium)
            Text(
                text = countText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (state.queued > 0) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(
            text = stringResource(R.string.offline_videos_queue, state.queued),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    state.notice?.let { notice ->
        Text(
            text = noticeText(notice),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Column(
        modifier = Modifier.clip(MaterialTheme.shapes.large),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.download_over_wifi_only)) },
            trailingContent = { Switch(checked = wifiOnly, onCheckedChange = onWifiOnly) },
            colors = rowColors,
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.offline_videos_title)) },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(countText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            },
            colors = rowColors,
            modifier = Modifier.selectable(selected = false, role = Role.Button, onClick = onPickCount),
        )
        ListItem(
            headlineContent = {
                Text(stringResource(R.string.offline_videos_storage, Formatter.formatShortFileSize(context, state.bytes)))
            },
            trailingContent = {
                FilledTonalButton(onClick = onClear, enabled = state.count > 0) {
                    Text(stringResource(R.string.offline_videos_clear))
                }
            },
            colors = rowColors,
        )
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun OfflinePicker(
    state: OfflineVideosUiState,
    onSelect: (Int) -> Unit,
    onDownload: () -> Unit,
    onBack: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.offline_videos_back))
        }
        Text(
            text = stringResource(R.string.offline_videos_pick_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.align(Alignment.Center),
        )
    }
    Text(
        text = stringResource(R.string.offline_videos_pick_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OfflineTargets.forEach { count ->
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = state.selected == count, role = Role.RadioButton, onClick = { onSelect(count) })
                    .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = state.selected == count, onClick = null, modifier = Modifier.padding(end = 12.dp))
            Text(pluralStringResource(R.plurals.offline_videos_count, count, count), style = MaterialTheme.typography.bodyLarge)
        }
    }
    Button(onClick = onDownload, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.offline_videos_download_action))
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun noticeText(notice: OfflineNotice): String =
    when (notice) {
        is OfflineNotice.Started -> pluralStringResource(R.plurals.offline_videos_started, notice.count, notice.count)
        OfflineNotice.NothingNeeded -> stringResource(R.string.offline_videos_nothing_needed)
        OfflineNotice.NoCandidates -> stringResource(R.string.offline_videos_none_found)
        is OfflineNotice.Cleared -> pluralStringResource(R.plurals.offline_videos_cleared, notice.count, notice.count)
    }

/** Up to three covers fanned out, the newest in front with a check mark, like TikTok's offline card. */
@Composable
private fun ThumbnailStack(thumbnails: List<String>) {
    Box(
        modifier = Modifier.fillMaxWidth().height(StackHeight),
        contentAlignment = Alignment.Center,
    ) {
        if (thumbnails.isEmpty()) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.size(StackWidth, StackHeight),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(Icons.Outlined.CloudDownload, contentDescription = null)
                }
            }
            return@Box
        }
        val shown = thumbnails.take(3)
        shown.indices.reversed().forEach { index ->
            val shift =
                when (index) {
                    0 -> 0.dp
                    1 -> -StackStep
                    else -> StackStep
                }
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.offset(x = shift).size(StackWidth, StackHeight),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AsyncImage(
                        model = shown[index],
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (index == 0) {
                        Box(
                            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.inverseOnSurface,
                                modifier = Modifier.size(36.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
