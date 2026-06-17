package es.davidrg.rommsync.data.remote

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Factory for creating and configuring the RomM API client.
 *
 * The base URL and API key are dynamic (user-configurable via DataStore),
 * so a new Retrofit instance must be created when the server config changes.
 */
object NetworkModule {

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    fun createOkHttpClient(apiKey: String): OkHttpClient {
        val authInterceptor = AuthInterceptor { apiKey }

        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        return OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS) // Long for large downloads
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun createRetrofit(baseUrl: String, client: OkHttpClient): Retrofit {
        val sanitizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return Retrofit.Builder()
            .baseUrl(sanitizedUrl)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    fun createApiService(baseUrl: String, apiKey: String): RomMApiService {
        val client = createOkHttpClient(apiKey)
        val retrofit = createRetrofit(baseUrl, client)
        return retrofit.create(RomMApiService::class.java)
    }
}
