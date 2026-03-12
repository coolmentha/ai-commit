package org.coolmentha.aicommit.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.intellij.ide.BrowserUtil
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal interface CodexOAuthCallbackReceiver : AutoCloseable {
    val redirectUri: URI

    fun awaitRedirect(timeout: Duration): String?
}

internal class CodexOAuthBrowserLogin(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build(),
    private val codexHomePathProvider: () -> Path = { CodexOAuthCredentialsResolver.resolveDefaultCodexHomePath() },
    private val browserOpener: (URI) -> Unit = { uri -> BrowserUtil.browse(uri) },
    private val callbackReceiverFactory: (Int) -> CodexOAuthCallbackReceiver = { port ->
        LocalCodexOAuthCallbackReceiver(port)
    },
    private val nowProvider: () -> Instant = { Instant.now() },
) {
    private val objectMapper = jacksonObjectMapper()

    fun login(manualCallbackProvider: (URI) -> String?): CodexOAuthCredential {
        val receiver = callbackReceiverFactory(DEFAULT_CALLBACK_PORT)
        try {
            val pkce = generatePkce()
            val state = generateState()
            val authUrl = buildAuthorizeUrl(receiver.redirectUri, pkce, state)
            try {
                browserOpener(authUrl)
            } catch (error: Exception) {
                throw AiCommitException("打开 Codex OAuth 登录页失败：${error.message ?: error.javaClass.simpleName}", error)
            }

            val callback = awaitAuthorizationCode(
                receiver = receiver,
                authUrl = authUrl,
                expectedState = state,
                manualCallbackProvider = manualCallbackProvider,
            )
            val tokens = exchangeCodeForTokens(
                redirectUri = receiver.redirectUri,
                codeVerifier = pkce.codeVerifier,
                code = callback.code,
            )
            persistTokens(tokens)
            return CodexOAuthCredentialsResolver(
                httpClient = httpClient,
                codexHomePathProvider = codexHomePathProvider,
                nowProvider = nowProvider,
            ).resolve()
        } finally {
            receiver.close()
        }
    }

    private fun awaitAuthorizationCode(
        receiver: CodexOAuthCallbackReceiver,
        authUrl: URI,
        expectedState: String,
        manualCallbackProvider: (URI) -> String?,
    ): AuthorizationCode {
        receiver.awaitRedirect(AUTOMATIC_CALLBACK_TIMEOUT)?.let {
            return parseCallbackInput(it, expectedState, requireState = true)
        }

        val manualInput = manualCallbackProvider(authUrl)?.trim().orEmpty()
        receiver.awaitRedirect(Duration.ZERO)?.let {
            return parseCallbackInput(it, expectedState, requireState = true)
        }
        if (manualInput.isBlank()) {
            throw AiCommitException("Codex OAuth 登录已取消。")
        }
        return parseCallbackInput(manualInput, expectedState, requireState = false)
    }

    internal fun buildAuthorizeUrl(
        redirectUri: URI,
        pkce: PkceCodes,
        state: String,
    ): URI {
        val query = linkedMapOf(
            "response_type" to "code",
            "client_id" to CodexOAuthCredentialsResolver.DEFAULT_OPENAI_CLIENT_ID,
            "redirect_uri" to redirectUri.toString(),
            "scope" to CodexOAuthCredentialsResolver.DEFAULT_OPENAI_OAUTH_SCOPE,
            "code_challenge" to pkce.codeChallenge,
            "code_challenge_method" to "S256",
            "id_token_add_organizations" to "true",
            "codex_cli_simplified_flow" to "true",
            "state" to state,
            "originator" to CodexOAuthCredentialsResolver.DEFAULT_OPENAI_OAUTH_ORIGINATOR,
        ).entries.joinToString("&") { (key, value) ->
            "${urlEncode(key)}=${urlEncode(value)}"
        }
        return URI.create("${CodexOAuthCredentialsResolver.OPENAI_AUTHORIZE_URL}?$query")
    }

    internal fun parseCallbackInput(
        rawInput: String,
        expectedState: String,
        requireState: Boolean,
    ): AuthorizationCode {
        val normalizedInput = rawInput.trim()
        if (normalizedInput.isBlank()) {
            throw AiCommitException("未收到 Codex OAuth 回调。")
        }

        val callbackUri = toCallbackUri(normalizedInput)
        if (callbackUri == null) {
            return AuthorizationCode(code = normalizedInput)
        }

        val params = parseQuery(callbackUri.rawQuery)
        params["error"]?.takeIf { it.isNotBlank() }?.let { errorCode ->
            val description = params["error_description"].orEmpty().ifBlank { "接口没有返回错误详情。" }
            throw AiCommitException("Codex OAuth 登录失败：$errorCode，$description")
        }

        val code = params["code"].orEmpty().trim()
        if (code.isBlank()) {
            throw AiCommitException("Codex OAuth 回调缺少授权码。")
        }

        val returnedState = params["state"].orEmpty().trim()
        if (requireState && returnedState != expectedState) {
            throw AiCommitException("Codex OAuth 回调校验失败，请重新登录。")
        }
        if (returnedState.isNotBlank() && returnedState != expectedState) {
            throw AiCommitException("Codex OAuth 回调 state 不匹配，请重新登录。")
        }

        return AuthorizationCode(
            code = code,
            state = returnedState.ifBlank { null },
        )
    }

    private fun toCallbackUri(rawInput: String): URI? {
        val normalized = when {
            rawInput.startsWith("http://") || rawInput.startsWith("https://") -> rawInput
            rawInput.startsWith("/auth/callback") -> "http://localhost$rawInput"
            rawInput.startsWith("auth/callback") -> "http://localhost/$rawInput"
            else -> return null
        }
        return runCatching {
            URI.create(normalized)
        }.getOrElse {
            throw AiCommitException("无法解析回调地址，请粘贴浏览器地址栏中的完整 URL。", it)
        }
    }

    private fun exchangeCodeForTokens(
        redirectUri: URI,
        codeVerifier: String,
        code: String,
    ): TokenResponse {
        val requestBody = linkedMapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri.toString(),
            "client_id" to CodexOAuthCredentialsResolver.DEFAULT_OPENAI_CLIENT_ID,
            "code_verifier" to codeVerifier,
        ).entries.joinToString("&") { (key, value) ->
            "${urlEncode(key)}=${urlEncode(value)}"
        }

        val request = HttpRequest.newBuilder(URI.create(CodexOAuthCredentialsResolver.OPENAI_OAUTH_TOKEN_URL))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
            .build()

        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        } catch (error: Exception) {
            throw AiCommitException("Codex OAuth 换取 token 失败：${error.message ?: error.javaClass.simpleName}", error)
        }

        if (response.statusCode() !in 200..299) {
            throw AiCommitException(
                "Codex OAuth 换取 token 失败（HTTP ${response.statusCode()}）：${extractErrorMessage(response.body())}",
            )
        }

        val payload = try {
            objectMapper.readTree(response.body())
        } catch (error: Exception) {
            throw AiCommitException("Codex OAuth 返回了无法解析的 token 响应。", error)
        }

        val accessToken = payload.path("access_token").asText("").trim()
        val refreshToken = payload.path("refresh_token").asText("").trim()
        if (accessToken.isBlank() || refreshToken.isBlank()) {
            throw AiCommitException("Codex OAuth token 响应缺少 access_token 或 refresh_token。")
        }

        return TokenResponse(
            accessToken = accessToken,
            refreshToken = refreshToken,
            idToken = payload.path("id_token").asText("").trim().ifBlank { null },
        )
    }

    private fun persistTokens(tokens: TokenResponse) {
        val codexHome = codexHomePathProvider()
        val accountId = CodexOAuthCredentialsResolver.extractAccountIdFromToken(tokens.accessToken)
            ?: tokens.idToken?.let(CodexOAuthCredentialsResolver::extractAccountIdFromToken)

        val payload = objectMapper.createObjectNode().apply {
            put("auth_mode", "chatgpt")
            putNull("OPENAI_API_KEY")
            put("last_refresh", nowProvider().toString())
            putObject("tokens").apply {
                put("access_token", tokens.accessToken)
                put("refresh_token", tokens.refreshToken)
                tokens.idToken?.takeIf { it.isNotBlank() }?.let { put("id_token", it) }
                accountId?.takeIf { it.isNotBlank() }?.let { put("account_id", it) }
            }
        }

        val authPath = codexHome.resolve(CodexOAuthCredentialsResolver.CODEX_AUTH_FILE_NAME)
        try {
            Files.createDirectories(codexHome)
            Files.writeString(
                authPath,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload) + System.lineSeparator(),
                StandardCharsets.UTF_8,
            )
        } catch (error: Exception) {
            throw AiCommitException("Codex OAuth 凭证写入本地失败：${error.message ?: error.javaClass.simpleName}", error)
        }

        CodexOAuthCredentialsResolver.clearSessionCredentialCache()
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) {
            return emptyMap()
        }
        return rawQuery.split('&')
            .mapNotNull { pair ->
                if (pair.isBlank()) {
                    return@mapNotNull null
                }
                val index = pair.indexOf('=')
                val rawKey = if (index >= 0) pair.substring(0, index) else pair
                val rawValue = if (index >= 0) pair.substring(index + 1) else ""
                val key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8)
                val value = URLDecoder.decode(rawValue, StandardCharsets.UTF_8)
                key to value
            }
            .toMap()
    }

    private fun extractErrorMessage(body: String): String {
        val parsed = runCatching {
            objectMapper.readTree(body)
        }.getOrNull()
        val errorDescription = parsed?.path("error_description")?.asText("").orEmpty()
        if (errorDescription.isNotBlank()) {
            return errorDescription
        }
        val errorMessage = when (val errorNode = parsed?.path("error")) {
            is JsonNode -> {
                when {
                    errorNode.isTextual -> errorNode.asText("").trim()
                    errorNode.isObject -> errorNode.path("message").asText("").trim()
                    else -> ""
                }
            }
            else -> ""
        }
        if (errorMessage.isNotBlank()) {
            return errorMessage
        }
        return body.ifBlank { "接口没有返回错误详情。" }
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun generatePkce(): PkceCodes {
        val verifierBytes = ByteArray(64)
        randomBytes(verifierBytes)
        val codeVerifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes)
        val digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray(StandardCharsets.UTF_8))
        val codeChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        return PkceCodes(
            codeVerifier = codeVerifier,
            codeChallenge = codeChallenge,
        )
    }

    private fun generateState(): String {
        val stateBytes = ByteArray(32)
        randomBytes(stateBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(stateBytes)
    }

    private fun randomBytes(target: ByteArray) {
        val random = java.security.SecureRandom()
        random.nextBytes(target)
    }

    internal data class PkceCodes(
        val codeVerifier: String,
        val codeChallenge: String,
    )

    internal data class AuthorizationCode(
        val code: String,
        val state: String? = null,
    )

    internal data class TokenResponse(
        val accessToken: String,
        val refreshToken: String,
        val idToken: String? = null,
    )

    companion object {
        internal const val DEFAULT_CALLBACK_PORT = 1455
        private val AUTOMATIC_CALLBACK_TIMEOUT: Duration = Duration.ofSeconds(60)
    }
}

private class LocalCodexOAuthCallbackReceiver(preferredPort: Int) : CodexOAuthCallbackReceiver {
    private val redirectFuture = CompletableFuture<String>()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ai-commit-codex-oauth-callback").apply {
            isDaemon = true
        }
    }
    private val server = bind(preferredPort)

    override val redirectUri: URI = URI.create("http://localhost:${server.address.port}/auth/callback")

    init {
        server.executor = executor
        server.createContext("/auth/callback") { exchange ->
            handleCallback(exchange)
        }
        server.start()
    }

    override fun awaitRedirect(timeout: Duration): String? {
        return try {
            redirectFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AiCommitException("等待 Codex OAuth 回调时被中断。")
        } catch (error: ExecutionException) {
            val cause = error.cause
            if (cause is RuntimeException) {
                throw cause
            }
            throw AiCommitException("读取 Codex OAuth 回调失败：${cause?.message ?: error.message}", cause ?: error)
        }
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun handleCallback(exchange: HttpExchange) {
        val requestUri = exchange.requestURI
        val fullUrl = URI(
            "http",
            null,
            "localhost",
            server.address.port,
            requestUri.path,
            requestUri.rawQuery,
            null,
        ).toString()
        redirectFuture.complete(fullUrl)
        writeHtmlResponse(
            exchange = exchange,
            statusCode = 200,
            html = SUCCESS_HTML,
        )
    }

    private fun writeHtmlResponse(
        exchange: HttpExchange,
        statusCode: Int,
        html: String,
    ) {
        val bytes = html.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
        exchange.responseBody.use { output ->
            output.write(bytes)
        }
    }

    private fun bind(preferredPort: Int): HttpServer {
        val loopbackAddress = InetAddress.getByName("127.0.0.1")
        return runCatching {
            HttpServer.create(InetSocketAddress(loopbackAddress, preferredPort), 0)
        }.getOrElse {
            HttpServer.create(InetSocketAddress(loopbackAddress, 0), 0)
        }
    }

    companion object {
        private const val SUCCESS_HTML = """
<!doctype html>
<html lang="zh-CN">
  <head>
    <meta charset="utf-8">
    <title>AI Commit OAuth</title>
  </head>
  <body style="font-family: sans-serif; padding: 24px;">
    <h2>Codex OAuth 登录完成</h2>
    <p>你可以返回 IntelliJ IDEA，继续使用 AI Commit。</p>
  </body>
</html>
"""
    }
}
