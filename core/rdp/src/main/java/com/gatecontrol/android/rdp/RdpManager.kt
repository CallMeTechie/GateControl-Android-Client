package com.gatecontrol.android.rdp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.RdpEndSessionRequest
import com.gatecontrol.android.network.RdpHeartbeatRequest
import com.gatecontrol.android.network.RdpRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

class RdpManager(
    private val context: Context,
    private val credentialHandler: RdpCredentialHandler,
    private val externalClient: RdpExternalClient,
    private val embeddedClient: RdpEmbeddedClient,
    private val monitor: RdpMonitor,
    private val wolClient: WolClient
) {

    sealed class ConnectResult {
        data class Success(val session: RdpSession, val passwordCopied: Boolean = false) : ConnectResult()
        data class Error(val message: String, val step: RdpProgress) : ConnectResult()
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
     * 4. Fetch credentials via the API (ECDH exchange).
     *    - credential_mode "full"      → decrypt username + password.
     *    - credential_mode "user_only" → decrypt username; return [ConnectResult.NeedsPassword] if no userPassword supplied.
     *    - credential_mode "none"      → no credentials.
     * 5. Launch the RDP client (external preferred, intent fired via Context).
     * 6. Start the server-side session.
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

        // Step 1: VPN check
        onProgress(RdpProgress.VPN_CHECK)
        if (!isVpnConnected) {
            return ConnectResult.VpnRequired("VPN connection required to reach RDP host")
        }

        // Step 2: Server-side TCP reachability check
        // Android VPN apps cannot connect to VPN addresses from their own process
        // (VpnService excludes the app to prevent routing loops), so the server
        // performs the TCP check on behalf of the client.
        onProgress(RdpProgress.TCP_CHECK)
        val host = route.host
        val port = route.port
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

        // Step 4: Fetch credentials
        onProgress(RdpProgress.CREDENTIALS)
        val credentialMode = when (route.credentialMode.lowercase()) {
            "full" -> CredentialMode.FULL
            "user_only" -> CredentialMode.USER_ONLY
            else -> CredentialMode.NONE
        }
        Timber.i("RDP connect: credentialMode=${route.credentialMode} -> $credentialMode")

        var resolvedUsername: String? = null
        var resolvedPassword: String? = null
        var resolvedDomain: String? = route.domain

        if (credentialMode != CredentialMode.NONE) {
            val publicKey = credentialHandler.generatePublicKey()
            Timber.i("RDP connect: ECDH publicKey generated (${publicKey.length} chars)")
            val connectionResponse = try {
                apiClient.getRdpConnection(route.id, ecdhPublicKey = publicKey)
            } catch (e: Exception) {
                Timber.e(e, "RDP connect: credential fetch FAILED")
                credentialHandler.clear()
                return ConnectResult.Error(
                    "Failed to fetch credentials: ${e.message}",
                    RdpProgress.CREDENTIALS
                )
            }

            val connection = connectionResponse.connection
            val e2eePayload = connection.credentialsE2ee
            Timber.i("RDP connect: e2eePayload=${if (e2eePayload != null) "present (data=${e2eePayload.data.length} chars)" else "NULL"}")
            Timber.i("RDP connect: connection host=${connection.host}, port=${connection.port}, credentialMode=${connection.credentialMode}")

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
                    Timber.i("RDP connect: decrypted username=${if (resolvedUsername.isNullOrEmpty()) "EMPTY" else "${resolvedUsername!!.length} chars"}, password=${if (resolvedPassword.isNullOrEmpty()) "EMPTY" else "${resolvedPassword!!.length} chars"}, domain=$resolvedDomain")
                } else {
                    Timber.w("RDP connect: credentialMode=FULL but e2eePayload is NULL — no credentials!")
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

        // Step 5: Launch client (prefer embedded FreeRDP, fall back to external)
        onProgress(RdpProgress.CLIENT_LAUNCH)
        val useEmbedded = embeddedClient.isAvailable()
        val isExternal = !useEmbedded

        Timber.i("RDP connect: launching client — embedded=$useEmbedded, username=${if (resolvedUsername.isNullOrEmpty()) "EMPTY" else "SET"}, password=${if (resolvedPassword.isNullOrEmpty()) "EMPTY" else "SET"}")

        if (useEmbedded) {
            var params = RdpConnectionParams.fromRoute(
                route = route,
                username = resolvedUsername,
                password = resolvedPassword,
                domain = resolvedDomain
            )
            // If route has no explicit resolution, use device screen size
            // capped to 1920x1080 to avoid pushing multi-MB frames over VPN.
            if (params.resolutionWidth <= 0 || params.resolutionHeight <= 0) {
                val dm = context.resources.displayMetrics
                val maxW = 1920
                val maxH = 1080
                val w = dm.widthPixels.coerceAtMost(maxW)
                val h = dm.heightPixels.coerceAtMost(maxH)
                params = params.copy(resolutionWidth = w, resolutionHeight = h)
                Timber.i("RDP connect: device=${dm.widthPixels}x${dm.heightPixels}, capped=${w}x${h}")
            }
            try {
                embeddedClient.launchSession(context, params)
            } catch (e: Exception) {
                credentialHandler.clear()
                return ConnectResult.Error(
                    "Failed to launch embedded RDP client: ${e.message}",
                    RdpProgress.CLIENT_LAUNCH
                )
            }
        } else {
            // Copy password to clipboard so the user can paste it in the RDP app.
            // The rdp:// URI scheme and .rdp files don't support password passing.
            if (!resolvedPassword.isNullOrEmpty()) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("rdp-password", resolvedPassword))
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
                return ConnectResult.Error(
                    "No RDP client could be launched: ${e.message}",
                    RdpProgress.CLIENT_LAUNCH
                )
            }
        }

        // Step 6: Start server-side session
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
                        endReason = "user_disconnect"
                    )
                )
            } catch (_: Exception) {
                // Best-effort; local state is already cleaned up
            }
        }
    }

}
