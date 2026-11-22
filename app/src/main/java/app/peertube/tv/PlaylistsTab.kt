package app.peertube.tv

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
internal fun watchLaterStatusText(status: WatchLaterStatus): String = stringResource(when (status) {
    WatchLaterStatus.LOCAL -> R.string.watch_later_status_local
    WatchLaterStatus.SYNCING -> R.string.watch_later_status_syncing
    WatchLaterStatus.SYNCED -> R.string.watch_later_status_synced
    WatchLaterStatus.PENDING -> R.string.watch_later_status_pending
    WatchLaterStatus.PARTIAL -> R.string.watch_later_status_partial
    WatchLaterStatus.SIGN_IN -> R.string.watch_later_status_sign_in
})

/** The list of playlists; "Watch later" is the account's built-in one. */
@Composable
internal fun ColumnScope.PlaylistsTab(m: HomeModel, ui: HomeUiState) {
    val watchLater by WatchLater.state.collectAsState()
    if (!ui.headerHidden) Text(stringResource(R.string.nav_playlists), style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(12.dp))
    Card(
        onClick = { ui.sectionFocusPending = true; m.tab = "watch_later" },
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().focusRequester(ui.sectionFirstFocusRequester)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Filled.WatchLater, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
            Column {
                Text(stringResource(R.string.watch_later), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.watch_later_count, watchLater.videos.size), style = MaterialTheme.typography.bodyMedium)
                Text(watchLaterStatusText(watchLater.status), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
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
