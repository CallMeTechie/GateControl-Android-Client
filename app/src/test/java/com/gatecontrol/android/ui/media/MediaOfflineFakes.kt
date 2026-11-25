package com.gatecontrol.android.ui.media

import com.gatecontrol.android.media.offline.MediaOffline
import com.gatecontrol.android.media.offline.OfflineAllowed
import com.gatecontrol.android.media.offline.OfflineItem
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow

/** The downloads for the media pages' tests: [items] as given, nothing loaded, Jellyfin lets the user download. */
internal fun fakeOffline(items: MutableStateFlow<List<OfflineItem>> = MutableStateFlow(emptyList()), allowed: OfflineAllowed = OfflineAllowed.YES): MediaOffline =
    mockk(relaxed = true) {
        every { this@mockk.items } returns items
        every { wifi } returns MutableStateFlow(true)
        every { ready(any(), any()) } answers { items.value.firstOrNull { it.sourceId == firstArg() && it.itemId == secondArg() && it.ready } }
        every { item(any(), any()) } answers { items.value.firstOrNull { it.sourceId == firstArg() && it.itemId == secondArg() } }
        every { usedBytes() } returns 0L
        every { limitBytes() } returns 20_000_000_000L
        coEvery { canDownload(any()) } returns allowed
    }
