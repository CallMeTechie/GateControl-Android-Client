package com.gatecontrol.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.gatecontrol.android.common.SigninRedirect

/**
 * Receives `gatecontrol://signin?…`, the browser's answer to "Mit Microsoft
 * anmelden", and hands it to the running [MainActivity] (closing the Custom
 * Tab above it), so the setup screen that started the sign-in — and holds
 * its PKCE verifier and state — takes it. Invisible, never shown in Recents.
 *
 * Exported because the browser opens it; the link only carries a one-time
 * handoff that is useless without the verifier, and the setup screen
 * rejects any link whose state it did not create.
 */
class SigninRedirectActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        if (data != null && SigninRedirect.isSigninLink(data.toString())) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setData(data)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    ),
            )
        }
        finish()
    }
}
