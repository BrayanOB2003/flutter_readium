# Locator

A `Locator` identifies an exact position within a publication resource. It is used for bookmarks, highlights, navigation, and progress tracking. See the Readium spec on the [Locator model](https://readium.org/architecture/models/locators/).

## Structure

```dart
Locator(
  href: '/OEBPS/chapter1.xhtml',  // resource path
  type: 'application/xhtml+xml',
  title: 'Chapter 1',
  locations: Locations(
    progression: 0.42,        // 0.0–1.0 within this resource
    totalProgression: 0.07,   // 0.0–1.0 across the whole publication
    position: 14,             // absolute page index (if available)
    cssSelector: '#p42',      // CSS selector pointing to the element
    fragments: ['page=42'],
  ),
  text: LocatorText(
    before: '...text before...',
    highlight: 'the highlighted text',
    after: '...text after...',
  ),
)
```

## Serialisation

`Locator` exposes a `toJson()` map and matching factories — serialise the map however your storage layer expects (e.g. `jsonEncode`, a database row, a message payload).

```dart
// To/from Map
final map  = locator.toJson();
final loc2 = Locator.fromJson(map);

// From a JSON string
final loc3 = Locator.fromJsonString(jsonString);

// Accept either a Map or a JSON string
final loc4 = Locator.fromJsonDynamic(anything);
```

### Note on href
`href` is stored decoded internally and percent-encoded during serialisation.

## Common patterns

```dart
// Display overall progress
final pct = (locator.locations?.totalProgression ?? 0) * 100;
print('${pct.toStringAsFixed(0)}%');

// Navigate
await FlutterReadium().goToLocator(locator);

// Convert a TOC link
final loc = pub.locatorFromLink(tocLink);
```

## Locations fields

| Field | Type | Description |
|-------|------|-------------|
| `progression` | `double?` | 0.0–1.0 within the current resource |
| `totalProgression` | `double?` | 0.0–1.0 across the whole publication |
| `position` | `int?` | Absolute page number (from page list) |
| `cssSelector` | `String?` | CSS selector for the target element |
| `domRange` | `String?` | DOM range (precise sub-element position) |
| `fragments` | `List<String>` | Additional URI fragments |

## PDF locators

PDFs are represented as a single-resource publication. The canonical position lives in `Locator.locations.position` as a **1-based page number** — this matches the upstream `PDFPositionsService` (swift-toolkit) and `Locator.locations.position` from `PdfNavigatorFragment.currentLocator` (kotlin-toolkit).

- The PDF resource `href` is always the single reading-order entry.
- On iOS the locator's `fragments` carry `"page=N"` (where the upstream parser produces it).
- Consumers should read `locations.position` for page navigation and round-trip via `goToLocator`.

### Selections and decorations (iOS)

A text selection in a PDF produces a locator that points at the selected area, using the PDF fragments from the [Readium Locator model](https://github.com/readium/architecture/tree/master/models/locators) (RFC 3778):

```json
{
  "locations": {
    "fragments": ["page=5", "viewrect=72.00,140.50,310.20,28.00"],
    "position": 5
  },
  "text": { "highlight": "selected words", "before": "…", "after": "…" }
}
```

- `viewrect=left,top,width,height` is in PDF points from the top-left of the page box and bounds the whole selection. Page rotation is not applied.
- `page=` is the page that holds the selection, which is not always the current page in spread or scroll layouts. A selection that crosses pages is recorded on its first page.
- `applyDecorations` draws `highlight` and `underline` styles as PDF annotations, one per text line. It finds `text.highlight` on the page and uses `viewrect`, then `text.before`, to pick the right occurrence. Without matching text it falls back to the text inside `viewrect`. A locator with neither cannot be drawn and is skipped with a log warning.
- Tapping a drawn decoration fires `onDecorationInteraction` instead of a chrome tap.
- Releasing a dragged selection handle can shorten the selection by a few characters. This is PDFKit's own behaviour (Files and Books do the same), so the plugin does not correct it.
- Scanned PDFs without a text layer cannot be selected or decorated.

On Android the same locator is written by the reader (androidx.pdf `PdfView`): `page=` is the page of the first selected rectangle, and `viewrect=` covers the selection rectangles on that page, in PDF points from the top-left. Decorations are painted with `PdfView.setHighlights`, found by searching the page for `text.highlight` and choosing the occurrence that overlaps `viewrect`. If the text is not found, the `viewrect` area itself is painted. An `underline` is drawn as a thin strip along each line. Page rotation is not applied.

**Don't duplicate upstream models.** If upstream Readium already models something (locator position, decoration style, …), use that representation rather than inventing a plugin-side parallel — alternatives create two sources of truth that diverge over time.

## Our additions to otherLocations

We try to enrich the Locator's `otherLocations` with the following data,
before it is sent over the bridge:

| Field | Type | Description |
|-------|------|-------------|
| `tocHref` | `string` | Current Table of Contents position in `href#identifier` format |
| `currentPage` | `int` | Current dynamic page-number of the rendered resource (starts from 1) |
| `totalPages` | `int` | Total number of dynamic pages of the rendered resource |
| `physicalPage` | `string` | Identifier for the most-recently passed physical page reference |
