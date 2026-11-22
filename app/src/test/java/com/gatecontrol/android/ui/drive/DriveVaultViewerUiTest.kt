package com.gatecontrol.android.ui.drive

import android.app.Application
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.navigation.GcContentWidth
import com.gatecontrol.android.ui.theme.GateControlTheme
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The vault's viewer on an unfolded phone (Pixel Fold class: wider than the pages' 720 dp): a window of its own over
 * the whole screen — not inside the vault page's width with the page's background around it — and kept out of
 * screenshots by itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w841dp-h701dp")
class DriveVaultViewerUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val node = DNode(7, "mine:7", 1, "Film.mp4", false, 10, "video/mp4", null, 1, null, null, null, vaultId = "v1")

    @Test
    fun `the viewer fills the whole screen of an unfolded phone, in a secure window of its own`() {
        rule.setContent {
            GateControlTheme {
                // as the vault page is shown (AppNavigation): within the pages' width
                GcContentWidth {
                    DriveVaultViewerScreen(
                        VaultViewer.Text(node, "Notiz.txt", "Hallo", markdown = false),
                        onClose = {}, onUse = {}, onLock = {}, onOpenWith = {}, videoPlayer = { null },
                    )
                }
            }
        }
        rule.waitForIdle()
        val viewer = rule.onNode(hasTestTag(VAULT_VIEWER_TAG) and hasAnyAncestor(isDialog()))
        viewer.assertWidthIsEqualTo(841.dp)
        viewer.assertHeightIsEqualTo(701.dp)
        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull(dialog)
        val flags = dialog.window!!.attributes.flags
        assertTrue("FLAG_SECURE on the viewer's window", flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }
}
