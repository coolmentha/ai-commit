package org.coolmentha.aicommit.ai

import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.URLDecoder
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
import kotlin.test.assertTrue

class CodexOAuthBrowserLoginTest {
    @BeforeTest
    fun resetCache() {
        CodexOAuthCredentialsResolver.clearSessionCredentialCache()
    }

    @Test
    fun `自动回调会完成网页登录并落盘 auth json`() {
        val capturedRequest = AtomicReference<HttpRequest>()
        val openedAuthUrl = AtomicReference<URI>()
        val codexHome = Files.createTempDirectory("codex-browser-login")
        val login = CodexOAuthBrowserLogin(
            httpClient = FakeHttpClient { request ->
                capturedRequest.set(request)
                FakeHttpResponse(
                    request = request,
                    statusCode = 200,
                    body = """
                    {
                      "id_token": "${idToken("acct_browser")}",
                      "access_token": "${accessToken(exp = Instant.parse("2026-03-20T00:00:00Z").epochSecond, accountId = "acct_browser")}",
                      "refresh_token": "refresh-browser"
                    }
                    """.trimIndent(),
                )
            },
            codexHomePathProvider = { codexHome },
            browserOpener = { uri -> openedAuthUrl.set(uri) },
            callbackReceiverFactory = {
                FakeCallbackReceiver { _, _ ->
                    val authUrl = assertNotNull(openedAuthUrl.get())
                    val state = queryParam(authUrl, "state")
                    "http://localhost:1455/auth/callback?code=browser-code&state=$state"
                }
            },
            nowProvider = { Instant.parse("2026-03-12T00:00:00Z") },
        )

        val credential = login.login { error("自动回调场景不应进入手工粘贴") }

        val authUrl = assertNotNull(openedAuthUrl.get())
        assertEquals("https", authUrl.scheme)
        assertEquals("auth.openai.com", authUrl.host)
        assertEquals("/oauth/authorize", authUrl.path)
        assertEquals("code", queryParam(authUrl, "response_type"))
        assertEquals("S256", queryParam(authUrl, "code_challenge_method"))
        assertEquals("true", queryParam(authUrl, "codex_cli_simplified_flow"))
        assertEquals("codex_cli_rs", queryParam(authUrl, "originator"))
        assertContains(queryParam(authUrl, "scope"), "offline_access")
        assertContains(queryParam(authUrl, "scope"), "api.connectors.read")

        val request = assertNotNull(capturedRequest.get())
        assertEquals("https://auth.openai.com/oauth/token", request.uri().toString())
        val persisted = codexHome.resolve("auth.json").readText(StandardCharsets.UTF_8)
        assertContains(persisted, "\"refresh_token\" : \"refresh-browser\"")
        assertContains(persisted, "\"account_id\" : \"acct_browser\"")
        assertEquals("refresh-browser", credential.refreshToken)
        assertEquals("acct_browser", credential.accountId)
        assertEquals(CodexOAuthCredentialSource.FILE, credential.source)
    }

    @Test
    fun `自动回调超时后可以通过手工粘贴回调 url 完成登录`() {
        val openedAuthUrl = AtomicReference<URI>()
        var manualPromptCount = 0
        val codexHome = Files.createTempDirectory("codex-browser-login-manual")
        val login = CodexOAuthBrowserLogin(
            httpClient = FakeHttpClient { request ->
                FakeHttpResponse(
                    request = request,
                    statusCode = 200,
                    body = """
                    {
                      "access_token": "${accessToken(exp = Instant.parse("2026-03-20T00:00:00Z").epochSecond, accountId = "acct_manual")}",
                      "refresh_token": "refresh-manual"
                    }
                    """.trimIndent(),
                )
            },
            codexHomePathProvider = { codexHome },
            browserOpener = { uri -> openedAuthUrl.set(uri) },
            callbackReceiverFactory = {
                FakeCallbackReceiver { _, callCount ->
                    if (callCount == 1) {
                        null
                    } else {
                        null
                    }
                }
            },
            nowProvider = { Instant.parse("2026-03-12T00:00:00Z") },
        )

        val credential = login.login { authUrl ->
            manualPromptCount += 1
            val state = queryParam(authUrl, "state")
            "http://localhost:1455/auth/callback?code=manual-code&state=$state"
        }

        assertEquals(1, manualPromptCount)
        assertEquals("refresh-manual", credential.refreshToken)
        assertEquals("acct_manual", credential.accountId)
        assertTrue(codexHome.resolve("auth.json").toFile().isFile)
    }

    private fun queryParam(uri: URI, name: String): String =
        uri.rawQuery.split('&')
            .mapNotNull { pair ->
                val index = pair.indexOf('=')
                val key = if (index >= 0) pair.substring(0, index) else pair
                if (URLDecoder.decode(key, StandardCharsets.UTF_8) != name) {
                    return@mapNotNull null
                }
                val rawValue = if (index >= 0) pair.substring(index + 1) else ""
                URLDecoder.decode(rawValue, StandardCharsets.UTF_8)
            }
            .first()

    private fun accessToken(exp: Long, accountId: String): String {
        val header = base64Url("""{"alg":"none","typ":"JWT"}""")
        val payload = base64Url(
            """
            {
              "exp": $exp,
              "client_id": "client-browser",
              "https://api.openai.com/auth": {
                "chatgpt_account_id": "$accountId"
              }
            }
            """.trimIndent(),
        )
        return "$header.$payload.signature"
    }

    private fun idToken(accountId: String): String {
        val header = base64Url("""{"alg":"none","typ":"JWT"}""")
        val payload = base64Url(
            """
            {
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

    private class FakeCallbackReceiver(
        private val responseProvider: (Duration, Int) -> String?,
    ) : CodexOAuthCallbackReceiver {
        private var callCount = 0

        override val redirectUri: URI = URI.create("http://localhost:1455/auth/callback")

        override fun awaitRedirect(timeout: Duration): String? {
            callCount += 1
            return responseProvider(timeout, callCount)
        }

        override fun close() = Unit
    }

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
