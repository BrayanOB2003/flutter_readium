package dk.nota.flutterreadium

/**
 * Injected into each EPUB resource on Android.
 *
 * Book mode: while text is selected, the column stays on the page where the
 * selection started. Dragging a handle to the edge, or the menu asking for room,
 * would otherwise turn the page. The page is noted before the selection exists,
 * because the WebView can scroll before the first `selectionchange`.
 * Scroll mode: a short tap outside the highlight clears the selection, which
 * book mode already does on its own.
 *
 * Keep the book-mode part in step with `SelectionPageLock.swift`.
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

      function hasSelection() {
        var sel = window.getSelection();
        return !!sel && !sel.isCollapsed;
      }

      function position() {
        var el = scrollingElement();
        return { left: el.scrollLeft, top: el.scrollTop };
      }

      var page = null;
      var locked = null;
      var touching = false;

      function notePage() {
        if (!locked) page = position();
      }

      function release() {
        locked = null;
        page = position();
      }

      document.addEventListener('selectionchange', function () {
        if (!hasSelection() || scrollMode()) {
          release();
          return;
        }
        if (!locked) locked = page || position();
      }, true);

      window.addEventListener('scroll', function () {
        if (!locked) {
          if (!touching) notePage();
          return;
        }
        if (!hasSelection() || scrollMode()) {
          release();
          return;
        }
        var el = scrollingElement();
        if (el.scrollLeft !== locked.left || el.scrollTop !== locked.top) {
          el.scrollTo(locked.left, locked.top);
        }
      }, true);

      var selectionEmptyAtPointerDown = true;
      function notePointerDown() {
        selectionEmptyAtPointerDown = !hasSelection();
        if (selectionEmptyAtPointerDown) notePage();
        touching = true;
      }
      function notePointerUp() {
        touching = false;
      }
      document.addEventListener('touchstart', notePointerDown, true);
      document.addEventListener('mousedown', notePointerDown, true);
      document.addEventListener('touchend', notePointerUp, true);
      document.addEventListener('touchcancel', notePointerUp, true);
      document.addEventListener('mouseup', notePointerUp, true);

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
