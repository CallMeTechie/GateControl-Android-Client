package com.gatecontrol.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.gatecontrol.android.mail.MailMicrosoft
import com.gatecontrol.android.mail.MailSigninRedirects

/**
 * Receives `msauth://com.gatecontrol.client/<hash>?code=…`, the browser's answer to "Mit Microsoft anmelden" of a
 * mail account, and hands it to the running Mail screen ([MailSigninRedirects]), bringing up [MainActivity] again
 * (which closes the Custom Tab above it). Invisible, never shown in Recents.
 *
 * Exported because the browser opens it, and enabled only while the server offers Mail (MailStartup). The link
 * carries a one-time code that is useless without the PKCE verifier, and the Mail screen drops any answer whose
 * state it did not create.
 */
class MailSigninActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raw = intent?.dataString
        if (raw != null && MailMicrosoft.isRedirect(raw)) {
            MailSigninRedirects.offer(raw)
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
        finish()
    }
}
