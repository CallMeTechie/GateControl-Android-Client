package com.gatecontrol.android.drive

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Drive 1.11: the menu's folder tree, the vaults kept out of the lists, the areas of a new vault. */
class DriveTreeTest {

    private fun area(id: String, kind: String, root: Long, role: String = "write", archived: Boolean = false) =
        DArea(id, kind, id, role, null, root, archived)

    private fun dir(id: Long, name: String, hasDirs: Boolean? = null) = DTreeDir(id, name, hasDirs)

    private fun node(id: Long, name: String, dir: Boolean = true, vault: String? = null, parent: Long = 1) =
        DNode(id, "mine:1", parent, name, dir, 0, null, null, 1, null, null, null, vaultId = vault)

    private val mine = area("mine:1", "mine", 1)
    private val family = area("family:1", "family", 100)

    @Test
    fun `closed areas show one row each, with an arrow until their folders are known`() {
        val rows = DriveTree.flatten(listOf(mine, family), emptySet(), emptyMap())
        assertEquals(listOf(1L, 100L), rows.map { it.id })
        assertTrue(rows.all { it.depth == 0 && it.expandable && !it.expanded && !it.loading })
        assertEquals(mine, rows[0].area)
        // an area without folders has no arrow once that is known
        val empty = DriveTree.flatten(listOf(mine), emptySet(), mapOf(1L to emptyList()))
        assertFalse(empty.single().expandable)
    }

    @Test
    fun `opened folders follow their parent one level deeper, in any depth`() {
        val children = mapOf(
            1L to listOf(dir(2, "Bilder", true), dir(3, "Dokumente", false)),
            2L to listOf(dir(4, "Kamera-Uploads", true)),
            4L to listOf(dir(5, "2026", null)),
        )
        val rows = DriveTree.flatten(listOf(mine, family), setOf(1L, 2L, 4L), children)
        assertEquals(listOf(1L, 2L, 4L, 5L, 3L, 100L), rows.map { it.id })
        assertEquals(listOf(0, 1, 2, 3, 1, 0), rows.map { it.depth })
        assertTrue(rows.take(5).all { it.areaId == "mine:1" })
        assertEquals("family:1", rows.last().areaId)
        // hasDirs false: no arrow; null (older Drive): the arrow until loaded
        assertFalse(rows.first { it.id == 3L }.expandable)
        assertTrue(rows.first { it.id == 5L }.expandable)
        assertNull(rows.first { it.id == 2L }.area)
    }

    @Test
    fun `an opened folder whose subfolders are not loaded yet is loading`() {
        val rows = DriveTree.flatten(listOf(mine), setOf(1L, 2L), mapOf(1L to listOf(dir(2, "Bilder", true))))
        assertTrue(rows.first { it.id == 2L }.loading)
        assertEquals(listOf(2L), DriveTree.needs(rows))
        // opened but known to have none: neither open nor loading
        val none = DriveTree.flatten(listOf(mine), setOf(1L, 2L), mapOf(1L to listOf(dir(2, "Bilder", false))))
        assertFalse(none.first { it.id == 2L }.expanded)
        assertTrue(DriveTree.needs(none).isEmpty())
    }

    @Test
    fun `a loop in the answers never hangs the tree`() {
        val rows = DriveTree.flatten(listOf(mine), setOf(1L, 2L), mapOf(1L to listOf(dir(2, "a", true)), 2L to listOf(dir(1, "loop", true), dir(3, "b"))))
        assertEquals(listOf(1L, 2L, 3L), rows.map { it.id })
    }

    @Test
    fun `opened folders are kept as strings and read back`() {
        val open = DriveTree.toggle(DriveTree.toggle(emptySet(), 5L), 7L)
        assertEquals(setOf(5L, 7L), open)
        assertEquals(setOf(7L), DriveTree.toggle(open, 5L))
        assertEquals(open, DriveTree.decode(DriveTree.encode(open)))
        assertEquals(setOf(3L), DriveTree.decode(setOf("3", "x", "-1", "0")))
        assertTrue(DriveTree.decode(null).isEmpty())
    }

    @Test
    fun `the tree lists folders only, never a vault, by name`() {
        val dirs = DriveTree.dirsOf(
            listOf(node(3, "zeta"), node(2, "Alpha"), node(4, "file.pdf", dir = false), node(5, "4f1c…", vault = "Vt9bJm0n2QwErTyUiOpAsD")),
            mapOf(2L to true),
        )
        assertEquals(listOf("Alpha", "zeta"), dirs.map { it.name })
        assertEquals(true, dirs[0].hasDirs)
        assertNull(dirs[1].hasDirs)
    }

    @Test
    fun `json - children with hasDirs (Drive 1_11) and without (older)`() {
        val o = JsonParser.parseString(
            """{"items":[
                {"id":2,"areaId":"mine:1","parentId":1,"name":"Bilder","isDir":true,"hasDirs":true},
                {"id":3,"areaId":"mine:1","parentId":1,"name":"Akten","isDir":true,"hasDirs":false},
                {"id":4,"areaId":"mine:1","parentId":1,"name":"a.pdf","isDir":false},
                {"id":5,"areaId":"mine:1","parentId":1,"name":"0123456789abcdef0123456789abcdef","isDir":true,"vaultId":"Vt9bJm0n2QwErTyUiOpAsD","encrypted":true}
            ],"total":4}""",
        ).asJsonObject
        val dirs = DriveJson.treeDirs(o)!!
        assertEquals(listOf(3L to false, 2L to true), dirs.map { it.id to it.hasDirs })
        assertNull(DriveJson.treeDirs(JsonParser.parseString("{}").asJsonObject))
    }

    @Test
    fun `groups - Persoenlich is Meine Dateien, Gemeinsam the rest`() {
        val team = area("team:1", "team", 200)
        val child = area("child:1", "child", 300)
        assertEquals(listOf(mine), DriveTree.personal(listOf(family, mine, team)))
        assertEquals(listOf(family, team, child), DriveTree.shared(listOf(family, mine, team, child)))
    }

    @Test
    fun `vault folders stay out of the lists, inside a vault nothing is left out`() {
        val vault = "Vt9bJm0n2QwErTyUiOpAsD"
        val top = DFolder(node(1, "", parent = 0).copy(parentId = null), emptyList(), "write", listOf(node(2, "a"), node(5, "x", vault = vault), node(6, "b.pdf", dir = false)), total = 3, next = null)
        val out = DriveLogic.withoutVaults(top)
        assertEquals(listOf(2L, 6L), out.items.map { it.id })
        assertEquals(2, out.total)
        val inside = DFolder(node(5, "x", vault = vault), emptyList(), "write", listOf(node(7, "y", vault = vault)), total = 1, next = null)
        assertEquals(inside, DriveLogic.withoutVaults(inside))
        assertEquals(listOf(2L), DriveLogic.withoutVaults(listOf(node(2, "a"), node(5, "x", vault = vault))).map { it.id })
    }

    @Test
    fun `a new vault - Nur ich, the family with a family seat, team folders with a business seat`() {
        val team = area("team:1", "team", 200)
        val teamRead = area("team:2", "team", 201, role = "read")
        val child = area("child:1", "child", 300)
        val archived = area("family:2", "family", 400, archived = true)
        assertEquals(listOf("mine:1", "family:1"), DriveLogic.vaultAreas(listOf(family, child, mine, archived), "family").map { it.id })
        assertEquals(listOf("mine:1", "team:1"), DriveLogic.vaultAreas(listOf(team, teamRead, mine, family), "business").map { it.id })
        assertTrue(DriveLogic.vaultAreas(listOf(mine.copy(role = "read")), "family").isEmpty())
    }
}
