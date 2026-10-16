import 'dart:async';

import 'package:flutter/services.dart';

/// Wire code for a measurement that a newer request replaced.
const pageCountSupersededCode = 'PageCountSuperseded';

/// Layout used to count pages in page-reading mode.
class PageCountRequest {
  const PageCountRequest({
    required this.viewportWidth,
    required this.viewportHeight,
    required this.fontSize,
    this.pageMargins,
    this.lineHeight,
    this.columnCount = 'auto',
    this.publisherStyles = false,
  });

  /// Logical pixels of the reader view.
  final double viewportWidth;
  final double viewportHeight;

  /// Readium font scale. `1` is the publisher size.
  final double fontSize;
  final double? pageMargins;
  final double? lineHeight;

  /// `auto`, `1`, or `2`.
  final String columnCount;
  final bool publisherStyles;

  String get key =>
      '$viewportWidth|$viewportHeight|$fontSize|$pageMargins|$lineHeight|$columnCount|$publisherStyles';

  Map<String, Object?> payload(int token) => {
    'token': token,
    'viewportWidth': viewportWidth,
    'viewportHeight': viewportHeight,
    'fontSize': fontSize,
    'columnCount': columnCount,
    'publisherStyles': publisherStyles,
    if (pageMargins != null) 'pageMargins': pageMargins,
    if (lineHeight != null) 'lineHeight': lineHeight,
  };
}

/// Rounds a reader layout onto the values that change the page count.
PageCountRequest normalizePageCountRequest({
  required double viewportWidth,
  required double viewportHeight,
  required double fontSize,
  double? pageMargins,
  double? lineHeight,
  String columnCount = 'auto',
  bool publisherStyles = false,
}) {
  return PageCountRequest(
    viewportWidth: viewportWidth.roundToDouble(),
    viewportHeight: viewportHeight.roundToDouble(),
    fontSize: (fontSize * 100).round() / 100,
    pageMargins: pageMargins == null ? null : (pageMargins * 100).round() / 100,
    lineHeight: lineHeight == null ? null : (lineHeight * 100).round() / 100,
    columnCount: columnCount,
    publisherStyles: publisherStyles,
  );
}

class _Waiter {
  _Waiter(this.request, this.completer);

  final PageCountRequest request;
  final Completer<int> completer;
}

/// Caches a page count and collapses repeated layout changes into one measurement.
class PageCountGate {
  PageCountGate({
    required this.measure,
    this.cancel,
    this.debounce = const Duration(milliseconds: 400),
  });

  final Duration debounce;
  final Future<int> Function(PageCountRequest request, int token) measure;

  /// Stops the native measurement that belongs to an older [token].
  final void Function(int token)? cancel;

  final Map<String, int> _cache = {};
  final List<_Waiter> _pending = [];
  Timer? _timer;
  int _generation = 0;
  String? _inflightKey;
  bool _measuring = false;

  Future<int> count(PageCountRequest request) {
    if (request.viewportWidth <= 0 || request.viewportHeight <= 0) {
      throw ArgumentError('The reader viewport must be larger than zero.');
    }
    final cached = _cache[request.key];
    if (cached != null) return Future<int>.value(cached);

    final completer = Completer<int>();
    _pending.add(_Waiter(request, completer));
    if (_measuring && request.key != _inflightKey) {
      _generation++;
      cancel?.call(_generation);
    }
    _schedule();
    return completer.future;
  }

  /// Drops cached totals and any measurement still running.
  void clear() {
    _generation++;
    cancel?.call(_generation);
    _timer?.cancel();
    _timer = null;
    _cache.clear();
    final waiting = List<_Waiter>.of(_pending);
    _pending.clear();
    for (final waiter in waiting) {
      if (!waiter.completer.isCompleted) {
        waiter.completer.completeError(
          StateError('The publication changed before the page count finished.'),
        );
      }
    }
  }

  void _schedule() {
    _timer?.cancel();
    _timer = Timer(debounce, () {
      _timer = null;
      _run();
    });
  }

  Future<void> _run() async {
    if (_measuring || _pending.isEmpty) return;
    final request = _pending.last.request;
    final generation = ++_generation;
    _inflightKey = request.key;
    _measuring = true;
    try {
      final count = await measure(request, generation);
      if (generation != _generation) return;
      _cache[request.key] = count;
      final waiting = List<_Waiter>.of(_pending);
      _pending.clear();
      for (final waiter in waiting) {
        waiter.completer.complete(count);
      }
    } on PlatformException catch (error, stack) {
      if (generation != _generation) return;
      _fail(request.key, error, stack);
    } catch (error, stack) {
      if (generation != _generation) return;
      _fail(request.key, error, stack);
    } finally {
      _measuring = false;
      _inflightKey = null;
      if (_pending.isNotEmpty) _schedule();
    }
  }

  void _fail(String key, Object error, StackTrace stack) {
    final matched = _pending.where((waiter) => waiter.request.key == key).toList();
    _pending.removeWhere((waiter) => waiter.request.key == key);
    for (final waiter in matched) {
      waiter.completer.completeError(error, stack);
    }
  }
}
