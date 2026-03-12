package org.coolmentha.aicommit.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class CodexOAuthCredential(
    val accessToken: String,
    val refreshToken: String,
    val accountId: String?,
    val clientId: String,
    val expiresAt: Instant,
    val source: CodexOAuthCredentialSource,
    val rawPayload: ObjectNode,
    val sourcePath: Path?,
    val keychainAccount: String?,
)

enum class CodexOAuthCredentialSource {
    KEYCHAIN,
    FILE,
}

class CodexOAuthCredentialsResolver(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build(),
    private val codexHomePathProvider: () -> Path = { resolveDefaultCodexHomePath() },
    private val keychainReader: (String) -> String? = { account -> readMacOsKeychainPayload(account) },
    private val keychainWriter: (String, String) -> Boolean = { account, payload ->
        writeMacOsKeychainPayload(account, payload)
    },
    private val nowProvider: () -> Instant = { Instant.now() },
) {
    private val objectMapper = jacksonObjectMapper()

    @Synchronized
    fun resolve(): CodexOAuthCredential {
        val cached = sessionCredential.get()
        val stored = readStoredCredential()
        val credential = listOfNotNull(cached, stored).maxByOrNull { it.expiresAt.toEpochMilli() }
            ?: throw AiCommitException("未找到 Codex OAuth 登录信息，请先在本机执行 `codex` 登录。")

        val resolved = if (shouldRefresh(credential)) {
            refreshCredential(credential)
        } else {
            credential
        }
        sessionCredential.set(resolved)
        return resolved
    }

    fun peekStoredCredential(): CodexOAuthCredential? = readStoredCredential()

    internal fun readStoredCredential(): CodexOAuthCredential? {
        val codexHome = codexHomePathProvider()
        return listOfNotNull(
            readKeychainCredential(codexHome),
            readFileCredential(codexHome),
        ).maxByOrNull { it.expiresAt.toEpochMilli() }
    }

    private fun readFileCredential(codexHome: Path): CodexOAuthCredential? {
        val authPath = codexHome.resolve(CODEX_AUTH_FILE_NAME)
        if (!Files.isRegularFile(authPath)) {
            return null
        }

        val payload = runCatching {
            objectMapper.readTree(Files.readString(authPath, StandardCharsets.UTF_8))
        }.getOrNull() as? ObjectNode ?: return null

        val fallbackExpiresAt = runCatching {
            Files.getLastModifiedTime(authPath).toInstant().plusSeconds(DEFAULT_FALLBACK_TTL_SECONDS)
        }.getOrElse {
            nowProvider().plusSeconds(DEFAULT_FALLBACK_TTL_SECONDS)
        }

        return toCredential(
            payload = payload,
            fallbackExpiresAt = fallbackExpiresAt,
            source = CodexOAuthCredentialSource.FILE,
            sourcePath = authPath,
            keychainAccount = null,
        )
    }

    private fun readKeychainCredential(codexHome: Path): CodexOAuthCredential? {
        if (!isMacOs()) {
            return null
        }

        val account = computeCodexKeychainAccount(codexHome.toString())
        val payloadText = keychainReader(account)?.trim().orEmpty()
        if (payloadText.isBlank()) {
            return null
        }

        val payload = runCatching {
            objectMapper.readTree(payloadText)
        }.getOrNull() as? ObjectNode ?: return null

        val fallbackExpiresAt = extractInstant(payload.path("last_refresh"))?.plusSeconds(DEFAULT_FALLBACK_TTL_SECONDS)
            ?: nowProvider().plusSeconds(DEFAULT_FALLBACK_TTL_SECONDS)

        return toCredential(
            payload = payload,
            fallbackExpiresAt = fallbackExpiresAt,
            source = CodexOAuthCredentialSource.KEYCHAIN,
            sourcePath = null,
            keychainAccount = account,
        )
    }

    private fun toCredential(
        payload: ObjectNode,
        fallbackExpiresAt: Instant,
        source: CodexOAuthCredentialSource,
        sourcePath: Path?,
        keychainAccount: String?,
    ): CodexOAuthCredential? {
        val tokens = payload.path("tokens")
        if (!tokens.isObject) {
            return null
        }

        val accessToken = tokens.path("access_token").asText("").trim()
        val refreshToken = tokens.path("refresh_token").asText("").trim()
        if (accessToken.isBlank() || refreshToken.isBlank()) {
            return null
        }

        val accountId = tokens.path("account_id").asText("").trim().ifBlank {
            extractAccountId(accessToken).orEmpty()
        }.ifBlank { null }

        return CodexOAuthCredential(
            accessToken = accessToken,
            refreshToken = refreshToken,
            accountId = accountId,
            clientId = extractClientId(accessToken) ?: DEFAULT_OPENAI_CLIENT_ID,
            expiresAt = extractAccessTokenExpiry(accessToken) ?: fallbackExpiresAt,
            source = source,
            rawPayload = payload.deepCopy(),
            sourcePath = sourcePath,
            keychainAccount = keychainAccount,
        )
    }

    private fun shouldRefresh(credential: CodexOAuthCredential): Boolean =
        credential.expiresAt <= nowProvider().plusSeconds(REFRESH_SKEW_SECONDS)

    private fun refreshCredential(current: CodexOAuthCredential): CodexOAuthCredential {
        val requestBody = buildRefreshFormBody(current)
        val request = HttpRequest.newBuilder(URI.create(OPENAI_OAUTH_TOKEN_URL))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
            .build()

        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        } catch (error: Exception) {
            throw AiCommitException("刷新 Codex OAuth 登录信息失败：${error.message ?: error.javaClass.simpleName}", error)
        }

        if (response.statusCode() !in 200..299) {
            throw AiCommitException(
                "刷新 Codex OAuth 登录信息失败（HTTP ${response.statusCode()}）：${extractErrorMessage(response.body())}",
            )
        }

        val refreshedPayload = try {
            objectMapper.readTree(response.body())
        } catch (error: Exception) {
            throw AiCommitException("Codex OAuth 刷新返回了无法解析的响应。", error)
        }

        val newAccessToken = refreshedPayload.path("access_token").asText("").trim()
        if (newAccessToken.isBlank()) {
            throw AiCommitException("Codex OAuth 刷新响应缺少 access_token。")
        }

        val newRefreshToken = refreshedPayload.path("refresh_token").asText("").trim().ifBlank {
            current.refreshToken
        }
        val newAccountId = extractAccountId(newAccessToken) ?: current.accountId
        val nextPayload = current.rawPayload.deepCopy().apply {
            put("last_refresh", nowProvider().toString())
            val tokensNode = path("tokens") as? ObjectNode ?: putObject("tokens")
            tokensNode.put("access_token", newAccessToken)
            tokensNode.put("refresh_token", newRefreshToken)
            val idToken = refreshedPayload.path("id_token").asText("").trim()
            if (idToken.isNotBlank()) {
                tokensNode.put("id_token", idToken)
            }
            if (!newAccountId.isNullOrBlank()) {
                tokensNode.put("account_id", newAccountId)
            }
        }

        persistRefreshedPayload(current, nextPayload)

        val expiresAt = refreshedPayload.path("expires_in").takeIf(JsonNode::canConvertToLong)?.asLong()?.takeIf { it > 0 }
            ?.let { nowProvider().plusSeconds(it) }
            ?: nowProvider().plusSeconds(DEFAULT_FALLBACK_TTL_SECONDS)

        return toCredential(
            payload = nextPayload,
            fallbackExpiresAt = expiresAt,
            source = current.source,
            sourcePath = current.sourcePath,
            keychainAccount = current.keychainAccount,
        ) ?: throw AiCommitException("刷新后的 Codex OAuth 凭证无效，请重新执行 `codex` 登录。")
    }

    private fun persistRefreshedPayload(current: CodexOAuthCredential, payload: ObjectNode) {
        val payloadText = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload) + System.lineSeparator()
        when (current.source) {
            CodexOAuthCredentialSource.KEYCHAIN -> {
                val account = current.keychainAccount
                if (!account.isNullOrBlank()) {
                    keychainWriter(account, payloadText)
                }
            }
            CodexOAuthCredentialSource.FILE -> {
                val path = current.sourcePath ?: return
                runCatching {
                    Files.createDirectories(path.parent)
                    Files.writeString(path, payloadText, StandardCharsets.UTF_8)
                }
            }
        }
    }

    private fun buildRefreshFormBody(current: CodexOAuthCredential): String {
        val entries = linkedMapOf(
            "grant_type" to "refresh_token",
            "refresh_token" to current.refreshToken,
            "client_id" to current.clientId.ifBlank { DEFAULT_OPENAI_CLIENT_ID },
        )
        return entries.entries.joinToString("&") { (key, value) ->
            "${urlEncode(key)}=${urlEncode(value)}"
        }
    }

    private fun extractErrorMessage(body: String): String {
        val parsed = runCatching {
            objectMapper.readTree(body)
        }.getOrNull()
        val errorDescription = parsed?.path("error_description")?.asText("").orEmpty()
        if (errorDescription.isNotBlank()) {
            return errorDescription
        }
        val errorMessage = parsed?.path("error")?.path("message")?.asText("").orEmpty()
        if (errorMessage.isNotBlank()) {
            return errorMessage
        }
        return body.ifBlank { "接口没有返回错误详情。" }
    }

    private fun extractAccessTokenExpiry(accessToken: String): Instant? = extractTokenExpiry(accessToken)

    private fun extractClientId(accessToken: String): String? = extractClientIdFromToken(accessToken)

    private fun extractAccountId(accessToken: String): String? = extractAccountIdFromToken(accessToken)

    private fun extractInstant(node: JsonNode): Instant? {
        val raw = node.asText("").trim()
        if (raw.isBlank()) {
            return null
        }
        return runCatching {
            Instant.parse(raw)
        }.getOrNull()
    }

    companion object {
        internal const val CODEX_AUTH_FILE_NAME = "auth.json"
        private const val DEFAULT_FALLBACK_TTL_SECONDS = 60 * 60L
        private const val REFRESH_SKEW_SECONDS = 60L
        private const val KEYCHAIN_SERVICE_NAME = "Codex Auth"
        internal const val OPENAI_OAUTH_TOKEN_URL = "https://auth.openai.com/oauth/token"
        internal const val OPENAI_AUTHORIZE_URL = "https://auth.openai.com/oauth/authorize"
        internal const val DEFAULT_OPENAI_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        internal const val OPENAI_AUTH_CLAIM_KEY = "https://api.openai.com/auth"
        internal const val DEFAULT_OPENAI_OAUTH_SCOPE =
            "openid profile email offline_access api.connectors.read api.connectors.invoke"
        internal const val DEFAULT_OPENAI_OAUTH_ORIGINATOR = "codex_cli_rs"
        private val sessionCredential = AtomicReference<CodexOAuthCredential?>(null)

        internal fun clearSessionCredentialCache() {
            sessionCredential.set(null)
        }

        internal fun resolveDefaultCodexHomePath(): Path {
            val configured = System.getenv("CODEX_HOME")?.trim().orEmpty()
            val rawPath = if (configured.isNotBlank()) {
                Paths.get(configured)
            } else {
                Paths.get(System.getProperty("user.home"), ".codex")
            }
            return runCatching {
                rawPath.toRealPath()
            }.getOrElse {
                rawPath.toAbsolutePath().normalize()
            }
        }

        internal fun computeCodexKeychainAccount(codexHomePath: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(codexHomePath.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
            return "cli|${digest.take(16)}"
        }

        internal fun decodeJwtPayload(token: String): JsonNode? {
            val segments = token.split('.')
            if (segments.size < 2) {
                return null
            }

            val payloadSegment = segments[1]
            val padded = payloadSegment + "=".repeat((4 - payloadSegment.length % 4) % 4)
            val decoded = runCatching {
                Base64.getUrlDecoder().decode(padded)
            }.getOrNull() ?: return null

            return runCatching {
                jacksonObjectMapper().readTree(String(decoded, StandardCharsets.UTF_8))
            }.getOrNull()
        }

        internal fun extractTokenExpiry(token: String): Instant? =
            decodeJwtPayload(token)?.path("exp")?.takeIf(JsonNode::canConvertToLong)?.asLong()?.let(Instant::ofEpochSecond)

        internal fun extractClientIdFromToken(token: String): String? =
            decodeJwtPayload(token)?.path("client_id")?.asText("")?.trim()?.ifBlank { null }

        internal fun extractAccountIdFromToken(token: String): String? {
            val payload = decodeJwtPayload(token) ?: return null
            val authNode = payload.path(OPENAI_AUTH_CLAIM_KEY)
            return listOf(
                authNode.path("chatgpt_account_id").asText("").trim(),
                authNode.path("account_id").asText("").trim(),
            ).firstOrNull { it.isNotBlank() }
        }

        private fun isMacOs(): Boolean = System.getProperty("os.name").lowercase().contains("mac")

        private fun readMacOsKeychainPayload(account: String): String? {
            if (!isMacOs()) {
                return null
            }
            val process = runCatching {
                ProcessBuilder(
                    "security",
                    "find-generic-password",
                    "-s",
                    KEYCHAIN_SERVICE_NAME,
                    "-a",
                    account,
                    "-w",
                )
                    .redirectErrorStream(true)
                    .start()
            }.getOrNull() ?: return null

            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            if (process.exitValue() != 0) {
                return null
            }
            return process.inputStream.bufferedReader(StandardCharsets.UTF_8).readText().trim().ifBlank { null }
        }

        private fun writeMacOsKeychainPayload(account: String, payload: String): Boolean {
            if (!isMacOs()) {
                return false
            }
            val process = runCatching {
                ProcessBuilder(
                    "security",
                    "add-generic-password",
                    "-U",
                    "-s",
                    KEYCHAIN_SERVICE_NAME,
                    "-a",
                    account,
                    "-w",
                    payload,
                )
                    .redirectErrorStream(true)
                    .start()
            }.getOrNull() ?: return false

            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return false
            }
            return process.exitValue() == 0
        }

        private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
    }
}
