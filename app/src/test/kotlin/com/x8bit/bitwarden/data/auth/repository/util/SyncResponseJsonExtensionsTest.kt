package com.x8bit.bitwarden.data.auth.repository.util

import com.bitwarden.network.model.OrganizationType
import com.bitwarden.network.model.SyncResponseJson
import com.bitwarden.network.model.createMockOrganizationNetwork
import com.bitwarden.network.model.createMockPermissions
import com.bitwarden.organizations.ProfileOrganization
import com.x8bit.bitwarden.data.auth.repository.model.createMockOrganization
import com.x8bit.bitwarden.data.auth.repository.model.createMockSdkProfileOrganization
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SyncResponseJsonExtensionsTest {
    @Test
    fun `toOrganization should output the correct organization`() {
        assertEquals(
            createMockOrganization(number = 1),
            createMockOrganizationNetwork(number = 1).toOrganization(),
        )
    }

    @Test
    fun `toOrganizations should output the correct list of organizations`() {
        assertEquals(
            listOf(
                createMockOrganization(
                    number = 1,
                    shouldUseKeyConnector = true,
                ),
                createMockOrganization(
                    number = 2,
                    shouldManageResetPassword = true,
                    role = OrganizationType.USER,
                ),
            ),
            listOf(
                createMockOrganizationNetwork(number = 1, isKeyConnectorEnabled = true),
                createMockOrganizationNetwork(
                    number = 2,
                    type = OrganizationType.USER,
                    permissions = createMockPermissions(shouldManageResetPassword = true),
                ),
            )
                .toOrganizations(),
        )
    }

    @Test
    fun `toSdkProfileOrganizations should correctly map a single organization`() {
        assertEquals(
            listOf(createMockSdkProfileOrganization(number = 1)),
            listOf(createMockOrganizationNetwork(number = 1)).toSdkProfileOrganizations(),
        )
    }

    @Test
    fun `toSdkProfileOrganizations should correctly map multiple organizations`() {
        assertEquals(
            listOf(
                createMockSdkProfileOrganization(number = 1),
                createMockSdkProfileOrganization(number = 2),
            ),
            listOf(
                createMockOrganizationNetwork(number = 1),
                createMockOrganizationNetwork(number = 2),
            )
                .toSdkProfileOrganizations(),
        )
    }

    @Test
    fun `toSdkProfileOrganizations should filter out organizations with null names`() {
        assertEquals(
            listOf(createMockSdkProfileOrganization(number = 1)),
            listOf(
                createMockOrganizationNetwork(number = 1),
                createMockOrganizationNetwork(number = 2, name = null),
            )
                .toSdkProfileOrganizations(),
        )
    }

    @Test
    fun `toSdkProfileOrganizations should filter out organizations with null user IDs`() {
        assertEquals(
            listOf(createMockSdkProfileOrganization(number = 1)),
            listOf(
                createMockOrganizationNetwork(number = 1),
                createMockOrganizationNetwork(number = 2, userId = null),
            )
                .toSdkProfileOrganizations(),
        )
    }

    @Suppress("MaxLineLength")
    @Test
    fun `toSdkProfileOrganizations should return empty list when all organizations have null names`() {
        assertEquals(
            emptyList<ProfileOrganization>(),
            listOf(
                createMockOrganizationNetwork(number = 1, name = null),
                createMockOrganizationNetwork(number = 2, name = null),
            )
                .toSdkProfileOrganizations(),
        )
    }

    @Test
    fun `toSdkProfileOrganizations should return empty list for empty input`() {
        assertEquals(
            emptyList<ProfileOrganization>(),
            emptyList<SyncResponseJson.Profile.Organization>().toSdkProfileOrganizations(),
        )
    }

}
