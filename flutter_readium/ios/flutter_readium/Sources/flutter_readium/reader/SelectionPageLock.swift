/// Injected into each EPUB resource on iOS.
///
/// Book mode: while text is selected, the column stays on the page where the
/// selection started. Dragging a handle to the edge would otherwise scroll the
/// paginated column and turn the page. The page is noted before the selection
/// exists, because WebKit can scroll before the first `selectionchange`.
///
/// Keep in step with the book-mode part of `SelectionGestures.kt` on Android.
let selectionPageLockScript = """
  (function () {
    if (window.__flutterReadiumSelectionPageLock) return;
    window.__flutterReadiumSelectionPageLock = true;

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

    function notePointerDown() {
      if (!hasSelection()) notePage();
      touching = true;
    }
    function notePointerUp() {
      touching = false;
    }
    document.addEventListener('touchstart', notePointerDown, { capture: true, passive: true });
    document.addEventListener('mousedown', notePointerDown, true);
    document.addEventListener('touchend', notePointerUp, true);
    document.addEventListener('touchcancel', notePointerUp, true);
    document.addEventListener('mouseup', notePointerUp, true);
  })();
  """
