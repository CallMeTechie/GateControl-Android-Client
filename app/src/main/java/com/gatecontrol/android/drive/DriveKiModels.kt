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
    /** Where Drive searched (the areas' labels). */
    val areas: List<String> = emptyList(),
    /** A deadline of a source whose day the answer names ("Frist in den Familienkalender"); [DDeadline.nodeId] its file. */
    val deadline: DDeadline? = null,
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

/**
 * `POST scans/convert`: the document Drive made — as bytes ([file]; GateControl lets no plugin write a file of its own,
 * the app uploads them and files them with `scans/save`), its [name], its recognised [text]; [file] null: a PDF
 * without pictures (the app makes it itself).
 */
data class DConversion(
    val kind: String,
    val target: String,
    val title: String?,
    val name: String?,
    val folderId: Long?,
    val text: String?,
    val file: DConvertedFile?,
)

/** The bytes of a converted scan. */
class DConvertedFile(val name: String, val mime: String, val data: ByteArray)

/** `POST scans/save`: the filed document and the scan's file while it stays. */
data class DSaved(val node: DNode, val original: DNode?)
