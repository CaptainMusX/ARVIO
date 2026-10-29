package com.arflix.tv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class EmojiPresentationTest {

    @Test
    fun `addon symbols ask for the colour emoji`() {
        assertEquals("[PM⚡️] Comet", withEmojiPresentation("[PM⚡] Comet"))
        assertEquals("[PM⏳️]", withEmojiPresentation("[PM⏳]"))
    }

    @Test
    fun `existing variation selectors are kept`() {
        assertEquals("⚡️", withEmojiPresentation("⚡️"))
        assertEquals("⚡︎", withEmojiPresentation("⚡︎"))
    }

    @Test
    fun `text without such symbols is returned unchanged`() {
        val text = "Torrentio ▶ 1080p 🔌"
        assertSame(text, withEmojiPresentation(text))
    }
}
