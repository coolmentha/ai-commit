package org.coolmentha.aicommit.ai

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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
) {
    private val objectMapper = jacksonObjectMapper()

    fun generateCommitMessage(settings: AiCommitSettingsState, prompt: String): String {
        if (!settings.isConfigured()) {
            throw AiCommitException("AI 配置不完整，请先填写 API Key 和模型。")
        }

        val endpoint = resolveEndpoint(settings.normalizedApiBaseUrl())
        val requestBody = ChatCompletionRequest(
            model = settings.model.trim(),
            messages = listOf(
                ChatMessage("system", "你负责根据代码差异生成 commit message，只输出最终提交信息。"),
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

        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        } catch (error: Exception) {
            throw AiCommitException("调用 AI 接口失败，请求 URL：$endpoint，原因：${error.message ?: error.javaClass.simpleName}", error)
        }

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

    internal fun resolveEndpoint(apiBaseUrl: String): URI {
        val normalized = apiBaseUrl.trim().trimEnd('/')
        val endpoint = if (normalized.endsWith("/chat/completions")) {
            normalized
        } else {
            "$normalized/chat/completions"
        }
        return URI.create(endpoint)
    }

    private fun extractErrorMessage(body: String): String {
        return try {
            objectMapper.readValue(body, ChatCompletionResponse::class.java).error?.message ?: body
        } catch (_: Exception) {
            body
        }.ifBlank {
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
    private data class ErrorPayload(
        val message: String? = null,
    )
}
