package com.gatecontrol.android.data

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LicenseRepositoryTest {

    private lateinit var repository: LicenseRepository

    @BeforeEach
    fun setUp() {
        repository = LicenseRepository()
    }

    @Test
    fun `community mode has no RDP by default`() {
        assertFalse(repository.hasFeature("rdp"))
    }

    @Test
    fun `pro mode has RDP after permissions updated`() {
        repository.updatePermissions(services = true, traffic = true, dns = true, rdp = true)

        assertTrue(repository.hasFeature("rdp"))
    }

    @Test
    fun `unknown feature returns false`() {
        repository.updatePermissions(services = true, traffic = true, dns = true, rdp = true)

        assertFalse(repository.hasFeature("unknown_feature"))
    }

    @Test
    fun `isApiTokenMode is false by default`() {
        assertFalse(repository.isApiTokenMode())
    }

    @Test
    fun `isApiTokenMode is true after updatePermissions`() {
        repository.updatePermissions(services = false, traffic = false, dns = false, rdp = false)

        assertTrue(repository.isApiTokenMode())
    }
}
