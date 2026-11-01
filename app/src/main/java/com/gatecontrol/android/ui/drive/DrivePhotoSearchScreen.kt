package com.gatecontrol.android.ui.drive

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import com.gatecontrol.android.drive.DMe
import com.gatecontrol.android.drive.DrivePlacesLogic
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIconSquare
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DriveApi
import com.gatecontrol.android.drive.DriveKi
import com.gatecontrol.android.drive.DriveLive
import com.gatecontrol.android.drive.DriveTransfers
import com.gatecontrol.android.drive.backup.DrivePhotoLabelsWork
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * "Fotos nach Inhalt finden" (Drive 1.14, D3, feature `photoSearch`): photos by the words of what is in them — found on
 * the phones that backed them up ([com.gatecontrol.android.drive.backup.DrivePhotoLabels]); the chips add the hits'
 * most frequent terms. Opening it asks for the labels still missing (in the background, under the backup's conditions).
 */
@HiltViewModel
class DrivePhotoSearchViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val api: DriveApi,
    private val transfers: DriveTransfers,
    val live: DriveLive,
) : ViewModel() {

    /** How many in all, the hits' terms and the people and places the words were taken as (from the first page). */
    data class Meta(val total: Int?, val terms: List<String>, val marks: com.gatecontrol.android.drive.DSearchMarks = com.gatecontrol.android.drive.DSearchMarks())

    private var term = ""
    private val _meta = MutableStateFlow(Meta(null, emptyList()))
    val meta: StateFlow<Meta> = _meta.asStateFlow()

    private val pager = PhotoPager(viewModelScope) { before ->
        api.photos(before = before, q = term).also { r ->
            if (before == null && r is DResult.Ok) _meta.value = Meta(r.value.total, r.value.terms, r.value.marks)
        }
    }
    val photos: StateFlow<PhotoPages> = pager.state

    private val _suggest = MutableStateFlow<com.gatecontrol.android.drive.DSuggest?>(null)
    /** Drive 1.16: what the words typed may mean (people, places, what is in the photos). */
    val suggest: StateFlow<com.gatecontrol.android.drive.DSuggest?> = _suggest.asStateFlow()

    private val _active = MutableStateFlow(false)
    /** A search is typed (else nothing shows). */
    val active: StateFlow<Boolean> = _active.asStateFlow()
    private var job: Job? = null

    init {
        DrivePhotoLabelsWork.request(context)
    }

    fun search(q: String) {
        job?.cancel()
        val t = q.trim().take(com.gatecontrol.android.drive.DriveJson.PHOTO_Q_MAX)
        val suggesting = live.me.value.let { it?.has(com.gatecontrol.android.drive.DMe.FEATURE_PHOTO_PLACES) == true || it?.has(com.gatecontrol.android.drive.DMe.FEATURE_PHOTO_PEOPLE) == true }
        if (t.length < 2) {
            term = ""
            _active.value = false
            _meta.value = Meta(null, emptyList())
            _suggest.value = null
            if (suggesting && t.isNotEmpty()) job = viewModelScope.launch {
                delay(250)
                _suggest.value = api.photoSuggest(t).valueOrNull()
            }
            return
        }
        job = viewModelScope.launch {
            delay(300)
            term = t
            _active.value = true
            if (suggesting) launch { _suggest.value = api.photoSuggest(t).valueOrNull() }
            pager.reload()
        }
    }

    fun more() = pager.more()

    fun retry() = pager.retry()

    suspend fun fetch(node: DNode): DResult<File> = transfers.fetch(node)
}

@Composable
fun DrivePhotoSearchScreen(
    onBack: () -> Unit,
    onPerson: (Long) -> Unit = {},
    onPlace: (String) -> Unit = {},
    viewModel: DrivePhotoSearchViewModel = hiltViewModel(),
) {
    val photos by viewModel.photos.collectAsStateWithLifecycle()
    val meta by viewModel.meta.collectAsStateWithLifecycle()
    val active by viewModel.active.collectAsStateWithLifecycle()
    val suggest by viewModel.suggest.collectAsStateWithLifecycle()
    val me by viewModel.live.me.collectAsStateWithLifecycle()
    val x = GateControlTheme.extraColors
    var q by rememberSaveable { mutableStateOf("") }
    var viewing by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) { if (q.isNotBlank()) viewModel.search(q) }
    val set: (String) -> Unit = { q = it; viewModel.search(it) }
    // Drive 1.16: people and places too (suggestions while typing, the marks of a search)
    val marked = me?.has(DMe.FEATURE_PHOTO_PLACES) == true || me?.has(DMe.FEATURE_PHOTO_PEOPLE) == true
    val labels = me?.has(DMe.FEATURE_PHOTO_SEARCH) == true
    val suggestions = remember(suggest) { DrivePlacesLogic.suggestions(suggest) }
    Column(Modifier.fillMaxSize()) {
        GcSubpageBar(stringResource(if (marked) R.string.drive_pp_search_title else R.string.drive_ki_photo_search), onBack = onBack, backDescription = stringResource(R.string.drive_back))
        Column(Modifier.padding(horizontal = 16.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GcTextField(
                value = q,
                onValueChange = { set(it.take(com.gatecontrol.android.drive.DriveJson.PHOTO_Q_MAX)) },
                label = stringResource(if (marked) R.string.drive_pp_search_title else R.string.drive_ki_photo_search_hint),
                placeholder = stringResource(if (marked) R.string.drive_pp_search_hint else R.string.drive_ki_photo_search_example),
                trailingIcon = if (q.isEmpty()) null else ({ GcIconButton(GcIcons.Close, contentDescription = stringResource(R.string.drive_pp_clear), onClick = { set("") }, size = 40.dp, iconSize = 18.dp) }),
            )
            val marks = if (active) DrivePlacesLogic.marks(meta.marks) else emptyList()
            val chips = DriveKi.chips(q, if (active) meta.terms else emptyList())
            if (marks.isNotEmpty() || chips.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // the people and places the words were taken as: a tap opens them, ✕ takes them out
                    items(marks, key = { "m-" + (it.person?.toString() ?: it.place.orEmpty()) }) { m ->
                        val remove = stringResource(R.string.drive_pp_mark_remove, m.name)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (m.person != null) {
                                PersonChip(m.person, m.name, m.name, selected = true, onClick = { onPerson(m.person) })
                            } else {
                                GcFilterChip("⌖ " + m.name, selected = true, onClick = { m.place?.let(onPlace) })
                            }
                            GcIconButton(GcIcons.Close, contentDescription = remove, onClick = { set(DrivePlacesLogic.withoutMark(q, m)) }, size = 36.dp, iconSize = 14.dp)
                        }
                    }
                    items(chips, key = { it.term.lowercase() + it.on }) { c ->
                        GcFilterChip(
                            if (c.on) c.term else "+ " + c.term,
                            selected = c.on,
                            onClick = { set(if (c.on) DriveKi.withoutTerm(q, c.term) else DriveKi.withTerm(q, c.term)) },
                        )
                    }
                }
            }
            if (active && photos.loaded) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    val n = meta.total ?: photos.items.size
                    Text(
                        pluralStringResource(R.plurals.drive_ki_photos_found, n, n),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    if (labels) Text(stringResource(R.string.drive_ki_photo_labels_device), style = MaterialTheme.typography.bodySmall, color = x.muted)
                }
            }
        }
        Box(Modifier.weight(1f).padding(top = 8.dp)) {
            if (active || (marked && q.isNotBlank())) {
                PhotoGrid(
                    pages = if (active) photos else PhotoPages(),
                    selected = emptySet(),
                    onOpen = { viewing = it },
                    onToggle = {},
                    onMore = viewModel::more,
                    onRetry = viewModel::retry,
                    empty = stringResource(R.string.drive_ki_photo_search_none),
                    header = if (!marked) null else ({
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SuggestionList(
                                q = q,
                                suggestions = suggestions,
                                loaded = suggest != null,
                                onPick = { sg ->
                                    when (sg.kind) {
                                        DrivePlacesLogic.Suggestion.Kind.PERSON -> sg.id.toLongOrNull()?.let(onPerson)
                                        DrivePlacesLogic.Suggestion.Kind.PLACE -> onPlace(sg.id)
                                        DrivePlacesLogic.Suggestion.Kind.LABEL -> set(DrivePlacesLogic.complete(q, sg.name))
                                    }
                                },
                            )
                        }
                    }),
                )
            }
        }
        if (labels) {
            GcBanner(tone = GcTone.Info, icon = GcIcons.Info, modifier = Modifier.navigationBarsPadding().padding(12.dp)) {
                Text(stringResource(R.string.drive_ki_photo_search_note), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    viewing?.let { start ->
        PhotoViewer(items = photos.items, start = start, fetch = viewModel::fetch, onMore = viewModel::more, onClose = { viewing = null })
    }
}

/** "Vorschläge": people, places and what is in the photos that fit the words; else "Kein Ort …"; then the tip. */
@Composable
private fun SuggestionList(q: String, suggestions: List<DrivePlacesLogic.Suggestion>, loaded: Boolean, onPick: (DrivePlacesLogic.Suggestion) -> Unit) {
    val x = GateControlTheme.extraColors
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        if (suggestions.isNotEmpty()) {
            Text(stringResource(R.string.drive_pp_suggestions).uppercase(), style = MaterialTheme.typography.labelSmall, color = x.muted, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
            suggestions.forEach { sg ->
                val (icon, tint, bg) = when (sg.kind) {
                    DrivePlacesLogic.Suggestion.Kind.PERSON -> Triple(GcIcons.User, x.warn, x.warnBg)
                    DrivePlacesLogic.Suggestion.Kind.PLACE -> Triple(GcIcons.Pin, x.blue, x.blueBg)
                    DrivePlacesLogic.Suggestion.Kind.LABEL -> Triple(GcIcons.Photo, x.accentText, x.accentBg)
                }
                val sub = sg.sub ?: stringResource(
                    when (sg.kind) {
                        DrivePlacesLogic.Suggestion.Kind.PERSON -> R.string.drive_pp_kind_person
                        DrivePlacesLogic.Suggestion.Kind.PLACE -> R.string.drive_pp_kind_place
                        DrivePlacesLogic.Suggestion.Kind.LABEL -> R.string.drive_pp_kind_label
                    },
                )
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { onPick(sg) }.padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    GcIconSquare(size = 40.dp, background = bg) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp)) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(sg.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(sub, style = MaterialTheme.typography.bodySmall, color = x.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(countText(sg.count), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = x.muted)
                }
            }
        } else if (loaded && q.isNotBlank()) {
            Text(stringResource(R.string.drive_pp_none, q.trim()), style = MaterialTheme.typography.bodySmall, color = x.muted, modifier = Modifier.padding(4.dp))
        }
        Text(stringResource(R.string.drive_pp_tip), style = MaterialTheme.typography.bodySmall, color = x.muted, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
    }
}
