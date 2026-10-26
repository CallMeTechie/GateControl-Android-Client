package com.gatecontrol.android.ui.setup

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.theme.GateControlTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber

/**
 * `gatecontrol://signin?…` links handed over by [com.gatecontrol.android.MainActivity]
 * (via [com.gatecontrol.android.SigninRedirectActivity]) to the setup screen
 * that started "Mit Microsoft anmelden". Holds the raw link only until that
 * screen took it.
 */
object SigninRedirects {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    fun offer(raw: String) {
        _pending.value = raw
    }

    fun consume(raw: String) {
        _pending.update { if (it == raw) null else it }
    }
}

/**
 * Opens the sign-in page in a Custom Tab (system browser as fallback).
 * False when no browser can show it.
 */
internal fun openSigninBrowser(context: Context, url: String): Boolean {
    val uri = Uri.parse(url)
    return try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri)
        true
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            Timber.w("No browser for the company sign-in: %s", e.javaClass.simpleName)
            false
        }
    }
}

/** „Mit Firmenkonto anmelden“: Microsoft buttons and the password form of the typed server. */
@Composable
internal fun CompanySigninSection(
    signin: CompanySigninUi,
    isLoading: Boolean,
    onMicrosoft: (Int) -> Unit,
    onUsernameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onTotpChanged: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val extra = GateControlTheme.extraColors
    val focusManager = LocalFocusManager.current
    var passwordVisible by remember { mutableStateOf(false) }
    val enabled = !isLoading && !signin.busy

    GcCard(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = stringResource(R.string.signin_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.signin_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = extra.muted,
        )

        signin.microsoft.forEach { entry ->
            GcOutlineButton(
                text = if (signin.microsoft.size == 1 || entry.label.isBlank()) {
                    stringResource(R.string.signin_microsoft)
                } else {
                    stringResource(R.string.signin_microsoft_label, entry.label)
                },
                onClick = { onMicrosoft(entry.id) },
                enabled = enabled,
                icon = GcIcons.User,
            )
        }

        if (signin.passwordEnabled) {
            GcTextField(
                value = signin.username,
                onValueChange = onUsernameChanged,
                label = stringResource(R.string.signin_username),
                enabled = enabled && !signin.totpRequired,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                    autoCorrectEnabled = false,
                ),
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
            )
            GcTextField(
                value = signin.password,
                onValueChange = onPasswordChanged,
                label = stringResource(R.string.signin_password),
                hint = signin.hint,
                enabled = enabled && !signin.totpRequired,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = stringResource(if (passwordVisible) R.string.common_hide else R.string.common_show),
                            tint = extra.muted,
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = if (signin.totpRequired) ImeAction.Next else ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) },
                    onDone = {
                        focusManager.clearFocus()
                        onSubmit()
                    },
                ),
            )
            if (signin.totpRequired) {
                GcTextField(
                    value = signin.totp,
                    onValueChange = onTotpChanged,
                    label = stringResource(R.string.signin_totp),
                    hint = stringResource(R.string.signin_totp_hint),
                    mono = true,
                    enabled = enabled,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            onSubmit()
                        },
                    ),
                )
            }
            GcPrimaryButton(
                text = stringResource(R.string.signin_submit),
                onClick = onSubmit,
                enabled = enabled,
                loading = signin.busy,
            )
        }
    }
}
