package com.x8bit.bitwarden.data.platform.manager

import com.bitwarden.policies.Policy
import com.bitwarden.policies.PolicyType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Primary implementation of [PolicyManager].
 *
 * Policies are rules set by an organization's admin. A SecretDoor vault belongs to no
 * organization, so no policy ever applies.
 */
class PolicyManagerImpl : PolicyManager {
    override fun getActivePoliciesFlow(type: PolicyType): Flow<List<Policy>> = flowOf(emptyList())

    override fun getActivePolicies(type: PolicyType): List<Policy> = emptyList()

    override fun getUserPolicies(
        userId: String,
        type: PolicyType,
    ): List<Policy> = emptyList()
}
