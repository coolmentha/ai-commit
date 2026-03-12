package org.coolmentha.aicommit.template

data class FormatValidationResult(
    val isValid: Boolean,
    val message: String? = null,
)

object CommitFormatValidator {
    fun validateTemplate(template: String): String? {
        if (template.isBlank()) {
            return null
        }
        return try {
            parse(template)
            null
        } catch (error: IllegalArgumentException) {
            error.message
        }
    }

    fun validateMessage(template: String, message: String): FormatValidationResult {
        if (template.isBlank()) {
            return FormatValidationResult(true)
        }

        val parsed = try {
            parse(template)
        } catch (error: IllegalArgumentException) {
            return FormatValidationResult(false, error.message)
        }

        val regex = parsed.toRegex(RegexOption.DOT_MATCHES_ALL)
        return if (regex.matches(message.trim())) {
            FormatValidationResult(true)
        } else {
            FormatValidationResult(false, "AI 返回内容不符合提交格式模板。")
        }
    }

    fun buildInstruction(template: String): String =
        if (template.isBlank()) {
            "未配置固定格式，请输出一条可直接提交的 commit message。"
        } else {
            "输出必须严格匹配以下模板，`{{字段名}}` 需要替换为真实内容，不能保留花括号：\n$template"
        }

    private fun parse(template: String): ParsedFormatTemplate {
        val segments = mutableListOf<FormatSegment>()
        var cursor = 0
        while (cursor < template.length) {
            val start = template.indexOf("{{", cursor)
            if (start < 0) {
                segments += FormatSegment.Literal(template.substring(cursor))
                break
            }

            if (start > cursor) {
                segments += FormatSegment.Literal(template.substring(cursor, start))
            }

            val end = template.indexOf("}}", start + 2)
            require(end >= 0) { "提交格式模板存在未闭合的占位符。请使用 `{{字段名}}` 语法。" }

            val name = template.substring(start + 2, end).trim()
            require(name.isNotEmpty()) { "提交格式模板中存在空占位符。请使用 `{{字段名}}` 语法。" }
            require(!name.contains('{') && !name.contains('}')) { "提交格式模板中的占位符名称不合法：$name" }

            segments += FormatSegment.Placeholder(name)
            cursor = end + 2
        }

        require(segments.any { it is FormatSegment.Placeholder }) {
            "提交格式模板至少需要包含一个 `{{字段名}}` 占位符。"
        }

        return ParsedFormatTemplate(segments)
    }

    private sealed interface FormatSegment {
        data class Literal(val text: String) : FormatSegment
        data class Placeholder(val name: String) : FormatSegment
    }

    private data class ParsedFormatTemplate(val segments: List<FormatSegment>) {
        fun toRegex(option: RegexOption): Regex {
            val pattern = buildString {
                append("^")
                for (segment in segments) {
                    when (segment) {
                        is FormatSegment.Literal -> append(Regex.escape(segment.text))
                        is FormatSegment.Placeholder -> append("(.+?)")
                    }
                }
                append("$")
            }
            return Regex(pattern, setOf(option))
        }
    }
}
