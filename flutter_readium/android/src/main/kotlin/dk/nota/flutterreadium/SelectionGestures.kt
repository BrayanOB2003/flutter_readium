package dk.nota.flutterreadium

/**
 * Injected into each EPUB resource on Android.
 *
 * Book mode: while text is selected, put the column back if the WebView scrolls
 * it to make room for the menu. Scroll mode: a short tap outside the highlight
 * clears the selection, which book mode already does on its own.
 */
internal const val SELECTION_GESTURES_JS =
    """
    (function () {
      if (window.__flutterReadiumSelectionGestures) return;
      window.__flutterReadiumSelectionGestures = true;

      function scrollMode() {
        var style = document.documentElement.style;
        var view = (style.getPropertyValue('--USER__view') || '').trim();
        var legacy = (style.getPropertyValue('--USER__scroll') || '').trim();
        return view === 'readium-scroll-on' || legacy === 'readium-scroll-on';
      }

      function scrollingElement() {
        return document.scrollingElement || document.documentElement;
      }

      var locked = null;
      document.addEventListener('selectionchange', function () {
        var sel = window.getSelection();
        if (!sel || sel.isCollapsed || scrollMode()) {
          locked = null;
          return;
        }
        if (!locked) {
          var el = scrollingElement();
          locked = { left: el.scrollLeft, top: el.scrollTop };
        }
      }, true);

      window.addEventListener('scroll', function () {
        if (!locked) return;
        var sel = window.getSelection();
        if (!sel || sel.isCollapsed || scrollMode()) {
          locked = null;
          return;
        }
        var el = scrollingElement();
        if (el.scrollLeft !== locked.left || el.scrollTop !== locked.top) {
          el.scrollTo(locked.left, locked.top);
        }
      }, true);

      var selectionEmptyAtPointerDown = true;
      function notePointerDown() {
        var sel = window.getSelection();
        selectionEmptyAtPointerDown = !sel || sel.isCollapsed;
      }
      document.addEventListener('touchstart', notePointerDown, true);
      document.addEventListener('mousedown', notePointerDown, true);

      document.addEventListener('click', function (event) {
        if (!scrollMode() || selectionEmptyAtPointerDown) return;
        var sel = window.getSelection();
        if (!sel || sel.isCollapsed || sel.rangeCount === 0) return;
        var range = sel.getRangeAt(0);
        var rects = range.getClientRects();
        for (var i = 0; i < rects.length; i++) {
          var rect = rects[i];
          if (event.clientX >= rect.left && event.clientX <= rect.right &&
              event.clientY >= rect.top && event.clientY <= rect.bottom) {
            return;
          }
        }
        event.preventDefault();
        event.stopPropagation();
        sel.removeAllRanges();
      }, true);
    })();
    """
