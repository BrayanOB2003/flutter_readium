import 'package:flutter/material.dart';
import 'package:flutter_readium/flutter_readium.dart';
import 'package:webview_flutter/webview_flutter.dart';

/// Full-grid view of a table that book mode could not fit on the page.
///
/// The markup is the publication's table. Font size and colors come from the
/// reader theme at the moment the button was tapped. Pinch-zoom works here
/// because this view is not paginated. Images with publication-relative URLs
/// are not loaded.
class TableViewPage extends StatefulWidget {
  const TableViewPage({required this.event, super.key});

  final EpubTableOpenEvent event;

  @override
  State<TableViewPage> createState() => _TableViewPageState();
}

class _TableViewPageState extends State<TableViewPage> {
  late final WebViewController _controller;
  late final Color _background;
  late final Color _foreground;

  @override
  void initState() {
    super.initState();
    _background = _parseCssColor(widget.event.backgroundColor) ?? Colors.white;
    _foreground = _parseCssColor(widget.event.textColor) ?? Colors.black;
    _controller = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.disabled)
      ..setBackgroundColor(_background)
      ..enableZoom(true)
      ..loadHtmlString(_document(widget.event));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: _background,
      appBar: AppBar(
        backgroundColor: _background,
        foregroundColor: _foreground,
        elevation: 0,
        title: const Text('Table'),
        leading: IconButton(
          key: const ValueKey<String>('table_view_close'),
          icon: const Icon(Icons.close),
          onPressed: () => Navigator.of(context).pop(),
        ),
      ),
      body: WebViewWidget(controller: _controller),
    );
  }
}

String _document(EpubTableOpenEvent event) {
  final fontSize = event.fontSizePx ?? 16;
  final family = _cssValue(event.fontFamily);
  final text = _cssValue(event.textColor);
  final background = _cssValue(event.backgroundColor);
  final table = _stripActiveContent(event.html);
  return '''
<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=5, user-scalable=yes">
<style>
  html { font-size: ${fontSize}px; }
  body {
    margin: 0;
    padding: 16px;
    color: $text;
    background: $background;
    font-family: $family;
  }
  table {
    border-collapse: collapse;
    width: max-content;
    min-width: 100%;
  }
  td, th {
    border: 1px solid currentColor;
    padding: 0.5em 0.65em;
    vertical-align: top;
    text-align: start;
  }
</style>
</head>
<body>$table</body>
</html>
''';
}

String _cssValue(String? value) => (value ?? '').replaceAll(RegExp('[<>]'), '');

String _stripActiveContent(String html) => html
    .replaceAll(RegExp('<script\\b[^>]*>[\\s\\S]*?</script>', caseSensitive: false), '')
    .replaceAll(RegExp('\\son\\w+\\s*=\\s*("[^"]*"|\'[^\']*\'|[^\\s>]+)', caseSensitive: false), '');

Color? _parseCssColor(String? value) {
  if (value == null || value.isEmpty) {
    return null;
  }
  final hex = RegExp(r'^#([0-9a-fA-F]{6})$').firstMatch(value.trim());
  if (hex != null) {
    return Color(int.parse('FF${hex.group(1)}', radix: 16));
  }
  final rgb = RegExp(r'rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)').firstMatch(value);
  if (rgb == null) {
    return null;
  }
  return Color.fromARGB(255, int.parse(rgb.group(1)!), int.parse(rgb.group(2)!), int.parse(rgb.group(3)!));
}
