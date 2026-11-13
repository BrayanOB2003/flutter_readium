import Foundation
import PDFKit
import ReadiumNavigator
import ReadiumShared
import UIKit

/// A decoration drawn into the open PDF document as PDFKit annotations, one per text line.
struct RenderedPDFDecoration {
  let decoration: Decoration
  let page: PDFPage
  let annotations: [PDFAnnotation]
}

/// Selection locators and decorations for PDF. Readium's PDF navigator is not a
/// `DecorableNavigator`, so highlights are drawn as PDFKit annotations here.
/// The locator format is described in `docs/api-reference/locator.md#pdf-locators`.
extension PDFReaderView {

  // MARK: - Selection locator

  /// Upstream builds the selection locator from the current page and the text only.
  /// This points it at the page that holds the selection and the area it covers,
  /// using the `page=` and `viewrect=` fragments from the Readium Locator model.
  func selectionLocator(from base: Locator) -> Locator {
    guard
      let pdfView,
      let document = pdfView.document,
      let selection = pdfView.currentSelection,
      let page = selection.pages.first
    else {
      return base
    }
    let index = document.index(for: page)
    guard index != NSNotFound else { return base }

    let pageNumber = index + 1
    let box = page.bounds(for: pdfView.displayBox)
    let viewRect = Self.viewRect(fromPageRect: selection.bounds(for: page), in: box)
    let progression = Double(index) / Double(max(document.pageCount, 1))
    let context = Self.textContext(of: selection, on: page)

    return base.copy(
      locations: { locations in
        locations.fragments = ["page=\(pageNumber)", viewRect]
        locations.position = pageNumber
        locations.progression = progression
        locations.totalProgression = progression
      },
      text: { text in
        if text.highlight == nil { text.highlight = selection.string }
        if let before = context.before { text.before = before }
        if let after = context.after { text.after = after }
      }
    )
  }

  private static let contextLength = 32

  private static func textContext(of selection: PDFSelection, on page: PDFPage) -> (before: String?, after: String?) {
    guard
      selection.numberOfTextRanges(on: page) > 0,
      let pageText = page.string as NSString?
    else {
      return (nil, nil)
    }
    let range = selection.range(at: 0, on: page)
    guard range.location != NSNotFound, NSMaxRange(range) <= pageText.length else { return (nil, nil) }

    let beforeStart = max(0, range.location - contextLength)
    let before = pageText.substring(with: NSRange(location: beforeStart, length: range.location - beforeStart))
    let afterEnd = min(pageText.length, NSMaxRange(range) + contextLength)
    let after = pageText.substring(with: NSRange(location: NSMaxRange(range), length: afterEnd - NSMaxRange(range)))
    return (before.isEmpty ? nil : before, after.isEmpty ? nil : after)
  }

  // MARK: - viewrect fragment

  /// RFC 3778 `viewrect=left,top,width,height`, in points from the top-left of the page box.
  static func viewRect(fromPageRect rect: CGRect, in box: CGRect) -> String {
    let left = rect.minX - box.minX
    let top = box.maxY - rect.maxY
    return String(format: "viewrect=%.2f,%.2f,%.2f,%.2f", left, top, rect.width, rect.height)
  }

  static func pageRect(fromViewRectIn locator: Locator, box: CGRect) -> CGRect? {
    guard let fragment = locator.locations.fragments.first(where: { $0.hasPrefix("viewrect=") }) else {
      return nil
    }
    let values = fragment.dropFirst("viewrect=".count)
      .split(separator: ",")
      .compactMap { Double($0.trimmingCharacters(in: .whitespaces)) }
    guard values.count == 4, values[2] > 0, values[3] > 0 else { return nil }
    return CGRect(
      x: box.minX + values[0],
      y: box.maxY - values[1] - values[3],
      width: values[2],
      height: values[3]
    )
  }

  static func pageNumber(of locator: Locator) -> Int? {
    for fragment in locator.locations.fragments where fragment.hasPrefix("page=") {
      if let page = Int(fragment.dropFirst("page=".count)) { return page }
    }
    return locator.locations.position
  }

  // MARK: - Applying decorations

  public func applyDecorations(_ decorations: [Decoration], forGroup groupIdentifier: String) {
    Log.reader.debug("applyDecorations: group=\(groupIdentifier), count=\(decorations.count)")
    decorationsByGroup[groupIdentifier] = decorations
    renderDecorations(inGroup: groupIdentifier)
  }

  /// Called when PDFKit swaps the document, which drops every annotation.
  func renderAllDecorations() {
    renderedDecorations.removeAll()
    for group in decorationsByGroup.keys {
      renderDecorations(inGroup: group)
    }
  }

  private func renderDecorations(inGroup group: String) {
    for rendered in renderedDecorations[group] ?? [] {
      rendered.annotations.forEach(rendered.page.removeAnnotation)
    }
    renderedDecorations[group] = nil

    guard let pdfView, let document = pdfView.document else {
      Log.reader.debug("applyDecorations: no document yet, will draw group \(group) when it loads")
      return
    }
    var rendered: [RenderedPDFDecoration] = []
    for decoration in decorationsByGroup[group] ?? [] {
      guard
        let pageNumber = Self.pageNumber(of: decoration.locator),
        let page = document.page(at: pageNumber - 1)
      else {
        Log.reader.warn("applyDecorations: no page for decoration \(decoration.id)")
        continue
      }
      let lines = Self.lineSelections(for: decoration.locator, on: page, displayBox: pdfView.displayBox)
      guard !lines.isEmpty else {
        Log.reader.warn("applyDecorations: text not found for decoration \(decoration.id) on page \(pageNumber)")
        continue
      }
      let annotations = lines.map { Self.annotation(for: decoration, bounds: $0.bounds(for: page)) }
      annotations.forEach(page.addAnnotation)
      rendered.append(RenderedPDFDecoration(decoration: decoration, page: page, annotations: annotations))
    }
    renderedDecorations[group] = rendered
    let requested = decorationsByGroup[group]?.count ?? 0
    Log.reader.debug("applyDecorations: drew \(rendered.count) of \(requested) decorations in group \(group)")
  }

  /// Locates the decorated text on the page. When the phrase occurs more than once,
  /// `viewrect` picks the occurrence, then `text.before`. Without matching text,
  /// the text inside `viewrect` is used.
  private static func lineSelections(for locator: Locator, on page: PDFPage, displayBox: PDFDisplayBox) -> [PDFSelection] {
    let area = pageRect(fromViewRectIn: locator, box: page.bounds(for: displayBox))
    if let highlight = locator.text.highlight, !highlight.isEmpty,
       let match = bestOccurrence(of: highlight, before: locator.text.before, near: area, on: page) {
      return match.selectionsByLine()
    }
    if let area, let selection = page.selection(for: area), selection.string?.isEmpty == false {
      return selection.selectionsByLine()
    }
    return []
  }

  private static func bestOccurrence(of text: String, before: String?, near area: CGRect?, on page: PDFPage) -> PDFSelection? {
    guard let pageText = page.string as NSString?, pageText.length > 0 else { return nil }

    var candidates: [(selection: PDFSelection, range: NSRange)] = []
    var searchStart = 0
    while searchStart < pageText.length, candidates.count < 100 {
      let found = pageText.range(
        of: text,
        options: [],
        range: NSRange(location: searchStart, length: pageText.length - searchStart)
      )
      if found.location == NSNotFound { break }
      if let selection = page.selection(for: found) {
        candidates.append((selection, found))
      }
      searchStart = found.location + 1
    }
    guard candidates.count > 1 else { return candidates.first?.selection }

    if let area {
      let overlapping = candidates
        .map { ($0.selection, overlap($0.selection.bounds(for: page), area)) }
        .filter { $0.1 > 0 }
      if let best = overlapping.max(by: { $0.1 < $1.1 }) { return best.0 }
    }
    if let before = before?.trimmingCharacters(in: .whitespacesAndNewlines), !before.isEmpty {
      let match = candidates.first { candidate in
        pageText.substring(to: candidate.range.location)
          .trimmingCharacters(in: .whitespacesAndNewlines)
          .hasSuffix(before)
      }
      if let match { return match.selection }
    }
    return candidates.first?.selection
  }

  private static func overlap(_ a: CGRect, _ b: CGRect) -> CGFloat {
    let intersection = a.intersection(b)
    return intersection.isNull ? 0 : intersection.width * intersection.height
  }

  private static func annotation(for decoration: Decoration, bounds: CGRect) -> PDFAnnotation {
    let isUnderline = decoration.style.id == .underline
    let annotation = PDFAnnotation(bounds: bounds, forType: isUnderline ? .underline : .highlight, withProperties: nil)
    let tint = (decoration.style.config as? Decoration.Style.HighlightConfig)?.tint
    annotation.color = tint ?? UIColor.systemYellow.withAlphaComponent(0.4)
    // Quad points are relative to the annotation origin: top-left, top-right, bottom-left, bottom-right.
    annotation.quadrilateralPoints = [
      CGPoint(x: 0, y: bounds.height),
      CGPoint(x: bounds.width, y: bounds.height),
      CGPoint(x: 0, y: 0),
      CGPoint(x: bounds.width, y: 0),
    ].map { NSValue(cgPoint: $0) }
    return annotation
  }

  // MARK: - Interaction

  /// Returns the decoration under a point in the PDF view's coordinates.
  func decoration(at point: CGPoint, in pdfView: PDFView) -> (group: String, decoration: Decoration)? {
    guard let page = pdfView.page(for: point, nearest: false) else { return nil }
    let pagePoint = pdfView.convert(point, to: page)
    for (group, rendered) in renderedDecorations {
      for item in rendered where item.page == page {
        if item.annotations.contains(where: { $0.bounds.insetBy(dx: -2, dy: -2).contains(pagePoint) }) {
          return (group, item.decoration)
        }
      }
    }
    return nil
  }
}
