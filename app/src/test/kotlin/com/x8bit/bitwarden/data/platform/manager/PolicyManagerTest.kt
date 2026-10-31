package com.x8bit.bitwarden.data.platform.manager

import app.cash.turbine.test
import com.bitwarden.policies.PolicyType
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PolicyManagerTest {
    private val policyManager: PolicyManager = PolicyManagerImpl()

    @Test
    fun `no policy should ever apply`() = runTest {
        PolicyType.entries.forEach { type ->
            assertEquals(emptyList<Any>(), policyManager.getActivePolicies(type = type))
            assertEquals(
                emptyList<Any>(),
                policyManager.getUserPolicies(userId = "userId", type = type),
            )
            policyManager.getActivePoliciesFlow(type = type).test {
                assertEquals(emptyList<Any>(), awaitItem())
                awaitComplete()
            }
        }
    }
}
