package com.gatecontrol.android.drive

// ─── Drive 1.14: AI in Drive (features `docAsk`, `scanDocs`, `photoSearch`, `deadlineCalendar`) ──

/**
 * `POST ask`: the answer to a question, only from the person's own files (never a vault). [status] `answered` |
 * `not_found`; [terms] what Drive searched for ("Gesucht nach: …").
 */
data class DAnswer(
    val status: String,
    val answer: String?,
    val sources: List<DSource>,
    val terms: List<String>,
) {
    val answered: Boolean get() = status == STATUS_ANSWERED && !answer.isNullOrBlank()

    companion object {
        const val STATUS_ANSWERED = "answered"
        const val STATUS_NOT_FOUND = "not_found"
    }
}

/** Where an answer comes from: the file, its folder path, the page and the words quoted. */
data class DSource(val nodeId: Long, val name: String, val path: String?, val page: Int?, val quote: String?)

/**
 * `POST scans/classify`: what a scan is — `printed` | `handwriting` | `table` | `whiteboard` | `other` — how sure the
 * AI is (0…1) and a short line about it ("3 Spalten, 12 Zeilen, Summenzeile").
 */
data class DScanKind(val kind: String, val confidence: Double?, val summary: String?)

/** `POST scans/convert`: the new document and, kept, the original scan. */
data class DConverted(val node: DNode, val original: DNode?)
