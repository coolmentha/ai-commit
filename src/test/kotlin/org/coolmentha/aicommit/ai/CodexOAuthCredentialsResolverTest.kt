package org.coolmentha.aicommit.ai

import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import kotlin.io.path.readText
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CodexOAuthCredentialsResolverTest {
    @BeforeTest
    fun resetCache() {
        CodexOAuthCredentialsResolver.clearSessionCredentialCache()
    }

    @Test
    fun `会从 auth json 读取 Codex OAuth 凭证`() {
        val codexHome = Files.createTempDirectory("codex-auth-home")
        writeAuthJson(
            codexHome = codexHome,
            accessToken = accessToken(exp = Instant.parse("2026-03-20T00:00:00Z").epochSecond),
            refreshToken = "refresh-test",
            accountId = "acct_test",
        )

        val resolver = CodexOAuthCredentialsResolver(
            codexHomePathProvider = { codexHome },
            nowProvider = { Instant.parse("2026-03-12T00:00:00Z") },
        )

        val credential = resolver.resolve()

        assertEquals("refresh-test", credential.refreshToken)
        assertEquals("acct_test", credential.accountId)
        assertEquals("client-test", credential.clientId)
        assertEquals(Instant.parse("2026-03-20T00:00:00Z"), credential.expiresAt)
        assertEquals(CodexOAuthCredentialSource.FILE, credential.source)
    }

    @Test
    fun `过期 access token 会触发 refresh 并回写 auth json`() {
        val capturedRequest = AtomicReference<HttpRequest>()
        val codexHome = Files.createTempDirectory("codex-refresh-home")
        writeAuthJson(
            codexHome = codexHome,
            accessToken = accessToken(exp = Instant.parse("2026-03-10T00:00:00Z").epochSecond),
            refreshToken = "refresh-old",
            accountId = "acct_old",
        )

        val resolver = CodexOAuthCredentialsResolver(
            httpClient = FakeHttpClient { request ->
                capturedRequest.set(request)
                FakeHttpResponse(
                    request = request,
                    statusCode = 200,
                    body = """
                    {
                      "access_token": "${accessToken(exp = Instant.parse("2026-03-15T00:00:00Z").epochSecond, accountId = "acct_new")}",
                      "refresh_token": "refresh-new",
                      "expires_in": 7200
                    }
                    """.trimIndent(),
                )
            },
            codexHomePathProvider = { codexHome },
            nowProvider = { Instant.parse("2026-03-12T00:00:00Z") },
        )

        val credential = resolver.resolve()

        assertEquals("refresh-new", credential.refreshToken)
        assertEquals("acct_new", credential.accountId)
        assertEquals(Instant.parse("2026-03-15T00:00:00Z"), credential.expiresAt)

        val request = assertNotNull(capturedRequest.get())
        assertEquals("https://auth.openai.com/oauth/token", request.uri().toString())
        val persisted = codexHome.resolve("auth.json").readText(StandardCharsets.UTF_8)
        assertContains(persisted, "\"refresh_token\" : \"refresh-new\"")
        assertContains(persisted, "\"account_id\" : \"acct_new\"")
    }

    private fun writeAuthJson(
        codexHome: Path,
        accessToken: String,
        refreshToken: String,
        accountId: String,
    ) {
        Files.createDirectories(codexHome)
        Files.writeString(
            codexHome.resolve("auth.json"),
            """
            {
              "auth_mode": "chatgpt",
              "OPENAI_API_KEY": null,
              "tokens": {
                "access_token": "$accessToken",
                "refresh_token": "$refreshToken",
                "account_id": "$accountId"
              },
              "last_refresh": "2026-03-09T01:41:58.953446915Z"
            }
            """.trimIndent(),
            StandardCharsets.UTF_8,
        )
    }

    private fun accessToken(exp: Long, accountId: String = "acct_test"): String {
        val header = base64Url("""{"alg":"none","typ":"JWT"}""")
        val payload = base64Url(
            """
            {
              "exp": $exp,
              "client_id": "client-test",
              "https://api.openai.com/auth": {
                "chatgpt_account_id": "$accountId"
              }
            }
            """.trimIndent(),
        )
        return "$header.$payload.signature"
    }

    private fun base64Url(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private class FakeHttpClient(
        private val responder: (HttpRequest) -> HttpResponse<String>,
    ) : HttpClient() {
        override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()

        override fun connectTimeout(): Optional<Duration> = Optional.empty()

        override fun followRedirects(): Redirect = Redirect.NEVER

        override fun proxy(): Optional<ProxySelector> = Optional.empty()

        override fun sslContext(): SSLContext = SSLContext.getDefault()

        override fun sslParameters(): SSLParameters = SSLParameters()

        override fun authenticator(): Optional<Authenticator> = Optional.empty()

        override fun version(): Version = Version.HTTP_1_1

        override fun executor(): Optional<Executor> = Optional.empty()

        @Suppress("UNCHECKED_CAST")
        override fun <T> send(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
        ): HttpResponse<T> {
            return responder(request) as HttpResponse<T>
        }

        override fun <T> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
        ): CompletableFuture<HttpResponse<T>> {
            return CompletableFuture.failedFuture(UnsupportedOperationException("测试中不会调用异步接口。"))
        }

        override fun <T> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
            pushPromiseHandler: HttpResponse.PushPromiseHandler<T>,
        ): CompletableFuture<HttpResponse<T>> {
            return CompletableFuture.failedFuture(UnsupportedOperationException("测试中不会调用异步接口。"))
        }
    }

    private class FakeHttpResponse(
        private val request: HttpRequest,
        private val statusCode: Int,
        private val body: String,
    ) : HttpResponse<String> {
        override fun statusCode(): Int = statusCode

        override fun request(): HttpRequest = request

        override fun previousResponse(): Optional<HttpResponse<String>> = Optional.empty()

        override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }

        override fun body(): String = body

        override fun sslSession(): Optional<SSLSession> = Optional.empty()

        override fun uri(): URI = request.uri()

        override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
    }
}
