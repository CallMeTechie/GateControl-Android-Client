package com.gatecontrol.android.tv.chat

import android.graphics.Bitmap
import com.gatecontrol.android.chat.ChatMessage
import com.gatecontrol.android.tv.drive.TvDriveMedia
import com.gatecontrol.android.tv.ui.drive.TvPhotoSource

/** The photos of a chat's history in the viewer of stage 2 (◀ ▶ through them; the file itself, screen-sized). */
class TvChatPhotos(private val items: List<ChatMessage>, private val chats: TvChats) : TvPhotoSource {
    override val count: Int get() = items.size
    override fun key(i: Int): String = "c:" + items[i].id
    override fun name(i: Int): String = items[i].meta?.fileName ?: items[i].text.orEmpty()
    override fun peek(i: Int): Bitmap? = chats.peekThumb(items[i])
    override suspend fun load(i: Int): Bitmap? = items[i].media?.let { chats.picture(it.id, TvDriveMedia.SCREEN_SIDE_PX) }
}
