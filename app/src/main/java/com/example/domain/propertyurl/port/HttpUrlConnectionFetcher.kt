package com.example.domain.propertyurl.port

/**
 * Compatibility name for older callers. The implementation now delegates to the secured OkHttp
 * transport so this entry point also enforces HTTPS, public-only DNS, bounded redirects, and
 * cross-origin credential stripping. New code should use [OkHttpHttpFetcher] directly.
 */
@Deprecated("Use OkHttpHttpFetcher; this compatibility wrapper now delegates to it.")
class HttpUrlConnectionFetcher(private val clock: Clock = SystemClock()) : HttpFetcher {
    private val delegate = OkHttpHttpFetcher(clock)

    override suspend fun fetch(request: HttpRequest, options: FetchOptions): HttpFetchResult =
        delegate.fetch(request, options)
}
