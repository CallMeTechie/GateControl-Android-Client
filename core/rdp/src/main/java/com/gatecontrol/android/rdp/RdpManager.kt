package com.gatecontrol.android.rdp

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.RdpEndSessionRequest
import com.gatecontrol.android.network.RdpHeartbeatRequest
import com.gatecontrol.android.network.RdpRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import retrofit2.HttpException
import timber.log.Timber

class RdpManager(
    private val context: Context,
    private val credentialHandler: RdpCredentialHandler,
    private val externalClient: RdpExternalClient,
    private val embeddedClient: RdpEmbeddedClient,
    private val monitor: RdpMonitor,
    private val wolClient: WolClient
) {

    companion object {
        const val ACCESS_MODE_GATEWAY = "gateway"

        /** End reasons of [endServerSession] (the server keeps `[a-z0-9_-]`). */
        const val END_USER_DISCONNECT = "user_disconnect"
        const val END_USER_CANCEL = "user_cancel"
        const val END_CONNECT_FAILED = "connect_failed"
        const val END_CONNECT_TIMEOUT = "connect_timeout"
        const val END_LAUNCH_FAILED = "launch_failed"
        private const val CLIPBOARD_LABEL = "rdp-password"
        private const val CLIPBOARD_CLEAR_MS = 60_000L

        /** Gateway routes reach the public server endpoint and need no VPN tunnel. */
        fun requiresVpn(accessMode: String?): Boolean =
            accessMode?.lowercase() != ACCESS_MODE_GATEWAY
    }

    sealed class ConnectResult {
        data class Success(val session: RdpSession, val passwordCopied: Boolean = false) : ConnectResult()
        /** [failure]: a refusal the app explains in its own words (null: show [message]). */
        data class Error(
            val message: String,
            val step: RdpProgress,
            val failure: RdpConnectFailure? = null
        ) : ConnectResult()
        data class NeedsPassword(val username: String, val domain: String?) : ConnectResult()
        data class MaintenanceWarning(val schedule: String?) : ConnectResult()
        data class VpnRequired(val message: String) : ConnectResult()
    }

    /**
     * Connect to an RDP route.
     *
     * Steps:
     * 1. VPN check — return [ConnectResult.VpnRequired] if not connected.
     * 2. TCP reachability check — 5-second Socket connect.
     *    If host is unreachable and WoL is enabled, send WoL then poll until online.
     * 3. Maintenance check — return [ConnectResult.MaintenanceWarning] if enabled and bypass not forced.
     * 4. Ask the server for the connection (GET /rdp/:id/connect) — always, also without credentials:
     *    for a PC registered with Fernzugriff this call opens its Windows firewall for this app and
     *    waits for the PC; without it port 3389 stays closed. A refusal the person can act on
     *    (firewall_timeout, firewall_failed, code_required, …) returns [ConnectResult.Error] with
     *    its [RdpConnectFailure].
     *    - credential_mode "full"      → decrypt username + password (ECDH exchange).
     *    - credential_mode "user_only" → decrypt username; return [ConnectResult.NeedsPassword] if no userPassword supplied.
     *    - credential_mode "none"      → no credentials.
     * 5. Start the server-side session (its id goes to the session window, which reports the end).
     * 6. Launch the RDP client (embedded preferred, external as fallback). If that fails, the
     *    server session ends at once — for a registered PC that closes the firewall rule again.
     * 7. Register the session in the monitor and return [ConnectResult.Success].
     */
    suspend fun connect(
        route: RdpRoute,
        apiClient: ApiClient,
        isVpnConnected: Boolean,
        userPassword: String? = null,
        forceMaintenanceBypass: Boolean = false,
        onProgress: (RdpProgress) -> Unit = {}
    ): ConnectResult {

        // Step 1: VPN check — gateway routes reach the public server endpoint
        // (connect_address) and do not need the tunnel.
        onProgress(RdpProgress.VPN_CHECK)
        if (requiresVpn(route.accessMode) && !isVpnConnected) {
            return ConnectResult.VpnRequired("VPN connection required to reach RDP host")
        }

        // Step 2: Server-side TCP reachability check
        // Android VPN apps cannot connect to VPN addresses from their own process
        // (VpnService excludes the app to prevent routing loops), so the server
        // performs the TCP check on behalf of the client.
        onProgress(RdpProgress.TCP_CHECK)
        // Effective connect endpoint. For gateway routes this is the public
        // connect_address (server:listen_port), not the LAN host. Keeps the
        // external-launch path, the session monitor, and error messages aligned
        // with the embedded path (which resolves the same via fromRoute).
        val host = route.connectAddress ?: route.host
        val port = route.connectPort ?: route.port
        val reachable = try {
            val statusResponse = apiClient.getRdpRouteStatus(route.id)
            statusResponse.ok && statusResponse.status?.online == true
        } catch (_: Exception) {
            false
        }

        if (!reachable) {
            val wolEnabled = route.wolEnabled == true
            if (wolEnabled) {
                val wolSent = wolClient.sendWol(apiClient, route.id)
                if (!wolSent) {
                    return ConnectResult.Error(
                        "Wake-on-LAN failed for route ${route.name}",
                        RdpProgress.TCP_CHECK
                    )
                }
                val cameOnline = wolClient.pollUntilOnline(apiClient, route.id)
                if (!cameOnline) {
                    return ConnectResult.Error(
                        "Host did not come online after Wake-on-LAN",
                        RdpProgress.TCP_CHECK
                    )
                }
            } else {
                return ConnectResult.Error(
                    "Cannot reach host $host:$port and Wake-on-LAN is not enabled",
                    RdpProgress.TCP_CHECK
                )
            }
        }

        // Step 3: Maintenance check
        if (route.maintenanceEnabled == true && !forceMaintenanceBypass) {
            return ConnectResult.MaintenanceWarning(schedule = null)
        }

        // Step 4: Connection data (and the PC's access), then credentials
        onProgress(RdpProgress.HOST_ACCESS)
        val credentialMode = when (route.credentialMode.lowercase()) {
            "full" -> CredentialMode.FULL
            "user_only" -> CredentialMode.USER_ONLY
            else -> CredentialMode.NONE
        }
        val authModeRaw = route.credentialMode
        val authModeResolved = credentialMode
        Timber.i("RDP connect: authMode=$authModeRaw -> $authModeResolved")

        var resolvedUsername: String? = null
        var resolvedPassword: String? = null
        var resolvedDomain: String? = route.domain

        // Always ask for the connection: for a registered PC this opens its firewall (and waits for it).
        val publicKey = if (credentialMode != CredentialMode.NONE) {
            credentialHandler.generatePublicKey().also {
                Timber.i("RDP connect: ECDH publicKey generated (${it.length} chars)")
            }
        } else {
            null
        }
        val connectionResponse = try {
            apiClient.getRdpConnection(route.id, ecdhPublicKey = publicKey)
        } catch (e: HttpException) {
            val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
            val failure = RdpConnectFailure.from(e.code(), body)
            Timber.w("RDP connect: connect refused HTTP %d (%s)", e.code(), failure ?: "unknown")
            credentialHandler.clear()
            return ConnectResult.Error(
                "Server refused the connection: HTTP ${e.code()}${RdpConnectFailure.errorOf(body)?.let { " ($it)" } ?: ""}",
                RdpProgress.HOST_ACCESS,
                failure
            )
        } catch (e: Exception) {
            Timber.e(e, "RDP connect: connection data fetch FAILED")
            credentialHandler.clear()
            return ConnectResult.Error(
                "Failed to fetch connection data: ${e.message}",
                RdpProgress.HOST_ACCESS
            )
        }

        if (credentialMode != CredentialMode.NONE) {
            onProgress(RdpProgress.CREDENTIALS)
            val connection = connectionResponse.connection
            val e2eePayload = connection.credentialsE2ee
            Timber.i("RDP connect: e2eePayload=${if (e2eePayload != null) "present (data=${e2eePayload.data.length} chars)" else "NULL"}")
            val connAuthMode = connection.credentialMode
            Timber.i("RDP connect: connection host=${connection.host}, port=${connection.port}, authMode=$connAuthMode")

            if (credentialMode == CredentialMode.FULL) {
                if (e2eePayload != null) {
                    val creds = try {
                        credentialHandler.decryptCredentials(e2eePayload)
                    } catch (e: Exception) {
                        Timber.e(e, "RDP connect: E2EE decrypt FAILED")
                        credentialHandler.clear()
                        return ConnectResult.Error(
                            "Failed to decrypt credentials: ${e.message}",
                            RdpProgress.CREDENTIALS
                        )
                    }
                    resolvedUsername = creds.username
                    resolvedPassword = creds.password
                    resolvedDomain = creds.domain ?: route.domain
                    // Presence only — never credential values or lengths.
                    val pwInfo = if (resolvedPassword.isNullOrEmpty()) "EMPTY" else "SET"
                    Timber.i("RDP connect: decrypted username=${if (resolvedUsername.isNullOrEmpty()) "EMPTY" else "SET"}, pw=$pwInfo, domain=${if (resolvedDomain.isNullOrEmpty()) "EMPTY" else "SET"}")
                } else {
                    Timber.w("RDP connect: authMode=FULL but e2eePayload is NULL — no auth data!")
                }
            } else if (credentialMode == CredentialMode.USER_ONLY) {
                if (e2eePayload != null) {
                    val creds = try {
                        credentialHandler.decryptCredentials(e2eePayload)
                    } catch (e: Exception) {
                        credentialHandler.clear()
                        return ConnectResult.Error(
                            "Failed to decrypt credentials: ${e.message}",
                            RdpProgress.CREDENTIALS
                        )
                    }
                    resolvedUsername = creds.username
                    resolvedDomain = creds.domain ?: route.domain
                }

                if (userPassword == null) {
                    return ConnectResult.NeedsPassword(
                        username = resolvedUsername ?: "",
                        domain = resolvedDomain
                    )
                }
                resolvedPassword = userPassword
            }
        }

        // Step 5: Start the server-side session before the client, so the window knows its id
        onProgress(RdpProgress.SESSION_START)
        val sessionResponse = try {
            apiClient.startRdpSession(route.id)
        } catch (e: Exception) {
            credentialHandler.clear()
            return ConnectResult.Error(
                "Failed to start server session: ${e.message}",
                RdpProgress.SESSION_START
            )
        }

        val networkSessionId = sessionResponse.session?.id
            ?: run {
                credentialHandler.clear()
                return ConnectResult.Error(
                    "Server returned no session ID",
                    RdpProgress.SESSION_START
                )
            }

        // Step 6: Launch client (prefer embedded FreeRDP, fall back to external)
        onProgress(RdpProgress.CLIENT_LAUNCH)
        val useEmbedded = embeddedClient.isAvailable()
        val isExternal = !useEmbedded

        val pwSet = if (resolvedPassword.isNullOrEmpty()) "EMPTY" else "SET"
        Timber.i("RDP connect: launching client — embedded=$useEmbedded, username=${if (resolvedUsername.isNullOrEmpty()) "EMPTY" else "SET"}, pw=$pwSet")

        if (useEmbedded) {
            var params = RdpConnectionParams.fromRoute(
                route = route,
                username = resolvedUsername,
                password = resolvedPassword,
                domain = resolvedDomain
            ).copy(serverSessionId = networkSessionId)
            // If route has no explicit resolution, use device screen size
            // scaled down proportionally so the longest edge is max 1920px.
            // This preserves the device's aspect ratio (critical for foldables
            // like Pixel Fold where inner=6:5 and outer=19:9) while keeping
            // the framebuffer small enough for smooth VPN performance.
            if (params.resolutionWidth <= 0 || params.resolutionHeight <= 0) {
                val dm = context.resources.displayMetrics
                val devW = dm.widthPixels
                val devH = dm.heightPixels
                val maxEdge = 1920
                val longestEdge = maxOf(devW, devH)
                val (w, h) = if (longestEdge > maxEdge) {
                    val scale = maxEdge.toFloat() / longestEdge
                    (devW * scale).toInt() to (devH * scale).toInt()
                } else {
                    devW to devH
                }
                params = params.copy(resolutionWidth = w, resolutionHeight = h)
                Timber.i("RDP connect: device=${devW}x${devH}, scaled=${w}x${h}")
            }
            try {
                embeddedClient.launchSession(context, params)
            } catch (e: Exception) {
                credentialHandler.clear()
                endServerSession(apiClient, route.id, networkSessionId, END_LAUNCH_FAILED)
                return ConnectResult.Error(
                    "Failed to launch embedded RDP client: ${e.message}",
                    RdpProgress.CLIENT_LAUNCH
                )
            }
        } else {
            // Copy password to clipboard so the user can paste it in the RDP app.
            // The rdp:// URI scheme and .rdp files don't support password passing.
            if (!resolvedPassword.isNullOrEmpty()) {
                copyPasswordToClipboard(context, resolvedPassword)
            }

            val intent = externalClient.launchIntent(
                host = host,
                port = port,
                username = resolvedUsername,
                domain = resolvedDomain
            )
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                credentialHandler.clear()
                endServerSession(apiClient, route.id, networkSessionId, END_LAUNCH_FAILED)
                return ConnectResult.Error(
                    "No RDP client could be launched: ${e.message}",
                    RdpProgress.CLIENT_LAUNCH
                )
            }
        }

        // Step 7: Register locally and return success
        onProgress(RdpProgress.COMPLETE)
        val localSession = monitor.startSession(
            routeId = route.id,
            sessionId = networkSessionId,
            host = host,
            isExternal = isExternal
        )

        credentialHandler.clear()
        return ConnectResult.Success(localSession, passwordCopied = !resolvedPassword.isNullOrEmpty() && isExternal)
    }

    /**
     * Report the end of a session to the server now (DELETE /client/rdp/:id/session) and forget it
     * locally. For a PC registered with Fernzugriff this is what closes its firewall rule for this app
     * (plugin `sessionEnd` → `appSessionEnded`), so the session window calls it as soon as a session
     * ends — also when it failed, timed out or was cancelled before the first picture.
     * [sessionId] null or ≤ 0: the server ends this caller's latest active session of the route.
     * Best effort: false when the server could not be told.
     */
    suspend fun endServerSession(apiClient: ApiClient, routeId: Int, sessionId: Int?, reason: String): Boolean {
        monitor.endSession(routeId)
        return try {
            apiClient.endRdpSession(
                routeId,
                RdpEndSessionRequest(sessionId = sessionId?.takeIf { it > 0 }, endReason = reason)
            )
            true
        } catch (e: Exception) {
            Timber.w(e, "RDP: could not report the session end for route %d", routeId)
            false
        }
    }

    /**
     * Disconnect from a route: end the server-side session, clean up credentials, remove from monitor.
     */
    fun disconnect(
        routeId: Int,
        apiClient: ApiClient,
        scope: CoroutineScope
    ) {
        val session = monitor.endSession(routeId) ?: return
        credentialHandler.clear()

        scope.launch {
            try {
                apiClient.endRdpSession(
                    routeId,
                    RdpEndSessionRequest(
                        sessionId = session.sessionId,
                        endReason = END_USER_DISCONNECT
                    )
                )
            } catch (_: Exception) {
                // Best-effort; local state is already cleaned up
            }
        }
    }


    /**
     * Put the password on the clipboard for pasting into an external RDP app.
     * Marked sensitive (hidden from the clipboard preview and keyboard
     * suggestions) and cleared again after [CLIPBOARD_CLEAR_MS] unless the
     * user copied something else meanwhile.
     */
    private fun copyPasswordToClipboard(context: Context, password: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(CLIPBOARD_LABEL, password).apply {
            description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clipboard.setPrimaryClip(clip)
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                if (clipboard.primaryClipDescription?.label == CLIPBOARD_LABEL) {
                    clipboard.clearPrimaryClip()
                }
            }
        }, CLIPBOARD_CLEAR_MS)
    }
}
