package me.danielstiner.dumble.mumble

import org.junit.Assert.assertEquals
import org.junit.Test

class StripHtmlTest {
    @Test fun stripsTagsAndUnescapes() {
        assertEquals("hi there", stripHtml("<p>hi <b>there</b></p>"))
        assertEquals("a & b < c", stripHtml("a &amp; b &lt; c"))
        assertEquals("plain", stripHtml("plain"))
        assertEquals("x y", stripHtml("x   \n  y"))
        assertEquals("a &lt; b", stripHtml("a &amp;lt; b"))          // &amp; unescaped LAST → no double-unescape
        assertEquals("Bold Italic", stripHtml("<b>Bold</b><i>Italic</i>"))   // adjacent tags → space, not glued
    }
}
