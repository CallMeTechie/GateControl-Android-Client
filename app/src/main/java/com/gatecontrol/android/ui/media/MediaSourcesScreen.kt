package com.gatecontrol.android.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.media.MediaLogic
import com.gatecontrol.android.media.MediaPhoneLogic
import com.gatecontrol.android.media.MediaQuality
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.jellyfin.JellyfinAuthorize
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcSwitchRow
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.theme.GateControlTheme

/**
 * Medien › Quellen (mockup 6): the sources with "Abmelden" / "Entfernen", "Immer Original" per Jellyfin server, the
 * limit on mobile data (question 28), "Weiterschauen auf Start", and "Gerät anmelden" (a TV's or PC's Quick Connect
 * code confirmed with this phone's access).
 */
@Composable
fun MediaSourcesScreen(onBack: () -> Unit, onAdd: () -> Unit, onSignInAgain: (MediaSource) -> Unit, viewModel: MediaSourcesViewModel = hiltViewModel()) {
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val cap by viewModel.mobileCap.collectAsStateWithLifecycle()
    val startCard by viewModel.startCard.collectAsStateWithLifecycle()
    val authorize by viewModel.authorize.collectAsStateWithLifecycle()
    val x = GateControlTheme.extraColors
    var removing by remember { mutableStateOf<MediaSource?>(null) }
    val shown = MediaLogic.shares(sources).let { shares -> sources.filter { it.kind == MediaSourceKind.JELLYFIN } + shares }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())) {
        GcSubpageBar(
            title = stringResource(R.string.media_sources),
            onBack = onBack,
            backDescription = stringResource(R.string.common_back),
            actions = { GcIconButton(GcIcons.Plus, contentDescription = stringResource(R.string.media_add_source), onClick = onAdd, tint = MaterialTheme.colorScheme.onBackground) },
        )
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (shown.isEmpty()) {
                GcCard {
                    Text(stringResource(R.string.media_empty_text), style = MaterialTheme.typography.bodyMedium, color = x.muted)
                    GcPrimaryButton(stringResource(R.string.media_add_source), onClick = onAdd, icon = GcIcons.Plus)
                }
            } else {
                GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
                    shown.forEachIndexed { i, s ->
                        if (i > 0) GcRowDivider()
                        SourceRow(
                            s,
                            partner = s.pairWith?.let { id -> sources.firstOrNull { it.id == id } },
                            onSignOut = { viewModel.signOut(s) },
                            onSignIn = { onSignInAgain(s) },
                            onRemove = { removing = s },
                        )
                    }
                }
            }
            Text(stringResource(R.string.media_encrypted), style = MaterialTheme.typography.bodySmall, color = x.faint, modifier = Modifier.padding(horizontal = 4.dp))

            // --- settings ---
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                GcSectionLabel(stringResource(R.string.media_settings), modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
                GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
                    sources.filter { it.kind == MediaSourceKind.JELLYFIN }.forEach { s ->
                        GcSwitchRow(
                            label = stringResource(R.string.media_always_original),
                            description = stringResource(R.string.media_always_original_desc, s.name),
                            checked = s.alwaysOriginal,
                            onCheckedChange = { viewModel.setAlwaysOriginal(s, it) },
                        )
                    }
                    MobileCapRow(cap, onCap = viewModel::setMobileCap)
                    GcSwitchRow(
                        label = stringResource(R.string.media_start_card),
                        description = stringResource(R.string.media_start_card_desc),
                        checked = startCard,
                        onCheckedChange = viewModel::setStartCard,
                    )
                }
            }

            // --- "Gerät anmelden" ---
            val server = viewModel.authorizeSource()
            if (server != null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    GcSectionLabel(stringResource(R.string.media_authorize), modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
                    GcCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            GcIconSquare(background = x.accentBg) { Icon(GcIcons.Tv, contentDescription = null, tint = x.accentText, modifier = Modifier.size(22.dp)) }
                            Text(stringResource(R.string.media_authorize_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        }
                        GcTextField(
                            value = authorize.code,
                            onValueChange = viewModel::setCode,
                            label = stringResource(R.string.media_quick_code),
                            placeholder = "000 000",
                            mono = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            modifier = Modifier.testTag(CODE_TAG),
                        )
                        GcPrimaryButton(
                            stringResource(R.string.media_authorize_confirm),
                            onClick = viewModel::confirm,
                            enabled = authorize.code.isNotBlank(),
                            loading = authorize.sending,
                        )
                        Text(
                            authorize.result?.let { authorizeText(it) } ?: stringResource(R.string.media_authorize_hint, server.userName ?: "?", server.name),
                            style = MaterialTheme.typography.bodySmall,
                            color = when (authorize.result) {
                                null -> x.muted
                                JellyfinAuthorize.CONFIRMED -> x.accentText
                                else -> MaterialTheme.colorScheme.error
                            },
                        )
                    }
                }
            }
        }
    }
    removing?.let { s ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.media_remove_title)) },
            text = { Text(stringResource(R.string.media_remove_text, sourceName(s))) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.remove(s)
                    removing = null
                }) { Text(stringResource(R.string.media_remove)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.media_cancel)) } },
        )
    }
}

@Composable
private fun sourceName(s: MediaSource): String = MediaLogic.sourceLabel(s, stringResource(R.string.media_share))

/** A source: its name, who is signed in or how a share is reached, the menu ("Abmelden", "Neu anmelden", "Entfernen"). */
@Composable
private fun SourceRow(s: MediaSource, partner: MediaSource?, onSignOut: () -> Unit, onSignIn: () -> Unit, onRemove: () -> Unit) {
    val x = GateControlTheme.extraColors
    var menu by remember { mutableStateOf(false) }
    val description = when {
        s.kind == MediaSourceKind.JELLYFIN && s.token == null -> stringResource(R.string.media_signed_out)
        s.kind == MediaSourceKind.JELLYFIN -> stringResource(R.string.media_signed_in_as, s.userName ?: "?")
        partner != null -> stringResource(R.string.media_pair_note)
        s.userName == null -> stringResource(if (s.kind == MediaSourceKind.SMB) R.string.media_type_smb else R.string.media_type_webdav) + " · " + stringResource(R.string.media_as_guest)
        else -> stringResource(if (s.kind == MediaSourceKind.SMB) R.string.media_type_smb else R.string.media_type_webdav) + " · " + s.userName
    }
    GcListRow(
        title = sourceName(s),
        description = description,
        leading = {
            GcIconSquare(background = if (s.kind == MediaSourceKind.JELLYFIN) x.accentBg else x.panel2) {
                Icon(if (s.kind == MediaSourceKind.JELLYFIN) GcIcons.Film else GcIcons.Folder, contentDescription = null, tint = if (s.kind == MediaSourceKind.JELLYFIN) x.accentText else x.muted, modifier = Modifier.size(22.dp))
            }
        },
        trailing = {
            Box {
                GcIconButton(GcIcons.MoreVertical, contentDescription = stringResource(R.string.media_source_menu), onClick = { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (s.kind == MediaSourceKind.JELLYFIN) {
                        if (s.token != null) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.media_sign_out)) }, onClick = { menu = false; onSignOut() })
                        } else {
                            DropdownMenuItem(text = { Text(stringResource(R.string.media_sign_in_again)) }, onClick = { menu = false; onSignIn() })
                        }
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.media_remove)) }, onClick = { menu = false; onRemove() })
                }
            }
        },
    )
}

/** "Mobilfunk-Grenze · 3 Mbit/s ▾ · etwa 1,4 GB pro Stunde" (question 28). */
@Composable
private fun MobileCapRow(cap: Long?, onCap: (Long?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val x = GateControlTheme.extraColors
    GcListRow(
        title = stringResource(R.string.media_mobile_cap),
        description = cap?.let { stringResource(R.string.media_mobile_cap_desc, MediaPhoneLogic.gbPerHour(it)) } ?: stringResource(R.string.media_mobile_cap_none_desc),
        onClick = { open = true },
        trailing = {
            Box {
                Text(cap?.let { MediaQuality.label(it) } ?: stringResource(R.string.media_mobile_cap_none), style = MaterialTheme.typography.labelLarge, color = x.accentText)
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    MediaPhoneLogic.MOBILE_CAPS.forEach { c ->
                        DropdownMenuItem(
                            text = { Text(c?.let { MediaQuality.label(it) } ?: stringResource(R.string.media_mobile_cap_none)) },
                            onClick = {
                                open = false
                                onCap(c)
                            },
                            trailingIcon = if (c == cap) { { Icon(GcIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp)) } } else null,
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun authorizeText(r: JellyfinAuthorize): String = stringResource(
    when (r) {
        JellyfinAuthorize.CONFIRMED -> R.string.media_authorize_done
        JellyfinAuthorize.DISABLED -> R.string.media_authorize_disabled
        JellyfinAuthorize.UNKNOWN_CODE -> R.string.media_authorize_unknown
        JellyfinAuthorize.SIGNED_OUT -> R.string.media_authorize_signed_out
        JellyfinAuthorize.OFFLINE -> R.string.media_problem_offline
        JellyfinAuthorize.FAILED -> R.string.media_problem_failed
    },
)

internal const val CODE_TAG = "media-code"
