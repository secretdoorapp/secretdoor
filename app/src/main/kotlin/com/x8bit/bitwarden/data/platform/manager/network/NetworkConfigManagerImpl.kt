package com.x8bit.bitwarden.data.platform.manager.network

import com.bitwarden.network.BitwardenServiceClient
import com.x8bit.bitwarden.data.auth.repository.AuthRepository

/**
 * Primary implementation of [NetworkConfigManager].
 *
 * SecretDoor never fetches a server configuration; it only wires the auth repository into the
 * network client.
 */
class NetworkConfigManagerImpl(
    authRepository: AuthRepository,
    bitwardenServiceClient: BitwardenServiceClient,
) : NetworkConfigManager {

    init {
        bitwardenServiceClient.setRefreshTokenProvider(authRepository)
    }
}
