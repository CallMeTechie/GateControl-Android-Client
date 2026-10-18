package com.gatecontrol.android.ui.chat.status

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.chat.ChatFeedItem
import com.gatecontrol.android.chat.ChatPerson
import com.gatecontrol.android.ui.chat.ChatPersonAvatar
import com.gatecontrol.android.ui.chat.ChatText
import com.gatecontrol.android.ui.theme.GateControlTheme

/** Ring around an avatar: glowing gradient for unseen stories, grey when seen, dashed for the own entry, accent for own active stories. */
enum class StoryRing { Unseen, Seen, Own, OwnActive, None }

/** The colours of the story ring (also around the own avatar on the Start tab). */
val STORY_GLOW = listOf(Color(0xFF2DD4BF), Color(0xFF4ADE80), Color(0xFF22D3EE), Color(0xFF2DD4BF))
private val GLOW = STORY_GLOW

/** An avatar with its story ring (status strip, Start tab). */
@Composable
fun StoryAvatar(person: ChatPerson?, ring: StoryRing, size: Dp, modifier: Modifier = Modifier) {
    val extra = GateControlTheme.extraColors
    val bg = MaterialTheme.colorScheme.background
    Box(
        modifier.size(size).drawBehind {
            val stroke = 3.dp.toPx()
            val r = this.size.minDimension / 2 - stroke / 2
            when (ring) {
                StoryRing.Unseen -> {
                    // A soft glow, then the gradient ring.
                    drawCircle(Brush.radialGradient(listOf(GLOW[0].copy(alpha = 0.45f), Color.Transparent), radius = this.size.minDimension * 0.62f), radius = this.size.minDimension * 0.62f)
                    drawCircle(Brush.sweepGradient(GLOW), radius = r, style = Stroke(stroke))
                }
                StoryRing.Seen -> drawCircle(extra.border2, radius = r, style = Stroke(stroke))
                StoryRing.Own -> drawCircle(extra.border2, radius = r, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
                StoryRing.OwnActive -> drawCircle(Brush.sweepGradient(GLOW), radius = r, style = Stroke(stroke))
                StoryRing.None -> Unit
            }
            if (ring != StoryRing.None) drawCircle(bg, radius = r - stroke / 2 - 0.5f, center = Offset(this.size.width / 2, this.size.height / 2))
        },
        contentAlignment = Alignment.Center,
    ) {
        ChatPersonAvatar(person, size - 12.dp)
    }
}

/**
 * The status strip under "Angeheftet" (mockup Chats – Status): the own
 * entry first ("Mein Status": with active stories a ring, a tap shows them,
 * + adds one), then the people with a status or stories — unseen with a
 * glowing ring, seen ones grey.
 */
@Composable
fun StatusStrip(
    me: ChatPerson?,
    meHasStories: Boolean,
    items: List<ChatFeedItem>,
    onOpenMine: () -> Unit,
    onOpen: (ChatFeedItem) -> Unit,
    onViewMine: () -> Unit = onOpenMine,
) {
    val ctx = LocalContext.current
    val stripLabel = stringResource(R.string.chat_status_strip)
    LazyRow(
        Modifier.fillMaxWidth().semantics { contentDescription = stripLabel },
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(key = "me") {
            val addLabel = stringResource(R.string.chat_status_add_story)
            StripEntry(
                stringResource(R.string.chat_status_mine),
                muted = !meHasStories,
                description = if (meHasStories) stringResource(R.string.chat_status_view_mine) else null,
                onClick = if (meHasStories) onViewMine else onOpenMine,
            ) {
                Box {
                    StoryAvatar(me, if (meHasStories) StoryRing.OwnActive else StoryRing.Own, 62.dp)
                    // + : a new status / story (its own target when the avatar shows the stories).
                    Box(
                        Modifier.align(Alignment.BottomEnd).size(26.dp).clip(CircleShape)
                            .clickable(role = Role.Button, onClickLabel = addLabel, onClick = onOpenMine)
                            .semantics { contentDescription = addLabel },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                                .border(2.dp, MaterialTheme.colorScheme.background, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Outlined.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp)) }
                    }
                }
            }
        }
        items(items, key = { it.user.userId }) { item ->
            val ring = when {
                item.stories.isEmpty() -> StoryRing.None
                item.unseen -> StoryRing.Unseen
                else -> StoryRing.Seen
            }
            val name = ChatText.name(ctx, item.user)
            StripEntry(
                name,
                muted = !item.unseen,
                description = name + " · " + stringResource(if (item.unseen) R.string.chat_status_new else R.string.chat_status_seen),
                onClick = { onOpen(item) },
            ) {
                StoryAvatar(item.user, ring, 62.dp)
            }
        }
    }
}

@Composable
private fun StripEntry(label: String, muted: Boolean, onClick: () -> Unit, description: String? = null, avatar: @Composable () -> Unit) {
    val extra = GateControlTheme.extraColors
    Column(
        // At least the avatar's width, wider for a longer label ("Mein Status") up to a limit; the
        // padding sits inside the rounded clip, so its corners never cut into the avatar or the label.
        Modifier.widthIn(min = 72.dp, max = 112.dp).clip(MaterialTheme.shapes.small).clickable(role = Role.Button, onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        avatar()
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = if (muted) extra.muted else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}
