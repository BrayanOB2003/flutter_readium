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
 * In book mode the WebView scrolls the column while a handle is dragged, and
 * Readium's `R2BasicWebView` drops that scroll while text is selected
 * (readium/kotlin-toolkit#325), so the column bounces back. Each bounce moves
 * the text under the handle and the selection cycles between pages while the
 * menu stays hidden. A column left moved is undone once scrolling and the
 * selection have been still for `SNAP_DELAY_MS`, or when the selection ends.
 * Book mode does not use Chromium's handle drag. That drag maps points in the
 * upper part of a column to the previous page and then scrolls to reveal them,
 * which fights Readium's scroll block. The handles and the highlight are drawn
 * here, and a hit is kept only when it lands on the line under the finger.
 * The DOM selection is installed when the finger lifts, so the system menu can
 * copy, share, and save the quote. Chromium's own handles are hidden by
 * `SelectionHandleGuard`.
 *
 * Keep the book-mode part in step with `SelectionPageLock.swift`, except for
 * the delay, which WebKit does not need.
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

      function diag(kind, extra) {
        var sel = window.getSelection();
        var el = scrollingElement();
        var info = kind + ' t=' + Math.round(performance.now()) +
          ' scroll=' + Math.round(el.scrollLeft) + ',' + Math.round(el.scrollTop);
        var vv = window.visualViewport;
        if (vv) {
          info += ' vv=' + Math.round(vv.offsetLeft) + ',' + Math.round(vv.offsetTop) + '@' + vv.scale.toFixed(2);
        }
        if (sel && sel.rangeCount > 0 && !sel.isCollapsed) {
          var focusText = (sel.focusNode && sel.focusNode.textContent) || '';
          var rects = sel.getRangeAt(0).getClientRects();
          var last = rects.length ? rects[rects.length - 1] : null;
          info += ' anchor=' + sel.anchorOffset + ' focus=' + sel.focusOffset +
            ' at="' + focusText.substr(Math.max(0, sel.focusOffset - 8), 16).replace(/\s+/g, ' ') + '"' +
            ' len=' + sel.toString().length + ' rects=' + rects.length;
          if (last) info += ' end=' + Math.round(last.right) + ',' + Math.round(last.bottom);
        }
        console.log('SelDiag js ' + info + (extra || ''));
      }

      document.addEventListener('selectionchange', function () {
        if (hasSelection()) diag('selection');
      }, true);

      if (typeof MutationObserver !== 'undefined') {
        new MutationObserver(function (records) {
          if (!hasSelection()) return;
          var first = records[0];
          var added = first.addedNodes;
          if (added) {
            for (var addedIndex = 0; addedIndex < added.length; addedIndex++) {
              var addedNode = added[addedIndex];
              if (addedNode.nodeType === 1 && addedNode.hasAttribute &&
                  addedNode.hasAttribute('data-fr-selection')) return;
            }
          }
          var target = first.target;
          var name = target.nodeName + (target.id ? '#' + target.id : '') +
            (target.className && typeof target.className === 'string' ? '.' + target.className.split(' ')[0] : '');
          diag('mutation', ' count=' + records.length + ' type=' + first.type +
            (first.attributeName ? ' attr=' + first.attributeName : '') + ' target=' + name);
        }).observe(document.documentElement, { attributes: true, childList: true, subtree: true });
      }

      window.addEventListener('resize', function () {
        if (hasSelection()) diag('resize', ' inner=' + window.innerWidth + 'x' + window.innerHeight);
      }, true);

      var SNAP_DELAY_MS = 150;
      var page = null;
      var locked = null;
      var touching = false;
      var snapTimer = null;

      function notePage() {
        if (!locked) page = position();
      }

      function cancelSnap() {
        if (snapTimer === null) return;
        clearTimeout(snapTimer);
        snapTimer = null;
      }

      function restoreColumn(reason) {
        if (!locked) return;
        var el = scrollingElement();
        if (el.scrollLeft !== locked.left || el.scrollTop !== locked.top) {
          console.log('SelDiag snap (' + reason + ') from ' + el.scrollLeft + ',' + el.scrollTop + ' to ' + locked.left + ',' + locked.top + ' touching=' + touching);
          ignoreClickUntil = Date.now() + 800;
          el.scrollTo(locked.left, locked.top);
        }
      }

      function scheduleSnap() {
        cancelSnap();
        snapTimer = setTimeout(function () {
          snapTimer = null;
          if ((!hasSelection() && !customRange) || scrollMode()) return;
          restoreColumn('settled');
        }, SNAP_DELAY_MS);
      }

      var customRange = null;
      var drag = null;
      var layer = null;
      var originProbe = null;
      var handles = { start: null, end: null };
      var boxes = [];
      var ignoreClickUntil = 0;

      function holdSelecting() {
        if (window.Android && Android.onSelectionStart) Android.onSelectionStart();
      }

      function releaseSelecting() {
        if (window.Android && Android.onSelectionEnd) Android.onSelectionEnd();
      }

      function caretAt(x, y) {
        if (!document.caretRangeFromPoint) return null;
        var range = document.caretRangeFromPoint(x, y);
        if (!range || !range.startContainer) return null;
        var rects = range.getClientRects();
        var rect = rects.length ? rects[0] : range.getBoundingClientRect();
        if (!rect || rect.height < 1) return null;
        var vw = window.innerWidth;
        var vh = window.innerHeight;
        if (y < rect.top - 4 || y > rect.bottom + 4) return null;
        if (x < rect.left - 28 || x > rect.right + 28) return null;
        if (rect.right < 0 || rect.left > vw || rect.bottom < 0 || rect.top > vh) return null;
        return range;
      }

      function wordRange(hit) {
        var node = hit.startContainer;
        var range = document.createRange();
        if (!node || node.nodeType !== 3) {
          range.setStart(hit.startContainer, hit.startOffset);
          range.setEnd(hit.startContainer, hit.startOffset);
          return range;
        }
        var text = node.textContent || '';
        var start = hit.startOffset;
        var end = start;
        while (start > 0 && /\S/.test(text.charAt(start - 1))) start--;
        while (end < text.length && /\S/.test(text.charAt(end))) end++;
        if (start === end) return null;
        range.setStart(node, start);
        range.setEnd(node, end);
        return range;
      }

      function visibleRects(range) {
        var vw = window.innerWidth;
        var vh = window.innerHeight;
        var all = range.getClientRects();
        var kept = [];
        for (var i = 0; i < all.length; i++) {
          var rect = all[i];
          if (rect.width < 1 || rect.height < 1) continue;
          if (rect.right <= 0 || rect.left >= vw || rect.bottom <= 0 || rect.top >= vh) continue;
          kept.push(rect);
        }
        return kept;
      }

      function makeHandle(which) {
        var el = document.createElement('div');
        el.setAttribute('data-fr-selection', '1');
        el.setAttribute('data-fr-handle', which);
        el.style.cssText = 'position:fixed;z-index:2;width:44px;height:52px;margin-left:-22px;display:none;pointer-events:auto;touch-action:none;';
        var stem = document.createElement('div');
        stem.style.cssText = 'width:2px;height:16px;margin:0 auto;background-color:#1a73e8 !important;';
        var knob = document.createElement('div');
        knob.style.cssText = 'width:22px;height:22px;margin:0 auto;border-radius:11px;background-color:#1a73e8 !important;box-shadow:0 1px 3px rgba(0,0,0,0.35);';
        el.appendChild(stem);
        el.appendChild(knob);
        el.addEventListener('touchstart', function (event) {
          if (!customRange || !event.changedTouches.length) return;
          event.preventDefault();
          event.stopPropagation();
          var touch = event.changedTouches[0];
          var box = el.getBoundingClientRect();
          var anchorNode = which === 'end' ? customRange.startContainer : customRange.endContainer;
          var anchorOffset = which === 'end' ? customRange.startOffset : customRange.endOffset;
          drag = {
            which: which,
            dx: touch.clientX - (box.left + box.width / 2),
            dy: touch.clientY - box.top,
            grabX: touch.clientX,
            grabY: touch.clientY,
            anchorNode: anchorNode,
            anchorOffset: anchorOffset,
            lastRect: null,
          };
          el.style.transform = '';
          ignoreClickUntil = Date.now() + 700;
          dismissMenu();
          var sel = window.getSelection();
          if (sel) sel.removeAllRanges();
          holdSelecting();
          console.log('SelDiag custom drag ' + which);
        }, { passive: false });
        el.addEventListener('touchmove', function (event) {
          if (!drag || drag.which !== which || !event.changedTouches.length) return;
          event.preventDefault();
          event.stopPropagation();
          holdSelecting();
          var touch = event.changedTouches[0];
          // The caret snaps between glyphs. Following it is what shakes the
          // handle, so the handle tracks the finger and the highlight tracks
          // the caret.
          el.style.transform = 'translate(' + (touch.clientX - drag.grabX) + 'px,' + (touch.clientY - drag.grabY) + 'px)';
          moveEndpoint(which, touch.clientX - drag.dx, touch.clientY - drag.dy);
        }, { passive: false });
        function endDrag() {
          if (!drag || drag.which !== which) return;
          drag = null;
          el.style.transform = '';
          commitNative();
          restoreColumn('commit');
          ignoreClickUntil = Date.now() + 800;
          paint();
        }
        el.addEventListener('touchend', endDrag);
        el.addEventListener('touchcancel', endDrag);
        return el;
      }

      function ensureLayer() {
        if (layer) return;
        layer = document.createElement('div');
        layer.setAttribute('data-fr-selection', '1');
        layer.style.cssText = 'position:fixed;left:0;top:0;width:0;height:0;z-index:2147483646;pointer-events:none;';
        document.body.appendChild(layer);
        handles.start = makeHandle('start');
        handles.end = makeHandle('end');
        layer.appendChild(handles.start);
        layer.appendChild(handles.end);
        originProbe = document.createElement('div');
        originProbe.setAttribute('data-fr-selection', '1');
        originProbe.style.cssText = 'position:fixed;left:0;top:0;width:1px;height:1px;pointer-events:none;';
        layer.appendChild(originProbe);
      }

      // Readium sets -webkit-perspective on the root, so fixed coordinates are
      // not viewport coordinates. The probe measures that shift.
      function toLayer(x, y) {
        ensureLayer();
        var origin = originProbe.getBoundingClientRect();
        return { x: x - origin.left, y: y - origin.top };
      }

      function placeAt(el, x, y) {
        el.style.display = 'block';
        var point = toLayer(x, y);
        el.style.left = point.x + 'px';
        el.style.top = point.y + 'px';
      }

      function caretBox(hit) {
        var rects = hit.getClientRects();
        var rect = rects.length ? rects[0] : hit.getBoundingClientRect();
        if (!rect || rect.height < 1) return null;
        return rect;
      }

      function caretNear(x, y) {
        var exact = caretAt(x, y);
        if (exact) return exact;
        var best = null;
        var bestDist = Infinity;
        var shifts = [8, -8, 16, -16, 24, -24];
        for (var i = 0; i < shifts.length; i++) {
          var hit = caretAt(x, y + shifts[i]);
          var rect = hit ? caretBox(hit) : null;
          if (!rect) continue;
          var dist = Math.abs((rect.top + rect.bottom) / 2 - y);
          if (dist < bestDist) {
            bestDist = dist;
            best = hit;
          }
        }
        if (best) return best;
        var clampedX = Math.max(16, Math.min(window.innerWidth - 16, x));
        if (clampedX !== x) return caretAt(clampedX, y);
        return null;
      }

      function rangeBetween(anchorNode, anchorOffset, focusNode, focusOffset) {
        var anchor = document.createRange();
        var focus = document.createRange();
        anchor.setStart(anchorNode, anchorOffset);
        anchor.collapse(true);
        focus.setStart(focusNode, focusOffset);
        focus.collapse(true);
        var next = document.createRange();
        if (anchor.compareBoundaryPoints(Range.START_TO_START, focus) <= 0) {
          next.setStart(anchorNode, anchorOffset);
          next.setEnd(focusNode, focusOffset);
        } else {
          next.setStart(focusNode, focusOffset);
          next.setEnd(anchorNode, anchorOffset);
        }
        return next.collapsed ? null : next;
      }

      function staysOnPage(range) {
        var rects = range.getClientRects();
        var vw = window.innerWidth;
        var vh = window.innerHeight;
        var onPage = 0;
        for (var i = 0; i < rects.length; i++) {
          var rect = rects[i];
          if (rect.width < 1 || rect.height < 1) continue;
          if (rect.left < -12 || rect.right > vw + 12 || rect.top < -12 || rect.bottom > vh + 12) return false;
          onPage++;
        }
        return onPage > 0;
      }

      function placeHandle(el, rect, which) {
        if (!rect) {
          el.style.display = 'none';
          return;
        }
        el.style.display = 'block';
        var point = toLayer(which === 'end' ? rect.right : rect.left, rect.top);
        el.style.left = point.x + 'px';
        el.style.top = point.y + 'px';
      }

      function paint() {
        if (!customRange) {
          boxes.forEach(function (box) { box.remove(); });
          boxes = [];
          if (handles.start) handles.start.style.display = 'none';
          if (handles.end) handles.end.style.display = 'none';
          return;
        }
        ensureLayer();
        boxes.forEach(function (box) { box.remove(); });
        boxes = [];
        var rects = visibleRects(customRange);
        for (var i = 0; i < rects.length; i++) {
          var rect = rects[i];
          var point = toLayer(rect.left, rect.top);
          var box = document.createElement('div');
          box.setAttribute('data-fr-selection', '1');
          box.style.cssText = 'position:fixed;z-index:1;pointer-events:none;background-color:rgba(180,216,254,0.55) !important;' +
            'left:' + point.x + 'px;top:' + point.y + 'px;width:' + Math.max(1, rect.width) + 'px;height:' + rect.height + 'px;';
          layer.appendChild(box);
          boxes.push(box);
        }
        if (drag) {
          var anchorIsStart = customRange.startContainer === drag.anchorNode &&
            customRange.startOffset === drag.anchorOffset;
          var anchorRect = anchorIsStart ? rects[0] : rects[rects.length - 1];
          var other = drag.which === 'start' ? handles.end : handles.start;
          if (anchorRect) {
            placeAt(other, anchorIsStart ? anchorRect.left : anchorRect.right, anchorRect.top);
          }
          return;
        }
        placeHandle(handles.start, rects[0], 'start');
        placeHandle(handles.end, rects[rects.length - 1], 'end');
      }

      function moveEndpoint(which, x, y) {
        var hit = caretNear(x, y);
        if (!hit || !drag) {
          console.log('SelDiag custom reject ' + which + ' x=' + Math.round(x) + ' y=' + Math.round(y));
          return;
        }
        var next = null;
        try {
          next = rangeBetween(drag.anchorNode, drag.anchorOffset, hit.startContainer, hit.startOffset);
        } catch (error) {
          return;
        }
        if (!next || !staysOnPage(next)) return;
        var rect = caretBox(hit);
        if (rect && drag.lastRect) {
          var lastMid = (drag.lastRect.top + drag.lastRect.bottom) / 2;
          var mid = (rect.top + rect.bottom) / 2;
          var lineChanged = Math.abs(mid - lastMid) > Math.max(8, drag.lastRect.height * 0.75);
          if (lineChanged && Math.abs(y - lastMid) + 8 < Math.abs(y - mid)) return;
        }
        if (rect) drag.lastRect = rect;
        customRange = next;
        paint();
      }

      function commitNative() {
        if (!customRange) return;
        var sel = window.getSelection();
        if (!sel) return;
        try {
          sel.setBaseAndExtent(
            customRange.startContainer,
            customRange.startOffset,
            customRange.endContainer,
            customRange.endOffset,
          );
        } catch (error) {
          console.log('SelDiag custom commit failed');
          return;
        }
        if (!locked) locked = page || position();
        console.log('SelDiag custom commit len=' + customRange.toString().length);
        if (window.FlutterPageSelection && FlutterPageSelection.settled) FlutterPageSelection.settled();
        showMenu();
      }

      function selectionBounds() {
        var rects = visibleRects(customRange);
        if (!rects.length) return null;
        var left = rects[0].left;
        var top = rects[0].top;
        var right = rects[0].right;
        var bottom = rects[0].bottom;
        for (var i = 1; i < rects.length; i++) {
          var rect = rects[i];
          if (rect.left < left) left = rect.left;
          if (rect.top < top) top = rect.top;
          if (rect.right > right) right = rect.right;
          if (rect.bottom > bottom) bottom = rect.bottom;
        }
        return { left: left, top: top, right: right, bottom: bottom, scale: window.devicePixelRatio || 1 };
      }

      function showMenu() {
        var box = selectionBounds();
        if (!box || !window.FlutterPageSelection || !FlutterPageSelection.showMenu) return;
        FlutterPageSelection.showMenu(JSON.stringify(box));
      }

      function dismissMenu() {
        if (window.FlutterPageSelection && FlutterPageSelection.dismissMenu) FlutterPageSelection.dismissMenu();
      }

      function clearCustom() {
        customRange = null;
        drag = null;
        dismissMenu();
        paint();
        var sel = window.getSelection();
        if (sel) sel.removeAllRanges();
        releaseSelecting();
      }

      function beginSelection(x, y) {
        var hit = caretAt(x, y);
        var range = hit ? wordRange(hit) : null;
        if (!range || range.collapsed) {
          console.log('SelDiag custom begin none x=' + Math.round(x) + ' y=' + Math.round(y));
          return;
        }
        customRange = range;
        if (!locked) locked = page || position();
        ignoreClickUntil = Date.now() + 700;
        paint();
        console.log('SelDiag custom begin len=' + range.toString().length);
        commitNative();
      }

      var press = null;
      document.addEventListener('selectstart', function (event) {
        if (!scrollMode()) event.preventDefault();
      }, true);
      document.addEventListener('contextmenu', function (event) {
        if (!scrollMode()) event.preventDefault();
      }, true);
      document.addEventListener('touchstart', function (event) {
        if (scrollMode() || event.touches.length !== 1) return;
        var target = event.target;
        if (target && target.closest && target.closest('[data-fr-handle]')) return;
        var touch = event.touches[0];
        if (press) clearTimeout(press.timer);
        press = {
          x: touch.clientX,
          y: touch.clientY,
          timer: setTimeout(function () {
            var point = press;
            press = null;
            if (!point) return;
            beginSelection(point.x, point.y);
          }, 380),
        };
      }, true);
      document.addEventListener('touchmove', function (event) {
        if (!press || !event.touches.length) return;
        var touch = event.touches[0];
        if (Math.abs(touch.clientX - press.x) > 8 || Math.abs(touch.clientY - press.y) > 8) {
          clearTimeout(press.timer);
          press = null;
        }
      }, true);
      document.addEventListener('touchend', function () {
        if (!press) return;
        clearTimeout(press.timer);
        press = null;
      }, true);

      function release() {
        cancelSnap();
        if (!scrollMode()) restoreColumn('released');
        locked = null;
        page = position();
      }

      document.addEventListener('selectionchange', function () {
        if (customRange) {
          if (!locked) locked = page || position();
          return;
        }
        if (!hasSelection() || scrollMode()) {
          release();
          return;
        }
        if (!locked) locked = page || position();
        if (snapTimer !== null) scheduleSnap();
      }, true);

      window.addEventListener('scroll', function () {
        if (hasSelection()) diag('scroll', locked ? ' locked=' + locked.left + ',' + locked.top : ' unlocked');
        if (drag && locked) {
          restoreColumn('drag');
          return;
        }
        if (!locked) {
          if (!touching) notePage();
          return;
        }
        if (!customRange && (!hasSelection() || scrollMode())) {
          release();
          return;
        }
        var el = scrollingElement();
        if (el.scrollLeft !== locked.left || el.scrollTop !== locked.top) scheduleSnap();
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

      document.addEventListener('click', function (event) {
        if (scrollMode() || !customRange || Date.now() < ignoreClickUntil) return;
        var target = event.target;
        if (target && target.closest && target.closest('[data-fr-handle]')) return;
        var rects = visibleRects(customRange);
        for (var i = 0; i < rects.length; i++) {
          var rect = rects[i];
          if (event.clientX >= rect.left && event.clientX <= rect.right &&
              event.clientY >= rect.top && event.clientY <= rect.bottom) {
            return;
          }
        }
        clearCustom();
      }, true);
    })();
    """
