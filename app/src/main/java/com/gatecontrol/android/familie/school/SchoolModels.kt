package com.gatecontrol.android.familie.school

/**
 * Models of the Schule plugin's app API (gatecontrol-schule,
 * `/api/v1/client/plugins/gatecontrol-schule/api/app/…`, README "API"):
 * the WebUntis timetable of the students the caller may see and the
 * change log. They come out of [SchoolJson], which validates what the
 * server sends; every field here is safe to use.
 */

const val SCHULE_PLUGIN = "gatecontrol-schule"

/** `state` of a lesson; anything the app does not know reads as [NORMAL]. */
enum class LessonState(val wire: String) {
    NORMAL("normal"),
    CANCELLED("cancelled"),
    CHANGED("changed"),
    EXTRA("extra");

    companion object {
        fun fromWire(value: String?): LessonState = entries.firstOrNull { it.wire == value } ?: NORMAL
    }
}

/** One lesson; times are the school's local time (`HH:MM`). */
data class SchoolLesson(
    val start: String,
    val end: String,
    /** Period numbers from the school's time grid (`3`, or `3`–`4` for a double lesson). */
    val period: Int? = null,
    val periodEnd: Int? = null,
    /** Short subject (`M`); may be blank when WebUntis sends none. */
    val subject: String = "",
    val subjectLong: String? = null,
    val subjectOld: String? = null,
    val teacher: String? = null,
    val teacherOld: String? = null,
    val room: String? = null,
    val roomOld: String? = null,
    val state: LessonState = LessonState.NORMAL,
    val info: String? = null,
    val substText: String? = null,
    val text: String? = null,
) {
    /** The long subject name, else the short one. */
    val title: String get() = subjectLong ?: subject

    val isSpecial: Boolean get() = state != LessonState.NORMAL
}

/** Homework due on a day (plugin 1.2+); [givenDate] = when it was set (`YYYY-MM-DD`). */
data class SchoolHomework(
    /** Short subject; may be blank when WebUntis sends none. */
    val subject: String = "",
    val subjectLong: String? = null,
    val text: String,
    val givenDate: String? = null,
    val completed: Boolean = false,
) {
    val title: String get() = subjectLong ?: subject
}

/** An exam on a day (plugin 1.2+); times are the school's local time (`HH:MM`), when known. */
data class SchoolExam(
    /** Short subject; may be blank when WebUntis sends none. */
    val subject: String = "",
    val subjectLong: String? = null,
    /** What the exam is called ("Klassenarbeit 2"); may be blank when only the subject is known. */
    val name: String = "",
    val start: String? = null,
    val end: String? = null,
    val type: String? = null,
    val text: String? = null,
) {
    val title: String get() = subjectLong ?: subject
}

/**
 * A day of the requested range (also without lessons); [holiday] names
 * the holidays. [homework] is due on the day, [exams] are written on it
 * (both empty for answers of plugins before 1.2).
 */
data class SchoolDay(
    val date: String,
    val holiday: String? = null,
    val lessons: List<SchoolLesson> = emptyList(),
    val homework: List<SchoolHomework> = emptyList(),
    val exams: List<SchoolExam> = emptyList(),
)

data class SchoolStudent(
    val id: Int,
    val name: String,
    /** GateControl user of the child, when mapped. */
    val userId: Int? = null,
    val klasse: String? = null,
    /** Last fetch of this student (epoch ms). */
    val updatedAt: Long? = null,
    val days: List<SchoolDay> = emptyList(),
    /** The child's colour (`#RRGGBB`, plugin 1.3+); null = the app's accent styling. */
    val colorHex: String? = null,
    /** Picture of the linked GateControl user (plugin 1.3+, same rules as user avatars); null = initials. */
    val avatarUrl: String? = null,
) {
    /** Colour or picture to tell this child apart (plugin 1.3+). */
    val hasFace: Boolean get() = colorHex != null || avatarUrl != null

    fun day(date: String): SchoolDay? = days.firstOrNull { it.date == date }
}

/** `GET app/timetable`: [updatedAt] = last fetch (epoch ms), null before the first. */
data class SchoolTimetable(
    val updatedAt: Long? = null,
    val students: List<SchoolStudent> = emptyList(),
) {
    fun student(id: Int?): SchoolStudent? = students.firstOrNull { it.id == id }
}

/** A timetable answer and its JSON as it came (kept on the device for offline starts). */
data class SchoolFetch(val timetable: SchoolTimetable, val raw: String)

/** `kind` of a logged change; anything unknown reads as [CHANGED]. */
enum class ChangeKind(val wire: String) {
    CANCELLED("cancelled"),
    CHANGED("changed"),
    EXTRA("extra"),
    /** "Findet doch statt": a cancellation was taken back. */
    RESTORED("restored");

    companion object {
        fun fromWire(value: String?): ChangeKind = entries.firstOrNull { it.wire == value } ?: CHANGED
    }
}

/** One entry of `GET app/changes` (newest first). */
data class SchoolChange(
    val id: Int,
    val studentId: Int,
    val student: String? = null,
    val date: String,
    val start: String? = null,
    val end: String? = null,
    val period: Int? = null,
    val kind: ChangeKind = ChangeKind.CHANGED,
    val subject: String = "",
    val subjectLong: String? = null,
    val subjectOld: String? = null,
    val teacher: String? = null,
    val teacherOld: String? = null,
    val room: String? = null,
    val roomOld: String? = null,
    val substText: String? = null,
    val info: String? = null,
    /** When the plugin noticed it (epoch ms). */
    val detectedAt: Long? = null,
    val pushed: Boolean = false,
) {
    val title: String get() = subjectLong ?: subject
}
