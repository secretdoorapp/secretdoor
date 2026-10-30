package com.x8bit.bitwarden.data.platform.manager.event

import com.x8bit.bitwarden.data.platform.manager.model.OrganizationEvent

/**
 * Default implementation of [OrganizationEventManager].
 *
 * SecretDoor vaults are local and have no organizations, so organization events are never stored
 * or uploaded.
 */
internal class OrganizationEventManagerImpl : OrganizationEventManager {
    override fun trackEvent(event: OrganizationEvent) = Unit
}
