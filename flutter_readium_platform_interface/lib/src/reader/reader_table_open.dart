import '../index.dart';

/// A table the reader could not fit on the page in book mode.
///
/// [html] is the table markup from the publication, before the reader restacks
/// it. Colors and font fields are the computed reader theme so a viewer can
/// match the page. Fired on iOS and Android. Web does not inject the table helper.
class EpubTableOpenEvent implements JSONable {
  const EpubTableOpenEvent({
    required this.html,
    this.textColor,
    this.backgroundColor,
    this.fontFamily,
    this.fontSizePx,
  });

  factory EpubTableOpenEvent.fromJson(final Map<String, dynamic> map) {
    final html = map.optString('html');
    if (html.isEmpty) {
      throw ArgumentError('EpubTableOpenEvent.fromJson: required field "html" is missing or empty');
    }
    return EpubTableOpenEvent(
      html: html,
      textColor: map.optNullableString('textColor'),
      backgroundColor: map.optNullableString('backgroundColor'),
      fontFamily: map.optNullableString('fontFamily'),
      fontSizePx: map.optNullableDouble('fontSizePx'),
    );
  }

  /// Original `<table>` markup.
  final String html;

  /// Computed text color, typically `rgb(...)` or a hex color.
  final String? textColor;

  /// Computed page background, typically `rgb(...)` or a hex color.
  final String? backgroundColor;

  /// Computed font family for the page body.
  final String? fontFamily;

  /// Root font size in CSS pixels, after the reader font-size preference.
  final double? fontSizePx;

  @override
  Map<String, dynamic> toJson() => {
    'html': html,
    if (textColor != null) 'textColor': textColor,
    if (backgroundColor != null) 'backgroundColor': backgroundColor,
    if (fontFamily != null) 'fontFamily': fontFamily,
    if (fontSizePx != null) 'fontSizePx': fontSizePx,
  };
}
