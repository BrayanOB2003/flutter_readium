# Book-mode page turns call these by name. R2WebView is final, so the
# reader's root view reaches them after the WebView has handled the touch.
-keepclassmembers class org.readium.r2.navigator.R2WebView {
    void setCurrentItem(int, boolean);
}
-keepclassmembers class org.readium.r2.navigator.R2BasicWebView {
    void scrollLeft(boolean);
    void scrollRight(boolean);
    boolean getScrollMode();
    boolean isSelecting;
}

# PdfReaderFragment swaps PdfView's internal render scope by name to add an
# exception handler (androidx.pdf 1.0.0-beta01 has none).
-keepclassmembers class androidx.pdf.view.PdfView {
    kotlinx.coroutines.CoroutineScope getBackgroundScope$pdf_viewer();
    void setBackgroundScope$pdf_viewer(kotlinx.coroutines.CoroutineScope);
}
