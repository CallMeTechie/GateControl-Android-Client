package com.gatecontrol.android.ui

import androidx.annotation.StringRes
import com.gatecontrol.android.R
import com.gatecontrol.android.network.MachineBindingError

/** User-facing explanation of a machine-binding (Gerätebindung) rejection. */
@get:StringRes
val MachineBindingError.messageRes: Int
    get() = when (this) {
        MachineBindingError.MISMATCH -> R.string.binding_mismatch
        MachineBindingError.REQUIRED -> R.string.binding_required
        MachineBindingError.INVALID -> R.string.binding_invalid
    }

fun MachineBindingError.toUiText(): UiText = UiText.Res(messageRes)
