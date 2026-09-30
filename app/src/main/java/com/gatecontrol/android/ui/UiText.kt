package com.gatecontrol.android.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * A user-facing message produced in a ViewModel and resolved in the UI.
 *
 * The app switches its language on the Activity's resources only, so a
 * ViewModel resolving strings via the application context would show them
 * in the system language. Carrying the resource id and resolving it with
 * [stringResource] keeps every message in the language chosen in the app.
 */
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText {
        constructor(@StringRes id: Int, vararg args: Any) : this(id, args.toList())
    }

    @Composable
    fun asString(): String = when (this) {
        is Res -> stringResource(id, *args.toTypedArray())
    }
}
