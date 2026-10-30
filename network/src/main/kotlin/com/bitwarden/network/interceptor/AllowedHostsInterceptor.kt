package com.bitwarden.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Have I Been Pwned's password range API, used by the optional "check known data breaches" option.
 */
private const val HAVE_I_BEEN_PWNED_HOST: String = "api.pwnedpasswords.com"

/**
 * Google's Digital Asset Links API, used to confirm that an app asking for a passkey really belongs
 * to the website the passkey is for.
 */
private const val DIGITAL_ASSET_LINKS_HOST: String = "digitalassetlinks.googleapis.com"

/**
 * Refuses every request to a host that is not in [allowedHosts], before any connection is made.
 *
 * SecretDoor keeps the vault on the device and never talks to Bitwarden servers, so this blocks
 * leftover server calls (configuration, sync, icons, events) at the network layer. The only hosts
 * allowed are the optional breach check and the passkey app check.
 */
class AllowedHostsInterceptor(
    private val allowedHosts: Set<String> = setOf(
        HAVE_I_BEEN_PWNED_HOST,
        DIGITAL_ASSET_LINKS_HOST,
    ),
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val host = chain.request().url.host
        if (host !in allowedHosts) {
            throw BlockedHostException(host = host)
        }
        return chain.proceed(chain.request())
    }
}

/**
 * Thrown when a request to [host] is blocked because SecretDoor works offline.
 */
class BlockedHostException(
    val host: String,
) : IOException("SecretDoor works offline and does not connect to $host")
