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
    ): ChatResult<ChatMessagePage> {
        val q = buildMap {
            put("limit", limit.coerceIn(1, 100).toString())
            before?.let { put("before", it.toString()) }
            after?.let { put("after", it.toString()) }
            changedSince?.let { put("changedSince", it) }
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
