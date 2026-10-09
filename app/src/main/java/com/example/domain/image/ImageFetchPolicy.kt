package com.example.domain.image

import com.example.domain.propertyurl.normalize.ValueGuards
import com.example.domain.propertyurl.port.PublicOnlyDns
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.Proxy

/**
 * Network policy for property image loading.
 *
 * Image URLs are taken from third-party listing pages, so they are untrusted. Without this policy the
 * image loader would follow redirects to any host and resolve any hostname, which lets a listing make
 * the device request private-network or cloud-metadata endpoints. The policy:
 *  - refuses every request whose URL fails [ValueGuards.imageUrlOrNull] (including URLs already stored
 *    before the ingestion guards existed),
 *  - resolves hostnames only to public addresses ([PublicOnlyDns]), so DNS cannot point at private ranges,
 *  - never follows redirects, so a public URL cannot bounce the request to a private destination,
 *  - sends no cookies, credentials or proxy.
 */
object ImageFetchPolicy {

    fun isAllowedImageUrl(url: String): Boolean = ValueGuards.imageUrlOrNull(url) != null

    fun createClient(base: OkHttpClient = OkHttpClient()): OkHttpClient =
        base.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .proxy(Proxy.NO_PROXY)
            .cookieJar(CookieJar.NO_COOKIES)
            .authenticator(Authenticator.NONE)
            .dns(PublicOnlyDns(Dns.SYSTEM))
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                if (!isAllowedImageUrl(request.url.toString())) {
                    // Do not include the URL in the message: it is untrusted and may be logged.
                    throw IOException("Image request refused: destination is not a public host")
                }
                chain.proceed(request)
            })
            .build()
}
