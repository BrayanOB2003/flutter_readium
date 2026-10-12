package dk.nota.flutterreadium

import android.webkit.JavascriptInterface
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * Android JavaScript interface exposed as `window.tableViewer` in the EPUB WebView.
 *
 * The helper calls `window.flutterReadiumOpenTable(json)`, which the bootstrap
 * shim forwards here. The JSON is the table markup plus the reader theme.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TableViewerInterface(
    private val reader: ReadiumReader,
) {
    @JavascriptInterface
    fun open(json: String) {
        val channel = reader.currentReaderWidget?.channel
        if (channel == null) {
            PluginLog.w(TAG, "::open. No reader channel available.")
            return
        }
        PluginLog.d(TAG, "::open. ${json.length} chars")
        channel.onTableOpen(json)
    }

    companion object {
        private const val TAG = "TableViewerInterface"

        const val JS_NAME = "tableViewer"
    }
}
