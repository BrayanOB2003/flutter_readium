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
 * here. While the finger is down the highlight follows that point. Word and
 * punctuation stops are applied when the finger lifts. A drag keeps the latest
 * point and paints it once per frame.
 * The DOM selection stays empty while the handles are on screen. Chromium's
 * own handles move one character at a time, and they appear as soon as a DOM
 * selection exists. Copy, share, and save install this range only for the
 * read, then drop it. `SelectionHandleGuard` hides any handle that still appears.
 *
 * Keep the book-mode part in step with `SelectionPageLock.swift`, except for
 * the delay, which WebKit does not need.
 */
internal const val SELECTION_GESTURES_JS =
    """
    (function () {
      if (window.__flutterReadiumSelectionGestures) return;
      window.__flutterReadiumSelectionGestures = true;
      ensureSelectionStyle();
      syncBookSelection();

      function syncBookSelection() {
        document.documentElement.classList.toggle('fr-book-selection', !scrollMode());
      }

      function ensureSelectionStyle() {
        if (document.getElementById('fr-selection-style')) return;
        var style = document.createElement('style');
        style.id = 'fr-selection-style';
        style.textContent = '.fr-book-selection ::selection, .fr-book-selection ::-moz-selection {' +
          'background-color: transparent !important; color: inherit !important;}' +
          '::highlight(fr-selection) { background-color: var(--RS__selectionBackgroundColor, #b4d8fe); color: inherit; }';
        (document.head || document.documentElement).appendChild(style);
      }

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
          var ownChange = true;
          for (var recordIndex = 0; recordIndex < records.length; recordIndex++) {
            var changed = records[recordIndex].target;
            if (!changed || changed.nodeType !== 1 || !changed.hasAttribute ||
                !changed.hasAttribute('data-fr-selection')) {
              ownChange = false;
              break;
            }
          }
          if (ownChange) return;
          syncBookSelection();
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
          layerOrigin = null;
          console.log('SelDiag snap (' + reason + ') from ' + el.scrollLeft + ',' + el.scrollTop + ' to ' + locked.left + ',' + locked.top + ' touching=' + touching);
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
      var pendingMove = null;
      var moveFrame = 0;
      var lastRejectLog = 0;
      var layer = null;
      var originProbe = null;
      var layerOrigin = null;
      var handles = { start: null, end: null };
      var boxes = [];

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

      // iOS moves a handle by a whole word or one punctuation mark. A letter
      // run, including an apostrophe inside it, is one stop. Each punctuation
      // mark is its own stop. Spaces are not.
      function isAlnum(ch) {
        if (!ch) return false;
        try {
          return /[\p{L}\p{N}]/u.test(ch);
        } catch (error) {
          return /[0-9A-Za-z\u00C0-\u024F]/.test(ch);
        }
      }

      function isPunct(ch) {
        if (!ch) return false;
        try {
          return /[\p{P}\p{S}]/u.test(ch);
        } catch (error) {
          return /[.,;:!?¿¡"'“”‘’«»()\[\]{}…—–\-]/.test(ch);
        }
      }

      function blockRoot(node) {
        var el = node && (node.nodeType === 1 ? node : node.parentElement);
        while (el && el !== document.documentElement) {
          if (/^(P|LI|DD|BLOCKQUOTE|PRE|H[1-6]|TD|TH|FIGCAPTION|DIV)$/.test(el.tagName)) return el;
          el = el.parentElement;
        }
        return document.body || document.documentElement;
      }

      function textNodesIn(node) {
        var root = blockRoot(node);
        var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
        var nodes = [];
        var current;
        while ((current = walker.nextNode())) nodes.push(current);
        return nodes;
      }

      var cachedChars = null;
      function charsIn(node) {
        var root = blockRoot(node);
        if (cachedChars && cachedChars.root === root) return cachedChars.chars;
        var nodes = textNodesIn(node);
        var chars = [];
        for (var n = 0; n < nodes.length; n++) {
          var text = nodes[n].textContent || '';
          for (var i = 0; i < text.length; i++) {
            chars.push({ node: nodes[n], index: i, ch: text.charAt(i) });
          }
        }
        cachedChars = { root: root, chars: chars };
        return chars;
      }

      function kindOfChar(chars, i) {
        var ch = chars[i].ch;
        if (ch === '\u00ad' || ch === '\u200b') return 'skip';
        if (/\s/.test(ch) || ch === '\u00a0') return 'space';
        var prev = i > 0 ? chars[i - 1].ch : '';
        var next = i + 1 < chars.length ? chars[i + 1].ch : '';
        if ((ch === "'" || ch === '\u2019') && isAlnum(prev) && isAlnum(next)) return 'word';
        if (isAlnum(ch)) return 'word';
        if (isPunct(ch)) return 'punct';
        return 'word';
      }

      function tokenBounds(chars, i) {
        var kind = kindOfChar(chars, i);
        if (kind === 'space' || kind === 'none' || kind === 'skip') return null;
        if (kind === 'punct') {
          return {
            node: chars[i].node,
            offset: chars[i].index,
            endNode: chars[i].node,
            end: chars[i].index + 1,
          };
        }
        var start = i;
        var end = i;
        while (start > 0) {
          var before = kindOfChar(chars, start - 1);
          if (before !== kind && before !== 'skip') break;
          start--;
        }
        while (end + 1 < chars.length) {
          var after = kindOfChar(chars, end + 1);
          if (after !== kind && after !== 'skip') break;
          end++;
        }
        return {
          node: chars[start].node,
          offset: chars[start].index,
          endNode: chars[end].node,
          end: chars[end].index + 1,
        };
      }

      // offset is a caret. dir 1 is the character after it, dir -1 the one before.
      function indexOfCaret(chars, node, offset, dir) {
        var i;
        if (dir > 0) {
          for (i = 0; i < chars.length; i++) {
            if (chars[i].node === node && chars[i].index === offset) return i;
          }
          var passed = false;
          for (i = 0; i < chars.length; i++) {
            if (chars[i].node === node) passed = true;
            else if (passed) return i;
          }
          return -1;
        }
        for (i = chars.length - 1; i >= 0; i--) {
          if (chars[i].node === node && chars[i].index === offset - 1) return i;
        }
        var seen = false;
        for (i = chars.length - 1; i >= 0; i--) {
          if (chars[i].node === node) seen = true;
          else if (seen) return i;
        }
        return -1;
      }

      // A comma is one character wide. Snapping to the far side of whatever
      // token the caret fell in made the gap after it swallow the next word,
      // so the comma only showed up when the handle was dragged all the way back.
      function endOfToken(chars, index) {
        var token = tokenBounds(chars, index);
        if (!token) return null;
        return { node: token.endNode, offset: token.end };
      }

      function endBefore(chars, index) {
        var prev = index;
        while (prev >= 0 && (kindOfChar(chars, prev) === 'space' || kindOfChar(chars, prev) === 'skip')) prev--;
        if (prev < 0) return null;
        return endOfToken(chars, prev);
      }

      function boundaryForCaret(node, offset) {
        if (!node || node.nodeType !== 3) return null;
        var chars = charsIn(node);
        var after = indexOfCaret(chars, node, offset, 1);
        var before = indexOfCaret(chars, node, offset, -1);
        if (after < 0) return endBefore(chars, before);
        var kind = kindOfChar(chars, after);
        if (kind === 'space' || kind === 'skip') return endBefore(chars, after - 1);
        if (kind === 'punct') return endOfToken(chars, after);
        var start = after;
        while (start > 0) {
          var back = kindOfChar(chars, start - 1);
          if (back !== kind && back !== 'skip') break;
          start--;
        }
        var end = after;
        while (end + 1 < chars.length) {
          var ahead = kindOfChar(chars, end + 1);
          if (ahead !== kind && ahead !== 'skip') break;
          end++;
        }
        var visible = 0;
        var depth = 0;
        for (var i = start; i <= end; i++) {
          if (kindOfChar(chars, i) === 'skip') continue;
          if (i < after) depth++;
          visible++;
        }
        if (depth >= 1) return endOfToken(chars, after);
        var earlier = endBefore(chars, start - 1);
        if (earlier) return earlier;
        var token = tokenBounds(chars, start);
        if (!token) return null;
        return { node: token.node, offset: token.offset };
      }

      function snapBoundary(node, offset) {
        if (!node || node.nodeType !== 3) return { node: node, offset: offset };
        return boundaryForCaret(node, offset) || { node: node, offset: offset };
      }

      function wordRange(hit) {
        var node = hit.startContainer;
        if (!node || node.nodeType !== 3) return null;
        var chars = charsIn(node);
        var after = indexOfCaret(chars, node, hit.startOffset, 1);
        var before = indexOfCaret(chars, node, hit.startOffset, -1);
        var pick = -1;
        if (after >= 0 && kindOfChar(chars, after) === 'word') pick = after;
        else if (before >= 0 && kindOfChar(chars, before) === 'word') pick = before;
        else if (after >= 0 && kindOfChar(chars, after) === 'punct') pick = after;
        else if (before >= 0 && kindOfChar(chars, before) === 'punct') pick = before;
        if (pick < 0) return null;
        var token = tokenBounds(chars, pick);
        if (!token) return null;
        var range = document.createRange();
        try {
          range.setStart(token.node, token.offset);
          range.setEnd(token.endNode, token.end);
        } catch (error) {
          return null;
        }
        return range.collapsed ? null : range;
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

      // Android's handle: a short stem and a circle under the line, as in scroll mode.
      function makeHandle(which) {
        var el = document.createElement('div');
        el.setAttribute('data-fr-selection', '1');
        el.setAttribute('data-fr-handle', which);
        el.style.cssText = 'position:fixed;z-index:2;left:0;top:0;width:48px;height:40px;margin-left:-24px;display:none;pointer-events:auto;touch-action:none;will-change:transform;';
        var stem = document.createElement('div');
        stem.style.cssText = 'width:2px;height:8px;margin:0 auto;background-color:#1a73e8 !important;';
        var knob = document.createElement('div');
        knob.style.cssText = 'width:22px;height:22px;margin:-1px auto 0;border-radius:11px;background-color:#1a73e8 !important;';
        el.appendChild(stem);
        el.appendChild(knob);
        el.addEventListener('touchstart', function (event) {
          if (!customRange || !event.changedTouches.length) return;
          event.preventDefault();
          event.stopPropagation();
          cachedChars = null;
          var anchorNode = which === 'end' ? customRange.startContainer : customRange.endContainer;
          var anchorOffset = which === 'end' ? customRange.startOffset : customRange.endOffset;
          drag = {
            which: which,
            anchorNode: anchorNode,
            anchorOffset: anchorOffset,
          };
          handlesReceiveTouches(false);
          hideHandles();
          gestureAdjustsSelection = true;
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
          var touch = event.changedTouches[0];
          queueEndpoint(which, touch.clientX, touch.clientY);
        }, { passive: false });
        function endDrag() {
          if (!drag || drag.which !== which) return;
          flushMove();
          settleDrag();
          drag = null;
          handlesReceiveTouches(true);
          commitNative();
          restoreColumn('commit');
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
        if (!layerOrigin) {
          var origin = originProbe.getBoundingClientRect();
          layerOrigin = { left: origin.left, top: origin.top };
        }
        return { x: x - layerOrigin.left, y: y - layerOrigin.top };
      }

      // One hit test. The caret under the finger is enough; extra probes were
      // what left the highlight a few events behind a fast drag.
      function caretHit(x, y) {
        if (!document.caretRangeFromPoint) return null;
        var range = document.caretRangeFromPoint(x, y);
        if (!range || !range.startContainer) return null;
        var rects = range.getClientRects();
        var rect = rects.length ? rects[0] : range.getBoundingClientRect();
        if (!rect || rect.height < 1) return null;
        var vw = window.innerWidth;
        var vh = window.innerHeight;
        if (rect.right < 0 || rect.left > vw || rect.bottom < 0 || rect.top > vh) return null;
        var mid = (rect.top + rect.bottom) / 2;
        if (Math.abs(y - mid) > Math.max(rect.height * 2.5, 48)) return null;
        if (x < rect.left - 64 || x > rect.right + 64) return null;
        return { range: range, rect: rect };
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

      function placeHandle(el, rect, which) {
        if (!rect) {
          el.style.display = 'none';
          return;
        }
        if (el.style.display !== 'block') el.style.display = 'block';
        var point = toLayer(which === 'end' ? rect.right : rect.left, rect.bottom);
        el.style.transform = 'translate3d(' + point.x + 'px,' + point.y + 'px,0)';
      }

      function paintHighlight(range) {
        if (!window.CSS || !CSS.highlights || typeof Highlight !== 'function') return false;
        if (!range) CSS.highlights.delete('fr-selection');
        else CSS.highlights.set('fr-selection', new Highlight(range));
        return true;
      }

      function paint(rects) {
        var highlighted = paintHighlight(customRange);
        if (!customRange) {
          boxes.forEach(function (box) { box.remove(); });
          boxes = [];
          if (handles.start) handles.start.style.display = 'none';
          if (handles.end) handles.end.style.display = 'none';
          return;
        }
        ensureLayer();
        if (boxes.length) {
          boxes.forEach(function (box) { box.remove(); });
          boxes = [];
        }
        if (!rects) rects = visibleRects(customRange);
        if (!highlighted) {
          for (var i = 0; i < rects.length; i++) {
            var rect = rects[i];
            var point = toLayer(rect.left, rect.top);
            var box = document.createElement('div');
            box.setAttribute('data-fr-selection', '1');
            box.style.cssText = 'position:fixed;z-index:1;pointer-events:none;background-color:#b4d8fe !important;opacity:0.45;' +
              'left:' + point.x + 'px;top:' + point.y + 'px;width:' + Math.max(1, rect.width) + 'px;height:' + rect.height + 'px;';
            layer.appendChild(box);
            boxes.push(box);
          }
        }
        if (drag || press) hideHandles();
        else {
          placeHandle(handles.start, rects[0], 'start');
          placeHandle(handles.end, rects[rects.length - 1], 'end');
        }
      }

      function sameRange(left, right) {
        return !!left && !!right &&
          left.startContainer === right.startContainer &&
          left.startOffset === right.startOffset &&
          left.endContainer === right.endContainer &&
          left.endOffset === right.endOffset;
      }

      function logReject(which, x, y) {
        var now = Date.now();
        if (now - lastRejectLog < 200) return;
        lastRejectLog = now;
        console.log('SelDiag custom reject ' + which + ' x=' + Math.round(x) + ' y=' + Math.round(y));
      }

      function hideHandles() {
        if (handles.start) handles.start.style.display = 'none';
        if (handles.end) handles.end.style.display = 'none';
      }

      function handlesReceiveTouches(receive) {
        var value = receive ? 'auto' : 'none';
        if (handles.start) handles.start.style.pointerEvents = value;
        if (handles.end) handles.end.style.pointerEvents = value;
      }

      // Follows the finger. Word and punctuation stops wait until the finger lifts.
      function moveEndpoint(which, x, y) {
        var hit = caretHit(x, y);
        if (!hit || !drag) {
          logReject(which, x, y);
          return;
        }
        var next = null;
        try {
          next = rangeBetween(
            drag.anchorNode,
            drag.anchorOffset,
            hit.range.startContainer,
            hit.range.startOffset,
          );
        } catch (error) {
          return;
        }
        if (!next || sameRange(customRange, next)) return;
        customRange = next;
        if (!paintHighlight(customRange)) paint(visibleRects(customRange));
      }

      function settleDrag() {
        if (!drag || !customRange) return;
        cachedChars = null;
        var node = drag.which === 'end' ? customRange.endContainer : customRange.startContainer;
        var offset = drag.which === 'end' ? customRange.endOffset : customRange.startOffset;
        var focus = snapBoundary(node, offset);
        var next = null;
        try {
          next = rangeBetween(drag.anchorNode, drag.anchorOffset, focus.node, focus.offset);
        } catch (error) {
          next = null;
        }
        if (next) customRange = next;
        paint();
      }

      function queueEndpoint(which, x, y) {
        pendingMove = { which: which, x: x, y: y };
        if (moveFrame) return;
        moveFrame = requestAnimationFrame(function () {
          moveFrame = 0;
          flushMove();
        });
      }

      function flushMove() {
        if (moveFrame) {
          cancelAnimationFrame(moveFrame);
          moveFrame = 0;
        }
        var move = pendingMove;
        pendingMove = null;
        if (!move || !drag || drag.which !== move.which) return;
        moveEndpoint(move.which, move.x, move.y);
      }

      function nativeSelectionMissing() {
        var sel = window.getSelection();
        return !sel || sel.rangeCount === 0 || sel.isCollapsed || !sel.toString().trim();
      }

      // Readium reads window.getSelection. It is installed for that read only.
      function installNativeSelection() {
        if (!customRange || !nativeSelectionMissing()) return true;
        var sel = window.getSelection();
        if (!sel) return false;
        holdNativeSelection = true;
        try {
          sel.setBaseAndExtent(
            customRange.startContainer,
            customRange.startOffset,
            customRange.endContainer,
            customRange.endOffset,
          );
        } catch (error) {
          holdNativeSelection = false;
          console.log('SelDiag custom commit failed');
          return false;
        }
        if (!locked) locked = page || position();
        var placed = !nativeSelectionMissing();
        if (!placed) holdNativeSelection = false;
        return placed;
      }

      window.__flutterReadiumInstallSelection = function () {
        if (drag || (press && press.dragging)) return;
        installNativeSelection();
      };

      var holdNativeSelection = false;

      function releaseNativeSelection() {
        if (drag || (press && press.dragging)) return;
        holdNativeSelection = false;
        var sel = window.getSelection();
        if (!sel || sel.rangeCount === 0) return;
        sel.removeAllRanges();
      }

      window.__flutterReadiumReleaseSelection = releaseNativeSelection;

      function commitNative() {
        if (!customRange) return;
        releaseNativeSelection();
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
        cachedChars = null;
        customRange = null;
        drag = null;
        pendingMove = null;
        if (moveFrame) {
          cancelAnimationFrame(moveFrame);
          moveFrame = 0;
        }
        dismissMenu();
        handlesReceiveTouches(true);
        paint();
        var sel = window.getSelection();
        if (sel) sel.removeAllRanges();
        releaseSelecting();
      }

      function beginSelection(x, y) {
        cachedChars = null;
        syncBookSelection();
        var hit = caretAt(x, y);
        var range = hit ? wordRange(hit) : null;
        if (!range || range.collapsed) {
          console.log('SelDiag custom begin none x=' + Math.round(x) + ' y=' + Math.round(y));
          return;
        }
        customRange = range;
        if (!locked) locked = page || position();
        holdSelecting();
        paint();
        console.log('SelDiag custom begin len=' + range.toString().length);
      }

      // The finger is still down after the word is selected. The end it moves
      // toward follows it, so the selection can grow without grabbing a handle.
      function armFingerDrag(x, y) {
        if (!customRange || !press) return;
        var dx = x - press.x;
        var dy = y - press.y;
        var which = dy > 2 || (Math.abs(dy) <= 2 && dx >= 0) ? 'end' : 'start';
        gestureAdjustsSelection = true;
        drag = {
          which: which,
          anchorNode: which === 'end' ? customRange.startContainer : customRange.endContainer,
          anchorOffset: which === 'end' ? customRange.startOffset : customRange.endOffset,
        };
        handlesReceiveTouches(false);
        hideHandles();
        holdSelecting();
        dismissMenu();
        var sel = window.getSelection();
        if (sel) sel.removeAllRanges();
      }

      function finishFingerDrag() {
        if (!press || !press.active) return;
        var moved = press.dragging;
        press = null;
        if (!customRange) {
          drag = null;
          handlesReceiveTouches(true);
          return;
        }
        flushMove();
        if (moved) settleDrag();
        drag = null;
        handlesReceiveTouches(true);
        commitNative();
        restoreColumn('commit');
        paint();
      }

      window.__flutterReadiumClearSelection = function () {
        clearCustom();
      };

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
          active: false,
          dragging: false,
          timer: setTimeout(function () {
            if (!press) return;
            beginSelection(press.x, press.y);
            if (!customRange) {
              clearTimeout(press.timer);
              press = null;
              return;
            }
            press.active = true;
          }, 380),
        };
      }, true);
      document.addEventListener('touchmove', function (event) {
        if (!press || !event.touches.length) return;
        var touch = event.touches[0];
        if (!press.active) {
          if (Math.abs(touch.clientX - press.x) > 8 || Math.abs(touch.clientY - press.y) > 8) {
            clearTimeout(press.timer);
            press = null;
          }
          return;
        }
        if (!press.dragging) {
          if (Math.abs(touch.clientX - press.x) <= 8 && Math.abs(touch.clientY - press.y) <= 8) return;
          press.dragging = true;
          armFingerDrag(touch.clientX, touch.clientY);
        }
        if (!drag) return;
        event.preventDefault();
        queueEndpoint(drag.which, touch.clientX, touch.clientY);
      }, { capture: true, passive: false });
      document.addEventListener('touchend', function () {
        if (!press) return;
        clearTimeout(press.timer);
        if (press.active) {
          finishFingerDrag();
          return;
        }
        press = null;
      }, true);
      document.addEventListener('touchcancel', function () {
        if (!press) return;
        clearTimeout(press.timer);
        if (press.active) {
          finishFingerDrag();
          return;
        }
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
      var gestureHadSelection = false;
      var gestureAdjustsSelection = false;
      function notePointerDown(event) {
        selectionEmptyAtPointerDown = !hasSelection();
        if (selectionEmptyAtPointerDown) notePage();
        touching = true;
        var target = event && event.target;
        gestureHadSelection = !!customRange;
        gestureAdjustsSelection = !!(target && target.closest && target.closest('[data-fr-handle]'));
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
        if (scrollMode() || !customRange || !gestureHadSelection || gestureAdjustsSelection) return;
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
        event.preventDefault();
        event.stopPropagation();
        clearCustom();
      }, true);
    })();
    """
