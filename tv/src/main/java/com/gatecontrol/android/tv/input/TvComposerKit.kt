package com.gatecontrol.android.tv.input

import com.gatecontrol.android.common.ContinueLink
import com.gatecontrol.android.tv.TvSettings
import com.gatecontrol.android.tv.gatedrop.TvGateDrop
import com.gatecontrol.android.tv.gatedrop.TvDropSend
import com.gatecontrol.android.tv.gatedrop.TvDropTarget
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the pages with a [com.gatecontrol.android.tv.ui.input.TvComposer] take from the app: the own text building
 * blocks as a [TvSnippetSource] and „Am Handy weiterschreiben“ over GateDrop to the person's own devices.
 */
@Singleton
class TvComposerKit @Inject constructor(
    private val settings: TvSettings,
    private val gateDrop: TvGateDrop,
) {
    /** The own building blocks of Einstellungen › Eingabe. */
    val ownSnippets: TvSnippetSource = TvSnippetSource { settings.snippets.value.map { TvSnippet(it, TvSnippetKind.OWN) } }

    /** The draft as a GateDrop text to an own device, [target] (chat, mail draft) as the link on its last line. */
    fun handoff(target: ContinueLink?): TvHandoff = TvHandoff(
        targets = gateDrop::ownDevices,
        send = { to, text -> gateDrop.sendText(ContinueLink.append(text, target), to) },
    )
}

/**
 * „Am Handy weiterschreiben“ (GateDrop text to the person's own devices): where it can go and the send of the text;
 * the caller puts the link to the chat or mail draft into [send].
 */
class TvHandoff(
    val targets: suspend () -> List<TvDropTarget>?,
    val send: suspend (TvDropTarget, String) -> TvDropSend,
)
