package org.coolmentha.aicommit.ai

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.coolmentha.aicommit.settings.AiCommitAuthMode
import org.coolmentha.aicommit.settings.AiCommitSettingsState
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

class OpenAiCompatibleClient(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build(),
    private val codexOAuthCredentialsProvider: () -> CodexOAuthCredential = {
        CodexOAuthCredentialsResolver(httpClient = httpClient).resolve()
    },
) {
    private val objectMapper = jacksonObjectMapper()

    fun generateCommitMessage(settings: AiCommitSettingsState, prompt: String): String {
        if (!settings.isConfigured()) {
            val message = when (settings.authModeType()) {
                AiCommitAuthMode.API_KEY -> "AI 配置不完整，请先填写 API Key 和模型。"
                AiCommitAuthMode.CODEX_OAUTH -> "AI 配置不完整，请先填写模型，并在设置中完成 Codex OAuth 登录。"
            }
            throw AiCommitException(message)
        }

        return when (settings.authModeType()) {
            AiCommitAuthMode.API_KEY -> generateViaChatCompletions(settings, prompt)
            AiCommitAuthMode.CODEX_OAUTH -> generateViaCodexResponses(settings, prompt)
        }
    }

    private fun generateViaChatCompletions(settings: AiCommitSettingsState, prompt: String): String {
        val endpoint = resolveEndpoint(settings.normalizedApiBaseUrl())
        val requestBody = ChatCompletionRequest(
            model = settings.model.trim(),
            messages = listOf(
                ChatMessage("system", COMMIT_SYSTEM_PROMPT),
                ChatMessage("user", prompt),
            ),
            temperature = 0.2,
        )

        val request = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${settings.apiKey.trim()}")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody), StandardCharsets.UTF_8))
            .build()

        val response = sendRequest(endpoint, request)
        if (response.statusCode() !in 200..299) {
            throw AiCommitException("AI 接口请求失败（HTTP ${response.statusCode()}，请求 URL：$endpoint）：${extractErrorMessage(response.body())}")
        }

        val parsed = try {
            objectMapper.readValue(response.body(), ChatCompletionResponse::class.java)
        } catch (error: Exception) {
            throw AiCommitException("AI 接口返回了无法解析的响应。", error)
        }

        val content = parsed.choices.firstOrNull()?.message?.content?.trim().orEmpty()
        if (content.isBlank()) {
            throw AiCommitException(parsed.error?.message ?: "AI 没有返回有效的 commit message。")
        }
        return content
    }

    private fun generateViaCodexResponses(settings: AiCommitSettingsState, prompt: String): String {
        val credential = codexOAuthCredentialsProvider()
        val endpoint = resolveResponsesEndpoint(settings.normalizedApiBaseUrl())
        val requestBody = ResponsesRequest(
            model = settings.model.trim(),
            instructions = COMMIT_SYSTEM_PROMPT,
            input = listOf(
                ResponseInputItem(
                    role = "user",
                    content = listOf(
                        ResponseInputContentItem(text = prompt),
                    ),
                ),
            ),
            store = false,
            stream = true,
        )

        val builder = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .header("Authorization", "Bearer ${credential.accessToken}")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody), StandardCharsets.UTF_8))

        credential.accountId?.takeIf { it.isNotBlank() }?.let {
            builder.header("ChatGPT-Account-Id", it)
        }

        val response = sendRequest(endpoint, builder.build())
        if (response.statusCode() !in 200..299) {
            throw AiCommitException("AI 接口请求失败（HTTP ${response.statusCode()}，请求 URL：$endpoint）：${extractErrorMessage(response.body())}")
        }

        val parsed = try {
            parseResponsesPayload(response.body())
        } catch (error: Exception) {
            throw AiCommitException("AI 接口返回了无法解析的响应。", error)
        }

        val content = parsed.content
        if (content.isBlank()) {
            throw AiCommitException(parsed.errorMessage ?: "AI 没有返回有效的 commit message。")
        }
        return content
    }

    internal fun resolveEndpoint(apiBaseUrl: String): URI {
        val normalized = apiBaseUrl.trim().trimEnd('/')
        val endpoint = if (normalized.endsWith("/chat/completions")) {
            normalized
        } else {
            "$normalized/chat/completions"
        }
        return URI.create(endpoint)
    }

    internal fun resolveResponsesEndpoint(apiBaseUrl: String): URI {
        val normalized = apiBaseUrl.trim().trimEnd('/')
        val endpoint = when {
            normalized.endsWith("/codex/responses") || normalized.endsWith("/responses") -> normalized
            normalized.endsWith("/codex") -> "$normalized/responses"
            normalized.endsWith("/backend-api") -> "$normalized/codex/responses"
            else -> "$normalized/responses"
        }
        return URI.create(endpoint)
    }

    private fun sendRequest(endpoint: URI, request: HttpRequest): HttpResponse<String> {
        return try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        } catch (error: Exception) {
            throw AiCommitException("调用 AI 接口失败，请求 URL：$endpoint，原因：${error.message ?: error.javaClass.simpleName}", error)
        }
    }

    private fun extractErrorMessage(body: String): String {
        return listOfNotNull(
            runCatching { objectMapper.readValue(body, ChatCompletionResponse::class.java).error?.message }.getOrNull(),
            runCatching { objectMapper.readValue(body, DetailPayload::class.java).detail }.getOrNull(),
            runCatching { parseResponsesPayload(body).errorMessage }.getOrNull(),
        ).firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty().ifBlank {
            "接口没有返回错误详情。"
        }
    }

    private data class ChatCompletionRequest(
        val model: String,
        val messages: List<ChatMessage>,
        val temperature: Double,
    )

    private data class ChatMessage(
        val role: String,
        val content: String,
    )

    private data class ResponsesRequest(
        val model: String,
        val instructions: String,
        val input: List<ResponseInputItem>,
        val store: Boolean,
        val stream: Boolean,
    )

    private data class ResponseInputItem(
        val role: String,
        val content: List<ResponseInputContentItem>,
    )

    private data class ResponseInputContentItem(
        val type: String = "input_text",
        val text: String,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ChatCompletionResponse(
        val choices: List<Choice> = emptyList(),
        val error: ErrorPayload? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Choice(
        val message: MessagePayload? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class MessagePayload(
        val content: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ResponsesResponse(
        @field:JsonProperty("output_text")
        val outputText: String? = null,
        val output: List<ResponseOutputItem> = emptyList(),
        val error: ErrorPayload? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ResponseOutputItem(
        val content: List<ResponseContentItem> = emptyList(),
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ResponseContentItem(
        val text: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ErrorPayload(
        val message: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class DetailPayload(
        val detail: String? = null,
    )

    private data class ParsedResponsesPayload(
        val content: String,
        val errorMessage: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ResponsesStreamEvent(
        val type: String? = null,
        val delta: String? = null,
        val text: String? = null,
        val response: ResponsesResponse? = null,
        val error: ErrorPayload? = null,
        val detail: String? = null,
    )

    private fun parseResponsesPayload(body: String): ParsedResponsesPayload {
        val trimmed = body.trim()
        if (trimmed.startsWith("{") && (
                trimmed.contains("\"output_text\"") ||
                    trimmed.contains("\"output\"") ||
                    trimmed.contains("\"error\"")
                )
        ) {
            val response = objectMapper.readValue(trimmed, ResponsesResponse::class.java)
            return ParsedResponsesPayload(
                content = extractResponsesContent(response),
                errorMessage = response.error?.message?.trim()?.takeIf { it.isNotBlank() },
            )
        }

        if (trimmed.startsWith("data:") || trimmed.startsWith("event:") || trimmed.contains("\ndata:") || trimmed.contains("\nevent:")) {
            return extractResponsesStreamPayload(trimmed)
        }

        throw IllegalArgumentException("Unsupported response payload format.")
    }

    // Codex OAuth 成功响应可能是普通 JSON，也可能是 SSE 事件流。
    private fun extractResponsesStreamPayload(body: String): ParsedResponsesPayload {
        val content = StringBuilder()
        var fallbackContent = ""
        var errorMessage: String? = null

        extractServerSentEventDataBlocks(body).forEach { dataBlock ->
            val payload = dataBlock.trim()
            if (payload.isBlank() || payload == "[DONE]") {
                return@forEach
            }

            val event = runCatching {
                objectMapper.readValue(payload, ResponsesStreamEvent::class.java)
            }.getOrNull() ?: return@forEach

            event.error?.message?.trim()?.takeIf { it.isNotBlank() }?.let {
                errorMessage = it
            }
            event.detail?.trim()?.takeIf { it.isNotBlank() }?.let {
                errorMessage = it
            }
            event.delta?.let(content::append)
            if (content.isEmpty()) {
                event.text?.trim()?.takeIf { it.isNotBlank() }?.let {
                    fallbackContent = it
                }
            }
            event.response?.let(::extractResponsesContent)?.takeIf { it.isNotBlank() }?.let {
                fallbackContent = it
            }
        }

        return ParsedResponsesPayload(
            content = content.toString().trim().ifBlank { fallbackContent.trim() },
            errorMessage = errorMessage,
        )
    }

    private fun extractServerSentEventDataBlocks(body: String): List<String> {
        val blocks = mutableListOf<String>()
        val currentBlock = mutableListOf<String>()
        body.lineSequence().forEach { rawLine ->
            val line = rawLine.removeSuffix("\r")
            when {
                line.startsWith("data:") -> currentBlock += line.removePrefix("data:").trimStart()
                line.isBlank() && currentBlock.isNotEmpty() -> {
                    blocks += currentBlock.joinToString("\n")
                    currentBlock.clear()
                }
            }
        }
        if (currentBlock.isNotEmpty()) {
            blocks += currentBlock.joinToString("\n")
        }
        return blocks
    }

    private fun extractResponsesContent(response: ResponsesResponse): String {
        response.outputText?.trim()?.takeIf { it.isNotBlank() }?.let {
            return it
        }

        return response.output
            .flatMap { it.content }
            .mapNotNull { it.text?.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .trim()
    }

    companion object {
        private const val COMMIT_SYSTEM_PROMPT = "你负责根据代码差异生成 commit message，只输出最终提交信息。"
    }
}
