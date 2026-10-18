package com.gatecontrol.android.chat

import com.gatecontrol.android.chat.ChatJson.addNull
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.ApiClientProvider
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okio.BufferedSink
import okio.source
import retrofit2.Response
import timber.log.Timber
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of a call to the messenger's app API. */
sealed interface ChatResult<out T> {
    data class Ok<T>(val value: T) : ChatResult<T>

    /** The server answered with an error: [code] from `{ok:false, code}` or `http_<status>`. */
    data class Failed(val code: String, val http: Int) : ChatResult<Nothing>

    /** No answer (network, VPN, timeout). */
    data object Offline : ChatResult<Nothing>

    data object NotConfigured : ChatResult<Nothing>
}

fun <T> ChatResult<T>.valueOrNull(): T? = (this as? ChatResult.Ok)?.value

/** A failure of another result type as this type (an Ok becomes an `unexpected` failure). */
fun <T> ChatResult<*>.asFailure(): ChatResult<T> = when (this) {
    is ChatResult.Failed -> this
    ChatResult.Offline -> ChatResult.Offline
    ChatResult.NotConfigured -> ChatResult.NotConfigured
    is ChatResult.Ok -> ChatResult.Failed("unexpected", 0)
}

/**
 * The messenger plugin's app API (README "App-API") through the core's
 * client plugin bridge (`/api/v1/client/plugins/gatecontrol-messenger/api/…`)
 * and its media routes (`…/media`). One method per route, typed answers
 * from [ChatJson]. Never throws.
 */
@Singleton
class ChatApi @Inject constructor(
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
    private val settingsRepository: SettingsRepository,
) {
    companion object {
        const val CODE_INVALID = "invalid_answer"
        private const val TIMEOUT_MS = 20_000L
        private const val UPLOAD_TIMEOUT_MS = 10 * 60_000L

        /** `conversations/<id>/messages` of a push's `reply` action, nothing else. */
        val REPLY_PATH_RE = Regex("^conversations/[1-9][0-9]{0,14}/messages$")

        /** `conversations/<id>/read` of a push's `mark_read` action, nothing else. */
        val READ_PATH_RE = Regex("^conversations/[1-9][0-9]{0,14}/read$")
    }

    private suspend fun lang(): String? =
        runCatching { settingsRepository.getLocale().first() }.getOrNull()?.takeIf { it == "de" || it == "en" }

    private suspend fun <T> call(
        timeoutMs: Long = TIMEOUT_MS,
        media: Boolean = false,
        request: suspend ApiClient.() -> Response<JsonObject>,
        parse: (JsonObject) -> T?,
    ): ChatResult<T> {
        val url = setupRepository.getServerUrl()
        if (url.isBlank() || !setupRepository.isConfigured()) return ChatResult.NotConfigured
        return try {
            val client = if (media) apiClientProvider.mediaClient(url) else apiClientProvider.getClient(url)
            val response = withTimeoutOrNull(timeoutMs) { client.request() } ?: return ChatResult.Offline
            if (response.isSuccessful) {
                val body = response.body() ?: JsonObject()
                if (body.has("ok") && body.get("ok").let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && !it.asBoolean }) {
                    return ChatResult.Failed(ChatJson.errorCode(body) ?: "failed", response.code())
                }
                parse(body)?.let { ChatResult.Ok(it) } ?: ChatResult.Failed(CODE_INVALID, response.code())
            } else {
                val err = runCatching {
                    response.errorBody()?.use { b -> b.string().takeIf { it.length < 16_384 } }
                        ?.let { JsonParser.parseString(it).takeIf { e -> e.isJsonObject }?.asJsonObject }
                }.getOrNull()
                Timber.d("Chats: HTTP %d", response.code())
                ChatResult.Failed(ChatJson.errorCode(err) ?: "http_${response.code()}", response.code())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            ChatResult.Offline
        } catch (e: Exception) {
            Timber.d("Chats: call failed (%s)", e.javaClass.simpleName)
            ChatResult.Failed(CODE_INVALID, 0)
        }
    }

    private suspend fun <T> get(path: String, query: Map<String, String> = emptyMap(), parse: (JsonObject) -> T?): ChatResult<T> {
        val q = lang()?.let { query + ("lang" to it) } ?: query
        return call(request = { pluginGet(MESSENGER_PLUGIN, path, q) }, parse = parse)
    }

    private suspend fun <T> post(path: String, body: JsonObject = JsonObject(), parse: (JsonObject) -> T?): ChatResult<T> {
        val q = lang()?.let { mapOf("lang" to it) } ?: emptyMap()
        return call(request = { pluginPost(MESSENGER_PLUGIN, path, body, q) }, parse = parse)
    }

    private suspend fun <T> patch(path: String, body: JsonObject, parse: (JsonObject) -> T?): ChatResult<T> =
        call(request = { pluginPatch(MESSENGER_PLUGIN, path, body) }, parse = parse)

    private suspend fun <T> put(path: String, body: JsonObject, parse: (JsonObject) -> T?): ChatResult<T> =
        call(request = { pluginPut(MESSENGER_PLUGIN, path, body) }, parse = parse)

    private val unit: (JsonObject) -> Unit = { }

    // ─── me / prefs / people ────────────────────────────────────────

    suspend fun me(): ChatResult<ChatProfile> = get("me", parse = ChatJson::profile)

    suspend fun putPrefs(readReceipts: Boolean? = null, typing: Boolean? = null, preview: Boolean? = null): ChatResult<ChatPrefs> =
        put(
            "prefs",
            JsonObject().apply {
                readReceipts?.let { addProperty("readReceipts", it) }
                typing?.let { addProperty("typing", it) }
                preview?.let { addProperty("preview", it) }
            },
        ) { o -> ChatJson.prefs(o.get("prefs")?.takeIf { it.isJsonObject }?.asJsonObject) }

    suspend fun people(): ChatResult<List<ChatPerson>> = get("people", parse = ChatJson::people)

    // ─── conversations ──────────────────────────────────────────────

    suspend fun conversations(since: String?): ChatResult<ChatConversationList> =
        get("conversations", if (since != null) mapOf("since" to since) else emptyMap(), ChatJson::conversationList)

    suspend fun conversation(id: Long): ChatResult<ChatConversation> = get("conversations/$id", parse = ChatJson::conversationOf)

    /** `{ conversation, created }` → the conversation. */
    suspend fun createDirect(peerId: Long): ChatResult<ChatConversation> =
        post("conversations", ChatJson.createDirectBody(peerId), ChatJson::conversationOf)

    suspend fun createGroup(title: String, userIds: List<Long>, avatarMediaId: String? = null): ChatResult<ChatConversation> =
        post("conversations", ChatJson.createGroupBody(title, userIds, avatarMediaId), ChatJson::conversationOf)

    /** Change of a conversation; [ChatPatch.left] after leaving (conversation null). */
    suspend fun patchConversation(id: Long, body: JsonObject): ChatResult<ChatPatch> =
        patch("conversations/$id", body) { o ->
            ChatPatch(ChatJson.conversationOf(o), o.get("left")?.takeIf { it.isJsonPrimitive }?.asBoolean == true)
        }

    // ─── messages ───────────────────────────────────────────────────

    suspend fun messages(
        conversationId: Long,
        before: Long? = null,
        after: Long? = null,
        changedSince: String? = null,
        limit: Int = 50,
        around: Long? = null,
    ): ChatResult<ChatMessagePage> {
        val q = buildMap {
            put("limit", limit.coerceIn(1, 100).toString())
            before?.let { put("before", it.toString()) }
            after?.let { put("after", it.toString()) }
            changedSince?.let { put("changedSince", it) }
            // Messenger 1.1.0: about half older than the message, the message, the newer ones.
            around?.let { put("around", it.toString()) }
        }
        return get("conversations/$conversationId/messages", q, ChatJson::messagePage)
    }

    /** `{ message, duplicate }` → the stored message. */
    suspend fun send(conversationId: Long, body: JsonObject): ChatResult<ChatMessage> =
        post("conversations/$conversationId/messages", body, ChatJson::messageOf)

    /** The `reply` action of a chat push: [path] checked against [REPLY_PATH_RE]. */
    suspend fun sendToPath(path: String, body: JsonObject): ChatResult<ChatMessage> {
        if (!REPLY_PATH_RE.matches(path)) return ChatResult.Failed(CODE_INVALID, 0)
        return post(path, body, ChatJson::messageOf)
    }

    /** The `mark_read` action of a chat push: [path] checked against [READ_PATH_RE]. */
    suspend fun readPath(path: String, body: JsonObject): ChatResult<Int?> {
        if (!READ_PATH_RE.matches(path)) return ChatResult.Failed(CODE_INVALID, 0)
        return post(path, body) { ChatJson.unreadOf(it) }
    }

    suspend fun edit(messageId: Long, text: String): ChatResult<ChatMessage> =
        patch("messages/$messageId", JsonObject().apply { addProperty("text", text) }, ChatJson::messageOf)

    /** [forAll] false: hidden for the caller only. Answers the tombstone for everyone (null for me). */
    suspend fun delete(messageId: Long, forAll: Boolean): ChatResult<ChatDeleted> =
        call(request = { pluginDelete(MESSENGER_PLUGIN, "messages/$messageId", mapOf("for" to if (forAll) "all" else "me")) }) { o ->
            ChatDeleted(ChatJson.messageOf(o))
        }

    suspend fun react(messageId: Long, emoji: String?): ChatResult<List<ChatReaction>> =
        put("messages/$messageId/reaction", ChatJson.reactionBody(emoji), ChatJson::reactionsOf)

    suspend fun read(conversationId: Long, upTo: Long): ChatResult<Int?> =
        post("conversations/$conversationId/read", ChatJson.readBody(upTo)) { ChatJson.unreadOf(it) }

    suspend fun typing(conversationId: Long, stop: Boolean = false): ChatResult<Unit> =
        post("conversations/$conversationId/typing", JsonObject().apply { if (stop) addProperty("stop", true) }, unit)

    suspend fun active(conversationId: Long, active: Boolean = true): ChatResult<Unit> =
        post("conversations/$conversationId/active", JsonObject().apply { if (!active) addProperty("active", false) }, unit)

    suspend fun search(conversationId: Long, q: String): ChatResult<ChatSearchResult> =
        get("conversations/$conversationId/search", mapOf("q" to q.take(100)), ChatJson::search)

    /** `GET conversations/:id/media`: [kind] image | voice | file | all, older than [before]. */
    suspend fun media(conversationId: Long, kind: String, before: Long? = null, limit: Int = 30): ChatResult<ChatMediaPage> {
        val q = buildMap {
            put("kind", kind)
            put("limit", limit.coerceIn(1, 100).toString())
            before?.let { put("before", it.toString()) }
        }
        return get("conversations/$conversationId/media", q, ChatJson::mediaPage)
    }

    // ─── Messenger 1.4: status and stories ──────────────────────────

    private suspend fun <T> del(path: String, parse: (JsonObject) -> T?): ChatResult<T> =
        call(request = { pluginDelete(MESSENGER_PLUGIN, path) }, parse = parse)

    /** `PUT status` → `{ status }` (null: cleared). */
    suspend fun putStatus(status: ChatStatus): ChatResult<ChatStatus?> =
        put("status", ChatJson14.statusBody(status)) { o -> ChatJson14.status(o.get("status")?.takeIf { it.isJsonObject }?.asJsonObject) ?: ChatStatus(null, ChatStatus.PRESET_NONE, null, ChatStatus.VIS_ALL) }

    suspend fun deleteStatus(): ChatResult<Unit> = del("status", unit)

    suspend fun feed(): ChatResult<ChatStatusFeed> = get("status/feed", parse = ChatJson14::feed)

    /** `POST status/stories` → `{ story }`. */
    suspend fun postStory(body: JsonObject): ChatResult<ChatStory?> =
        post("status/stories", body) { o -> Optional(ChatJson14.story(o.get("story")?.takeIf { it.isJsonObject }?.asJsonObject)) }.unwrap()

    suspend fun deleteStory(id: String): ChatResult<Unit> {
        val key = ChatJson14.pathKey(id) ?: return ChatResult.Failed(CODE_INVALID, 0)
        return del("status/stories/$key", unit)
    }

    suspend fun storySeen(id: String): ChatResult<Unit> {
        val key = ChatJson14.pathKey(id) ?: return ChatResult.Failed(CODE_INVALID, 0)
        return post("status/stories/$key/seen", JsonObject(), unit)
    }

    suspend fun storyViewers(id: String): ChatResult<List<ChatStoryViewer>> {
        val key = ChatJson14.pathKey(id) ?: return ChatResult.Failed(CODE_INVALID, 0)
        return get("status/stories/$key/viewers", parse = ChatJson14::viewers)
    }

    suspend fun storyReact(id: String, emoji: String): ChatResult<Unit> {
        val key = ChatJson14.pathKey(id) ?: return ChatResult.Failed(CODE_INVALID, 0)
        return post("status/stories/$key/react", JsonObject().apply { addProperty("emoji", emoji) }, unit)
    }

    // ─── Messenger 1.4: preferences and tools ───────────────────────

    /** `PUT prefs` with any keys (1.0's and 1.4's `privacy`, `notify`, `chats`) → the profile's prefs. */
    suspend fun putPrefsBody(body: JsonObject): ChatResult<Pair<ChatPrefs, ChatPrefs14>> =
        put("prefs", body) { o ->
            val p = o.get("prefs")?.takeIf { it.isJsonObject }?.asJsonObject
            ChatJson.prefs(p) to ChatJson14.prefs14(p)
        }

    suspend fun blocks(): ChatResult<List<ChatPerson>> = get("blocks", parse = ChatJson14::blocks)

    /** → the blocks after the change (`{ blocks }`), null when the answer has none. */
    suspend fun block(userId: Long): ChatResult<List<ChatPerson>?> =
        post("blocks", JsonObject().apply { addProperty("userId", userId) }) { o -> Optional(ChatJson14.blocks(o)) }.unwrap()

    suspend fun unblock(userId: Long): ChatResult<List<ChatPerson>?> = del("blocks/$userId") { o -> Optional(ChatJson14.blocks(o)) }.unwrap()

    suspend fun archive(conversationId: Long, archived: Boolean): ChatResult<ChatConversation?> =
        post("conversations/$conversationId/archive", JsonObject().apply { addProperty("archived", archived) }) { o -> Optional(ChatJson.conversationOf(o)) }.unwrap()

    suspend fun pin(conversationId: Long, messageId: Long): ChatResult<ChatConversation?> =
        post("conversations/$conversationId/pins", JsonObject().apply { addProperty("messageId", messageId) }) { o -> Optional(ChatJson.conversationOf(o)) }.unwrap()

    suspend fun unpin(conversationId: Long, messageId: Long): ChatResult<ChatConversation?> =
        del("conversations/$conversationId/pins/$messageId") { o -> Optional(ChatJson.conversationOf(o)) }.unwrap()

    suspend fun lists(): ChatResult<List<ChatList>> = get("lists", parse = ChatJson14::lists)

    suspend fun putLists(lists: List<ChatList>): ChatResult<List<ChatList>> =
        put("lists", ChatJson14.listsBody(lists)) { o -> ChatJson14.lists(o) ?: lists }

    /**
     * `GET conversations/:id/export?format=txt` → the whole text, oldest first. The server answers
     * the newest part (≤ 800 KB) with `truncated` / `before`; older parts are asked for up to
     * [maxParts] times and put in front.
     */
    suspend fun export(conversationId: Long, maxParts: Int = 8): ChatResult<ChatExportPart> {
        var before: Long? = null
        val parts = ArrayList<String>()
        var name: String? = null
        var truncated: Boolean
        var count = 0
        do {
            val q = buildMap {
                put("format", "txt")
                before?.let { put("before", it.toString()) }
            }
            val r = get("conversations/$conversationId/export", q, ChatJson14::exportPart)
            if (r !is ChatResult.Ok) return if (parts.isEmpty()) r else ChatResult.Ok(ChatExportPart(parts.reversed().joinToString(""), name, true, before))
            parts += r.value.text
            name = name ?: r.value.fileName
            truncated = r.value.truncated
            before = r.value.before
            count++
        } while (truncated && before != null && count < maxParts)
        return ChatResult.Ok(ChatExportPart(parts.reversed().joinToString(""), name, truncated, before))
    }

    suspend fun remind(messageId: Long, atIso: String): ChatResult<Unit> =
        post("messages/$messageId/remind", JsonObject().apply { addProperty("at", atIso) }, unit)

    suspend fun reminders(): ChatResult<List<ChatReminder>> = get("reminders", parse = ChatJson14::reminders)

    suspend fun deleteReminder(id: String): ChatResult<Unit> {
        val key = ChatJson14.pathKey(id) ?: return ChatResult.Failed(CODE_INVALID, 0)
        return del("reminders/$key", unit)
    }

    suspend fun suggest(messageId: Long): ChatResult<ChatSuggestion> = get("messages/$messageId/suggest", parse = ChatJson14::suggestion)

    suspend fun toList(messageId: Long, items: List<String>): ChatResult<Unit> =
        post("messages/$messageId/to-list", JsonObject().apply { add("items", JsonArray().apply { items.forEach { add(it) } }) }, unit)

    suspend fun toCalendar(messageId: Long, title: String, startsAt: String, endsAt: String?): ChatResult<Unit> =
        post(
            "messages/$messageId/to-calendar",
            JsonObject().apply {
                addProperty("title", title)
                addProperty("startsAt", startsAt)
                endsAt?.let { addProperty("endsAt", it) }
            },
            unit,
        )

    suspend fun places(): ChatResult<List<ChatPlace>> = get("familie/places", parse = ChatJson14::places)

    // ─── Messenger 1.4: polls, events, arrival, actions ─────────────

    /** Answers `{ message }` (the changed message) or just `{ ok }` (null: fetch the change). */
    private val messageOrNull: (JsonObject) -> ChatMessage? = { o -> ChatJson.messageOf(o) }

    private suspend fun messagePost(path: String, body: JsonObject = JsonObject()): ChatResult<ChatMessage?> =
        call(request = { pluginPost(MESSENGER_PLUGIN, path, body, lang()?.let { mapOf("lang" to it) } ?: emptyMap()) }) { o -> Optional(messageOrNull(o)) }.unwrap()

    suspend fun vote(messageId: Long, optionIds: List<String>) = messagePost("messages/$messageId/vote", ChatJson14.voteBody(optionIds))

    suspend fun addOption(messageId: Long, text: String) = messagePost("messages/$messageId/options", JsonObject().apply { addProperty("text", text.take(ChatPoll.OPTION_MAX)) })

    suspend fun closePoll(messageId: Long) = messagePost("messages/$messageId/close")

    suspend fun nudge(messageId: Long) = messagePost("messages/$messageId/nudge")

    /** [answer] null withdraws the answer. */
    suspend fun rsvp(messageId: Long, answer: String?, guests: Int?) = messagePost(
        "messages/$messageId/rsvp",
        JsonObject().apply {
            if (answer == null) addNull("answer") else addProperty("answer", answer)
            guests?.let { addProperty("guests", it.coerceIn(0, 99)) }
        },
    )

    suspend fun checklist(messageId: Long, itemId: String, done: Boolean): ChatResult<ChatMessage?> {
        val key = ChatJson14.pathKey(itemId) ?: return ChatResult.Failed(CODE_INVALID, 0)
        return messagePost("messages/$messageId/checklist/$key", JsonObject().apply { addProperty("done", done) })
    }

    suspend fun patchEvent(messageId: Long, body: JsonObject): ChatResult<ChatMessage?> =
        call(request = { pluginPatch(MESSENGER_PLUGIN, "messages/$messageId/event", body) }) { o -> Optional(messageOrNull(o)) }.unwrap()

    /** → whether the destination is reached now (`{ accepted, arrived }`). */
    suspend fun arrivalPosition(messageId: Long, body: JsonObject): ChatResult<Boolean> =
        post("messages/$messageId/arrival", body) { o -> ChatJson14.arrived(o) }

    suspend fun arrivalStop(messageId: Long) = messagePost("messages/$messageId/arrival/stop")

    suspend fun homeActions(): ChatResult<List<ChatHomeAction>> = get("actions", parse = ChatJson14::homeActions)

    private suspend fun runPost(path: String, body: JsonObject = JsonObject()): ChatResult<ChatRunResult> =
        post(path, body, ChatJson14::runResult)

    suspend fun actionRun(messageId: Long, actionId: String? = null, plugin: String? = null) =
        runPost("messages/$messageId/action/run", ChatJson14.actionRunBody(actionId, plugin))

    suspend fun actionApprove(messageId: Long) = runPost("messages/$messageId/action/approve")

    suspend fun actionDeny(messageId: Long) = runPost("messages/$messageId/action/deny")

    /** The picture of a plugin card (base64). */
    suspend fun cardImage(messageId: Long): ChatResult<String> = get("messages/$messageId/card/image", parse = ChatJson14::cardImage)

    // ─── media ──────────────────────────────────────────────────────

    /** Raw upload of [file] as [mime] → the media id to send with a message. */
    suspend fun upload(file: File, mime: String): ChatResult<String> {
        val type = mime.toMediaTypeOrNull() ?: return ChatResult.Failed(CODE_INVALID, 0)
        val length = file.length()
        val body = object : RequestBody() {
            override fun contentType() = type
            override fun contentLength(): Long = length
            override fun writeTo(sink: BufferedSink) {
                file.source().use { sink.writeAll(it) }
            }
        }
        return call(timeoutMs = UPLOAD_TIMEOUT_MS, media = true, request = { pluginMediaUpload(MESSENGER_PLUGIN, body) }, parse = ChatJson::uploadedMediaId)
    }

    /** The file [mediaId] (the caller closes the body); null when unreachable or refused. */
    suspend fun download(mediaId: String, range: String? = null): Response<ResponseBody>? {
        if (ChatJson.mediaId(mediaId) == null) return null
        val url = setupRepository.getServerUrl()
        if (url.isBlank() || !setupRepository.isConfigured()) return null
        return try {
            withTimeoutOrNull(TIMEOUT_MS) { apiClientProvider.mediaClient(url).pluginMediaGet(MESSENGER_PLUGIN, mediaId, range) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("Chats: download failed (%s)", e.javaClass.simpleName)
            null
        }
    }

}

/** A value that may be null inside a [ChatResult.Ok] (a parse of null would mean "invalid answer"). */
data class Optional<T>(val value: T?)

fun <T> ChatResult<Optional<T>>.unwrap(): ChatResult<T?> = if (this is ChatResult.Ok) ChatResult.Ok(value.value) else asFailure()

/** Answer of `DELETE messages/:id`. */
data class ChatDeleted(val tombstone: ChatMessage?)

/** Answer of `PATCH conversations/:id`. */
data class ChatPatch(val conversation: ChatConversation?, val left: Boolean)

/** Bodies of `PATCH conversations/:id` (README "Routes"). */
object ChatPatches {
    fun title(title: String) = JsonObject().apply { addProperty("title", title) }

    fun avatar(mediaId: String?) = JsonObject().apply { if (mediaId == null) addNull("avatarMediaId") else addProperty("avatarMediaId", mediaId) }

    fun disappearing(seconds: Int) = JsonObject().apply { addProperty("disappearingS", seconds) }

    /** Muted until [untilIso] (null = not muted). */
    fun mute(untilIso: String?) = JsonObject().apply { if (untilIso == null) addNull("mutedUntil") else addProperty("mutedUntil", untilIso) }

    fun notify(all: Boolean) = JsonObject().apply { addProperty("notify", if (all) "all" else "none") }

    fun add(userIds: List<Long>) = JsonObject().apply { add("add", JsonArray().apply { userIds.forEach { add(it) } }) }

    fun remove(userIds: List<Long>) = JsonObject().apply { add("remove", JsonArray().apply { userIds.forEach { add(it) } }) }

    fun role(userId: Long, admin: Boolean) = JsonObject().apply {
        add("role", JsonObject().apply {
            addProperty("userId", userId)
            addProperty("role", if (admin) ChatPerson.ROLE_ADMIN else ChatPerson.ROLE_MEMBER)
        })
    }
}
