package app.peertube.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun watchLaterStatusText(status: WatchLaterStatus): String = stringResource(when (status) {
    WatchLaterStatus.LOCAL -> R.string.watch_later_status_local
    WatchLaterStatus.SYNCING -> R.string.watch_later_status_syncing
    WatchLaterStatus.SYNCED -> R.string.watch_later_status_synced
    WatchLaterStatus.PENDING -> R.string.watch_later_status_pending
    WatchLaterStatus.PARTIAL -> R.string.watch_later_status_partial
    WatchLaterStatus.SIGN_IN -> R.string.watch_later_status_sign_in
})

@Composable
private fun PlaylistCard(icon: ImageVector, title: String, count: Int, status: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier.widthIn(max = 560.dp).fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2)
                Text(stringResource(R.string.watch_later_count, count), style = MaterialTheme.typography.bodyMedium)
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** "Watch later" (the account's built-in playlist), the user's own playlists and a button to make a new one. */
@Composable
internal fun ColumnScope.PlaylistsTab(m: HomeModel, ui: HomeUiState) {
    val context = LocalContext.current
    val watchLater by WatchLater.state.collectAsState()
    val playlists by Playlists.state.collectAsState()
    var creating by remember { mutableStateOf(false) }
    if (!ui.headerHidden) Text(stringResource(R.string.nav_playlists), style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(12.dp))
    val playlistStatus = watchLaterStatusText(playlists.status)
    val localStatus = stringResource(R.string.watch_later_status_local)
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        item(key = "watch_later") {
            PlaylistCard(Icons.Filled.WatchLater, stringResource(R.string.watch_later), watchLater.videos.size, watchLaterStatusText(watchLater.status),
                Modifier.focusRequester(ui.sectionFirstFocusRequester)) { ui.sectionFocusPending = true; m.tab = "watch_later" }
        }
        items(playlists.playlists, key = { it.id }) { playlist ->
            PlaylistCard(Icons.AutoMirrored.Filled.PlaylistPlay, playlist.name, playlist.list.items.size,
                if (playlists.status == WatchLaterStatus.SYNCED && playlist.remoteId == null) localStatus else playlistStatus) {
                ui.openedPlaylistId = playlist.id
                ui.sectionFocusPending = true
                m.tab = "user_playlist"
            }
        }
        item(key = "new") {
            OutlinedButton(onClick = { creating = true }, modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.playlist_new))
            }
        }
    }
    if (creating) NewPlaylistDialog(onDismiss = { creating = false }) { name -> Playlists.create(context, name); creating = false }
}

@Composable
internal fun NewPlaylistDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.playlist_new)) },
        text = { TvRemoteTextField(name, { name = it.take(120) }, stringResource(R.string.playlist_name), Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.playlist_create)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

/** Heading of the opened "Watch later" list; keeps a focus target when the list is empty. */
@Composable
internal fun WatchLaterHeader(state: WatchLaterState, ui: HomeUiState) {
    if (!ui.headerHidden) {
        Text(stringResource(R.string.watch_later), style = MaterialTheme.typography.headlineSmall)
        Text(watchLaterStatusText(state.status), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (state.videos.isEmpty()) {
        Text(stringResource(R.string.watch_later_empty), modifier = Modifier.padding(16.dp).focusRequester(ui.sectionFirstFocusRequester).focusable())
    }
}

/** Heading of an opened user playlist with its delete button (confirmed, and on the server too when linked). */
@Composable
internal fun UserPlaylistHeader(m: HomeModel, ui: HomeUiState, playlist: UserPlaylist?, status: WatchLaterStatus) {
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    if (playlist == null) {
        Text(stringResource(R.string.playlist_missing), modifier = Modifier.padding(16.dp).focusRequester(ui.sectionFirstFocusRequester).focusable())
        return
    }
    if (!ui.headerHidden) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(playlist.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2)
                Text(if (status == WatchLaterStatus.SYNCED && playlist.remoteId == null) stringResource(R.string.watch_later_status_local) else watchLaterStatusText(status),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = { confirmDelete = true }, modifier = if (playlist.list.items.isEmpty()) Modifier.focusRequester(ui.sectionFirstFocusRequester) else Modifier) {
                Text(stringResource(R.string.playlist_delete))
            }
        }
    }
    if (playlist.list.items.isEmpty()) Text(stringResource(R.string.playlist_empty), modifier = Modifier.padding(16.dp))
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.playlist_delete_confirm, playlist.name)) },
        text = if (playlist.remoteId != null) ({ Text(stringResource(R.string.playlist_delete_synced)) }) else null,
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                Playlists.delete(context, playlist.id)
                ui.sectionFocusPending = true
                m.tab = "playlists"
            }) { Text(stringResource(R.string.playlist_delete)) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } }
    )
}

/** Side panel from the video menu: every playlist with a mark when the video is in it, and "New playlist". */
@Composable
internal fun PlaylistPickerDialog(video: Video, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val watchLater by WatchLater.state.collectAsState()
    val playlists by Playlists.state.collectAsState()
    var creating by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.48f)), contentAlignment = Alignment.CenterEnd) {
            Surface(Modifier.fillMaxHeight().fillMaxWidth(0.30f), color = MaterialTheme.colorScheme.background, shape = RoundedCornerShape(0.dp)) {
                Column(Modifier.fillMaxSize().padding(12.dp)) {
                    Text(stringResource(R.string.playlist_add), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                    HorizontalDivider()
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        PickerRow(stringResource(R.string.watch_later), video.uuid in watchLater, Modifier.focusRequester(firstFocus)) { WatchLater.toggle(context, video) }
                        playlists.playlists.forEach { playlist ->
                            PickerRow(playlist.name, video.uuid in playlist.list) { Playlists.toggle(context, playlist.id, video) }
                        }
                        TextButton(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.playlist_new), modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
    if (creating) NewPlaylistDialog(onDismiss = { creating = false }) { name ->
        Playlists.create(context, name)?.let { Playlists.toggle(context, it, video) }
        creating = false
    }
}

@Composable
private fun PickerRow(title: String, checked: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Text(title, modifier = Modifier.weight(1f), maxLines = 2)
        if (checked) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}
