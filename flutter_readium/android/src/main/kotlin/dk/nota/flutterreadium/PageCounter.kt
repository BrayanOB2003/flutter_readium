package dk.nota.flutterreadium

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Layout
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.epub.EpubLayout
import org.readium.r2.shared.publication.epub.layout
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.mediatype.MediaType
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.math.roundToInt

internal class PageCountSupersededException : Exception()

/**
 * Counts pages in page-reading mode for the open publication.
 *
 * Reflowable chapters are laid out in a hidden WebView at the reader size.
 * The total is the sum of those pages. A newer [token] abandons the run.
 */
@OptIn(ExperimentalReadiumApi::class)
internal object PageCounter {
    private const val HOST = "pagecount.local"
    private const val CHAPTER_TIMEOUT_MS = 8_000L
    private val activeToken = AtomicInteger(0)
    private var abandon: (() -> Unit)? = null

    fun cancel(token: Int) {
        activeToken.set(token)
        abandon?.invoke()
    }

    suspend fun count(
        activity: Activity,
        publication: Publication,
        arguments: Map<*, *>,
    ): Int {
        val token = (arguments["token"] as? Number)?.toInt() ?: throw PageCountSupersededException()
        activeToken.set(token)
        check(token)

        if (publication.conformsTo(Publication.Profile.AUDIOBOOK)) {
            throw PublicationErrorException(PublicationError.Unavailable("This publication has no pages."))
        }
        if (!publication.isReflowableEpub()) {
            val declared = publication.metadata.numberOfPages
            if (declared != null && declared > 0) return declared
            return publication.readingOrder.size
        }

        val viewportWidth = (arguments["viewportWidth"] as? Number)?.toDouble() ?: 0.0
        val viewportHeight = (arguments["viewportHeight"] as? Number)?.toDouble() ?: 0.0
        if (viewportWidth <= 0.0 || viewportHeight <= 0.0) {
            throw PublicationErrorException(PublicationError.InvalidArgument("viewportWidth and viewportHeight are required"))
        }
        val fontSize = (arguments["fontSize"] as? Number)?.toDouble() ?: 1.0
        val pageMargins = (arguments["pageMargins"] as? Number)?.toDouble()
        val lineHeight = (arguments["lineHeight"] as? Number)?.toDouble()
        val columnCount = arguments["columnCount"] as? String ?: "auto"
        val publisherStyles = arguments["publisherStyles"] as? Boolean ?: false

        val css = loadCss(activity, publisherStyles)
        val style = userStyle(fontSize, pageMargins, lineHeight, columnCount)
        val density = activity.resources.displayMetrics.density
        val widthPx = (viewportWidth * density).roundToInt().coerceAtLeast(1)
        val heightPx = (viewportHeight * density).roundToInt().coerceAtLeast(1)

        return withContext(Dispatchers.Main) {
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            val webView = WebView(activity)
            var closed = false
            fun close() {
                if (closed) return
                closed = true
                webView.stopLoading()
                webView.webViewClient = WebViewClient()
                root.removeView(webView)
                webView.destroy()
            }
            try {
                webView.settings.javaScriptEnabled = true
                webView.isFocusable = false
                webView.isClickable = false
                webView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                webView.translationX = widthPx + 10000f
                root.addView(
                    webView,
                    0,
                    ViewGroup.LayoutParams(widthPx, heightPx),
                )
                webView.webViewClient =
                    ResourceClient(publication) {
                        activeToken.get() != token
                    }
                var total = 0
                for (link in publication.readingOrder) {
                    check(token)
                    total +=
                        if (link.isHtmlResource()) {
                            measureChapter(webView, publication, link, css, style, token)
                        } else {
                            1
                        }
                    delay(16)
                }
                total
            } finally {
                close()
            }
        }
    }

    private suspend fun measureChapter(
        webView: WebView,
        publication: Publication,
        link: Link,
        css: String,
        style: String,
        token: Int,
    ): Int {
        check(token)
        val href = link.href.toString().substringBefore('#').substringBefore('?')
        val bytes =
            withContext(Dispatchers.IO) {
                val url = Url(href) ?: return@withContext null
                publication.get(url)?.read()?.getOrElse { null }
            }
        if (bytes == null) return 1
        val html = inject(String(bytes, Charsets.UTF_8), css, style)
        val directory = href.substringBeforeLast('/', "")
        val base =
            if (directory.isEmpty()) {
                "https://$HOST/"
            } else {
                "https://$HOST/$directory/"
            }
        val pages =
            withTimeoutOrNull(CHAPTER_TIMEOUT_MS) {
                loadPages(webView, html, base, token)
            }
        check(token)
        return if (pages == null || pages < 1) 1 else pages
    }

    private suspend fun loadPages(
        webView: WebView,
        html: String,
        baseUrl: String,
        token: Int,
    ): Int =
        suspendCancellableCoroutine { continuation ->
            val resumed = AtomicBoolean(false)
            fun finish(value: Int) {
                if (!resumed.compareAndSet(false, true)) return
                abandon = null
                if (continuation.isActive) {
                    runCatching { continuation.resume(value) }
                }
            }
            abandon = {
                webView.post {
                    if (webView.parent != null) {
                        webView.stopLoading()
                        finish(-1)
                    }
                }
            }
            val client = webView.webViewClient as ResourceClient
            client.loadId += 1
            client.onFinished = {
                if (activeToken.get() != token) {
                    finish(-1)
                } else {
                    webView.evaluateJavascript(PAGE_COUNT_JS) { raw ->
                        val pages = raw?.trim('"')?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                        finish(pages)
                    }
                }
            }
            continuation.invokeOnCancellation { webView.stopLoading() }
            webView.loadDataWithBaseURL(baseUrl, html, "application/xhtml+xml", "utf-8", null)
        }

    private fun check(token: Int) {
        if (activeToken.get() != token) throw PageCountSupersededException()
    }

    private fun loadCss(
        activity: Activity,
        publisherStyles: Boolean,
    ): String {
        val names =
            buildList {
                add("readium/readium-css/ReadiumCSS-before.css")
                if (!publisherStyles) add("readium/readium-css/ReadiumCSS-default.css")
                add("readium/readium-css/ReadiumCSS-after.css")
            }
        val loaded =
            names.mapNotNull { name ->
                runCatching { activity.assets.open(name).bufferedReader().use { it.readText() } }.getOrNull()
            }
        return (loaded + PAGE_LAYOUT_CSS).joinToString("\n")
    }

    private class ResourceClient(
        private val publication: Publication,
        private val cancelled: () -> Boolean,
    ) : WebViewClient() {
        var onFinished: (() -> Unit)? = null
        var loadId: Int = 0

        override fun onPageFinished(
            view: WebView?,
            url: String?,
        ) {
            if (cancelled()) return
            val finishedLoad = loadId
            view?.postDelayed({
                if (finishedLoad != loadId || cancelled()) return@postDelayed
                onFinished?.invoke()
            }, 50)
        }

        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?,
        ): WebResourceResponse? {
            val uri = request?.url ?: return null
            if (uri.host != HOST) return null
            val path = uri.path?.trimStart('/')?.substringBefore('?').orEmpty()
            if (path.isEmpty()) return null
            val bytes = read(path) ?: return null
            val mime = mimeFor(path)
            val encoding = if (mime.startsWith("text/") || mime.contains("xml") || mime.contains("javascript")) "utf-8" else null
            return WebResourceResponse(mime, encoding, ByteArrayInputStream(bytes))
        }

        private fun read(path: String): ByteArray? =
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                val url = Url(path) ?: return@runBlocking null
                publication.get(url)?.read()?.getOrElse { null }
            }

        private fun mimeFor(path: String): String {
            val lower = path.substringBefore('?').lowercase()
            return when {
                lower.endsWith(".css") -> "text/css"
                lower.endsWith(".js") -> "text/javascript"
                lower.endsWith(".png") -> "image/png"
                lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
                lower.endsWith(".gif") -> "image/gif"
                lower.endsWith(".svg") -> "image/svg+xml"
                lower.endsWith(".webp") -> "image/webp"
                lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm") -> "application/xhtml+xml"
                else -> "application/octet-stream"
            }
        }
    }
}

@OptIn(ExperimentalReadiumApi::class)
private fun Publication.isReflowableEpub(): Boolean {
    if (!conformsTo(Publication.Profile.EPUB)) return false
    if (metadata.layout == Layout.FIXED) return false
    if (readingOrder.isNotEmpty() && readingOrder.all { it.properties.layout == EpubLayout.FIXED }) return false
    return true
}

private fun Link.isHtmlResource(): Boolean {
    val type = mediaType
    if (type != null && (type.matches(MediaType.HTML) || type.matches(MediaType.XHTML))) return true
    val href = this.href.toString().substringBefore('?').lowercase()
    return href.endsWith(".html") || href.endsWith(".xhtml") || href.endsWith(".htm")
}

private fun userStyle(
    fontSize: Double,
    pageMargins: Double?,
    lineHeight: Double?,
    columnCount: String,
): String {
    val columns =
        when (columnCount) {
            "2" -> "--RS__colCount: 2; column-count: 2 !important; column-width: auto !important;"
            else -> "--RS__colCount: 1; column-count: 1 !important; column-width: 100vw !important;"
        }
    val margins = pageMargins?.let { "--USER__pageMargins: $it;" }.orEmpty()
    val leading =
        lineHeight?.let {
            "--USER__advancedSettings: readium-advanced-on; --USER__lineHeight: $it;"
        }.orEmpty()
    return "--USER__fontSize: ${fontSize * 100}%; $margins $leading $columns"
}

private fun inject(
    html: String,
    css: String,
    style: String,
): String {
    val styled = addHtmlStyle(html, style)
    val block = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><style>$css</style>"
    val head = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(styled)
    if (head != null) {
        val at = head.range.last + 1
        return styled.substring(0, at) + block + styled.substring(at)
    }
    return "<head>$block</head>$styled"
}

private fun addHtmlStyle(
    html: String,
    style: String,
): String {
    val match = Regex("<html\\b([^>]*)>", RegexOption.IGNORE_CASE).find(html) ?: return html
    val attrs = match.groupValues[1]
    val existing = Regex("style\\s*=\\s*([\"'])(.*?)\\1", RegexOption.IGNORE_CASE).find(attrs)
    val replacedAttrs =
        if (existing == null) {
            "$attrs style=\"$style\""
        } else {
            val quote = existing.groupValues[1]
            attrs.replace(existing.value, "style=$quote${existing.groupValues[2]}; $style$quote")
        }
    return html.replaceRange(match.range, "<html$replacedAttrs>")
}

private const val PAGE_LAYOUT_CSS =
    """
    html {
      height: 100vh !important;
      margin: 0 !important;
      column-gap: 0 !important;
      column-fill: auto !important;
      overflow-x: auto !important;
    }
    body { margin: 0 !important; }
    """

private const val PAGE_COUNT_JS =
    "(function(){var root=document.scrollingElement||document.documentElement;var width=root.clientWidth;if(!width)return 1;return Math.max(1,Math.round(root.scrollWidth/width));})();"
