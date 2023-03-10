package info.nightscout.sdk.networking

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

internal object NetworkStackBuilder {

    @JvmSynthetic
    internal fun getApi(
        baseUrl: String,
        context: Context,
        accessToken: String, // refresh token
        logging: Boolean = false
    ): NightscoutRemoteService = getRetrofit(
        baseUrl = baseUrl,
        context = context,
        refreshToken = accessToken,
        logging = logging
    ).create(NightscoutRemoteService::class.java)

    private fun getRetrofit(
        baseUrl: String,
        context: Context,
        refreshToken: String,
        logging: Boolean
    ): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://$baseUrl/api/")
            .client(
                getOkHttpClient(
                    context = context,
                    logging = logging,
                    refreshToken = refreshToken,
                    authRefreshRetrofit = getAuthRefreshRetrofit(baseUrl, context, logging)
                )
            )
            .addConverterFactory(GsonConverterFactory.create(provideGson()))
            .build()

    private fun getAuthRefreshRetrofit(
        baseUrl: String,
        context: Context,
        logging: Boolean
    ): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://$baseUrl/api/")
            .client(getAuthRefreshOkHttpClient(context = context, logging = logging))
            .addConverterFactory(GsonConverterFactory.create(provideGson()))
            .build()

    private fun getOkHttpClient(
        context: Context,
        logging: Boolean,
        refreshToken: String,
        authRefreshRetrofit: Retrofit
    ): OkHttpClient = OkHttpClient.Builder().run {
        addInterceptor(NSAuthInterceptor(refreshToken, authRefreshRetrofit))
        commonOkHttpSetup(logging, context)
    }

    private fun getAuthRefreshOkHttpClient(
        context: Context,
        logging: Boolean,
    ): OkHttpClient = OkHttpClient.Builder().run { commonOkHttpSetup(logging, context) }

    private fun OkHttpClient.Builder.commonOkHttpSetup(
        logging: Boolean,
        context: Context
    ): OkHttpClient {
        if (logging) {
            addNetworkInterceptor(
                HttpLoggingInterceptor().also { it.level = HttpLoggingInterceptor.Level.BODY }
            )
        }
        cache(Cache(context.cacheDir, OK_HTTP_CACHE_SIZE))
        readTimeout(OK_HTTP_READ_TIMEOUT, TimeUnit.MILLISECONDS)
        writeTimeout(OK_HTTP_WRITE_TIMEOUT, TimeUnit.MILLISECONDS)
        return build()
    }

    private fun provideGson(): Gson = GsonBuilder().create()

    private const val OK_HTTP_CACHE_SIZE = 10L * 1024 * 1024
    private const val OK_HTTP_READ_TIMEOUT = 60L * 1000
    private const val OK_HTTP_WRITE_TIMEOUT = 60L * 1000
}
