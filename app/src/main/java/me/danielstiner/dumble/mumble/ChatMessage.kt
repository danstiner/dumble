package me.danielstiner.dumble.mumble

/** One chat line for the in-call chat log. [sender] is resolved at receive time so it stays correct
 *  even if that user later leaves; [mine] marks our own sent messages. */
data class ChatMessage(val sender: String, val text: String, val mine: Boolean, val timestampMs: Long)

private val TAG_RE = Regex("<[^>]*>")
private val WS_RE = Regex("\\s+")

/** Strip HTML tags and unescape common entities for plain-text chat display. Good enough for v1
 *  (Mumble messages "may be HTML"); rich rendering is a deferred follow-up. */
fun stripHtml(s: String): String =
    TAG_RE.replace(s, " ")
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .let { WS_RE.replace(it, " ") }
        .trim()
