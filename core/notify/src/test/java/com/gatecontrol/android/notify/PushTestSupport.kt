package com.gatecontrol.android.notify

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.notify.model.PushMessage
import com.gatecontrol.android.notify.model.PushTopic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.Closeable
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

fun testStore(dir: File): PushPrefsStore = PushPrefsStore(
    PreferenceDataStoreFactory.create(
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        produceFile = { File(dir, "push_test.preferences_pb") },
    ),
)

class FakePresenter : PushPresenter {
    data class Shown(val message: PushMessage, val id: Int, val channel: ChannelSpec, val quiet: Boolean)

    val shown = CopyOnWriteArrayList<Shown>()
    val cancelled = CopyOnWriteArrayList<Int>()
    val channelTopics = CopyOnWriteArrayList<List<PushTopic>>()
    val fresh = CopyOnWriteArrayList<PushMessage>()

    override suspend fun onNew(message: PushMessage) {
        fresh += message
    }

    override suspend fun ensureTopicChannels(topics: List<PushTopic>) {
        channelTopics += topics
    }

    override suspend fun show(message: PushMessage, notificationId: Int, channel: ChannelSpec, quiet: Boolean) {
        shown += Shown(message, notificationId, channel, quiet)
    }

    override fun cancel(notificationId: Int) {
        cancelled += notificationId
    }
}

class FakePlatform(var restricted: Boolean = false) : PushPlatform {
    var onChange: ((String) -> Unit)? = null
    override fun isBatteryRestricted(): Boolean = restricted
    override fun watchNetwork(onChange: (String) -> Unit): Closeable {
        this.onChange = onChange
        return Closeable { this.onChange = null }
    }
}

/** Endpoint against a MockWebServer (or any URL); [url] may change at runtime. */
class TestEndpoint(var url: String, var token: String = "gc_test") : PushEndpoint {
    private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    override fun serverUrl(): String = url
    override fun credentialKey(): String = "$url|$token"
    override fun api(serverUrl: String): ApiClient = Retrofit.Builder()
        .baseUrl(if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/")
        .client(http.newBuilder().readTimeout(5, TimeUnit.SECONDS).build())
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(ApiClient::class.java)

    override fun streamingClient(serverUrl: String): OkHttpClient = http
}

fun message(
    id: Long,
    seq: Long = id,
    topic: String = "devices",
    priority: String = "normal",
    collapseKey: String? = null,
    silent: Boolean = false,
    state: String = "delivered",
) = PushMessage(
    seq = seq, id = id, eventId = "e", topic = topic, priority = priority, title = "Title $id", body = "Body",
    createdAt = "2026-10-10T21:42:03Z", expiresAt = null, collapseKey = collapseKey, silent = silent,
    route = null, actions = emptyList(), state = state,
)

class FakeControlSink : ServerControlSink {
    val states = CopyOnWriteArrayList<com.gatecontrol.android.notify.model.ServerState>()
    val controls = CopyOnWriteArrayList<com.gatecontrol.android.notify.model.ServerControl>()
    override fun onServerState(state: com.gatecontrol.android.notify.model.ServerState) {
        states += state
    }

    override fun onControl(control: com.gatecontrol.android.notify.model.ServerControl) {
        controls += control
    }
}
