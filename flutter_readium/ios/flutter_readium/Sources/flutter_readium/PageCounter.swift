import Foundation
import UIKit
import WebKit
import ReadiumShared

enum PageCountError: Error {
  case superseded
  case unavailable
  case noPublication
}

/// Counts pages in page-reading mode for the open publication.
///
/// Reflowable chapters are laid out in a hidden web view at the reader size.
/// A newer token abandons the run.
@MainActor
final class PageCounter: NSObject, WKNavigationDelegate {
  static let shared = PageCounter()

  private let handler = ResourceHandler()
  private var webView: WKWebView?
  private var activeToken = 0
  private var continuation: CheckedContinuation<Int, Never>?
  private var loadId = 0

  func cancel(_ token: Int) {
    activeToken = token
    continuation?.resume(returning: -1)
    continuation = nil
    webView?.stopLoading()
  }

  func count(publication: Publication, arguments: [String: Any]) async throws -> Int {
    let token = (arguments["token"] as? NSNumber)?.intValue ?? arguments["token"] as? Int ?? 0
    activeToken = token
    try check(token)

    if publication.conforms(to: Publication.Profile.audiobook) {
      throw PageCountError.unavailable
    }
    if !isReflowableEpub(publication) {
      if let declared = publication.metadata.numberOfPages, declared > 0 {
        return declared
      }
      return publication.readingOrder.count
    }

    let viewportWidth = doubleValue(arguments["viewportWidth"]) ?? 0
    let viewportHeight = doubleValue(arguments["viewportHeight"]) ?? 0
    if viewportWidth <= 0 || viewportHeight <= 0 {
      throw PageCountError.unavailable
    }
    let fontSize = doubleValue(arguments["fontSize"]) ?? 1
    let pageMargins = doubleValue(arguments["pageMargins"])
    let lineHeight = doubleValue(arguments["lineHeight"])
    let columnCount = arguments["columnCount"] as? String ?? "auto"
    let publisherStyles = arguments["publisherStyles"] as? Bool ?? false
    let css = loadCss(publisherStyles: publisherStyles)
    let style = userStyle(fontSize: fontSize, pageMargins: pageMargins, lineHeight: lineHeight, columnCount: columnCount)

    handler.publication = publication
    handler.owner = self
    let view = makeWebView(width: viewportWidth, height: viewportHeight)
    webView = view
    defer {
      view.stopLoading()
      view.navigationDelegate = nil
      view.removeFromSuperview()
      if webView === view {
        webView = nil
      }
    }

    var total = 0
    for link in publication.readingOrder {
      try check(token)
      if isHtml(link) {
        total += await measure(link: link, css: css, style: style, token: token)
      } else {
        total += 1
      }
      try await Task.sleep(nanoseconds: 16_000_000)
      try check(token)
    }
    return total
  }

  private func measure(link: Link, css: String, style: String, token: Int) async -> Int {
    let href = link.href.components(separatedBy: "#").first?.components(separatedBy: "?").first ?? link.href
    guard let bytes = await handler.data(for: href),
          var html = String(data: bytes, encoding: .utf8) ?? String(data: bytes, encoding: .isoLatin1) else {
      return 1
    }
    html = inject(html: html, css: css, style: style)
    let directory = (href as NSString).deletingLastPathComponent
    let base = directory.isEmpty ? "pagecount://local/" : "pagecount://local/\(directory)/"
    guard let baseURL = URL(string: base), let view = webView else { return 1 }

    let pages = await withCheckedContinuation { (next: CheckedContinuation<Int, Never>) in
      continuation?.resume(returning: -1)
      continuation = next
      loadId += 1
      let expected = loadId
      view.navigationDelegate = self
      view.loadHTMLString(html, baseURL: baseURL)
      Task { @MainActor in
        try? await Task.sleep(nanoseconds: 8_000_000_000)
        guard expected == self.loadId, let pending = self.continuation else { return }
        self.continuation = nil
        pending.resume(returning: 1)
      }
    }
    if activeToken != token || pages < 1 {
      return activeToken == token ? 1 : 0
    }
    return pages
  }

  func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
    let expected = loadId
      Task { @MainActor in
        try? await Task.sleep(nanoseconds: 50_000_000)
        guard expected == self.loadId, let pending = self.continuation else { return }
        self.continuation = nil
        let raw = try? await webView.evaluateJavaScript(Self.pageCountJS)
        let pages = (raw as? NSNumber)?.intValue ?? 1
        pending.resume(returning: max(1, pages))
      }
  }

  private func check(_ token: Int) throws {
    if activeToken != token {
      throw PageCountError.superseded
    }
  }

  private func makeWebView(width: Double, height: Double) -> WKWebView {
    let configuration = WKWebViewConfiguration()
    configuration.setURLSchemeHandler(handler, forURLScheme: "pagecount")
    let view = WKWebView(frame: CGRect(x: -10000, y: 0, width: width, height: height), configuration: configuration)
    view.isUserInteractionEnabled = false
    view.accessibilityElementsHidden = true
    if let host = UIApplication.shared.connectedScenes
      .compactMap({ $0 as? UIWindowScene })
      .flatMap(\.windows)
      .first(where: \.isKeyWindow) {
      host.addSubview(view)
    }
    return view
  }

  private static let pageCountJS =
    "(function(){var root=document.scrollingElement||document.documentElement;var width=root.clientWidth;if(!width)return 1;return Math.max(1,Math.round(root.scrollWidth/width));})();"
}

private func doubleValue(_ value: Any?) -> Double? {
  (value as? NSNumber)?.doubleValue
}

private func isReflowableEpub(_ publication: Publication) -> Bool {
  guard publication.conforms(to: Publication.Profile.epub) else { return false }
  if publication.metadata.layout == .fixed { return false }
  if !publication.readingOrder.isEmpty && publication.readingOrder.allSatisfy({ $0.properties["layout"]?.string == "fixed" }) {
    return false
  }
  return true
}

private func isHtml(_ link: Link) -> Bool {
  let href = link.href.lowercased()
  if href.hasSuffix(".html") || href.hasSuffix(".xhtml") || href.hasSuffix(".htm") {
    return true
  }
  let type = link.mediaType?.string.lowercased() ?? ""
  return type.contains("html")
}

private func userStyle(fontSize: Double, pageMargins: Double?, lineHeight: Double?, columnCount: String) -> String {
  let columns: String
  if columnCount == "2" {
    columns = "--RS__colCount: 2; column-count: 2 !important; column-width: auto !important;"
  } else {
    columns = "--RS__colCount: 1; column-count: 1 !important; column-width: 100vw !important;"
  }
  let margins = pageMargins.map { "--USER__pageMargins: \($0);" } ?? ""
  let leading = lineHeight.map { "--USER__advancedSettings: readium-advanced-on; --USER__lineHeight: \($0);" } ?? ""
  return "--USER__fontSize: \(fontSize * 100)%; \(margins) \(leading) \(columns)"
}

private func inject(html: String, css: String, style: String) -> String {
  let styled = addHtmlStyle(html: html, style: style)
  let block = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><style>\(css)</style>"
  if let range = styled.range(of: "<head", options: .caseInsensitive),
     let end = styled.range(of: ">", range: range.lowerBound..<styled.endIndex) {
    var copy = styled
    copy.insert(contentsOf: block, at: end.upperBound)
    return copy
  }
  return "<head>\(block)</head>\(styled)"
}

private func addHtmlStyle(html: String, style: String) -> String {
  guard let tag = html.range(of: "<html", options: .caseInsensitive),
        let end = html.range(of: ">", range: tag.lowerBound..<html.endIndex) else {
    return html
  }
  let attrs = html[tag.upperBound..<end.lowerBound]
  if let styleRange = attrs.range(of: "style\\s*=\\s*([\"'])(.*?)\\1", options: .regularExpression) {
    let existing = String(attrs[styleRange])
    let quote: Character = existing.contains("\"") ? "\"" : "'"
    let replacement = "style=\(quote)\(style)\(quote)"
    var copy = html
    let absolute = html.index(tag.upperBound, offsetBy: attrs.distance(from: attrs.startIndex, to: styleRange.lowerBound))
    let absoluteEnd = html.index(absolute, offsetBy: existing.count)
    copy.replaceSubrange(absolute..<absoluteEnd, with: replacement)
    return copy
  }
  var copy = html
  copy.insert(contentsOf: " style=\"\(style)\"", at: end.lowerBound)
  return copy
}

private func loadCss(publisherStyles: Bool) -> String {
  var names = ["ReadiumCSS-before.css"]
  if !publisherStyles {
    names.append("ReadiumCSS-default.css")
  }
  names.append("ReadiumCSS-after.css")
  let sheets = names.compactMap(readiumStylesheet)
  return (sheets + [pageLayoutCss]).joined(separator: "\n")
}

private func readiumStylesheet(_ name: String) -> String? {
  for bundle in Bundle.allBundles + Bundle.allFrameworks {
    if let nested = bundle.url(forResource: "ReadiumNavigator", withExtension: "bundle"),
       let inner = Bundle(url: nested),
       let url = inner.url(forResource: name, withExtension: nil, subdirectory: "Assets/Static/readium-css"),
       let text = try? String(contentsOf: url, encoding: .utf8) {
      return text
    }
  }
  return nil
}

private let pageLayoutCss = """
html {
  height: 100vh !important;
  margin: 0 !important;
  column-gap: 0 !important;
  column-fill: auto !important;
  overflow-x: auto !important;
}
body { margin: 0 !important; }
"""

private final class ResourceHandler: NSObject, WKURLSchemeHandler {
  weak var owner: PageCounter?
  var publication: Publication?
  private var stopped = Set<ObjectIdentifier>()

  func webView(_ webView: WKWebView, start urlSchemeTask: WKURLSchemeTask) {
    let id = ObjectIdentifier(urlSchemeTask)
    let path = urlSchemeTask.request.url?.path ?? ""
    Task {
      guard !stopped.contains(id) else { return }
      guard let data = await data(for: path) else {
        if !stopped.contains(id) {
          urlSchemeTask.didFailWithError(URLError(.fileDoesNotExist))
        }
        return
      }
      guard !stopped.contains(id),
            let url = urlSchemeTask.request.url,
            let response = HTTPURLResponse(
              url: url,
              statusCode: 200,
              httpVersion: nil,
              headerFields: ["Content-Type": mime(for: path)]
            ) else { return }
      urlSchemeTask.didReceive(response)
      urlSchemeTask.didReceive(data)
      urlSchemeTask.didFinish()
    }
  }

  func webView(_ webView: WKWebView, stop urlSchemeTask: WKURLSchemeTask) {
    stopped.insert(ObjectIdentifier(urlSchemeTask))
  }

  func data(for path: String) async -> Data? {
    guard let publication else { return nil }
    let trimmed = path.hasPrefix("/") ? String(path.dropFirst()) : path
    let decoded = trimmed.removingPercentEncoding ?? trimmed
    guard let relative = RelativeURL(path: decoded),
          let link = publication.linkWithHREF(relative),
          let resource = publication.get(link) else {
      return nil
    }
    var bytes = Data()
    let streamed = await resource.stream { chunk in
      bytes.append(chunk)
    }
    guard case .success = streamed else { return nil }
    return bytes
  }

  private func mime(for path: String) -> String {
    let lower = path.lowercased()
    if lower.hasSuffix(".png") { return "image/png" }
    if lower.hasSuffix(".jpg") || lower.hasSuffix(".jpeg") { return "image/jpeg" }
    if lower.hasSuffix(".gif") { return "image/gif" }
    if lower.hasSuffix(".svg") { return "image/svg+xml" }
    if lower.hasSuffix(".webp") { return "image/webp" }
    if lower.hasSuffix(".css") { return "text/css" }
    return "application/xhtml+xml"
  }
}
