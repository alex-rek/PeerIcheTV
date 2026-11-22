package app.peertube.tv

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import coil3.compose.AsyncImage

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BoxScope.HomeTopBar(m: HomeModel, ui: HomeUiState, search: SearchUiState, accountState: PeerTubeAccountsState) {
    val topBarOffset by animateDpAsState(targetValue = if (ui.headerHidden) (-92).dp else 0.dp, label = "topBarOffset")
    Row(
        Modifier.align(Alignment.TopStart).offset(y = topBarOffset).graphicsLayer { alpha = if (ui.headerHidden) 0f else 1f }
            .padding(start = if (ui.menuExpanded) 226.dp else if (m.miniSectionRailEnabled) 80.dp else 20.dp, end = 20.dp, top = 4.dp).fillMaxWidth().zIndex(10f)
            .focusProperties { canFocus = !ui.menuExpanded && !ui.headerHidden }
            // The bar sits outside the content's key handler, so its left edge leads to the menu here.
            .onPreviewKeyEvent { event ->
                val atLeftEdge = ui.searchButtonFocused || (ui.accountButtonFocused && !m.showSearchIcon)
                if (event.type != KeyEventType.KeyDown || event.key != Key.DirectionLeft || !atLeftEdge) return@onPreviewKeyEvent false
                val rail = if (m.miniSectionRailEnabled) ui.collapsedMenuFocusRequesters[ui.focusedMenuSectionKey] else null
                if (rail != null) runCatching { rail.requestFocus() } else ui.menuExpanded = true
                true
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        if (m.showSearchIcon) {
            IconButton(
                onClick = { search.text = m.query; search.submitted = false; m.tab = "sepia" },
                modifier = Modifier.size(46.dp).focusRequester(ui.searchButtonFocusRequester)
                    .focusProperties { if (m.tab == "home") down = ui.homeFirstCardFocusRequester else if (m.tab != "sepia") down = ui.sectionFirstFocusRequester }
                    .onFocusChanged { ui.searchButtonFocused = it.isFocused }
                    .then(if (ui.searchButtonFocused) Modifier.border(3.dp, Color(0xFFFFB066), CircleShape).padding(3.dp) else Modifier)
                    .background(if (ui.searchButtonFocused) Color(0xFFC45100) else MaterialTheme.colorScheme.primary, CircleShape)
            ) { SearchGlyph() }
        }
        if (accountState.active != null) {
            val activeAccount = accountState.active!!
            IconButton(
                onClick = { ui.settingsCategory = "accounts"; ui.settingsSubpage = null; ui.settingsOverlay = true },
                modifier = Modifier.padding(start = if (m.showSearchIcon) 18.dp else 0.dp).size(44.dp).onFocusChanged { ui.accountButtonFocused = it.isFocused }
            ) {
                if (!activeAccount.avatar.isNullOrBlank()) AsyncImage(activeAccount.avatar, contentDescription = activeAccount.displayName,
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant, CircleShape), contentScale = ContentScale.Crop)
                else Icon(Icons.Filled.AccountCircle, contentDescription = activeAccount.displayName, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) { LocalDateTime(m.clock24Hour) }
        Spacer(Modifier.width(14.dp))
        Image(painter = painterResource(LocalPeerIcheTheme.current.logoResource), contentDescription = stringResource(R.string.app_name), contentScale = ContentScale.Fit, modifier = Modifier.width(176.dp).height(38.dp))
    }
}
