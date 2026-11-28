package com.gatecontrol.android.ui.drive

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DVaultLocal
import com.gatecontrol.android.drive.DVaultLocalStart
import com.gatecontrol.android.drive.vault.DriveVaultCrypto
import com.gatecontrol.android.drive.vault.DriveVaultLocalFolder
import com.gatecontrol.android.drive.vault.DriveVaultLocalItem
import com.gatecontrol.android.drive.vault.DriveVaultLocalJobs
import com.gatecontrol.android.drive.vault.DriveVaultLocalTrashEntry
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.theme.GateControlTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * "Nur auf diesem Gerät" on the phone (A1–A8 of the approved draft): the choice "Wo liegt dieser Ordner?", the
 * confirmation that needs the tick, the job's card, the folder kept here, another device's placeholder. With
 * `VAULT_LOCAL_SHOTS=<dir>` the drawn parts are saved there (for a look, not part of CI).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "de-w411dp-h891dp")
class DriveVaultLocalUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val vaultId = "Vt9bJm0n2QwErTyUiOpAsD"
    private val content = mutableStateOf<@Composable () -> Unit>({})
    private val darkMode = mutableStateOf(false)

    private fun page(dark: Boolean = false, block: @Composable () -> Unit) {
        darkMode.value = dark
        content.value = block
        if (!started) {
            started = true
            rule.setContent {
                GateControlTheme(darkTheme = darkMode.value) {
                    Column(
                        Modifier.testTag("shot").background(MaterialTheme.colorScheme.background).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) { content.value() }
                }
            }
        }
        rule.waitForIdle()
    }

    private var started = false

    private fun shot(name: String, dialog: Boolean = false) {
        val dir = System.getenv("VAULT_LOCAL_SHOTS") ?: return
        val node = if (dialog) rule.onNode(isDialog()) else rule.onNodeWithTag("shot")
        val bmp = node.captureToImage().asAndroidBitmap()
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun node(id: Long, dir: Boolean, size: Long = 0, local: DVaultLocal? = null) =
        DNode(id, "mine:1", 1, "0".repeat(32), dir, size, null, null, 1, "2026-11-12T10:00:00Z", null, null, vaultId = vaultId, keyVersion = 1, local = local)

    private fun other(state: String = DVaultLocal.STATE_LOCAL) =
        DVaultLocal(10, vaultId, "a7K-bh8_xeRZDI08-8JcCw", "Pixel 8", "android", "approved", state, "2026-11-27T10:00:00Z", mine = true, thisDevice = false, purgeAt = null, undo = false)

    private fun folder(lastChange: Long = 1000) = DriveVaultLocalFolder(
        "srv", vaultId, 10, 1, "mine:1", null, "dev", DriveVaultLocalFolder.State.LOCAL, since = 1000, purgeAt = System.currentTimeMillis() + 3_600_000, changedAt = lastChange,
        items = listOf(
            DriveVaultLocalItem(21, 10, false, "n", size = 1_200_000),
            DriveVaultLocalItem(22, 10, false, "n", size = 2_800_000),
            DriveVaultLocalItem(23, 10, false, "n", size = 3_400_000),
        ),
    )

    @Test
    fun `A1 the list marks a folder kept here and greys out another device's folder`() {
        page {
            GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                NodeRow(node(10, true), title = "Steuer 2025", description = "23 Dateien · 412 MB", onClick = {}, icon = { VaultLocalIcon(node(10, true), here = true) }, trailing = { VaultLocalChip() }, onMore = {})
                GcRowDivider()
                NodeRow(node(11, true), title = "Gesundheit", description = "14 Dateien · 3 offline", onClick = {}, trailing = { VaultOfflineChip(loading = false) }, onMore = {})
                GcRowDivider()
                VaultPlaceholderRow(node(12, true, local = other()), "Ausweise", other()) {}
                GcRowDivider()
                NodeRow(node(13, false, 184_000), title = "Passwortliste.pdf", onClick = {}, trailing = { VaultOfflineChip(loading = false) }, onMore = {})
            }
        }
        rule.onNodeWithText("Nur hier").assertExists()
        rule.onNodeWithText("Nur auf Pixel 8", substring = true).assertExists()
        shot("A1-liste")
        page(dark = true) {
            GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                NodeRow(node(10, true), title = "Steuer 2025", description = "23 Dateien · 412 MB", onClick = {}, icon = { VaultLocalIcon(node(10, true), here = true) }, trailing = { VaultLocalChip() }, onMore = {})
                GcRowDivider()
                VaultPlaceholderRow(node(12, true, local = other()), "Ausweise", other()) {}
            }
        }
        shot("A1-liste-dunkel")
    }

    @Test
    fun `A2 the menu's choice offers only on this phone when it is offered`() {
        var chosen: VaultWhere? = null
        page {
            Text("Ausweise", style = MaterialTheme.typography.titleMedium)
            VaultWhereChoice(VaultWhere.SERVER, localOffered = true, online = true, fixedExtra = false) { chosen = it }
        }
        rule.onNodeWithText("Wo liegt dieser Ordner?".uppercase()).assertExists()
        rule.onNodeWithText("Zusätzlich offline auf diesem Handy").assertExists()
        shot("A2-wo-liegt")
        rule.onNodeWithText("Nur auf diesem Handy").performClick()
        assertEquals(VaultWhere.HERE, chosen)
        page { VaultWhereChoice(VaultWhere.SERVER, localOffered = false, online = true, fixedExtra = false) {} }
        rule.onNodeWithText("Nur auf diesem Handy").assertDoesNotExist()
    }

    @Test
    fun `A3 nothing happens before the tick`() {
        var confirmed = false
        page {
            VaultLocalAskDialog(
                "Ausweise",
                DVaultLocalStart(null, emptyList(), files = 6, folders = 1, bytes = 18_000_000, versions = 4, versionBytes = 9_000_000, trashEntries = 1, trashFiles = 1, trashBytes = 100),
                free = 21_400_000_000,
                onConfirm = { confirmed = true },
                onDismiss = {},
            )
        }
        rule.onNodeWithText("Nur auf diesem Handy").assertIsNotEnabled()
        rule.onNodeWithText("Ich weiß, dass es danach keine Kopie auf dem Server gibt.").performClick()
        rule.onNodeWithText("Nur auf diesem Handy").assertIsEnabled()
        shot("A3-bestaetigung", dialog = true)
        rule.onNodeWithText("Nur auf diesem Handy").performClick()
        assertEquals(true, confirmed)
    }

    @Test
    fun `A4 the job's card shows the three steps and can be called off`() {
        var cancelled = false
        page {
            VaultLocalJobCard(
                DriveVaultLocalJobs.Progress(DriveVaultLocalJobs.Kind.MOVE, vaultId, 10, "Ausweise", DriveVaultLocalJobs.Step.CHECK, done = 6, checked = 4, total = 6, bytes = 18_000_000),
                onCancel = { cancelled = true }, onRetry = {}, onClose = {},
            )
            GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                NodeRow(node(10, true), title = "Ausweise", description = "Wird auf dieses Handy verschoben …", onClick = null, icon = { VaultLocalIcon(node(10, true), here = true) })
            }
        }
        rule.onNodeWithText("Prüfen und hier verschlüsseln").assertExists()
        rule.onNodeWithText("Vom Server löschen").assertExists()
        shot("A4-fortschritt")
        rule.onNodeWithText("Abbrechen").performClick()
        assertEquals(true, cancelled)
        page {
            VaultLocalJobCard(
                DriveVaultLocalJobs.Progress(DriveVaultLocalJobs.Kind.MOVE, vaultId, 10, "Ausweise", done = 3, checked = 3, total = 6, error = "offline"),
                onCancel = {}, onRetry = {}, onClose = {},
            )
        }
        rule.onNodeWithText("Weiter").assertExists()
        shot("A4-angehalten")
    }

    @Test
    fun `A5 a folder kept here says so, with undo while nothing changed`() {
        page {
            VaultLocalCard(folder(), undoUntil = System.currentTimeMillis() + 3_600_000, onBack = {}, onUndo = {})
            GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                listOf("Personalausweis.pdf" to 1_200_000L, "Reisepass.pdf" to 2_800_000L, "Führerschein.jpg" to 3_400_000L).forEachIndexed { i, (n, size) ->
                    if (i > 0) GcRowDivider()
                    NodeRow(node(21L + i, false, size).copy(keyVersion = DriveVaultCrypto.LOCAL_KEY_VERSION), title = n, onClick = {}, onMore = {})
                }
            }
        }
        rule.onNodeWithText("Liegt nur auf diesem Handy").assertExists()
        rule.onNodeWithText("Rückgängig").assertExists()
        rule.onNodeWithText("Auf den Server").assertExists()
        shot("A5-ordner-nur-hier")
        page { VaultLocalCard(folder().copy(revoked = true), undoUntil = null, onBack = null, onUndo = null) }
        rule.onNodeWithText("Dieses Gerät wurde aus dem Tresor entfernt", substring = true).assertExists()
        shot("A5-gesperrt")
    }

    @Test
    fun `A6 another device's placeholder explains itself, its owner may remove it`() {
        var forget = false
        page { VaultPlaceholderInfo("Steuer 2025", other(), onForget = { forget = true }) }
        rule.onNodeWithText("Dieser Ordner liegt nur auf deinem Gerät „Pixel 8“", substring = true).assertExists()
        shot("A6-platzhalter")
        rule.onNodeWithText("Platzhalter entfernen …").performClick()
        assertEquals(true, forget)
        page { VaultPlaceholderInfo("Steuer 2025", other(DVaultLocal.STATE_MOVING), onForget = null) }
        rule.onNodeWithText("Platzhalter entfernen …").assertDoesNotExist()
        rule.onNodeWithText("verschoben", substring = true).assertExists()
    }

    @Test
    fun `A8 a folder kept here goes back from the same choice`() {
        var chosen: VaultWhere? = null
        page {
            Text("Steuer 2025", style = MaterialTheme.typography.titleMedium)
            VaultWhereChoice(VaultWhere.HERE, localOffered = true, online = true, fixedExtra = false, since = "27.11.2026") { chosen = it }
        }
        rule.onNodeWithText("Zurück auf den Server").assertExists()
        rule.onNodeWithText("Auf den Server und zusätzlich offline").assertExists()
        rule.onNodeWithText("Seit 27.11.2026").assertExists()
        shot("A8-zurueck")
        rule.onNodeWithText("Auf den Server und zusätzlich offline").performClick()
        assertEquals(VaultWhere.EXTRA, chosen)
        page { VaultLocalBackDialog("Steuer 2025", keep = true, onConfirm = {}, onDismiss = {}) }
        shot("A8-zurueck-dialog", dialog = true)
    }

    @Test
    fun `A5 the reminder of a backup and the export in the card`() {
        page {
            VaultLocalCard(folder(), undoUntil = null, onBack = {}, onUndo = null, extra = { VaultLocalExportButton {} })
            VaultLocalBackupBanner(folder(), onExport = null)
        }
        rule.onNodeWithText("Sicherung exportieren").assertExists()
        rule.onNodeWithText("Noch keine Sicherung.", substring = true).assertExists()
        shot("A5-sicherung")
        page { VaultLocalBackupBanner(folder().copy(lastExport = 1000), onExport = null) }
        rule.onNodeWithText("Zeit für eine neue Sicherung.", substring = true).assertExists()
    }

    @Test
    fun `A7 signing out asks first, twice before anything goes`() {
        var left = false
        var hinted = false
        page {
            VaultLocalLeaveDialog(
                listOf(folder().copy(lastExport = 1_764_000_000_000), folder().copy(nodeId = 11)),
                onFirstSave = { hinted = true }, onLeave = { left = true }, onDismiss = {},
            )
        }
        rule.onNodeWithText("2 Ordner liegen nur auf diesem Handy").assertExists()
        rule.onNodeWithText("noch nie gesichert", substring = true).assertExists()
        shot("A7-abmelden", dialog = true)
        rule.onNodeWithText("Erst sichern").performClick()
        assertEquals(true, hinted)
        rule.onNodeWithText("Trotzdem abmelden …").performClick()
        assertEquals(false, left)
        rule.onNodeWithText("Löschen und abmelden").performClick()
        assertEquals(true, left)
    }

    @Test
    fun `a device removed from the vault keeps its folders locked, export and delete stay`() {
        page { VaultLocalLockedFolders(listOf(folder()), onExport = {}, onDelete = {}) }
        rule.onNodeWithText("Sicherung exportieren").assertExists()
        rule.onNodeWithText("Löschen").assertExists()
        shot("gesperrt-entfernt")
    }

    // ─── the local trash (30 days) ──────────────────────────────────

    private val trashed = DriveVaultLocalFolder(
        "srv", vaultId, 10, 1, "mine:1", null, "dev", DriveVaultLocalFolder.State.LOCAL, since = 1000, changedAt = 2000,
        items = listOf(DriveVaultLocalItem(21, 10, false, "n", size = 1_200_000)),
        trash = listOf(
            DriveVaultLocalTrashEntry(31, 10, 1_764_300_000_000, listOf(DriveVaultLocalItem(31, 10, false, "n", size = 2_800_000))),
            DriveVaultLocalTrashEntry(32, 10, 1_764_200_000_000, listOf(DriveVaultLocalItem(32, 10, true, "d"), DriveVaultLocalItem(33, 32, false, "n", size = 400_000), DriveVaultLocalItem(34, 32, false, "n", size = 600_000))),
        ),
    )

    private fun trashRows(f: DriveVaultLocalFolder) = listOf(
        VaultLocalTrashRow(f.trash[0], node(31, false, 2_800_000).copy(keyVersion = DriveVaultCrypto.LOCAL_KEY_VERSION), "Reisepass.pdf", "Ausweise"),
        VaultLocalTrashRow(f.trash[1], node(32, true).copy(keyVersion = DriveVaultCrypto.LOCAL_KEY_VERSION), "Alte Scans", "Ausweise"),
    ).filter { r -> f.trash.any { it.id == r.entry.id } }

    @Test
    fun `deleting inside asks with the 30 days of the folder's trash`() {
        var ok = false
        page { VaultLocalTrashDialog("Reisepass.pdf", onConfirm = { ok = true }, onDismiss = {}) }
        rule.onNodeWithText("Kommt 30 Tage in den Papierkorb dieses Ordners", substring = true).assertExists()
        shot("T1-loeschen-rueckfrage", dialog = true)
        rule.onNodeWithText("In den Papierkorb").performClick()
        assertEquals(true, ok)
    }

    @Test
    fun `the card counts the trash and opens it`() {
        var opened = false
        page { VaultLocalCard(trashed, undoUntil = null, onBack = {}, onUndo = null, onTrash = { opened = true }, extra = { VaultLocalExportButton {} }) }
        rule.onNodeWithText("Papierkorb: 2 Elemente · ", substring = true).assertExists()
        shot("T2-karte-papierkorb")
        rule.onNodeWithText("Papierkorb: 2 Elemente · ", substring = true).performClick()
        assertEquals(true, opened)
        page { VaultLocalCard(folder(), undoUntil = null, onBack = {}, onUndo = null, onTrash = {}) }
        rule.onNodeWithText("Papierkorb", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the trash shows until when, restores, deletes for good and empties`() {
        val restored = ArrayList<Long>()
        val purged = ArrayList<Long>()
        var emptied = false
        page {
            VaultLocalTrashScreen(trashed, trashRows(trashed), canRestore = true, onBack = {}, onRestore = { restored += it }, onPurge = { purged += it }, onEmpty = { emptied = true })
        }
        rule.onNodeWithText("Reisepass.pdf").assertExists()
        assertEquals(2, rule.onAllNodesWithText("wird am", substring = true).fetchSemanticsNodes().size)
        assertEquals(2, rule.onAllNodesWithText("aus Ausweise", substring = true).fetchSemanticsNodes().size)
        rule.onNodeWithText("Die Sicherung enthält den Papierkorb nicht.", substring = true).assertExists()
        shot("T3-papierkorb")
        rule.onAllNodesWithText("Wiederherstellen")[0].performClick()
        assertEquals(listOf(31L), restored)
        rule.onAllNodesWithContentDescription("Endgültig löschen")[1].performClick()
        rule.onNodeWithText("„Alte Scans“ endgültig löschen?", substring = true).assertExists()
        shot("T4-endgueltig", dialog = true)
        rule.onNodeWithText("Endgültig löschen").performClick()
        assertEquals(listOf(32L), purged)
        rule.onNodeWithText("Papierkorb leeren").performClick()
        rule.onNodeWithText("werden endgültig gelöscht", substring = true).assertExists()
        shot("T5-leeren", dialog = true)
        rule.onAllNodesWithText("Papierkorb leeren")[1].performClick()
        assertEquals(true, emptied)
        page { VaultLocalTrashScreen(trashed.copy(trash = emptyList()), emptyList(), canRestore = true, onBack = {}, onRestore = {}, onPurge = {}, onEmpty = {}) }
        rule.onNodeWithText("Der Papierkorb ist leer.").assertExists()
        rule.onNodeWithText("Papierkorb leeren").assertDoesNotExist()
        page { VaultLocalTrashScreen(trashed, trashRows(trashed), canRestore = false, onBack = {}, onRestore = {}, onPurge = {}, onEmpty = {}) }
        rule.onNodeWithText("Wiederherstellen").assertDoesNotExist()
    }

    @Test
    fun `the way back says the trash stays behind`() {
        page { VaultLocalBackDialog("Ausweise", keep = true, onConfirm = {}, onDismiss = {}, trash = 2) }
        rule.onNodeWithText("2 Elemente im Papierkorb dieses Ordners kommen nicht mit auf den Server", substring = true).assertExists()
        shot("T6-zurueck-papierkorb", dialog = true)
    }
}
