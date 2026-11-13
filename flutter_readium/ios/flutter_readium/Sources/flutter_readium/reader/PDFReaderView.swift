import ReadiumNavigator
import ReadiumShared
import Flutter
import PDFKit
import UIKit

public class PDFReaderView: NSObject, FlutterPlatformView, ReadiumReaderView, PDFNavigatorDelegate, SelectionActionHandling, UIGestureRecognizerDelegate {

  private let channel: ReadiumReaderChannel
  private let _view: EPUBContainerView
  private let pdfViewController: PDFNavigatorViewController
  private var navigationAdapter: DirectionalNavigationAdapter?
  private var hasSentReady = false
  private var isJumpingToLocator = false
  private let publication: Publication
  private var lastViewport: NavigatorViewport?
  private var documentObserver: NSObjectProtocol?

  /// The navigator replaces its PDF view when the layout changes.
  weak var pdfView: PDFDocumentView?
  var decorationsByGroup: [String: [Decoration]] = [:]
  var renderedDecorations: [String: [RenderedPDFDecoration]] = [:]

  var publicationIdentifier: String?

  public func view() -> UIView {
    Log.reader.debug("getView")
    return _view
  }

  deinit {
    Log.reader.info("deinit PDFReaderView")
    if let documentObserver {
      NotificationCenter.default.removeObserver(documentObserver)
    }
    pdfViewController.view.removeFromSuperview()
    pdfViewController.delegate = nil
    channel.setMethodCallHandler(nil)
    FlutterReadiumPlugin.instance?.clearCurrentReaderView(ifIs: self)
  }

  init(
    frame: CGRect,
    viewIdentifier viewId: Int64,
    arguments args: Any?,
    registrar: FlutterPluginRegistrar
  ) {
    Log.reader.info("init")
    let creationParams = args as! Dictionary<String, Any?>

    let publication = FlutterReadiumPlugin.instance!.getCurrentPublication()!
    self.publication = publication
    self.publicationIdentifier = publication.metadata.identifier

    let locatorStr = creationParams["initialLocator"] as? String
    let locator = locatorStr == nil ? nil : try! Locator(legacyJSONString: locatorStr!)
    Log.reader.debug("publication = \(publication)")

    channel = ReadiumReaderChannel(
      name: "\(readiumReaderViewType):\(viewId)", binaryMessenger: registrar.messenger())

    emitReaderStatusChanged(status: ReadiumReaderStatusLoading)

    Log.reader.info("Publication: (identifier=\(String(describing: publication.metadata.identifier)),title=\(String(describing: publication.metadata.title)))")
    Log.reader.info("Added PDF publication at \(String(describing: publication.baseURL))")

    let containerView = EPUBContainerView()
    var config = PDFNavigatorViewController.Configuration()
    config.editingActions = Self.editingActions(
      allowedDefaultActions: creationParams["allowedDefaultActions"] as? [String],
      selectionActions: creationParams["selectionActions"] as? [[String: Any]] ?? [],
      container: containerView
    )

    pdfViewController = try! PDFNavigatorViewController(
      publication: publication,
      initialLocation: locator,
      config: config
    )

    _view = containerView
    super.init()
    containerView.readerView = self

    channel.setMethodCallHandler(onMethodCall)
    pdfViewController.delegate = self

    let child: UIView = pdfViewController.view
    let view = _view
    view.addSubview(pdfViewController.view)

    child.translatesAutoresizingMaskIntoConstraints = false

    NSLayoutConstraint.activate(
      [
        child.leadingAnchor.constraint(equalTo: view.leadingAnchor),
        child.trailingAnchor.constraint(equalTo: view.trailingAnchor),
        child.topAnchor.constraint(equalTo: view.topAnchor),
        child.bottomAnchor.constraint(equalTo: view.bottomAnchor)
      ]
    )

    FlutterReadiumPlugin.instance?.registerAsCurrentReaderView(self)

    /// Arrow keys turn pages. Taps are reported to Flutter instead, which
    /// decides what each part of the screen does.
    navigationAdapter = DirectionalNavigationAdapter(
      pointerPolicy: .init(types: [])
    )
    navigationAdapter?.bind(to: pdfViewController)

    Log.reader.debug("init success")
  }

  // MARK: - PDFNavigatorDelegate / NavigatorDelegate

  public func navigatorContentInset(_ navigator: VisualNavigator) -> UIEdgeInsets? {
    // All margin & safe-area is handled on the Flutter side.
    return .init(top: 0, left: 0, bottom: 0, right: 0)
  }

  public func navigator(_ navigator: PDFNavigatorViewController, setupPDFView view: PDFDocumentView) {
    pdfView = view
    // The document is assigned after this call, and a new document has no annotations.
    if let documentObserver {
      NotificationCenter.default.removeObserver(documentObserver)
    }
    documentObserver = NotificationCenter.default.addObserver(
      forName: .PDFViewDocumentChanged,
      object: view,
      queue: .main
    ) { [weak self] _ in
      self?.renderAllDecorations()
    }

    // PDFKit reports a tap only after its double-tap zoom recognizer fails.
    // The book reports the tap as soon as the finger lifts, so the chrome
    // uses that same moment.
    view.gestureRecognizers?
      .filter { $0 is PdfChromeTapRecognizer }
      .forEach(view.removeGestureRecognizer)
    let recognizer = PdfChromeTapRecognizer()
    recognizer.cancelsTouchesInView = false
    recognizer.delaysTouchesBegan = false
    recognizer.delaysTouchesEnded = false
    recognizer.delegate = self
    recognizer.onTap = { [weak self] point in
      self?.forwardTap(at: point, in: view)
    }
    view.addGestureRecognizer(recognizer)
  }

  public func gestureRecognizer(
    _ gestureRecognizer: UIGestureRecognizer,
    shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
  ) -> Bool {
    true
  }

  private func forwardTap(at point: CGPoint, in pdfView: PDFView) {
    // As in the book, a tap on a decoration is not a tap for the reader chrome.
    if let hit = decoration(at: point, in: pdfView) {
      channel.onDecorationInteraction(
        decorationId: hit.decoration.id,
        group: hit.group,
        type: "tap",
        locator: hit.decoration.locator
      )
      return
    }
    let navigatorView = pdfViewController.view!
    let location = navigatorView.convert(point, from: pdfView)
    let size = navigatorView.bounds.size
    guard size.width > 0, size.height > 0 else { return }
    channel.onTap(
      x: Double(location.x / size.width),
      y: Double(location.y / size.height)
    )
  }

  public func navigator(_ navigator: SelectableNavigator, shouldShowMenuForSelection selection: Selection) -> Bool {
    let locator = selectionLocator(from: selection.locator)
    channel.onTextSelected(locator: locator, selectedText: locator.text.highlight)
    return true
  }

  public func navigator(_ navigator: SelectableNavigator, canPerformAction action: EditingAction, for selection: Selection) -> Bool {
    return true
  }

  func handleSelectionAction(actionId: String) {
    guard let selection = pdfViewController.currentSelection else {
      Log.reader.warn("handleSelectionAction: no current selection")
      return
    }
    let locator = selectionLocator(from: selection.locator)
    channel.onSelectionAction(
      actionId: actionId,
      locator: locator,
      selectedText: locator.text.highlight
    )
    if actionId == "save" {
      pdfViewController.clearSelection()
    }
  }

  private static func editingActions(
    allowedDefaultActions: [String]?,
    selectionActions: [[String: Any]],
    container: EPUBContainerView
  ) -> [EditingAction] {
    var editingActions: [EditingAction]
    if let allowedDefaults = allowedDefaultActions {
      editingActions = []
      for name in allowedDefaults {
        switch name {
        case "copy": editingActions.append(.copy)
        case "share": editingActions.append(.share)
        case "lookup": editingActions.append(.lookup)
        case "translate": editingActions.append(.translate)
        default: break
        }
      }
    } else {
      editingActions = EditingAction.defaultActions
    }

    let actions = selectionActions.compactMap { dict -> (id: String, title: String)? in
      guard let id = dict["id"] as? String, let title = dict["title"] as? String else { return nil }
      return (id: id, title: title)
    }
    if !actions.isEmpty {
      container.configureActions(actions)
      editingActions += container.editingActions()
    }
    return editingActions
  }

  public func navigator(_ navigator: Navigator, presentError error: NavigatorError) {
    Log.reader.error("Should present error: \(error)")
  }

  public func navigator(_ navigator: Navigator, didFailToLoadResourceAt href: ReadiumShared.RelativeURL, withError error: ReadiumShared.ReadError) {
    Log.reader.warn("didFailToLoadResourceAt: \(href). err: \(error)")
    emitReaderStatusChanged(status: ReadiumReaderStatusError)

    let payload = FlutterReadiumError(message: error.localizedDescription, code: "ResourceReadError", data: ["href": href.string])
    FlutterReadiumPlugin.instance?.errorStreamHandler?.sendEvent(payload.toJsonString())
  }

  public func navigator(_ navigator: any Navigator, didJumpTo locator: Locator) {
    Log.reader.debug("didJumpTo: \(locator)")
    isJumpingToLocator = false
  }

  public func navigator(_ navigator: any ViewportObservingNavigator, viewportDidChange viewport: NavigatorViewport?) {
    lastViewport = viewport
  }

  public func navigator(_ navigator: Navigator, locationDidChange locator: Locator) {
    Log.reader.debug("locationDidChange: \(locator)")
    if (!hasSentReady) {
      channel.onReaderReady()
      emitReaderStatusChanged(status: ReadiumReaderStatusReady)
      hasSentReady = true
    }
    emitOnPageChanged(locator: locator)
  }

  public func navigator(_ navigator: Navigator, presentExternalURL url: URL) {
    guard ["http", "https"].contains(url.scheme?.lowercased() ?? "") else {
      Log.reader.warn("skipped non-http external URL: \(url)")
      return
    }
    emitOnExternalLinkActivated(url: url)
  }

  // MARK: - ReadiumReaderView protocol

  public func getCurrentLocation() -> Locator? {
    return self.pdfViewController.currentLocation
  }

  public func getFirstVisibleLocator() async -> Locator? {
    // PDF shows a single page at a time — the current location is the first
    // (and only) visible locator.
    return self.pdfViewController.currentLocation
  }

  public func goToLocator(_ locator: Locator, animated: Bool) async -> Bool {
    Log.reader.debug("goToLocator: \(locator)")
    isJumpingToLocator = true
    return await pdfViewController.go(to: locator, options: NavigatorGoOptions(animated: animated))
  }

  public func goToProgression(_ progression: Double, animated: Bool) async -> Bool {
    Log.reader.debug("goToProgression: \(progression)")
    guard let totalPages = publication.metadata.numberOfPages, totalPages > 0 else {
      Log.reader.warn("goToProgression: numberOfPages unknown, cannot navigate.")
      return false
    }
    let coerced = min(max(progression, 0.0), 1.0)
    let targetPage = Int((coerced * Double(totalPages - 1)).rounded()) + 1
    let clampedPage = min(max(targetPage, 1), totalPages)
    Log.reader.debug("goToProgression: progression=\(coerced) -> page \(clampedPage)/\(totalPages)")

    guard let currentLocator = getCurrentLocation() else {
      return false
    }
    let newLocator = currentLocator.copy(locations: { locs in
      locs.fragments = []
      locs.otherLocations = [:]
      locs.progression = coerced
      locs.position = clampedPage
    })
    return await pdfViewController.go(to: newLocator, options: NavigatorGoOptions(animated: animated))
  }

  public func syncToLocator(_ locator: Locator, animated: Bool, segmentDuration: TimeInterval?, isWordRange: Bool) async -> Bool {
    // PDF has no media-overlay or pre-recorded-audio sync to honour.
    Log.reader.debug("syncToLocator: ignored for PDF")
    return false
  }

  public func onCustomEditingAction() -> Void {
    Log.reader.debug("onCustomEditingAction: not supported for PDF")
  }

  public func setMOActive(_ active: Bool) {
    // PDF has no CSS column layout — no-op.
  }

  // MARK: - Locator emission

  private func emitOnPageChanged(locator: Locator) -> Void {
    Log.reader.debug("emitOnPageChanged, locator: \(locator)")

    Task.detached(priority: .high) { [locator] in
      var resultLocator = locator

      // PDF TOC enrichment: find the last TOC entry whose "#page=N" fragment
      // is ≤ the current position, then attach its href and title to the locator.
      if let position = locator.locations.position {
        let tocEntry = await self.publication.getFlattenedToC()
          .compactMap { link -> (Int, Link)? in
            let parts = link.href.components(separatedBy: "#")
            guard parts.count == 2,
                  let fragment = parts.last,
                  fragment.hasPrefix("page="),
                  let tocPage = Int(fragment.dropFirst(5)) else { return nil }
            return (tocPage, link)
          }
          .filter { $0.0 <= position }
          .max(by: { $0.0 < $1.0 })?.1
        if let entry = tocEntry {
          resultLocator.title = entry.title
          resultLocator.locations.otherLocations["tocHref"] = .string(entry.href)
        }
      }

      let finalLocator = resultLocator
      await MainActor.run() {
        self.channel.onPageChanged(locator: finalLocator)
        do {
          FlutterReadiumPlugin.instance?.textLocatorStreamHandler?.sendEvent(try finalLocator.jsonString())
        } catch {
          // `try?` used to discard this error, so a serialization failure left no
          // trace anywhere: no event on the stream and nothing in the logs.
          Log.reader.error("Failed to serialize locator for text-locator stream: \(error)")
        }
      }
    }
  }

  private func emitOnExternalLinkActivated(url: URL) {
    Log.reader.info("emitOnExternalLinkActivated: \(url)")
    Task.detached(priority: .high) {
      await MainActor.run() {
        self.channel.onExternalLinkActivated(url: url)
      }
    }
  }

  // MARK: - Flutter method-channel handler

  func onMethodCall(call: FlutterMethodCall, result: @escaping FlutterResult) {
    Log.reader.debug("onMethodCall: \(call.method)")
    switch call.method {
    case "go":
      let args = call.arguments as! [Any?]
      let locator = try! Locator(legacyJSONString: args[0] as! String, warnings: readiumBugLogger)!
      let animated = args[1] as? Bool ?? false

      Task.detached(priority: .high) {
        let success = await self.goToLocator(locator, animated: animated)
        await MainActor.run() {
          result(success)
        }
      }
    case "goBackward":
      let animated = call.arguments as? Bool ?? false
      let navOptions = NavigatorGoOptions(animated: animated)
      let pdfViewController = self.pdfViewController

      Task.detached(priority: .high) {
        let success = await pdfViewController.goBackward(options: navOptions)
        await MainActor.run() {
          result(success)
        }
      }
    case "goForward":
      let animated = call.arguments as? Bool ?? false
      let navOptions = NavigatorGoOptions(animated: animated)
      let pdfViewController = self.pdfViewController

      Task.detached(priority: .high) {
        let success = await pdfViewController.goForward(options: navOptions)
        await MainActor.run() {
          result(success)
        }
      }
    case "setPreferences":
      let args = call.arguments as! [String: Any]
      let preferences = FlutterPDFPreferences(fromMap: args)
      pdfViewController.submitPreferences(preferences.readium)
      result(nil)
    case "applyDecorations":
      let args = call.arguments as! [Any?]
      let identifier = args[0] as! String
      let decorationsStr = args[1] as! [String]
      guard let decorations = try? decorationsStr.map({ try Decoration(fromJson: $0) }) else {
        return result(FlutterError.init(
          code: "JSON mapping error",
          message: "Could not map decorations from JSON: \(decorationsStr)",
          details: nil))
      }
      applyDecorations(decorations, forGroup: identifier)
      result(nil)
    case "configureSelectionActions":
      let args = call.arguments as! [[String: Any]]
      let actions = args.compactMap { dict -> (id: String, title: String)? in
        guard let id = dict["id"] as? String, let title = dict["title"] as? String else { return nil }
        return (id: id, title: title)
      }
      _view.configureActions(actions)
      result(nil)
    case "notifyUserNavigation":
      // Also called unconditionally on every user tap/swipe (see
      // reader_widget.dart's `_onInteraction`), to let narration-driven
      // readers detect manual navigation and exit sync mode. PDF has no
      // narration/Media Overlay concept, so this is a no-op here too.
      Log.reader.warn("notifyUserNavigation: not supported for PDF")
      result(nil)
    case "dispose":
      Log.reader.info("Disposing pdfViewController")
      pdfViewController.view.removeFromSuperview()
      pdfViewController.delegate = nil
      FlutterReadiumPlugin.instance?.clearCurrentReaderView(ifIs: self)
      emitReaderStatusChanged(status: ReadiumReaderStatusClosed)
      result(nil)
    default:
      Log.reader.warn("Unhandled call: \(call.method)")
      result(FlutterMethodNotImplemented)
    }
  }
}

/// Reports a tap when the finger lifts, without waiting for PDFKit to decide
/// the gesture was not a double-tap zoom. A drag or a long press is not a tap,
/// matching the book: the chrome hears the lift of a tap and nothing else.
private final class PdfChromeTapRecognizer: UIGestureRecognizer {
  var onTap: ((CGPoint) -> Void)?

  private var origin: CGPoint?
  private var beganAt: TimeInterval?
  private var dismissesSelection = false
  private let slop: CGFloat = 10
  private let maximumDuration: TimeInterval = 0.5

  override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
    guard
      state == .possible,
      touches.count == 1,
      event.allTouches?.count == 1,
      let touch = touches.first
    else {
      state = .failed
      return
    }
    origin = touch.location(in: view)
    beganAt = touch.timestamp
    dismissesSelection = (view as? PDFView)?.currentSelection != nil
  }

  override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent) {
    guard let origin, let touch = touches.first else { return }
    if distance(from: origin, to: touch.location(in: view)) > slop {
      state = .failed
    }
  }

  override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent) {
    guard state == .possible, let origin, let beganAt, let touch = touches.first else {
      state = .failed
      return
    }
    let point = touch.location(in: view)
    let held = touch.timestamp - beganAt
    guard
      !dismissesSelection,
      distance(from: origin, to: point) <= slop,
      held <= maximumDuration
    else {
      state = .failed
      return
    }
    state = .recognized
    onTap?(point)
  }

  override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent) {
    state = .cancelled
  }

  override func reset() {
    super.reset()
    origin = nil
    beganAt = nil
    dismissesSelection = false
  }

  private func distance(from start: CGPoint, to end: CGPoint) -> CGFloat {
    hypot(end.x - start.x, end.y - start.y)
  }
}
