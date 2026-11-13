import 'package:flutter_readium_platform_interface/flutter_readium_platform_interface.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('PDFPreferences.merge', () {
    test('values set in the other preferences replace the current ones', () {
      const current = PDFPreferences(layout: PDFLayout.paginated, fit: PDFFit.page, pageSpacing: 4);
      const update = PDFPreferences(layout: PDFLayout.scrollVertical);

      final merged = current.merge(update);

      expect(merged.layout, PDFLayout.scrollVertical);
      expect(merged.fit, PDFFit.page);
      expect(merged.pageSpacing, 4);
    });

    test('an empty update changes nothing', () {
      const current = PDFPreferences(layout: PDFLayout.paginated, spread: PDFSpread.always);

      expect(current.merge(const PDFPreferences()), current);
    });
  });
}
