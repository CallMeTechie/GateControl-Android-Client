package com.gatecontrol.android.familie.push

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.gatecontrol.android.MainActivity
import com.gatecontrol.android.familie.FamilieDest
import com.gatecontrol.android.push.PushNavTarget
import com.gatecontrol.android.push.PushNavigation

/**
 * Invisible, NON-exported activity behind the app's own Familie
 * notifications (place reminders): hands the screen to the navigation and
 * brings up MainActivity, which itself acts on no intent extras.
 */
class FamilieOpenActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val dest = FamilieDest.parse(intent.getStringExtra(EXTRA_DEST)) ?: FamilieDest.Home
            PushNavigation.request(PushNavTarget.Familie(dest.key))
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }
        finish()
    }

    companion object {
        private const val EXTRA_DEST = "com.gatecontrol.android.familie.extra.DEST"

        fun intent(context: Context, dest: FamilieDest): Intent =
            Intent(context, FamilieOpenActivity::class.java)
                .putExtra(EXTRA_DEST, dest.key)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
    }
}
