package org.coolmentha.aicommit.ai

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.coolmentha.aicommit.settings.AiCommitAuthMode
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
import java.nio.ByteBuffer
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Flow
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import kotlin.test.assertEquals
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

    @Test
    fun `Codex OAuth 模式会走 ChatGPT Codex Responses 流式接口且不会复用 API Key base url`() {
        val capturedRequest = AtomicReference<HttpRequest>()
        val client = OpenAiCompatibleClient(
            httpClient = FakeHttpClient { request ->
                capturedRequest.set(request)
                FakeHttpResponse(
                    request = request,
                    statusCode = 200,
                    body = """
event: response.output_text.delta
data: {"type":"response.output_text.delta","delta":"feat: "}

event: response.output_text.delta
data: {"type":"response.output_text.delta","delta":"use codex oauth"}

data: [DONE]
                    """.trimIndent(),
                )
            },
            codexOAuthCredentialsProvider = ::testCodexCredential,
        )

        val message = client.generateCommitMessage(
            AiCommitSettingsState(
                authMode = AiCommitAuthMode.CODEX_OAUTH.id,
                model = "gpt-5.4",
                apiBaseUrl = AiCommitSettingsState.DEFAULT_API_BASE_URL,
            ),
            "test prompt",
        )

        assertEquals("feat: use codex oauth", message)
        val request = capturedRequest.get()
        assertEquals("https://chatgpt.com/backend-api/codex/responses", request.uri().toString())
        assertEquals("Bearer oauth-access-token", request.headers().firstValue("Authorization").orElse(""))
        assertEquals("text/event-stream", request.headers().firstValue("Accept").orElse(""))
        assertEquals("acct_test", request.headers().firstValue("ChatGPT-Account-Id").orElse(""))
        val requestBody = readBody(request)
        assertContains(requestBody, "\"store\":false")
        assertContains(requestBody, "\"stream\":true")
        assertContains(requestBody, "\"instructions\":\"你负责根据代码差异生成 commit message，只输出最终提交信息。\"")
        assertContains(requestBody, "\"input\":[")
        assertContains(requestBody, "\"role\":\"user\"")
        assertContains(requestBody, "\"type\":\"input_text\"")
        assertContains(requestBody, "\"text\":\"test prompt\"")
    }

    @Test
    fun `Codex OAuth 模式兼容非流式 JSON 响应`() {
        val client = OpenAiCompatibleClient(
            httpClient = FakeHttpClient { request ->
                FakeHttpResponse(
                    request = request,
                    statusCode = 200,
                    body = """{"output_text":"feat: fallback json"}""",
                )
            },
            codexOAuthCredentialsProvider = ::testCodexCredential,
        )

        val message = client.generateCommitMessage(
            AiCommitSettingsState(
                authMode = AiCommitAuthMode.CODEX_OAUTH.id,
                model = "gpt-5.4",
                apiBaseUrl = AiCommitSettingsState.DEFAULT_API_BASE_URL,
            ),
            "test prompt",
        )

        assertEquals("feat: fallback json", message)
    }

    private fun testSettings(): AiCommitSettingsState {
        return AiCommitSettingsState(
            apiBaseUrl = "https://example.com/v1",
            apiKey = "test-key",
            model = "test-model",
        )
    }

    private fun testCodexCredential(): CodexOAuthCredential {
        return CodexOAuthCredential(
            accessToken = "oauth-access-token",
            refreshToken = "oauth-refresh-token",
            accountId = "acct_test",
            clientId = "client-test",
            expiresAt = Instant.parse("2026-03-12T12:00:00Z"),
            source = CodexOAuthCredentialSource.FILE,
            rawPayload = jacksonObjectMapper().createObjectNode(),
            sourcePath = null,
            keychainAccount = null,
        )
    }

    private fun readBody(request: HttpRequest): String {
        val publisher = request.bodyPublisher().orElseThrow()
        val collector = ByteArrayCollector()
        publisher.subscribe(collector)
        return collector.await()
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

    private class ByteArrayCollector : Flow.Subscriber<ByteBuffer> {
        private val future = CompletableFuture<String>()
        private val output = StringBuilder()

        override fun onSubscribe(subscription: Flow.Subscription) {
            subscription.request(Long.MAX_VALUE)
        }

        override fun onNext(item: ByteBuffer) {
            val bytes = ByteArray(item.remaining())
            item.get(bytes)
            output.append(String(bytes, Charsets.UTF_8))
        }

        override fun onError(throwable: Throwable) {
            future.completeExceptionally(throwable)
        }

        override fun onComplete() {
            future.complete(output.toString())
        }

        fun await(): String = future.join()
    }
}
