package com.gatecontrol.android.ui.drive

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.gatecontrol.android.R
import com.gatecontrol.android.drive.DMe
import com.gatecontrol.android.drive.DriveApi
import com.gatecontrol.android.drive.DriveLive
import com.gatecontrol.android.drive.DriveOps
import com.gatecontrol.android.drive.DriveTransfers
import com.gatecontrol.android.drive.vault.DriveVaultMove
import com.gatecontrol.android.ui.components.GcShowOnce
import com.gatecontrol.android.ui.theme.GateControlTheme
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The snackbar after a Drive bulk job ("3 Elemente nach „Rechnungen“ verschoben") stays for its whole duration: it was
 * shown in the effect keyed by the snack, which taking the snack restarted — the snackbar went after one frame. Also
 * the vault's „In Tresor verschieben“: the label, the hint before and the snackbar after (in German).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "de")
class DriveBulkSnackbarUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun bulk(): DriveBulkActions {
        val live = mockk<DriveLive>(relaxed = true).also {
            every { it.me } returns MutableStateFlow<DMe?>(null)
            every { it.ops } returns MutableSharedFlow()
        }
        return DriveBulkActions(
            context = ctx,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            api = mockk<DriveApi>(relaxed = true),
            ops = mockk<DriveOps>(relaxed = true),
            transfers = mockk<DriveTransfers>(relaxed = true),
            live = live,
            items = { emptyList() },
        )
    }

    @Test
    fun `the bulk snackbar stays visible for its duration, then goes`() {
        val bulk = bulk()
        rule.mainClock.autoAdvance = false
        rule.setContent {
            GateControlTheme {
                val host = remember { SnackbarHostState() }
                Box(Modifier.fillMaxSize()) {
                    DriveBulkHost(bulk, host)
                    SnackbarHost(host, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
        rule.mainClock.advanceTimeByFrame()
        bulk.postSnack(DriveBulkActions.Snack("move", 3, "Rechnungen"))
        val text = "3 Elemente nach „Rechnungen“ verschoben"
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText(text).assertIsDisplayed()
        // taken at once (shown once only), still there seconds later (a short snackbar: 4 s)
        assertNull(bulk.snack.value)
        rule.mainClock.advanceTimeBy(2_500)
        rule.onNodeWithText(text).assertIsDisplayed()
        rule.mainClock.advanceTimeBy(3_000)
        rule.onAllNodesWithText(text).assertCountEquals(0)
    }

    @Test
    fun `the vault's snackbar after a move stays and says what became of the originals`() {
        val moved = MutableStateFlow<DriveVaultMove.Outcome?>(null)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            GateControlTheme {
                val host = remember { SnackbarHostState() }
                val o = moved.collectAsState().value
                Box(Modifier.fillMaxSize()) {
                    GcShowOnce(o, consume = { moved.value = null }) { host.showSnackbar(vaultMoveText(ctx, it)) }
                    SnackbarHost(host, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
        rule.mainClock.advanceTimeByFrame()
        moved.value = DriveVaultMove.Outcome(moved = 2, deleted = 2)
        rule.mainClock.advanceTimeBy(500)
        val text = "2 Dateien in den Tresor verschoben – die Originale wurden gelöscht."
        rule.onNodeWithText(text).assertIsDisplayed()
        rule.mainClock.advanceTimeBy(3_000)
        rule.onNodeWithText(text).assertIsDisplayed()
    }

    @Test
    fun `labels and texts of the move into a vault (German)`() {
        assertEquals("In Tresor verschieben", ctx.getString(R.string.drive_vault_move_in))
        assertEquals("Wird verschlüsselt hochgeladen, danach wird das Original gelöscht", ctx.getString(R.string.drive_vault_move_in_desc))
        assertEquals("1 Datei in den Tresor verschoben – das Original wurde gelöscht.", vaultMoveText(ctx, DriveVaultMove.Outcome(moved = 1, deleted = 1)))
        assertEquals(
            "Hochgeladen. Das Original konnte nicht gelöscht werden – bitte selbst löschen.",
            vaultMoveText(ctx, DriveVaultMove.Outcome(moved = 1, kept = 1)),
        )
        assertEquals(
            "3 Dateien in den Tresor hochgeladen. 1 Original wurde gelöscht. Das Original konnte nicht gelöscht werden – bitte selbst löschen. " +
                "Das Original wurde nicht gelöscht, weil du abgelehnt hast – bitte selbst löschen.",
            vaultMoveText(ctx, DriveVaultMove.Outcome(moved = 3, deleted = 1, kept = 1, declined = 1)),
        )
        assertEquals(
            "Hochgeladen. Das Original liegt noch im Drive-Papierkorb – bitte dort endgültig löschen.",
            vaultMoveText(ctx, DriveVaultMove.Outcome(moved = 1, inTrash = 1)),
        )
        assertEquals(
            "Die Datei ist nicht vollständig im Tresor angekommen. Das Original bleibt erhalten – bitte noch einmal versuchen.",
            vaultViewText(ctx, "verify"),
        )
    }
}
