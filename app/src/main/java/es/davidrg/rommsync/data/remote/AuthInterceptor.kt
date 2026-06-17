package es.davidrg.rommsync.data.remote

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Injects the RomM API key as a Bearer token into every outgoing request.
 *
 * The app authenticates exclusively via API Key (rmm_... format) to avoid
 * OAuth/CSRF session blocks and OIDC redirect issues (Authentik, Authelia).
 */
class AuthInterceptor(
    private var apiKeyProvider: () -> String,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val apiKey = apiKeyProvider().trim()
        val request = chain.request().newBuilder().apply {
            if (apiKey.isNotEmpty()) {
                addHeader("Authorization", "Bearer $apiKey")
            }
        }.build()
        return chain.proceed(request)
    }

    fun updateApiKey(newKey: String) {
        apiKeyProvider = { newKey }
    }
}
