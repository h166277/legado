package io.legado.app.help.readaloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookDubbingChapterHelperTest {

    @Test
    fun splitParagraphs_filtersBlankAndCleans() {
        val raw = """
            第一段内容。
            
            第二段内容。
            
            <p>第三段带标签</p>
        """.trimIndent()
        val parts = BookDubbingChapterHelper.splitParagraphs(raw)
        assertEquals(3, parts.size)
        assertTrue(parts[0].contains("第一段"))
        assertTrue(parts[2].contains("第三段"))
        assertTrue(parts.none { it.contains("<p>") })
    }
}
