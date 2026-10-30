package com.x8bit.bitwarden.data.platform.manager.event

import com.x8bit.bitwarden.data.platform.manager.model.OrganizationEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OrganizationEventManagerTest {

    private val organizationEventManager: OrganizationEventManager = OrganizationEventManagerImpl()

    @Test
    fun `trackEvent should do nothing`() {
        assertEquals(
            Unit,
            organizationEventManager.trackEvent(
                event = OrganizationEvent.CipherClientViewed(cipherId = "cipherId"),
            ),
        )
    }
}
