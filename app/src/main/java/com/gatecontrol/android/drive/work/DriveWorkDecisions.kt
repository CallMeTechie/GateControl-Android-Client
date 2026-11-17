package com.gatecontrol.android.drive.work

import com.gatecontrol.android.drive.DNode
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * What the person decided about a photo in Drive ("Aufräumen": ticked = work, unticked = private; "Arbeit" / "Privat"
 * of the bar; moved into or out of the work folder) — kept apart from what the AI learns (its examples go beyond
 * [DriveWorkFeatures.MAX_EXAMPLES], "Neu lernen" drops them), so a photo decided once is never suggested again.
 *
 * A decision holds every key the photo had then ([keysOf]): its node id (`n:<id>`), its content (`h:<sha256>`, Drive
 * 1.30.1 sends it with the photo items) and its size with the time it was taken (`s:<size>@<takenAt>`). A photo is
 * looked up by its id and its content — by size and time only when its content is unknown (an older Drive), since two
 * photos may share them ([lookupKeys]): the same photo after a move or a rename (same id), after a new upload of the
 * same file (new id, same content).
 */
data class WorkDecision(val keys: Set<String>, val work: Boolean, val at: Long)

object DriveWorkDecisions {

    /** Decisions kept at most (the oldest go). */
    const val MAX = 20_000

    /** The keys a decision of [n] keeps: its id, its content and its size with the time it was taken, when known. */
    fun keysOf(n: DNode): Set<String> = buildSet {
        add(idKey(n.id))
        n.sha256?.let { add("h:$it") }
        sizeKey(n)?.let { add(it) }
    }

    /** The keys [n] is looked up by: its id, its content — or, unknown, its size with the time it was taken. */
    fun lookupKeys(n: DNode): Set<String> = buildSet {
        add(idKey(n.id))
        val sha = n.sha256
        if (sha != null) add("h:$sha") else sizeKey(n)?.let { add(it) }
    }

    private fun sizeKey(n: DNode): String? {
        val taken = n.takenAt?.takeIf { it.isNotBlank() } ?: return null
        return if (n.size > 0) "s:${n.size}@$taken" else null
    }

    fun idKey(id: Long) = "n:$id"

    /** Key → its decision. */
    fun index(list: List<WorkDecision>): Map<String, WorkDecision> {
        val out = HashMap<String, WorkDecision>(list.size * 2)
        // the newest wins a key two decisions share
        for (d in list.sortedBy { it.at }) for (k in d.keys) out[k] = d
        return out
    }

    /** The decision of [n] (any of its keys), else null. */
    fun of(index: Map<String, WorkDecision>, n: DNode): WorkDecision? = lookupKeys(n).firstNotNullOfOrNull { index[it] }

    /**
     * [nodes] decided ([work]) at [at]: one decision per photo with all its keys; an earlier decision of the same photo
     * (its id or content) goes — a later answer replaces an earlier one. The oldest go beyond [MAX].
     */
    fun decide(list: List<WorkDecision>, nodes: List<DNode>, work: Boolean, at: Long): List<WorkDecision> {
        if (nodes.isEmpty()) return list
        val add = nodes.distinctBy { it.id }.map { WorkDecision(keysOf(it), work, at) }
        val taken = add.flatMapTo(HashSet()) { d -> d.keys.filter { !it.startsWith("s:") } }
        val out = list.filter { d -> d.keys.none { it in taken } } + add
        return if (out.size > MAX) out.sortedBy { it.at }.takeLast(MAX) else out
    }

    /** The decisions of the photos [ids] go (an assignment taken back). */
    fun forget(list: List<WorkDecision>, ids: Collection<Long>): List<WorkDecision> {
        if (ids.isEmpty()) return list
        val keys = ids.mapTo(HashSet()) { idKey(it) }
        return list.filter { d -> d.keys.none { it in keys } }
    }

    /**
     * Of [nodes], those nobody decided about — and [list] with the keys the decided ones have now added to their
     * decision (a decision kept by id alone learns the content, so a later copy of it is known too); the same [list]
     * when nothing was added.
     */
    fun undecided(list: List<WorkDecision>, nodes: List<DNode>): Pair<List<DNode>, List<WorkDecision>> {
        if (list.isEmpty() || nodes.isEmpty()) return nodes to list
        val index = index(list)
        val grown = HashMap<WorkDecision, MutableSet<String>>()
        val open = nodes.filter { n ->
            val d = of(index, n) ?: return@filter true
            val more = keysOf(n) - d.keys
            if (more.isNotEmpty()) grown.getOrPut(d) { HashSet() } += more
            false
        }
        if (grown.isEmpty()) return open to list
        return open to list.map { d -> grown[d]?.let { d.copy(keys = d.keys + it) } ?: d }
    }

    /**
     * The decisions before they were kept apart (the app up to 1.129): the examples the person gave — "Aufräumen", the
     * bar, a move — by node id (`n:<id>`; `m:<sha256>` as content).
     */
    fun fromExamples(examples: List<WorkExample>): List<WorkDecision> = examples.mapNotNull { e ->
        if (e.source != WorkExample.Source.CLEANUP && e.source != WorkExample.Source.ASSIGN && e.source != WorkExample.Source.MOVE) return@mapNotNull null
        val key = when {
            e.key.startsWith("n:") -> e.key.takeIf { it.substring(2).toLongOrNull() != null }
            e.key.startsWith("m:") -> "h:" + e.key.substring(2)
            else -> null
        } ?: return@mapNotNull null
        WorkDecision(setOf(key), e.work, e.at)
    }

    /** `{ v:1, d:[{ k:[…], w, at }, …] }`. */
    fun encode(list: List<WorkDecision>): String = JsonObject().apply {
        addProperty("v", 1)
        add("d", JsonArray().apply {
            list.forEach { d ->
                add(JsonObject().apply {
                    add("k", JsonArray().apply { d.keys.forEach { add(it) } })
                    addProperty("w", d.work)
                    addProperty("at", d.at)
                })
            }
        })
    }.toString()

    /** Anything broken reads as nothing; null when there is no text at all (never kept). */
    fun decode(text: String?): List<WorkDecision>? {
        if (text == null) return null
        return runCatching {
            JsonParser.parseString(text).asJsonObject.getAsJsonArray("d").mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val keys = o.getAsJsonArray("k")?.mapNotNull { k -> k.takeIf { it.isJsonPrimitive }?.asString?.take(80) }?.toSet()
                    ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val work = o.get("w")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: return@mapNotNull null
                WorkDecision(keys, work, o.get("at")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L)
            }
        }.getOrDefault(emptyList())
    }
}
