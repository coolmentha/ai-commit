package org.coolmentha.aicommit.ai

import org.coolmentha.aicommit.settings.AiCommitSettingsState
import java.io.IOException
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class OpenAiCompatibleClientTest {
    @Test
    fun `HTTP 错误消息会带上请求 URL`() {
        val client = OpenAiCompatibleClient(
            httpClient = FakeHttpClient { request ->
                FakeHttpResponse(
                    request = request,
                    statusCode = 404,
                    body = """{"error":{"message":"当前 API 不支持所选模型 claude-sonnet-4-5-20250929","type":"error"}}""",
                )
            },
        )

        val error = assertFailsWith<AiCommitException> {
            client.generateCommitMessage(testSettings(), "test prompt")
        }

        val message = error.message.orEmpty()
        assertContains(message, "HTTP 404")
        assertContains(message, "请求 URL：https://example.com/v1/chat/completions")
        assertContains(message, "当前 API 不支持所选模型 claude-sonnet-4-5-20250929")
    }

    @Test
    fun `发送异常消息会带上请求 URL`() {
        val client = OpenAiCompatibleClient(
            httpClient = FakeHttpClient {
                throw IOException("connect timed out")
            },
        )

        val error = assertFailsWith<AiCommitException> {
            client.generateCommitMessage(testSettings(), "test prompt")
        }

        val message = error.message.orEmpty()
        assertContains(message, "请求 URL：https://example.com/v1/chat/completions")
        assertContains(message, "connect timed out")
    }

    private fun testSettings(): AiCommitSettingsState {
        return AiCommitSettingsState(
            apiBaseUrl = "https://example.com/v1",
            apiKey = "test-key",
            model = "test-model",
        )
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
