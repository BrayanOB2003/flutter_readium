import 'dart:async';

import 'package:fake_async/fake_async.dart';
import 'package:flutter/services.dart';
import 'package:flutter_readium/src/page_count.dart';
import 'package:flutter_test/flutter_test.dart';

PageCountRequest _request(double fontSize) {
  return normalizePageCountRequest(
    viewportWidth: 390,
    viewportHeight: 800,
    fontSize: fontSize,
  );
}

void main() {
  test('repeated font changes measure the last size once', () {
    fakeAsync((async) {
      final measured = <double>[];
      final gate = PageCountGate(
        debounce: const Duration(milliseconds: 400),
        measure: (request, token) async {
          measured.add(request.fontSize);
          return 120;
        },
      );

      final results = [for (final size in [1.0, 1.1, 1.2, 1.3, 1.4]) gate.count(_request(size))];
      async.elapse(const Duration(milliseconds: 399));
      expect(measured, isEmpty);

      async.elapse(const Duration(milliseconds: 1));
      async.flushMicrotasks();
      expect(measured, [1.4]);
      final pages = <int>[];
      for (final result in results) {
        result.then(pages.add);
      }
      async.flushMicrotasks();
      expect(pages, [120, 120, 120, 120, 120]);
    });
  });

  test('a cached layout is not measured again', () async {
    var calls = 0;
    final gate = PageCountGate(
      debounce: Duration.zero,
      measure: (request, token) async {
        calls++;
        return 40;
      },
    );

    expect(await gate.count(_request(1)), 40);
    expect(await gate.count(_request(1)), 40);
    expect(calls, 1);

    expect(await gate.count(_request(1.2)), 40);
    expect(calls, 2);
  });

  test('a newer layout discards the measurement already running', () {
    fakeAsync((async) {
      final measured = <double>[];
      late PageCountGate gate;
      final first = Completer<int>();
      gate = PageCountGate(
        debounce: const Duration(milliseconds: 400),
        measure: (request, token) {
          measured.add(request.fontSize);
          if (request.fontSize == 1) return first.future;
          return Future<int>.value(80);
        },
      );

      final firstResult = gate.count(_request(1));
      async.elapse(const Duration(milliseconds: 400));
      expect(measured, [1]);

      final secondResult = gate.count(_request(1.5));
      first.complete(10);
      async.flushMicrotasks();

      async.elapse(const Duration(milliseconds: 400));
      async.flushMicrotasks();
      expect(measured, [1, 1.5]);
      final pages = <int>[];
      firstResult.then(pages.add);
      secondResult.then(pages.add);
      async.flushMicrotasks();
      expect(pages, [80, 80]);
    });
  });

  test('a native measurement replaced by a newer one is ignored', () {
    fakeAsync((async) {
      final release = Completer<void>();
      final gate = PageCountGate(
        debounce: const Duration(milliseconds: 400),
        measure: (request, token) async {
          if (request.fontSize == 1) {
            await release.future;
            throw PlatformException(code: pageCountSupersededCode);
          }
          return 80;
        },
      );

      final firstResult = gate.count(_request(1));
      async.elapse(const Duration(milliseconds: 400));
      final secondResult = gate.count(_request(1.5));
      release.complete();
      async.flushMicrotasks();
      async.elapse(const Duration(milliseconds: 400));
      async.flushMicrotasks();
      final pages = <int>[];
      firstResult.then(pages.add);
      secondResult.then(pages.add);
      async.flushMicrotasks();
      expect(pages, [80, 80]);
    });
  });
}
