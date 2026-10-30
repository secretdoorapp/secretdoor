package com.x8bit.bitwarden.data.platform.manager.network

import com.bitwarden.network.BitwardenServiceClient
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Test

class NetworkConfigManagerTest {

    private val authRepository: AuthRepository = mockk()
    private val bitwardenServiceClient: BitwardenServiceClient = mockk {
        every { setRefreshTokenProvider(any()) } just runs
    }

    @Test
    fun `init should set the refresh token provider`() {
        NetworkConfigManagerImpl(
            authRepository = authRepository,
            bitwardenServiceClient = bitwardenServiceClient,
        )

        verify(exactly = 1) { bitwardenServiceClient.setRefreshTokenProvider(authRepository) }
    }
}
