package com.x8bit.bitwarden.data.platform.manager

import com.bitwarden.policies.Policy
import com.bitwarden.policies.PolicyType
import kotlinx.coroutines.flow.Flow

/**
 * A manager for the organization policies that apply to the user.
 */
interface PolicyManager {
    /**
     * Returns a flow of all the active policies of the given type.
     */
    fun getActivePoliciesFlow(type: PolicyType): Flow<List<Policy>>

    /**
     * Get all the policies of the given [type] that are enabled and applicable to the user.
     */
    fun getActivePolicies(type: PolicyType): List<Policy>

    /**
     * Get all the policies of the given [type] that are enabled and applicable to the [userId].
     */
    fun getUserPolicies(
        userId: String,
        type: PolicyType,
    ): List<Policy>
}
