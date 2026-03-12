package org.coolmentha.aicommit.template

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommitFormatValidatorTest {
    @Test
    fun `空模板直接通过`() {
        val result = CommitFormatValidator.validateMessage("", "feat: add feature")
        assertTrue(result.isValid)
    }

    @Test
    fun `合法模板校验通过`() {
        val result = CommitFormatValidator.validateMessage("{{type}}({{scope}}): {{subject}}", "feat(commit): add validator")
        assertTrue(result.isValid)
    }

    @Test
    fun `不匹配模板时返回失败`() {
        val result = CommitFormatValidator.validateMessage("{{type}}: {{subject}}", "feat add validator")
        assertFalse(result.isValid)
        assertEquals("AI 返回内容不符合提交格式模板。", result.message)
    }

    @Test
    fun `模板语法检查会拒绝空占位符`() {
        val error = CommitFormatValidator.validateTemplate("{{}}: {{subject}}")
        assertEquals("提交格式模板中存在空占位符。请使用 `{{字段名}}` 语法。", error)
    }

    @Test
    fun `模板语法检查允许空模板`() {
        assertNull(CommitFormatValidator.validateTemplate(""))
    }

    @Test
    fun `格式说明会包含模板原文`() {
        val instruction = CommitFormatValidator.buildInstruction("{{type}}: {{subject}}")
        assertTrue(instruction.contains("{{type}}: {{subject}}"))
    }
}
