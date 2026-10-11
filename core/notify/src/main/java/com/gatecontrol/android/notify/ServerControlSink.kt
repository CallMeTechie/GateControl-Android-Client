package com.gatecontrol.android.notify

import com.gatecontrol.android.notify.model.ServerControl
import com.gatecontrol.android.notify.model.ServerState

/**
 * Receives what the server pushes about this device itself (policy,
 * support bundle request, peer switched off, new config) — the things the
 * VPN heartbeat used to poll for. Implemented by the app, which runs the
 * same code paths the heartbeat answer drives.
 *
 * Both calls come from the stream's coroutine: they must return quickly and
 * do their work asynchronously.
 */
interface ServerControlSink {
    /** `hello.state` after every (re)connect: reconcile with what is known locally. */
    fun onServerState(state: ServerState)

    /** `event: control`: act on it right away. */
    fun onControl(control: ServerControl)
}
