import 'package:flutter_readium_platform_interface/flutter_readium_platform_interface.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('EpubTableOpenEvent round-trips the table and theme', () {
    const event = EpubTableOpenEvent(
      html: '<table><tr><td>A</td></tr></table>',
      textColor: 'rgb(1, 2, 3)',
      backgroundColor: 'rgb(4, 5, 6)',
      fontFamily: 'Georgia, serif',
      fontSizePx: 18,
    );

    final decoded = EpubTableOpenEvent.fromJson(event.toJson());

    expect(decoded.html, event.html);
    expect(decoded.textColor, event.textColor);
    expect(decoded.backgroundColor, event.backgroundColor);
    expect(decoded.fontFamily, event.fontFamily);
    expect(decoded.fontSizePx, 18);
  });

  test('EpubTableOpenEvent rejects an empty table', () {
    expect(() => EpubTableOpenEvent.fromJson({'html': ''}), throwsArgumentError);
  });
}
