package com.gatecontrol.android.chat

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.push.PushOpenActivity
import com.gatecontrol.android.ui.adaptive.LocalInDetailPane
import com.gatecontrol.android.ui.chat.ChatImageViewer
import com.gatecontrol.android.ui.chat.ConversationScreen
import com.gatecontrol.android.ui.theme.GateControlTheme
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject

/**
 * Content of a chat bubble (Android 11+, BubbleMetadata of the chat
 * notifications): one conversation, embedded and resizable. NOT exported —
 * only the app's own bubble PendingIntent starts it. Pictures open inside
 * the bubble; the chat info opens the conversation in the app.
 */
@AndroidEntryPoint
class ChatBubbleActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var repository: ChatRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val conversationId = intent.getLongExtra(EXTRA_CONVERSATION, 0L)
        if (conversationId <= 0L || !repository.available.value) {
            finish()
            return
        }
        setContent {
            val theme by settingsRepository.getTheme().collectAsStateWithLifecycle(initialValue = "system")
            val sysLang = rememberSaveable { Locale.getDefault().language }
            val locale by settingsRepository.getLocale().collectAsStateWithLifecycle(initialValue = if (sysLang == "de") "de" else "en")
            LaunchedEffect(locale) {
                val config = Configuration(resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)) }
                @Suppress("DEPRECATION")
                resources.updateConfiguration(config, resources.displayMetrics)
            }
            val dark = when (theme) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            GateControlTheme(darkTheme = dark) {
                var image by rememberSaveable { mutableStateOf<String?>(null) }
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.systemBars)) {
                    val open = image
                    if (open != null) {
                        BackHandler { image = null }
                        ChatImageViewer(mediaId = open, onBack = { image = null })
                    } else {
                        // No back arrow: the bubble collapses with the system's back.
                        CompositionLocalProvider(LocalInDetailPane provides true) {
                            com.gatecontrol.android.ui.chat.ChatSecureGate {
                                ConversationScreen(
                                    conversationId = conversationId,
                                    onBack = { finish() },
                                    onOpenInfo = { openInApp(conversationId) },
                                    onOpenImage = { image = it },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /** The conversation in the app itself (info, media overview). */
    private fun openInApp(conversationId: Long) {
        startActivity(PushOpenActivity.chatIntent(this, conversationId))
    }

    companion object {
        private const val EXTRA_CONVERSATION = "com.gatecontrol.android.chat.extra.BUBBLE_CONVERSATION"

        /** Explicit intent to this non-exported activity. */
        fun intent(context: Context, conversationId: Long): Intent =
            Intent(context, ChatBubbleActivity::class.java).putExtra(EXTRA_CONVERSATION, conversationId)
    }
}
