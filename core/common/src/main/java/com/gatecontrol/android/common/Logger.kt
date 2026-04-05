package com.gatecontrol.android.common

import timber.log.Timber

object Logger {
    fun init(debug: Boolean) {
        if (debug) {
            Timber.plant(Timber.DebugTree())
        }
    }
}
