package com.bitwarden.network.interceptor

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AllowedHostsInterceptorTest {
    private val interceptor = AllowedHostsInterceptor()
    private val response: Response = mockk()

    @Test
    fun `requests to Have I Been Pwned should proceed`() {
        val chain = createChain(url = "https://api.pwnedpasswords.com/range/ABCDE")

        assertEquals(response, interceptor.intercept(chain))
    }

    @Test
    fun `requests to Digital Asset Links should proceed`() {
        val chain = createChain(
            url = "https://digitalassetlinks.googleapis.com/v1/assetlinks:check",
        )

        assertEquals(response, interceptor.intercept(chain))
    }

    @Test
    fun `requests to any other host should be blocked without proceeding`() {
        val chain = createChain(url = "https://api.bitwarden.com/config")

        val exception = assertThrows<BlockedHostException> { interceptor.intercept(chain) }

        assertEquals("api.bitwarden.com", exception.host)
        verify(exactly = 0) { chain.proceed(any()) }
    }

    private fun createChain(url: String): Interceptor.Chain {
        val request = Request.Builder().url(url).build()
        return mockk {
            every { request() } returns request
            every { proceed(request) } returns response
        }
    }
}
