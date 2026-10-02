package com.gatecontrol.android.service

import com.gatecontrol.android.common.ClientPolicy
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow

/** A [ClientPolicyManager] double that always reports [policy]. */
fun fakeClientPolicyManager(policy: ClientPolicy = ClientPolicy.UNRESTRICTED): ClientPolicyManager =
    mockk(relaxed = true) {
        every { this@mockk.policy } returns MutableStateFlow(policy)
        coEvery { current() } returns policy
    }
