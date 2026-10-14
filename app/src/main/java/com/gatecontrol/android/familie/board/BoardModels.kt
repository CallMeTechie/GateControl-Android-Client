package com.gatecontrol.android.familie.board

/*
 * Familie 1.2 Pinnwand & Packlisten (`board`): sticky notes, checklists and
 * checklist templates ("Urlaub", "Schwimmbad"). All members read and write;
 * authors and parents delete notes. Parsed in FamilieJson12.
 */

data class BoardNote(
    val id: Int,
    val text: String,
    /** yellow | green | blue | pink | grey */
    val color: String,
    val pinned: Boolean,
    val authorId: Int?,
    val createdAt: String?,
    val expiresAt: String?,
) {
    companion object {
        const val YELLOW = "yellow"
        val COLORS = listOf(YELLOW, "green", "blue", "pink", "grey")
        const val MAX_TEXT = 500
    }
}

data class ChecklistItem(val id: Int, val text: String, val done: Boolean, val doneBy: Int?) {
    companion object {
        const val MAX_TEXT = 80
    }
}

data class Checklist(
    val id: Int,
    val title: String,
    val items: List<ChecklistItem>,
    val fromTemplateId: Int?,
    val createdAt: String?,
) {
    val open: Int get() = items.count { !it.done }

    companion object {
        const val MAX_TITLE = 60
    }
}

data class ChecklistTemplate(val id: Int, val title: String, val items: List<String>)

/** `GET board`. */
data class BoardPayload(
    /** Pinned first, then newest. */
    val notes: List<BoardNote>,
    val checklists: List<Checklist>,
    val templates: List<ChecklistTemplate>,
)

/** `POST board/notes` / `PATCH board/notes/<id>`. */
data class NoteDraft(
    val text: String,
    val color: String = BoardNote.YELLOW,
    val pinned: Boolean = false,
    /** `YYYY-MM-DD` (end of that day) or null: stays. */
    val expiresAt: String? = null,
)

/** `overview.board`: pinned notes and open checklists. */
data class OverviewBoard(val pinned: Int, val openChecklists: Int, val pinnedTexts: List<String> = emptyList())
