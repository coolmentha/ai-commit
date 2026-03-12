package org.coolmentha.aicommit.template

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PromptTemplateRendererTest {
    @Test
    fun `渲染时会替换全部变量`() {
        val rendered = PromptTemplateRenderer.render(
            "branch=\${branch}\nformat=\${format_instruction}\ndiff=\${diff}",
            PromptTemplateVariables(
                diff = "diff --git a/a.txt b/a.txt",
                branch = "feature/demo",
                formatInstruction = "use conventional commits",
            ),
        )

        assertTrue(rendered.contains("feature/demo"))
        assertTrue(rendered.contains("use conventional commits"))
        assertTrue(rendered.contains("diff --git a/a.txt b/a.txt"))
    }

    @Test
    fun `空模板使用默认模板`() {
        val rendered = PromptTemplateRenderer.render(
            "",
            PromptTemplateVariables(
                diff = "sample diff",
                branch = "main",
                formatInstruction = "sample format",
            ),
        )

        assertTrue(rendered.contains("sample diff"))
        assertTrue(rendered.contains("main"))
    }

    @Test
    fun `prompt 模板必须包含 diff 变量`() {
        val error = PromptTemplateRenderer.validateTemplate("only \${branch}")
        assertNotNull(error)
        assertTrue(error.contains("\${diff}"))
    }

    @Test
    fun `空 prompt 模板允许使用默认值`() {
        assertNull(PromptTemplateRenderer.validateTemplate(""))
    }
}
