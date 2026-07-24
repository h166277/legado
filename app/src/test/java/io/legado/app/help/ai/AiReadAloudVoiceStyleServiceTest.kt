package io.legado.app.help.ai

import io.legado.app.data.entities.BookCharacter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiReadAloudVoiceStyleServiceTest {

    @Test
    fun normalizeStyle_keepsFirstUsefulLine() {
        val raw = """
            示例：
            男性，约45岁，40%低沉磁性，35%威严浑厚，25%冷峻疏离。
            不要输出解释
        """.trimIndent()
        val style = AiReadAloudVoiceStyleService.normalizeStyle(raw)
        assertTrue(style.contains("低沉磁性"))
        assertFalse(style.startsWith("示例"))
    }

    @Test
    fun fallbackStyle_forNarrator() {
        val character = BookCharacter(name = "旁白", gender = BookCharacter.GENDER_MALE)
        val style = AiReadAloudVoiceStyleService.fallbackStyle(character)
        assertTrue(style.contains("旁白") || style.contains("叙述") || style.contains("男性"))
        assertTrue(style.contains("%"))
    }

    @Test
    fun previewSentence_includesName() {
        val character = BookCharacter(name = "陈珩", personality = "男性，约25岁，40%清朗，30%沉稳，30%干净。")
        val text = AiReadAloudVoiceStyleService.previewSentence(character)
        assertTrue(text.contains("陈珩"))
        assertEquals(true, text.length > 4)
    }
}
