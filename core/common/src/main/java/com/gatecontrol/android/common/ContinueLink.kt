package com.gatecontrol.android.common

/**
 * „Am Handy weiterschreiben“ (GateControl TV): the TV sends an answer begun there as a GateDrop text to the person's
 * own devices, its last line a link to where it belongs — `gatecontrol://chat/<conversation>` or
 * `gatecontrol://mail/draft/<draft>`. The phone app reads the link from the received text and opens that chat or
 * draft with the text filled in.
 *
 * Pure parsing (no Android) so both apps and their unit tests share it.
 */
sealed interface ContinueLink {
    val link: String

    /** A conversation of the messenger plugin. */
    data class Chat(val conversationId: Long) : ContinueLink {
        override val link: String get() = "gatecontrol://chat/$conversationId"
    }

    /** A draft of the mail plugin (on the server). */
    data class MailDraft(val draftId: Long) : ContinueLink {
        override val link: String get() = "gatecontrol://mail/draft/$draftId"
    }

    /** A received GateDrop text split into what was written and where it continues. */
    data class InText(val target: ContinueLink, val text: String)

    companion object {
        private val CHAT_RE = Regex("^gatecontrol://chat/([1-9][0-9]{0,17})$", RegexOption.IGNORE_CASE)
        private val MAIL_RE = Regex("^gatecontrol://mail/draft/([1-9][0-9]{0,17})$", RegexOption.IGNORE_CASE)

        /** One link on its own; null when it is no continue link. */
        fun parse(raw: String?): ContinueLink? {
            val s = raw?.trim()?.takeIf { it.length in 1..64 } ?: return null
            CHAT_RE.matchEntire(s)?.let { m -> return m.groupValues[1].toLongOrNull()?.let(::Chat) }
            MAIL_RE.matchEntire(s)?.let { m -> return m.groupValues[1].toLongOrNull()?.let(::MailDraft) }
            return null
        }

        /** [text] with [target] as its own last line (what the TV sends). */
        fun append(text: String, target: ContinueLink?): String {
            val body = text.trimEnd()
            return if (target == null) body else body + "\n\n" + target.link
        }

        /**
         * The link in the last line of a received [text] and the text before it; null when the last line is no
         * continue link (an ordinary GateDrop text stays one).
         */
        fun find(text: String?): InText? {
            val t = text?.trimEnd() ?: return null
            val cut = t.lastIndexOf('\n')
            val target = parse(if (cut < 0) t else t.substring(cut + 1)) ?: return null
            return InText(target, if (cut < 0) "" else t.substring(0, cut).trim())
        }
    }
}
