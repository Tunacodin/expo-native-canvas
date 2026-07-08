import ExpoModulesCore
import UIKit
import CoreText

/**
 * Native canvas view (iOS) — Android ile full parite.
 *
 * Tool'lar: pen | eraser | select
 * Select alt-modlar: rect | lasso (pick); move | resize | rotate (transform)
 * Bounded canvas + dış gri overlay, scale-aware stroke (constant screen-px),
 * 1sn duration/idle gating için stroke timing event'i, replaceLastStrokeWithShape.
 */
public class ExpoNativeCanvasView: ExpoView {
  let onStrokeCountChange = EventDispatcher()
  let onZoomChange = EventDispatcher()
  // NOT: Apple Pencil 2 double-tap / Pencil Pro squeeze ayrı bir paket
  // (expo-stylus-button) tarafından yakalanıp app/canvas.tsx'te tool toggle
  // için kullanılıyor. Burada UIPencilInteraction tekrar eklenirse aynı olay
  // iki kez delegate'e gider ve toggle çift tetiklenir — yapma.

  private let contentView: CanvasContentView

  required init(appContext: AppContext? = nil) {
    contentView = CanvasContentView(frame: .zero)
    super.init(appContext: appContext)
    contentView.frame = bounds
    contentView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
    contentView.onCountChanged = { [weak self] total, redo, points, durationMs, idleMs in
      self?.onStrokeCountChange([
        "totalStrokes": total,
        "redoCount": redo,
        "points": points,
        "durationMs": durationMs,
        "idleMs": idleMs,
      ])
    }
    contentView.onZoomChanged = { [weak self] z in
      self?.onZoomChange([
        "zoom": z,
        "rotation": self?.contentView.currentRotation() ?? 0,
      ])
    }
    addSubview(contentView)
  }

  // ─── Prop setters ──────────────────────────────────────────────────────
  func setImageUri(_ uri: String?) { contentView.setImageUri(uri) }

  func setTool(_ tool: String) {
    let newTool: CanvasTool = {
      switch tool {
      case "eraser": return .eraser
      case "select": return .select
      case "image": return .image
      default: return .pen
      }
    }()
    // SELECT'ten çıkarken seçimi temizle
    if contentView.activeTool == .select && newTool != .select {
      contentView.clearSelection()
    }
    // SELECT/IMAGE'a girerken aktif çizimi iptal et
    if newTool == .select || newTool == .image {
      contentView.cancelLiveStrokePublic()
    }
    contentView.activeTool = newTool
  }

  func setStrokeColor(_ hex: String) { contentView.strokeColor = parseColor(hex) }
  func setStrokeWidth(_ width: CGFloat) { contentView.strokeWidth = width }
  func setStrokeOpacity(_ opacity: CGFloat) { contentView.strokeOpacity = max(0, min(1, opacity)) }
  func setMinZoom(_ z: CGFloat) { contentView.minZoom = z }
  func setMaxZoom(_ z: CGFloat) { contentView.maxZoom = z }
  func setCanvasBackgroundColor(_ hex: String) {
    contentView.canvasBgColor = parseColor(hex)
    contentView.setNeedsDisplay()
  }
  func setCanvasOverlay(_ overlay: String) {
    contentView.canvasOverlay = overlay
    contentView.setNeedsDisplay()
  }
  func setSelectionMode(_ mode: String) {
    contentView.setSelectionMode(mode)
  }

  // ─── View commands ─────────────────────────────────────────────────────
  func runUndo() { contentView.undo() }
  func runRedo() { contentView.redo() }
  func runClear() { contentView.clear() }
  func runReplaceLastStrokeWithShape(_ shape: [String: Any]) {
    contentView.replaceLastStrokeWithShape(shape)
  }
  func runResetCanvasTransform() { contentView.resetCanvasTransform() }
  func runResetImageTransform() { contentView.resetImageTransform() }
  func runInsertText(_ text: String, fontSize: CGFloat, color: String) {
    contentView.insertText(text, fontSize: fontSize, color: parseColor(color))
  }
  func runSetSelectedStrokeColor(_ color: String) {
    contentView.setSelectedStrokeColor(parseColor(color))
  }
  func runDeleteSelection() { contentView.deleteSelection() }

  private func parseColor(_ hex: String) -> UIColor {
    var clean = hex.replacingOccurrences(of: "#", with: "")
    if clean.count == 3 {
      clean = clean.map { "\($0)\($0)" }.joined()
    }
    guard clean.count == 6, let v = UInt32(clean, radix: 16) else { return .black }
    let r = CGFloat((v >> 16) & 0xff) / 255.0
    let g = CGFloat((v >> 8) & 0xff) / 255.0
    let b = CGFloat(v & 0xff) / 255.0
    return UIColor(red: r, green: g, blue: b, alpha: 1.0)
  }
}

enum CanvasTool { case pen, eraser, select, image }
enum SelectMode { case idle, drawingRect, drawingLasso, moving, resizing, rotating, rotatingHandle }
enum SelectionPickMode { case rect, lasso }

class CanvasContentView: UIView {
  // ─── Render state ──────────────────────────────────────────────────────
  private var strokes: [Stroke] = []
  // Snapshot tabanlı geçmiş — silgi artık birden çok stroke'u mutate ettiği için
  // (gerçek vektörel kesme) tek-stroke pop yeterli değil; tüm diziyi snapshot'la.
  private var undoStack: [[Stroke]] = []
  private var redoStack: [[Stroke]] = []
  private let maxHistory = 80
  private let livePath = UIBezierPath()
  private var liveActive = false
  private var liveLastPoint = CGPoint.zero
  private var liveTool: CanvasTool = .pen
  private var liveColor: UIColor = .black
  private var liveWidth: CGFloat = 8
  private var liveOpacity: CGFloat = 1
  private var livePoints: [CGFloat] = []  // [x0,y0,x1,y1,...]
  private var liveStartMs: Int64 = 0
  private var lastMoveMs: Int64 = 0
  // Apple Pencil — aktif çizimi yapan kalem touch'ı. Set ise, bu touch
  // canlı stroke'u kontrol eder; aynı anda gelen parmak touch'ları (palm)
  // touchesBegan'da yok sayılır.
  private weak var pencilDrawTouch: UITouch?
  // Pencil basıncı ile değişken kalınlık — her örnek için sample biriktirilir,
  // renderStroke variableWidthSamples varsa segment-by-segment width interp eder.
  private var liveWidthSamples: [WidthSample] = []
  private var liveBaseWidth: CGFloat = 8  // strokeWidth (basınçsız taban)
  private var pencilStrokeActive: Bool = false  // bu stroke pencil mi parmak mı

  // Cached committed strokes image
  private var committedImage: UIImage?

  // Image background
  private var imageBitmap: UIImage?
  private var imageUri: String?

  // World transform
  private var worldScale: CGFloat = 1.0
  private var worldRotation: CGFloat = 0.0  // radians
  private var worldTx: CGFloat = 0.0
  private var worldTy: CGFloat = 0.0
  private var savedScale: CGFloat = 1.0
  private var savedRotation: CGFloat = 0.0
  private var savedTx: CGFloat = 0.0
  private var savedTy: CGFloat = 0.0

  // Image item — image tool ile transform edilen interaktif obje
  private var imageCenter: CGPoint = .zero  // world coords
  private var imageScale: CGFloat = 1.0  // baseFit'e ek katsayı
  private var imageRotation: CGFloat = 0.0  // radians
  private var savedImageCenter: CGPoint = .zero
  private var savedImageScale: CGFloat = 1.0
  private var savedImageRotation: CGFloat = 0.0
  private var imageInitialized = false
  // Image tool gesture state
  private var imageDragStartWorld: CGPoint = .zero
  private var imageDragStartCenter: CGPoint = .zero
  private var imagePinchInitialDist: CGFloat = 0
  private var imagePinchInitialAngle: CGFloat = 0
  private var imageLockedLocal: CGPoint = .zero  // image-local coords
  // World pinch ek state — rotation izleme
  private var pinchInitialAngle: CGFloat = 0
  // Rotation deadzone — küçük kasıtsız twist'i yok say, intent kanıtlanınca aktive et
  // 8° eşiği aşılana kadar rotation uygulanmaz; aktive olunca initialAngle re-baseline'lanır (jump yok)
  private var pinchRotationActivated: Bool = false
  private var imagePinchRotationActivated: Bool = false
  private let rotationDeadzone: CGFloat = 0.14   // ~8°
  private let rotationSnapTolerance: CGFloat = 0.175  // ~10° — release'te cardinal'e yakınsa snap

  // Props
  var activeTool: CanvasTool = .pen
  var strokeColor: UIColor = .black
  var strokeWidth: CGFloat = 8.0
  var strokeOpacity: CGFloat = 1.0
  var minZoom: CGFloat = 0.5
  var maxZoom: CGFloat = 6.0
  var canvasBgColor: UIColor = .white
  var canvasOverlay: String = "none"
  var selectionPickMode: SelectionPickMode = .rect

  // Bounded canvas — world coords içinde sabit drawable alan (view boyutu)
  private var canvasRect: CGRect = .zero

  // Pinch
  private var pinchInitialDist: CGFloat = 0
  private var pinchInitialFocal: CGPoint = .zero
  private var lockedWorld: CGPoint = .zero
  private var activeTouchCount: Int = 0
  // Pinch boyunca KULLANILAN iki parmak — Set<UITouch> sıralı değil,
  // her frame `pts[0]/pts[1]` farklı parmak gelirse currentAngle π kayar
  // ve canvas tepe taklak + yer kayması yaşar. Aynı iki parmağı ID ile takip et.
  private weak var pinchTouchA: UITouch?
  private weak var pinchTouchB: UITouch?
  private weak var imagePinchTouchA: UITouch?
  private weak var imagePinchTouchB: UITouch?
  private weak var selectionTouchA: UITouch?
  private weak var selectionTouchB: UITouch?

  // ─── Selection state ───────────────────────────────────────────────────
  private var selectMode: SelectMode = .idle
  private var selectedIndices = Set<Int>()
  private var selectionRect: CGRect? = nil
  private var selectionBounds: CGRect? = nil
  private var lassoPoints: [CGFloat] = []
  private var selectionBaseImage: UIImage? = nil   // seçili olmayan strokes

  // Move state
  private var moveStartWorld: CGPoint = .zero
  private var moveDelta: CGPoint = .zero
  // Resize state
  private var resizeCorner: Int = -1  // 0=TL, 1=TR, 2=BL, 3=BR
  private var resizeAnchor: CGPoint = .zero
  private var resizeStartDist: CGFloat = 1
  private var resizeLiveScale: CGFloat = 1
  // Rotate state
  private var rotateCenter: CGPoint = .zero
  private var rotateLiveAngle: CGFloat = 0  // radians
  private var rotateStartAngle: CGFloat = 0  // single-finger rotate handle başlangıç açısı
  // 2-finger transform state
  private var twoFingerInitialDist: CGFloat = 0
  private var twoFingerInitialAngle: CGFloat = 0
  private var selectionTransforming = false

  // Callbacks
  var onCountChanged: ((Int, Int, [CGFloat], Int64, Int64) -> Void)?
  var onZoomChanged: ((CGFloat) -> Void)?

  override init(frame: CGRect) {
    super.init(frame: frame)
    backgroundColor = .clear
    isMultipleTouchEnabled = true
    contentMode = .redraw
  }

  required init?(coder: NSCoder) { fatalError("init(coder:) not supported") }

  // ─── Selection mode ───────────────────────────────────────────────────
  func setSelectionMode(_ mode: String) {
    let newMode: SelectionPickMode = (mode == "lasso") ? .lasso : .rect
    if selectionPickMode == newMode { return }
    selectionPickMode = newMode
    if selectMode == .drawingRect || selectMode == .drawingLasso {
      selectionRect = nil
      lassoPoints.removeAll()
      selectMode = .idle
      setNeedsDisplay()
    }
  }

  // ─── Image loading ─────────────────────────────────────────────────────
  func setImageUri(_ uri: String?) {
    if uri == imageUri { return }
    imageUri = uri
    guard let uri = uri, !uri.isEmpty else {
      imageBitmap = nil
      setNeedsDisplay()
      return
    }
    DispatchQueue.global(qos: .userInitiated).async { [weak self] in
      let img = self?.loadImage(uri: uri)
      DispatchQueue.main.async {
        self?.imageBitmap = img
        self?.setNeedsDisplay()
      }
    }
  }

  private func loadImage(uri: String) -> UIImage? {
    if uri.hasPrefix("http://") || uri.hasPrefix("https://") {
      if let url = URL(string: uri), let data = try? Data(contentsOf: url) {
        return UIImage(data: data)
      }
    } else if uri.hasPrefix("file://") {
      if let url = URL(string: uri), let data = try? Data(contentsOf: url) {
        return UIImage(data: data)
      }
    } else {
      return UIImage(contentsOfFile: uri)
    }
    return nil
  }

  // ─── Layout ───────────────────────────────────────────────────────────
  override func layoutSubviews() {
    super.layoutSubviews()
    let size = bounds.size
    if size.width > 0 && size.height > 0 {
      let newRect = CGRect(origin: .zero, size: size)
      if newRect != canvasRect {
        canvasRect = newRect
        // Bounds her değiştiğinde (ilk init + orientation rotation) image'ı yeniden fit et.
        // computeImageBaseFit aspect'i otomatik korur; burada sadece center/scale/rotation'ı sıfırlıyoruz.
        imageCenter = CGPoint(x: canvasRect.midX, y: canvasRect.midY)
        imageScale = 1
        imageRotation = 0
        imageInitialized = true
        clearSelection()
        redrawCommittedImage()
      } else if committedImage == nil {
        redrawCommittedImage()
      }
    }
  }

  private func computeImageBaseFit(_ bmp: UIImage) -> CGSize {
    let iw = bmp.size.width
    let ih = bmp.size.height
    if iw <= 0 || ih <= 0 { return .zero }
    let targetW = canvasRect.width * 0.9
    let targetH = canvasRect.height * 0.9
    let fit = min(targetW / iw, targetH / ih)
    return CGSize(width: iw * fit, height: ih * fit)
  }

  func resetCanvasTransform() {
    worldScale = 1; worldRotation = 0; worldTx = 0; worldTy = 0
    savedScale = 1; savedRotation = 0; savedTx = 0; savedTy = 0
    setNeedsDisplay()
    onZoomChanged?(worldScale)
  }

  func resetImageTransform() {
    imageCenter = CGPoint(x: canvasRect.midX, y: canvasRect.midY)
    imageScale = 1
    imageRotation = 0
    setNeedsDisplay()
  }

  func currentRotation() -> CGFloat { worldRotation }

  // ─── Drawing ──────────────────────────────────────────────────────────
  override func draw(_ rect: CGRect) {
    guard let ctx = UIGraphicsGetCurrentContext() else { return }

    // 1. Tüm view: açık gri (canvas-dışı arka plan)
    UIColor(red: 0.898, green: 0.906, blue: 0.922, alpha: 1.0).setFill()  // #e5e7eb
    ctx.fill(bounds)

    // 2. World transform — center-pivot
    let cx = bounds.midX
    let cy = bounds.midY
    ctx.saveGState()
    ctx.translateBy(x: cx + worldTx, y: cy + worldTy)
    if worldRotation != 0 { ctx.rotate(by: worldRotation) }
    ctx.scaleBy(x: worldScale, y: worldScale)
    ctx.translateBy(x: -cx, y: -cy)

    // 3. Clip to bounded canvas
    ctx.saveGState()
    ctx.clip(to: canvasRect)

    // 4. Canvas BG (sadece çizim alanı)
    canvasBgColor.setFill()
    ctx.fill(canvasRect)

    // 5. Overlay (grid / lines) — IMAGE'DAN ÖNCE çiz ki image üzerine basmasın
    drawCanvasOverlay(ctx)

    // 6. Image item — kendi transform'u (overlay'in üzerine binerek grid'i kapatır)
    if let bmp = imageBitmap {
      let base = computeImageBaseFit(bmp)
      if base.width > 0 && base.height > 0 {
        ctx.saveGState()
        ctx.translateBy(x: imageCenter.x, y: imageCenter.y)
        if imageRotation != 0 { ctx.rotate(by: imageRotation) }
        ctx.scaleBy(x: imageScale, y: imageScale)
        bmp.draw(in: CGRect(
          x: -base.width / 2, y: -base.height / 2,
          width: base.width, height: base.height))
        ctx.restoreGState()
      }
    }

    // 7. Committed strokes — selection transform sırasında vektörel preview
    let previewActive = activeTool == .select &&
      (selectMode == .moving || selectMode == .resizing || selectMode == .rotating || selectMode == .rotatingHandle)
    if previewActive {
      // Zoom aktif iken selectionBaseImage upsample edilirse pixelation olur →
      // non-selected stroke'ları vektörel render et.
      let zoomed = abs(worldScale - 1.0) > 0.001
      if zoomed {
        ctx.beginTransparencyLayer(auxiliaryInfo: nil)
        for i in strokes.indices where !selectedIndices.contains(i) {
          renderStroke(strokes[i])
        }
        ctx.endTransparencyLayer()
      } else {
        selectionBaseImage?.draw(at: .zero)
      }
      // Seçili strokes'u transform ile yeniden render et — kendi transparency
      // layer'inde, böylece eraser CLEAR sadece seçili pen pixel'lerini siler,
      // alttaki base image'a (taşınmayan diğer içerik) dokunmaz.
      let transform = currentSelectionTransform()
      ctx.saveGState()
      ctx.concatenate(transform)
      ctx.beginTransparencyLayer(auxiliaryInfo: nil)
      for i in selectedIndices where i < strokes.count {
        renderStroke(strokes[i])
      }
      ctx.endTransparencyLayer()
      ctx.restoreGState()
    } else if abs(worldScale - 1.0) > 0.001 {
      // Zoom aktif → committed image upsample/downsample edilince pixelation oluyor.
      // Doğrudan vektör path'leri çiz; eraser CLEAR'ın bg image'e dokunmaması için
      // transparency layer'a render et.
      ctx.beginTransparencyLayer(auxiliaryInfo: nil)
      for s in strokes { renderStroke(s) }
      ctx.endTransparencyLayer()
    } else {
      committedImage?.draw(at: .zero)
    }

    // 8. Canlı stroke
    if liveActive {
      if liveTool == .eraser {
        UIColor.gray.withAlphaComponent(0.3).setStroke()
        livePath.lineWidth = liveWidth
        livePath.lineCapStyle = .round
        livePath.lineJoinStyle = .round
        livePath.stroke()
      } else if liveWidthSamples.count >= 2 {
        // Pressure-aware preview — kullanıcı kalemle çizerken anlık variable width
        liveColor.withAlphaComponent(liveOpacity).setStroke()
        for i in 1..<liveWidthSamples.count {
          let a = liveWidthSamples[i - 1]
          let b = liveWidthSamples[i]
          let seg = UIBezierPath()
          seg.move(to: a.point)
          seg.addLine(to: b.point)
          seg.lineWidth = (a.width + b.width) / 2
          seg.lineCapStyle = .round
          seg.lineJoinStyle = .round
          seg.stroke()
        }
      } else {
        liveColor.withAlphaComponent(liveOpacity).setStroke()
        livePath.lineWidth = liveWidth
        livePath.lineCapStyle = .round
        livePath.lineJoinStyle = .round
        livePath.stroke()
      }
    }

    // 9a. IMAGE UI — rotated bounding box (image üzerinde dashed quad)
    if activeTool == .image {
      drawImageBoundsUI(ctx)
    }

    // 9. SELECT UI
    if activeTool == .select {
      if selectMode == .drawingRect, let r = selectionRect {
        drawSelectionRectUI(ctx, rect: normalizedRect(r))
      }
      if selectMode == .drawingLasso && lassoPoints.count >= 4 {
        drawLassoUI(ctx)
      }
      if !selectedIndices.isEmpty, let b = effectiveSelectionBounds() {
        drawSelectionBoundsUI(ctx, bounds: b)
      }
    }

    ctx.restoreGState()  // clip
    ctx.restoreGState()  // world transform

    // 10. Canvas-dışı gri overlay (clip dışında)
    drawOutsideOverlay(ctx)
  }

  private func currentSelectionTransform() -> CGAffineTransform {
    switch selectMode {
    case .moving:
      return CGAffineTransform(translationX: moveDelta.x, y: moveDelta.y)
    case .resizing:
      var t = CGAffineTransform.identity
      t = t.translatedBy(x: resizeAnchor.x, y: resizeAnchor.y)
      t = t.scaledBy(x: resizeLiveScale, y: resizeLiveScale)
      t = t.translatedBy(x: -resizeAnchor.x, y: -resizeAnchor.y)
      return t
    case .rotating, .rotatingHandle:
      var t = CGAffineTransform.identity
      t = t.translatedBy(x: rotateCenter.x, y: rotateCenter.y)
      t = t.rotated(by: rotateLiveAngle)
      t = t.translatedBy(x: -rotateCenter.x, y: -rotateCenter.y)
      return t
    default:
      return .identity
    }
  }

  private func effectiveSelectionBounds() -> CGRect? {
    guard let b = selectionBounds else { return nil }
    switch selectMode {
    case .moving:
      return b.offsetBy(dx: moveDelta.x, dy: moveDelta.y)
    case .resizing:
      let t = currentSelectionTransform()
      return b.applying(t)
    case .rotating, .rotatingHandle:
      return b  // rotate'da bounds gösterimi sabit kalır (preview rotate olur)
    default:
      return b
    }
  }

  private func drawCanvasOverlay(_ ctx: CGContext) {
    if canvasOverlay == "none" { return }
    let lum = colorLuminance(canvasBgColor)
    let alpha: CGFloat = lum > 0.5 ? 0.14 : 0.10
    let color: UIColor = lum > 0.5 ? UIColor(white: 0, alpha: alpha) : UIColor(white: 1, alpha: alpha)
    color.setStroke()

    let spacing: CGFloat = 24

    let path = UIBezierPath()
    if canvasOverlay == "grid" {
      var x = canvasRect.minX
      while x <= canvasRect.maxX {
        path.move(to: CGPoint(x: x, y: canvasRect.minY))
        path.addLine(to: CGPoint(x: x, y: canvasRect.maxY))
        x += spacing
      }
      var y = canvasRect.minY
      while y <= canvasRect.maxY {
        path.move(to: CGPoint(x: canvasRect.minX, y: y))
        path.addLine(to: CGPoint(x: canvasRect.maxX, y: y))
        y += spacing
      }
    } else if canvasOverlay == "lines" {
      var y = canvasRect.minY
      while y <= canvasRect.maxY {
        path.move(to: CGPoint(x: canvasRect.minX, y: y))
        path.addLine(to: CGPoint(x: canvasRect.maxX, y: y))
        y += spacing
      }
    }
    path.lineWidth = 1.0 / worldScale
    path.stroke()
  }

  private func colorLuminance(_ color: UIColor) -> CGFloat {
    var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
    color.getRed(&r, green: &g, blue: &b, alpha: &a)
    return 0.299 * r + 0.587 * g + 0.114 * b
  }

  private func drawOutsideOverlay(_ ctx: CGContext) {
    // canvasRect 4 köşesi screen-space (rotation+scale+pan dahil)
    let tl = worldToScreen(CGPoint(x: canvasRect.minX, y: canvasRect.minY))
    let tr = worldToScreen(CGPoint(x: canvasRect.maxX, y: canvasRect.minY))
    let br = worldToScreen(CGPoint(x: canvasRect.maxX, y: canvasRect.maxY))
    let bl = worldToScreen(CGPoint(x: canvasRect.minX, y: canvasRect.maxY))
    // EvenOdd fill: outer rect (tüm view) − inner polygon (rotated canvas) = sadece dış alan
    let path = UIBezierPath()
    path.usesEvenOddFillRule = true
    path.append(UIBezierPath(rect: bounds))
    let inner = UIBezierPath()
    inner.move(to: tl)
    inner.addLine(to: tr)
    inner.addLine(to: br)
    inner.addLine(to: bl)
    inner.close()
    path.append(inner)
    UIColor(white: 0, alpha: 0.20).setFill()
    path.fill()
  }

  private func drawImageBoundsUI(_ ctx: CGContext) {
    guard let bmp = imageBitmap else { return }
    let base = computeImageBaseFit(bmp)
    if base.width <= 0 || base.height <= 0 { return }
    let halfW = base.width / 2 * imageScale
    let halfH = base.height / 2 * imageScale
    let cosR = cos(imageRotation); let sinR = sin(imageRotation)
    func localToWorld(_ lx: CGFloat, _ ly: CGFloat) -> CGPoint {
      CGPoint(
        x: imageCenter.x + lx * cosR - ly * sinR,
        y: imageCenter.y + lx * sinR + ly * cosR)
    }
    let p0 = localToWorld(-halfW, -halfH)
    let p1 = localToWorld(halfW, -halfH)
    let p2 = localToWorld(halfW, halfH)
    let p3 = localToWorld(-halfW, halfH)
    let sw = 2.0 / worldScale
    ctx.saveGState()
    let path = UIBezierPath()
    path.move(to: p0); path.addLine(to: p1); path.addLine(to: p2); path.addLine(to: p3); path.close()
    selUiColor.withAlphaComponent(0.08).setFill()
    path.fill()
    ctx.setStrokeColor(selUiColor.cgColor)
    ctx.setLineWidth(sw)
    ctx.setLineDash(phase: 0, lengths: [12 / worldScale, 6 / worldScale])
    path.stroke()
    ctx.restoreGState()
  }

  // ─── Selection UI drawing ─────────────────────────────────────────────
  private let selUiColor = UIColor(red: 0.388, green: 0.4, blue: 0.945, alpha: 1.0) // #6366f1

  private func drawSelectionRectUI(_ ctx: CGContext, rect: CGRect) {
    let sw = 2.0 / worldScale
    ctx.saveGState()
    ctx.setStrokeColor(selUiColor.cgColor)
    ctx.setLineWidth(sw)
    let dash: [CGFloat] = [12 / worldScale, 6 / worldScale]
    ctx.setLineDash(phase: 0, lengths: dash)
    ctx.stroke(rect)
    ctx.restoreGState()
  }

  private func drawLassoUI(_ ctx: CGContext) {
    let sw = 2.0 / worldScale
    ctx.saveGState()
    let path = UIBezierPath()
    path.move(to: CGPoint(x: lassoPoints[0], y: lassoPoints[1]))
    var i = 2
    while i < lassoPoints.count - 1 {
      path.addLine(to: CGPoint(x: lassoPoints[i], y: lassoPoints[i + 1]))
      i += 2
    }
    path.close()
    selUiColor.withAlphaComponent(0.08).setFill()
    path.fill()
    ctx.setStrokeColor(selUiColor.cgColor)
    ctx.setLineWidth(sw)
    ctx.setLineDash(phase: 0, lengths: [12 / worldScale, 6 / worldScale])
    path.stroke()
    ctx.restoreGState()
  }

  private func drawSelectionBoundsUI(_ ctx: CGContext, bounds b: CGRect) {
    let sw = 2.0 / worldScale
    ctx.saveGState()
    selUiColor.withAlphaComponent(0.08).setFill()
    ctx.fill(b)
    ctx.setStrokeColor(selUiColor.cgColor)
    ctx.setLineWidth(sw)
    ctx.setLineDash(phase: 0, lengths: [])
    ctx.stroke(b)
    // Köşe handles
    let density = UIScreen.main.scale
    let hr = 4.0 * density / worldScale
    let corners = [
      CGPoint(x: b.minX, y: b.minY),
      CGPoint(x: b.maxX, y: b.minY),
      CGPoint(x: b.minX, y: b.maxY),
      CGPoint(x: b.maxX, y: b.maxY),
    ]
    for c in corners {
      let r = CGRect(x: c.x - hr, y: c.y - hr, width: hr * 2, height: hr * 2)
      ctx.setFillColor(selUiColor.cgColor)
      ctx.fillEllipse(in: r)
      ctx.setStrokeColor(UIColor.white.cgColor)
      ctx.strokeEllipse(in: r)
    }
    // Rotate handle: üst kenarın ortasından yukarı bir çizgi + circle
    let topCenter = CGPoint(x: (b.minX + b.maxX) / 2, y: b.minY)
    let handleOffset = CanvasContentView.rotateHandleOffsetDp / worldScale
    var handlePos = CGPoint(x: topCenter.x, y: topCenter.y - handleOffset)
    var connectorStart = topCenter
    if selectMode == .rotatingHandle && rotateLiveAngle != 0 {
      let cx = rotateCenter.x; let cy = rotateCenter.y
      let cosA = cos(rotateLiveAngle); let sinA = sin(rotateLiveAngle)
      let dx0 = handlePos.x - cx; let dy0 = handlePos.y - cy
      handlePos = CGPoint(x: cx + dx0 * cosA - dy0 * sinA, y: cy + dx0 * sinA + dy0 * cosA)
      let dx1 = topCenter.x - cx; let dy1 = topCenter.y - cy
      connectorStart = CGPoint(x: cx + dx1 * cosA - dy1 * sinA, y: cy + dx1 * sinA + dy1 * cosA)
    }
    // Bağlantı çizgisi
    ctx.setStrokeColor(selUiColor.cgColor)
    ctx.setLineWidth(sw)
    ctx.move(to: connectorStart)
    ctx.addLine(to: handlePos)
    ctx.strokePath()
    // Rotate handle dolgu + kenar
    let rhr = 5.0 * density / worldScale
    let rRect = CGRect(x: handlePos.x - rhr, y: handlePos.y - rhr, width: rhr * 2, height: rhr * 2)
    ctx.setFillColor(selUiColor.cgColor)
    ctx.fillEllipse(in: rRect)
    ctx.setStrokeColor(UIColor.white.cgColor)
    ctx.strokeEllipse(in: rRect)
    ctx.restoreGState()
  }

  static let rotateHandleOffsetDp: CGFloat = 36

  // ─── Committed image ──────────────────────────────────────────────────
  private func redrawCommittedImage() {
    guard bounds.width > 0 && bounds.height > 0 else { return }
    let renderer = UIGraphicsImageRenderer(size: bounds.size)
    committedImage = renderer.image { _ in
      for s in strokes { renderStroke(s) }
    }
  }

  private func drawStrokeIncrementally(_ s: Stroke) {
    guard bounds.width > 0 && bounds.height > 0 else { return }
    let renderer = UIGraphicsImageRenderer(size: bounds.size)
    committedImage = renderer.image { _ in
      committedImage?.draw(at: .zero)
      renderStroke(s)
    }
  }

  private func renderStroke(_ s: Stroke) {
    if s.tool == .eraser {
      if let ctx = UIGraphicsGetCurrentContext() {
        ctx.saveGState()
        ctx.setBlendMode(.clear)
        ctx.setStrokeColor(UIColor.clear.cgColor)
        s.path.lineWidth = s.width
        s.path.lineCapStyle = .round
        s.path.lineJoinStyle = .round
        s.path.stroke()
        ctx.restoreGState()
      }
    } else if s.isText {
      s.color.withAlphaComponent(s.opacity).setFill()
      s.path.fill()
    } else if let samples = s.variableWidthSamples, samples.count >= 2 {
      // Pressure-aware render: ardışık örnekleri ayrı segment'lere böl,
      // her segmentin width'i iki uç sample'ın ortalaması.
      let baseColor = s.color.withAlphaComponent(s.opacity)
      baseColor.setStroke()
      for i in 1..<samples.count {
        let a = samples[i - 1]
        let b = samples[i]
        let seg = UIBezierPath()
        seg.move(to: a.point)
        seg.addLine(to: b.point)
        seg.lineWidth = (a.width + b.width) / 2
        seg.lineCapStyle = .round
        seg.lineJoinStyle = .round
        seg.stroke()
      }
    } else {
      s.color.withAlphaComponent(s.opacity).setStroke()
      s.path.lineWidth = s.width
      s.path.lineCapStyle = .round
      s.path.lineJoinStyle = .round
      s.path.stroke()
    }
  }

  // ─── History ───────────────────────────────────────────────────────────
  // Her mutasyondan ÖNCE çağrılır: mevcut strokes'u undo yığınına iter, redo'yu
  // temizler. Stroke.path referansları hiçbir yerde in-place mutate edilmez
  // (transform daima yeni UIBezierPath üretir) → shallow snapshot güvenli.
  private func pushHistory() {
    undoStack.append(strokes)
    if undoStack.count > maxHistory { undoStack.removeFirst() }
    redoStack.removeAll()
  }

  // ─── Commands ──────────────────────────────────────────────────────────
  func undo() {
    guard let prev = undoStack.popLast() else { return }
    clearSelection()
    redoStack.append(strokes)
    strokes = prev
    redrawCommittedImage()
    setNeedsDisplay()
    emitCount()
  }

  func redo() {
    guard let next = redoStack.popLast() else { return }
    clearSelection()
    undoStack.append(strokes)
    strokes = next
    redrawCommittedImage()
    setNeedsDisplay()
    emitCount()
  }

  func clear() {
    guard !strokes.isEmpty else { return }
    clearSelection()
    pushHistory()
    strokes.removeAll()
    redrawCommittedImage()
    setNeedsDisplay()
    emitCount()
  }

  func replaceLastStrokeWithShape(_ shape: [String: Any]) {
    guard !strokes.isEmpty else { return }
    let last = strokes[strokes.count - 1]
    if last.tool != .pen || last.isText { return }
    guard let newPath = buildPathFromShape(shape) else { return }
    let lastIdx = strokes.count - 1
    strokes[lastIdx] = Stroke(
      path: newPath, tool: last.tool, color: last.color, width: last.width, opacity: last.opacity)
    redrawCommittedImage()
    // Auto-select recognized shape — user üst handle ile döndürüp, sürükleyip
    // boyutlandırabilir. JS taraf bunu görür ve tool'u 'select'e çevirir.
    selectedIndices.removeAll()
    selectedIndices.insert(lastIdx)
    selectionBounds = newPath.bounds
    selectionRect = nil
    lassoPoints.removeAll()
    buildSelectionBaseImage()
    setNeedsDisplay()
    emitCount()
  }

  /**
   * Text item ekle — CoreText ile glyph path'leri çıkarılır, dolgu (FILL) Stroke
   * olarak strokes listesine girer. Eraser CLEAR, selection ve rotate/scale otomatik.
   */
  func insertText(_ text: String, fontSize: CGFloat, color: UIColor) {
    let clean = text.trimmingCharacters(in: .whitespacesAndNewlines)
    if clean.isEmpty { return }
    let resolvedFont: UIFont = UIFont(name: "Bradley Hand", size: max(8, fontSize))
      ?? UIFont(name: "Noteworthy-Bold", size: max(8, fontSize))
      ?? UIFont(name: "SnellRoundhand", size: max(8, fontSize))
      ?? UIFont.italicSystemFont(ofSize: max(8, fontSize))
    let attributed = NSAttributedString(string: clean, attributes: [.font: resolvedFont])
    let line = CTLineCreateWithAttributedString(attributed)
    guard let runs = CTLineGetGlyphRuns(line) as? [CTRun], !runs.isEmpty else { return }

    let combined = CGMutablePath()
    for run in runs {
      let attrs = CTRunGetAttributes(run) as NSDictionary
      guard let ctFont = attrs[kCTFontAttributeName as String] as! CTFont? else { continue }
      let glyphCount = CTRunGetGlyphCount(run)
      if glyphCount <= 0 { continue }
      var glyphs = [CGGlyph](repeating: 0, count: glyphCount)
      var positions = [CGPoint](repeating: .zero, count: glyphCount)
      CTRunGetGlyphs(run, CFRangeMake(0, glyphCount), &glyphs)
      CTRunGetPositions(run, CFRangeMake(0, glyphCount), &positions)
      for i in 0..<glyphCount {
        guard let g = CTFontCreatePathForGlyph(ctFont, glyphs[i], nil) else { continue }
        var t = CGAffineTransform(translationX: positions[i].x, y: positions[i].y)
        combined.addPath(g, transform: t)
      }
    }
    // CoreText path Y-up koordinat; UIKit Y-down — flip
    var flipT = CGAffineTransform(scaleX: 1, y: -1)
    guard let flipped = combined.copy(using: &flipT) else { return }
    let raw = flipped.boundingBoxOfPath
    if raw.isEmpty || raw.isNull { return }
    // Canvas merkezine taşı
    let targetX = canvasRect.midX - raw.midX
    let targetY = canvasRect.midY - raw.midY
    var translateT = CGAffineTransform(translationX: targetX, y: targetY)
    guard let final = flipped.copy(using: &translateT) else { return }

    let bezier = UIBezierPath(cgPath: final)
    let stroke = Stroke(
      path: bezier, tool: .pen, color: color,
      width: fontSize, opacity: 1, isText: true)
    pushHistory()
    strokes.append(stroke)
    drawStrokeIncrementally(stroke)

    // Auto-select — JS taraf insertText sonrası 'select' tool'una geçer
    selectedIndices.removeAll()
    selectedIndices.insert(strokes.count - 1)
    selectionBounds = bezier.bounds
    selectionRect = nil
    lassoPoints.removeAll()
    moveDelta = .zero
    resizeLiveScale = 1; resizeCorner = -1
    rotateLiveAngle = 0
    selectionTransforming = false
    selectMode = .idle
    buildSelectionBaseImage()
    setNeedsDisplay()
    emitCount()
  }

  func cancelLiveStrokePublic() { cancelLiveStroke() }

  // ─── Selection lifecycle ──────────────────────────────────────────────
  func clearSelection() {
    selectedIndices.removeAll()
    selectionBounds = nil
    selectionRect = nil
    lassoPoints.removeAll()
    selectionBaseImage = nil
    moveDelta = .zero
    resizeLiveScale = 1; resizeCorner = -1
    rotateLiveAngle = 0
    selectionTransforming = false
    selectMode = .idle
    setNeedsDisplay()
  }

  private func handleSelectTouchDown(world wp: CGPoint) {
    if let b = selectionBounds, !selectedIndices.isEmpty {
      // Rotate handle önce kontrol et
      if hitTestRotateHandle(world: wp, bounds: b) {
        rotateCenter = CGPoint(x: b.midX, y: b.midY)
        rotateStartAngle = atan2(wp.y - rotateCenter.y, wp.x - rotateCenter.x)
        rotateLiveAngle = 0
        selectMode = .rotatingHandle
        setNeedsDisplay()
        return
      }
      let corner = hitTestCorner(world: wp, bounds: b)
      if corner >= 0 {
        resizeCorner = corner
        resizeAnchor = CGPoint(
          x: (corner == 0 || corner == 2) ? b.maxX : b.minX,
          y: (corner == 0 || corner == 1) ? b.maxY : b.minY)
        let origCorner = CGPoint(
          x: (corner == 0 || corner == 2) ? b.minX : b.maxX,
          y: (corner == 0 || corner == 1) ? b.minY : b.maxY)
        resizeStartDist = max(1, hypot(origCorner.x - resizeAnchor.x, origCorner.y - resizeAnchor.y))
        resizeLiveScale = 1
        selectMode = .resizing
        return
      }
      if b.contains(wp) {
        moveStartWorld = wp; moveDelta = .zero
        selectMode = .moving
        return
      }
      // Boş alana tap → seçimi temizle ve dur. Yeni rect/lasso başlatma —
      // kullanıcı ikinci bir tap/drag ile yeni seçim başlatabilir.
      clearSelection()
      return
    }
    if selectionPickMode == .lasso {
      lassoPoints.removeAll()
      lassoPoints.append(wp.x); lassoPoints.append(wp.y)
      selectMode = .drawingLasso
    } else {
      selectionRect = CGRect(x: wp.x, y: wp.y, width: 0, height: 0)
      selectMode = .drawingRect
    }
    setNeedsDisplay()
  }

  private func handleSelectTouchMove(world wp: CGPoint) {
    switch selectMode {
    case .drawingRect:
      if var r = selectionRect {
        r.size.width = wp.x - r.origin.x
        r.size.height = wp.y - r.origin.y
        selectionRect = r
        setNeedsDisplay()
      }
    case .drawingLasso:
      let n = lassoPoints.count
      if n >= 2 {
        let dx = wp.x - lassoPoints[n - 2]
        let dy = wp.y - lassoPoints[n - 1]
        if dx * dx + dy * dy >= 4 {
          lassoPoints.append(wp.x); lassoPoints.append(wp.y)
          setNeedsDisplay()
        }
      }
    case .moving:
      moveDelta = CGPoint(x: wp.x - moveStartWorld.x, y: wp.y - moveStartWorld.y)
      setNeedsDisplay()
    case .resizing:
      let dist = hypot(wp.x - resizeAnchor.x, wp.y - resizeAnchor.y)
      resizeLiveScale = max(0.1, min(10, dist / resizeStartDist))
      setNeedsDisplay()
    case .rotatingHandle:
      let current = atan2(wp.y - rotateCenter.y, wp.x - rotateCenter.x)
      // atan2 ±π wrap'ini normalize et
      var handleDelta = current - rotateStartAngle
      while handleDelta > .pi { handleDelta -= 2 * .pi }
      while handleDelta < -.pi { handleDelta += 2 * .pi }
      rotateLiveAngle = handleDelta
      setNeedsDisplay()
    default: break
    }
  }

  private func handleSelectTouchUp() {
    switch selectMode {
    case .drawingRect: finalizeRectSelection()
    case .drawingLasso: finalizeLassoSelection()
    case .moving:
      if moveDelta != .zero { applyMoveTransform(moveDelta) }
      selectMode = .idle
    case .resizing:
      if resizeLiveScale != 1 { applyResizeTransform(resizeLiveScale) }
      selectMode = .idle
    case .rotatingHandle:
      if rotateLiveAngle != 0 { applyRotationTransform(rotateLiveAngle) }
      rotateLiveAngle = 0
      selectMode = .idle
    default: break
    }
    setNeedsDisplay()
  }

  private func finalizeRectSelection() {
    guard let r = selectionRect else { selectMode = .idle; return }
    let norm = normalizedRect(r)
    selectionRect = nil
    if norm.width < 5 || norm.height < 5 { selectMode = .idle; return }

    selectedIndices.removeAll()
    // Önce pen / text stroke'larını topla — eraser değerlendirmesi bunlara göre yapılır.
    for (i, s) in strokes.enumerated() {
      if s.tool == .eraser { continue }
      let pb = s.path.bounds
      if pb.intersects(norm) { selectedIndices.insert(i) }
    }
    if selectedIndices.isEmpty { selectMode = .idle; return }
    // Eraser stroke'ları: bounds'u tamamen selection rect içinde kalanlar seçime
    // dahil edilir. Böylece "çiz → bir kısmını sil → seç ve taşı" akışında
    // silinmiş bölgeler de pen stroke'uyla birlikte taşınır (eraser'ın geri
    // dönüp orijinal stroke'u açığa çıkarmasını engeller).
    for (i, s) in strokes.enumerated() {
      if s.tool != .eraser { continue }
      let pb = s.path.bounds
      if norm.contains(pb) { selectedIndices.insert(i) }
    }
    var combined: CGRect? = nil
    for i in selectedIndices {
      let pb = strokes[i].path.bounds
      combined = combined?.union(pb) ?? pb
    }
    selectionBounds = combined
    buildSelectionBaseImage()
    selectMode = .idle
  }

  private func finalizeLassoSelection() {
    if lassoPoints.count < 6 { lassoPoints.removeAll(); selectMode = .idle; return }
    var lassoBox = CGRect(x: lassoPoints[0], y: lassoPoints[1], width: 0, height: 0)
    var i = 2
    while i < lassoPoints.count - 1 {
      lassoBox = lassoBox.union(CGRect(x: lassoPoints[i], y: lassoPoints[i + 1], width: 0, height: 0))
      i += 2
    }
    selectedIndices.removeAll()
    for (idx, s) in strokes.enumerated() {
      if s.tool == .eraser { continue }
      let pb = s.path.bounds
      if !pb.intersects(lassoBox) { continue }
      let cx = pb.midX; let cy = pb.midY
      if pointInPolygon(x: cx, y: cy, poly: lassoPoints) {
        selectedIndices.insert(idx)
      }
    }
    // Eraser stroke'ları: bounds'u tamamen lasso bbox'ı içinde kalanlar seçime
    // dahil edilir (rect modu ile aynı mantık — silinmiş bölgenin pen ile
    // birlikte taşınmasını garanti eder).
    if !selectedIndices.isEmpty {
      for (idx, s) in strokes.enumerated() {
        if s.tool != .eraser { continue }
        let pb = s.path.bounds
        if lassoBox.contains(pb) { selectedIndices.insert(idx) }
      }
    }
    lassoPoints.removeAll()
    if selectedIndices.isEmpty { selectMode = .idle; return }
    var combined: CGRect? = nil
    for i in selectedIndices {
      let pb = strokes[i].path.bounds
      combined = combined?.union(pb) ?? pb
    }
    selectionBounds = combined
    buildSelectionBaseImage()
    selectMode = .idle
  }

  private func pointInPolygon(x px: CGFloat, y py: CGFloat, poly: [CGFloat]) -> Bool {
    var inside = false
    let n = poly.count / 2
    var j = n - 1
    for i in 0..<n {
      let xi = poly[i * 2], yi = poly[i * 2 + 1]
      let xj = poly[j * 2], yj = poly[j * 2 + 1]
      let dy = yj - yi
      let denom: CGFloat = dy == 0 ? 0.0001 : dy
      let intersect = (yi > py) != (yj > py) &&
        (px < (xj - xi) * (py - yi) / denom + xi)
      if intersect { inside.toggle() }
      j = i
    }
    return inside
  }

  private func buildSelectionBaseImage() {
    guard bounds.width > 0 && bounds.height > 0 else { return }
    let renderer = UIGraphicsImageRenderer(size: bounds.size)
    selectionBaseImage = renderer.image { _ in
      // Seçili olan tüm stroke'lar (pen + eraser) base'den çıkarılır.
      // Eraser bounds'u selection rect içinde kalanlar finalize sırasında
      // selectedIndices'e eklendiği için base'de kalmaz; bu sayede taşıma
      // sırasında orijinal pen'in altında bir "kalıntı eraser" gözükmez ve
      // taşınan kompozit hem pen hem eraser cut'larını birlikte götürür.
      for (i, s) in strokes.enumerated() {
        if !selectedIndices.contains(i) {
          renderStroke(s)
        }
      }
    }
  }

  private func applyMoveTransform(_ d: CGPoint) {
    let t = CGAffineTransform(translationX: d.x, y: d.y)
    applyTransformToSelection(t)
    moveDelta = .zero
  }

  private func applyResizeTransform(_ scale: CGFloat) {
    var t = CGAffineTransform.identity
    t = t.translatedBy(x: resizeAnchor.x, y: resizeAnchor.y)
    t = t.scaledBy(x: scale, y: scale)
    t = t.translatedBy(x: -resizeAnchor.x, y: -resizeAnchor.y)
    applyTransformToSelection(t)
    resizeLiveScale = 1
  }

  private func applyRotationTransform(_ angle: CGFloat) {
    var t = CGAffineTransform.identity
    t = t.translatedBy(x: rotateCenter.x, y: rotateCenter.y)
    t = t.rotated(by: angle)
    t = t.translatedBy(x: -rotateCenter.x, y: -rotateCenter.y)
    applyTransformToSelection(t)
    rotateLiveAngle = 0
  }

  private func applyTransformToSelection(_ t: CGAffineTransform) {
    // Resize için ölçek faktörünü transform'dan çıkar (a/d'nin uzunluğu).
    // Rotation/translate'de scale ≈ 1 olur ve width değişmez.
    let scaleFactor = sqrt(t.a * t.a + t.b * t.b)
    pushHistory()
    for i in selectedIndices where i < strokes.count {
      let s = strokes[i]
      let newPath = UIBezierPath(cgPath: s.path.cgPath)
      newPath.apply(t)
      let newSamples: [WidthSample]? = s.variableWidthSamples?.map { sample in
        WidthSample(
          point: sample.point.applying(t),
          width: sample.width * scaleFactor)
      }
      // Silginin doğru çalışması için polyline noktaları da taşınır.
      let newPoints = s.points.map { $0.applying(t) }
      strokes[i] = Stroke(
        path: newPath, tool: s.tool, color: s.color,
        width: s.width * scaleFactor, opacity: s.opacity,
        isText: s.isText, variableWidthSamples: newSamples, points: newPoints)
    }
    // Bounds yeniden hesapla
    var combined: CGRect? = nil
    for i in selectedIndices {
      let pb = strokes[i].path.bounds
      combined = combined?.union(pb) ?? pb
    }
    selectionBounds = combined
    redrawCommittedImage()
    buildSelectionBaseImage()
    emitCount()
  }

  func setSelectedStrokeColor(_ color: UIColor) {
    if selectedIndices.isEmpty { return }
    pushHistory()
    for i in selectedIndices where i < strokes.count {
      let s = strokes[i]
      strokes[i] = Stroke(
        path: s.path, tool: s.tool, color: color,
        width: s.width, opacity: s.opacity,
        isText: s.isText, variableWidthSamples: s.variableWidthSamples, points: s.points)
    }
    redrawCommittedImage()
    buildSelectionBaseImage()
    setNeedsDisplay()
  }

  private func hitTestCorner(world wp: CGPoint, bounds b: CGRect) -> Int {
    let density = UIScreen.main.scale
    let touchR = 24 * density / worldScale / UIScreen.main.scale
    let corners = [
      CGPoint(x: b.minX, y: b.minY),
      CGPoint(x: b.maxX, y: b.minY),
      CGPoint(x: b.minX, y: b.maxY),
      CGPoint(x: b.maxX, y: b.maxY),
    ]
    for (i, c) in corners.enumerated() {
      let dx = wp.x - c.x; let dy = wp.y - c.y
      if dx * dx + dy * dy <= touchR * touchR { return i }
    }
    return -1
  }

  private func hitTestRotateHandle(world wp: CGPoint, bounds b: CGRect) -> Bool {
    let cx = (b.minX + b.maxX) / 2
    let cy = b.minY - CanvasContentView.rotateHandleOffsetDp / worldScale
    let touchR: CGFloat = 28 / worldScale
    let dx = wp.x - cx; let dy = wp.y - cy
    return dx * dx + dy * dy <= touchR * touchR
  }

  private func normalizedRect(_ r: CGRect) -> CGRect {
    let left = min(r.minX, r.maxX), right = max(r.minX, r.maxX)
    let top = min(r.minY, r.maxY), bottom = max(r.minY, r.maxY)
    return CGRect(x: left, y: top, width: right - left, height: bottom - top)
  }

  // ─── Shape recognition path builder ──────────────────────────────────
  private func buildPathFromShape(_ shape: [String: Any]) -> UIBezierPath? {
    guard let type = shape["type"] as? String else { return nil }
    let p = UIBezierPath()
    switch type {
    case "line":
      guard let x1 = num(shape["x1"]), let y1 = num(shape["y1"]),
            let x2 = num(shape["x2"]), let y2 = num(shape["y2"]) else { return nil }
      p.move(to: CGPoint(x: x1, y: y1))
      p.addLine(to: CGPoint(x: x2, y: y2))
    case "rect":
      guard let minX = num(shape["minX"]), let minY = num(shape["minY"]),
            let maxX = num(shape["maxX"]), let maxY = num(shape["maxY"]) else { return nil }
      p.move(to: CGPoint(x: minX, y: minY))
      p.addLine(to: CGPoint(x: maxX, y: minY))
      p.addLine(to: CGPoint(x: maxX, y: maxY))
      p.addLine(to: CGPoint(x: minX, y: maxY))
      p.close()
    case "triangle":
      guard let verts = shape["vertices"] as? [[String: Any]], verts.count >= 3 else { return nil }
      for (i, v) in verts.enumerated() {
        guard let vx = num(v["x"]), let vy = num(v["y"]) else { return nil }
        if i == 0 { p.move(to: CGPoint(x: vx, y: vy)) } else { p.addLine(to: CGPoint(x: vx, y: vy)) }
      }
      p.close()
    case "quad":
      guard let verts = shape["vertices"] as? [[String: Any]], verts.count >= 4 else { return nil }
      for (i, v) in verts.enumerated() {
        guard let vx = num(v["x"]), let vy = num(v["y"]) else { return nil }
        if i == 0 { p.move(to: CGPoint(x: vx, y: vy)) } else { p.addLine(to: CGPoint(x: vx, y: vy)) }
      }
      p.close()
    case "circle":
      guard let cx = num(shape["cx"]), let cy = num(shape["cy"]), let r = num(shape["r"]) else { return nil }
      p.append(UIBezierPath(arcCenter: CGPoint(x: cx, y: cy), radius: r,
        startAngle: 0, endAngle: .pi * 2, clockwise: true))
    case "ellipse":
      guard let cx = num(shape["cx"]), let cy = num(shape["cy"]),
            let rx = num(shape["rx"]), let ry = num(shape["ry"]) else { return nil }
      let rect = CGRect(x: cx - rx, y: cy - ry, width: rx * 2, height: ry * 2)
      p.append(UIBezierPath(ovalIn: rect))
    default:
      return nil
    }
    return p
  }

  private func num(_ v: Any?) -> CGFloat? {
    if let n = v as? NSNumber { return CGFloat(truncating: n) }
    if let n = v as? Int { return CGFloat(n) }
    if let n = v as? Double { return CGFloat(n) }
    if let n = v as? Float { return CGFloat(n) }
    return nil
  }

  private func emitCount(
    points: [CGFloat] = [], durationMs: Int64 = 0, idleMs: Int64 = 0
  ) {
    // 1. param JS'te canUndo türetir. Snapshot modelde undo edilebilirlik
    // strokes.count değil undoStack doluluğuyla belirlenir (örn. "hepsini sil"
    // sonrası strokes=0 ama undo mümkün). Bu yüzden undoStack.count gönderiyoruz.
    onCountChanged?(undoStack.count, redoStack.count, points, durationMs, idleMs)
  }

  // ─── Touch handling ───────────────────────────────────────────────────
  // Apple Pencil + parmak ayrımı:
  //  - Pencil touch'ı her zaman çizer (pen/eraser tool için).
  //  - Pencil aktifken (pencilDrawTouch != nil), yeni parmak touch'ları
  //    palm/yanlışlıkla dokunma sayılır ve YOK SAYILIR. Pinch için bile
  //    açmak istemiyoruz — Apple Notes/Procreate davranışı: kalem aktifken
  //    bir el dayama varsa ekran "kilitli" hisseder.
  //  - Pencil yokken eski parmak akışı korunur (pinch, drag, vs.).
  override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
    let allTouches = event?.allTouches ?? touches
    activeTouchCount = allTouches.count

    // 1) Bu turda gelen pencil touch'ını öncele
    if let pt = touches.first(where: { $0.type == .pencil }) {
      if activeTool == .pen || activeTool == .eraser {
        // Devam eden parmak stroke'u varsa iptal et, kalem devraldı
        if liveActive && !pencilStrokeActive { cancelLiveStroke() }
        pencilDrawTouch = pt
        pencilStrokeActive = true
        startStroke(at: pt.location(in: self), pressure: pencilNormalizedPressure(pt))
      }
      return
    }

    // 2) Pencil aktifken parmak touch'larını yok say (palm rejection)
    if pencilDrawTouch != nil { return }

    // 3) Eski parmak akışı
    let fingerCount = allTouches.filter { $0.type != .pencil }.count
    if fingerCount == 1, let t = touches.first(where: { $0.type != .pencil }) {
      let screen = t.location(in: self)
      switch activeTool {
      case .select:
        handleSelectTouchDown(world: screenToWorld(screen))
      case .image:
        if imageBitmap != nil { startImageDrag(screen: screen) }
      default:
        pencilStrokeActive = false
        startStroke(at: screen, pressure: nil)
      }
    } else if fingerCount >= 2 {
      let fingerTouches = Set(allTouches.filter { $0.type != .pencil })
      switch activeTool {
      case .select:
        moveDelta = .zero
        resizeLiveScale = 1
        selectionRect = nil
        lassoPoints.removeAll()
        if !selectedIndices.isEmpty {
          startSelectionTwoFinger(allTouches: fingerTouches)
          selectMode = .rotating
          selectionTransforming = true
          return
        }
        selectMode = .idle
        startPinch(touches: fingerTouches)
      case .image:
        if imageBitmap != nil { startImagePinch(touches: fingerTouches) }
      default:
        cancelLiveStroke()
        startPinch(touches: fingerTouches)
      }
    }
  }

  override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
    let allTouches = event?.allTouches ?? touches

    // 1) Pencil aktif çizimi varsa — coalescedTouches ile yüksek frekans örnekle
    if let pencil = pencilDrawTouch, touches.contains(pencil) {
      if liveActive {
        let coalesced = event?.coalescedTouches(for: pencil) ?? [pencil]
        for ct in coalesced {
          continueStroke(to: ct.location(in: self), pressure: pencilNormalizedPressure(ct))
        }
      }
      return
    }

    // 2) Pencil aktifken parmak hareketlerini yok say
    if pencilDrawTouch != nil { return }

    // 3) Eski parmak akışı
    let fingerTouches = Set(allTouches.filter { $0.type != .pencil })
    if fingerTouches.count == 1, let t = touches.first(where: { $0.type != .pencil }) {
      let screen = t.location(in: self)
      switch activeTool {
      case .select:
        handleSelectTouchMove(world: screenToWorld(screen))
      case .image:
        if imageBitmap != nil { updateImageDrag(screen: screen) }
      default:
        if liveActive { continueStroke(to: screen, pressure: nil) }
      }
    } else if fingerTouches.count >= 2 {
      switch activeTool {
      case .select:
        if selectionTransforming { updateSelectionTwoFinger(allTouches: fingerTouches) }
        else { updatePinch(touches: fingerTouches) }
      case .image:
        if imageBitmap != nil { updateImagePinch(touches: fingerTouches) }
      default:
        updatePinch(touches: fingerTouches)
      }
    }
  }

  // Pencil basıncı [0..1]. force == 0 (eski Pencil veya force yok) → nil dön,
  // çağıran taraf default tabanlığa düşer.
  private func pencilNormalizedPressure(_ t: UITouch) -> CGFloat? {
    guard t.type == .pencil else { return nil }
    let maxF = t.maximumPossibleForce
    if maxF > 0 && t.force > 0 {
      return max(0, min(1, t.force / maxF))
    }
    return nil
  }

  override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
    let allTouches = event?.allTouches ?? Set<UITouch>()
    activeTouchCount = allTouches.count - touches.count

    // Pencil çizimi bittiyse — diğer parmak gestures'ı ne olursa olsun stroke'u commit
    if let pencil = pencilDrawTouch, touches.contains(pencil) {
      if liveActive { finishStroke() }
      pencilDrawTouch = nil
      pencilStrokeActive = false
      return
    }

    // Pencil aktifken parmak end'ini yok say
    if pencilDrawTouch != nil { return }

    switch activeTool {
    case .select:
      if selectionTransforming && activeTouchCount <= 1 {
        endSelectionTwoFinger()
      } else if !selectionTransforming && activeTouchCount <= 0 {
        handleSelectTouchUp()
      }
    case .image:
      if activeTouchCount <= 1 { endImagePinch() }
    default:
      if activeTouchCount <= 0 && liveActive {
        finishStroke()
      } else if activeTouchCount <= 1 {
        endPinch()
      }
    }
  }

  override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
    if let pencil = pencilDrawTouch, touches.contains(pencil) {
      cancelLiveStroke()
      pencilDrawTouch = nil
      pencilStrokeActive = false
      return
    }
    switch activeTool {
    case .select:
      selectMode = .idle
      moveDelta = .zero; resizeLiveScale = 1
      selectionRect = nil; lassoPoints.removeAll()
      selectionTransforming = false; rotateLiveAngle = 0
    case .image:
      break
    default:
      cancelLiveStroke()
    }
  }

  // ─── Stroke ───────────────────────────────────────────────────────────
  // Basınç (Pencil): variableWidthSamples ile her örnek için bir width kaydedilir.
  // Parmak: pressure nil → tek-width klasik stroke (geriye dönük uyumlu).
  // Pressure → width mapping: 0.4x..1.4x base width arasında lineer.
  private func widthFromPressure(_ pressure: CGFloat?) -> CGFloat {
    guard let p = pressure else { return liveBaseWidth }
    let factor: CGFloat = 0.4 + p * 1.0  // 0 → 0.4x, 1.0 → 1.4x
    return liveBaseWidth * factor
  }

  private func startStroke(at screen: CGPoint, pressure: CGFloat?) {
    let w = screenToWorld(screen)
    livePath.removeAllPoints()
    livePath.move(to: w)
    liveLastPoint = w
    liveActive = true
    liveTool = activeTool
    liveColor = strokeColor
    // Scale-aware: ekran-px sabit kalsın diye width'i worldScale ile böl
    liveBaseWidth = strokeWidth / max(0.001, worldScale)
    let initialWidth = widthFromPressure(pressure)
    liveWidth = initialWidth  // legacy single-width fallback
    liveOpacity = strokeOpacity
    livePoints = [w.x, w.y]
    liveWidthSamples = (pressure != nil) ? [WidthSample(point: w, width: initialWidth)] : []
    liveStartMs = nowMs()
    lastMoveMs = liveStartMs
    setNeedsDisplay()
  }

  private func continueStroke(to screen: CGPoint, pressure: CGFloat?) {
    let w = screenToWorld(screen)
    let dx = w.x - liveLastPoint.x
    let dy = w.y - liveLastPoint.y
    if dx * dx + dy * dy < 0.5 { return }
    let mid = CGPoint(x: (liveLastPoint.x + w.x) / 2, y: (liveLastPoint.y + w.y) / 2)
    livePath.addQuadCurve(to: mid, controlPoint: liveLastPoint)
    liveLastPoint = w
    livePoints.append(w.x); livePoints.append(w.y)
    if pressure != nil {
      liveWidthSamples.append(WidthSample(point: w, width: widthFromPressure(pressure)))
    }
    lastMoveMs = nowMs()
    setNeedsDisplay()
  }

  private func finishStroke() {
    let endMs = nowMs()
    let durationMs = max(0, endMs - liveStartMs)
    let idleMs = max(0, endMs - lastMoveMs)
    let worldPoints = worldPointsFromLive()

    if liveTool == .eraser {
      // Silgi artık kalıcı bir stroke olarak SAKLANMAZ — altındaki serbest çizim
      // noktalarını gerçekten keser. Böylece "üstünü boyama" hilesi biter.
      liveActive = false
      livePath.removeAllPoints()
      liveWidthSamples.removeAll()
      applyEraser(worldPoints, radius: max(1, liveWidth / 2))
      setNeedsDisplay()
      emitCount()
      return
    }

    livePath.addLine(to: liveLastPoint)
    let committedPath = UIBezierPath(cgPath: livePath.cgPath)
    let samples = liveWidthSamples.isEmpty ? nil : liveWidthSamples
    let s = Stroke(
      path: committedPath, tool: liveTool, color: liveColor,
      width: liveWidth, opacity: liveOpacity,
      variableWidthSamples: samples, points: worldPoints)
    pushHistory()
    strokes.append(s)
    drawStrokeIncrementally(s)
    liveActive = false
    livePath.removeAllPoints()
    liveWidthSamples.removeAll()
    setNeedsDisplay()
    let pointsToEmit = (liveTool == .pen) ? livePoints : []
    emitCount(points: pointsToEmit, durationMs: durationMs, idleMs: idleMs)
  }

  // livePoints ([x0,y0,x1,y1,...] world) → [CGPoint]
  private func worldPointsFromLive() -> [CGPoint] {
    var pts: [CGPoint] = []
    var i = 0
    while i + 1 < livePoints.count {
      pts.append(CGPoint(x: livePoints[i], y: livePoints[i + 1]))
      i += 2
    }
    return pts
  }

  // ─── Gerçek vektörel silme ─────────────────────────────────────────────
  // Silgi polyline'ının yakınından geçtiği serbest-çizim noktalarını çıkarır ve
  // stroke'u kalan parçalara böler. Text/şekil (points boş) all-or-nothing silinir.
  private func applyEraser(_ eraserPts: [CGPoint], radius: CGFloat) {
    guard !eraserPts.isEmpty else { return }
    var newStrokes: [Stroke] = []
    var changed = false
    for s in strokes {
      let hitR = radius + s.width / 2
      if !s.points.isEmpty {
        let subs = splitStrokeByErase(s, eraserPts: eraserPts, hitR: hitR)
        if subs.count == 1 && subs[0].points.count == s.points.count {
          newStrokes.append(s)          // hiç dokunulmadı
        } else {
          changed = true
          newStrokes.append(contentsOf: subs)   // parçalandı (ya da tamamen silindi)
        }
      } else {
        // Şekil / yazı: noktası yok → dokunulduysa tümü silinir.
        let flat = flattenPath(s.path)
        if polylineHit(flat, eraserPts: eraserPts, hitR: hitR) {
          changed = true               // stroke düşürülür
        } else {
          newStrokes.append(s)
        }
      }
    }
    guard changed else { return }
    pushHistory()
    strokes = newStrokes
    redrawCommittedImage()
  }

  // Silgi yarıçapına giren noktaları çıkararak stroke'u ardışık run'lara böler.
  private func splitStrokeByErase(_ s: Stroke, eraserPts: [CGPoint], hitR: CGFloat) -> [Stroke] {
    let r2 = hitR * hitR
    var result: [Stroke] = []
    var run: [CGPoint] = []
    func flush() {
      if run.count >= 2 {
        result.append(Stroke(
          path: smoothPath(run), tool: s.tool, color: s.color,
          width: s.width, opacity: s.opacity, isText: false,
          variableWidthSamples: nil, points: run))
      }
      run.removeAll()
    }
    for p in s.points {
      if pointNearPolyline(p, eraserPts, r2) { flush() } else { run.append(p) }
    }
    flush()
    return result
  }

  // Serbest çizim noktalarından (live pipeline ile aynı) düz path üret.
  private func smoothPath(_ pts: [CGPoint]) -> UIBezierPath {
    let p = UIBezierPath()
    guard let first = pts.first else { return p }
    p.move(to: first)
    if pts.count == 1 { p.addLine(to: first); return p }
    var last = first
    for i in 1..<pts.count {
      let w = pts[i]
      let mid = CGPoint(x: (last.x + w.x) / 2, y: (last.y + w.y) / 2)
      p.addQuadCurve(to: mid, controlPoint: last)
      last = w
    }
    p.addLine(to: last)
    return p
  }

  private func pointNearPolyline(_ p: CGPoint, _ poly: [CGPoint], _ r2: CGFloat) -> Bool {
    if poly.count == 1 {
      let dx = p.x - poly[0].x, dy = p.y - poly[0].y
      return dx * dx + dy * dy <= r2
    }
    for i in 0..<(poly.count - 1) {
      if distToSegmentSq(p, poly[i], poly[i + 1]) <= r2 { return true }
    }
    return false
  }

  private func polylineHit(_ poly: [CGPoint], eraserPts: [CGPoint], hitR: CGFloat) -> Bool {
    let r2 = hitR * hitR
    for p in poly where pointNearPolyline(p, eraserPts, r2) { return true }
    return false
  }

  private func distToSegmentSq(_ p: CGPoint, _ a: CGPoint, _ b: CGPoint) -> CGFloat {
    let dx = b.x - a.x, dy = b.y - a.y
    let len2 = dx * dx + dy * dy
    if len2 <= 0 {
      let px = p.x - a.x, py = p.y - a.y
      return px * px + py * py
    }
    var t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / len2
    t = max(0, min(1, t))
    let projX = a.x + t * dx, projY = a.y + t * dy
    let ex = p.x - projX, ey = p.y - projY
    return ex * ex + ey * ey
  }

  // UIBezierPath'i hit-test için örnek noktalara indirger (şekil/yazı silme).
  private func flattenPath(_ path: UIBezierPath) -> [CGPoint] {
    var pts: [CGPoint] = []
    var current = CGPoint.zero
    var start = CGPoint.zero
    path.cgPath.applyWithBlock { elem in
      let e = elem.pointee
      switch e.type {
      case .moveToPoint:
        current = e.points[0]; start = current; pts.append(current)
      case .addLineToPoint:
        current = e.points[0]; pts.append(current)
      case .addQuadCurveToPoint:
        let c = e.points[0], end = e.points[1]
        for k in 1...4 {
          let t = CGFloat(k) / 4
          let mt = 1 - t
          let x = mt * mt * current.x + 2 * mt * t * c.x + t * t * end.x
          let y = mt * mt * current.y + 2 * mt * t * c.y + t * t * end.y
          pts.append(CGPoint(x: x, y: y))
        }
        current = end
      case .addCurveToPoint:
        let c1 = e.points[0], c2 = e.points[1], end = e.points[2]
        for k in 1...4 {
          let t = CGFloat(k) / 4
          let mt = 1 - t
          let x = mt * mt * mt * current.x + 3 * mt * mt * t * c1.x + 3 * mt * t * t * c2.x + t * t * t * end.x
          let y = mt * mt * mt * current.y + 3 * mt * mt * t * c1.y + 3 * mt * t * t * c2.y + t * t * t * end.y
          pts.append(CGPoint(x: x, y: y))
        }
        current = end
      case .closeSubpath:
        pts.append(start); current = start
      @unknown default:
        break
      }
    }
    return pts
  }

  // ─── Seçili bölgeyi sil ────────────────────────────────────────────────
  func deleteSelection() {
    guard !selectedIndices.isEmpty else { return }
    pushHistory()
    let kept = strokes.enumerated().filter { !selectedIndices.contains($0.offset) }.map { $0.element }
    strokes = kept
    clearSelection()
    redrawCommittedImage()
    setNeedsDisplay()
    emitCount()
  }

  private func cancelLiveStroke() {
    liveActive = false
    livePath.removeAllPoints()
    liveWidthSamples.removeAll()
    // Tool switch sırasında bu fonksiyon public wrapper ile çağrılır;
    // pencilDrawTouch'ı bırakmazsak sonraki gesture'da palm rejection
    // takılı kalır.
    pencilDrawTouch = nil
    pencilStrokeActive = false
    setNeedsDisplay()
  }

  private func nowMs() -> Int64 {
    return Int64(Date().timeIntervalSince1970 * 1000)
  }

  // ─── Pan/Pinch (canvas zoom + rotate) ─────────────────────────────────
  // İki parmağı ID-stable seç: tracked çiftten en az biri kayboldysa
  // mevcut iki parmakla re-baseline yap; jump yok.
  private func resolvePinchTouches(from touches: Set<UITouch>) -> (UITouch, UITouch)? {
    if let a = pinchTouchA, let b = pinchTouchB, touches.contains(a), touches.contains(b) {
      return (a, b)
    }
    let arr = Array(touches.prefix(2))
    guard arr.count == 2 else { return nil }
    return (arr[0], arr[1])
  }

  // Pinch baseline'ı YENİ iki parmağa göre kur: saved* = world* (jump yok)
  private func baselinePinch(_ a: UITouch, _ b: UITouch) {
    pinchTouchA = a; pinchTouchB = b
    let p0 = a.location(in: self)
    let p1 = b.location(in: self)
    savedScale = worldScale
    savedRotation = worldRotation
    savedTx = worldTx
    savedTy = worldTy
    pinchInitialDist = max(0.0001, distance(p0, p1))
    pinchInitialAngle = atan2(p1.y - p0.y, p1.x - p0.x)
    pinchRotationActivated = false
    pinchInitialFocal = CGPoint(x: (p0.x + p1.x) / 2, y: (p0.y + p1.y) / 2)
    let cx = bounds.midX
    let cy = bounds.midY
    let dx = pinchInitialFocal.x - cx - savedTx
    let dy = pinchInitialFocal.y - cy - savedTy
    let sc = max(0.0001, savedScale)
    let cosR = cos(-savedRotation); let sinR = sin(-savedRotation)
    lockedWorld = CGPoint(
      x: cx + (dx * cosR - dy * sinR) / sc,
      y: cy + (dx * sinR + dy * cosR) / sc
    )
  }

  private func startPinch(touches: Set<UITouch>) {
    let arr = Array(touches.prefix(2))
    guard arr.count == 2 else { return }
    baselinePinch(arr[0], arr[1])
  }

  private func updatePinch(touches: Set<UITouch>) {
    guard let (ta, tb) = resolvePinchTouches(from: touches) else { return }
    // Tracked çift değiştiyse (parmak değişimi) re-baseline ve frame'i atla
    if ta !== pinchTouchA || tb !== pinchTouchB {
      baselinePinch(ta, tb)
      return
    }
    if pinchInitialDist <= 0 { return }
    let p0 = ta.location(in: self)
    let p1 = tb.location(in: self)
    let currentDist = distance(p0, p1)
    let rawScale = currentDist / pinchInitialDist
    let damped = 1.0 + (rawScale - 1.0) * 0.8
    let newScale = max(minZoom, min(maxZoom, savedScale * damped))
    let currentAngle = atan2(p1.y - p0.y, p1.x - p0.x)
    // atan2 ±π'de wrap eder; delta'yı [-π, π]'ye normalize ederek "tepe taklak" sıçramayı önle.
    var angleDelta = currentAngle - pinchInitialAngle
    while angleDelta > .pi { angleDelta -= 2 * .pi }
    while angleDelta < -.pi { angleDelta += 2 * .pi }
    // Deadzone: eşik aşılana kadar rotation 0, aşılınca re-baseline (intent kanıtlandı)
    if !pinchRotationActivated {
      if abs(angleDelta) > rotationDeadzone {
        pinchRotationActivated = true
        pinchInitialAngle = currentAngle
      }
      angleDelta = 0
    }
    let newRotation = savedRotation + angleDelta
    let focal = CGPoint(x: (p0.x + p1.x) / 2, y: (p0.y + p1.y) / 2)
    let cx = bounds.midX
    let cy = bounds.midY
    // worldTx = focal - center - R(rot) * scale * (locked - center)
    let ldx = (lockedWorld.x - cx) * newScale
    let ldy = (lockedWorld.y - cy) * newScale
    let cosR = cos(newRotation); let sinR = sin(newRotation)
    let rx = ldx * cosR - ldy * sinR
    let ry = ldx * sinR + ldy * cosR
    worldScale = newScale
    worldRotation = newRotation
    worldTx = focal.x - cx - rx
    worldTy = focal.y - cy - ry
    setNeedsDisplay()
    onZoomChanged?(newScale)
  }

  private func endPinch() {
    // Cardinal snap: worldRotation π/2 katına ~10° yakınsa o açıya kilitle.
    // tx/ty dokunulmaz; rotation pivot'u (cx+tx, cy+ty) — küçük delta'da görsel sıçrama minimal.
    let snapStep: CGFloat = .pi / 2
    let target = (worldRotation / snapStep).rounded() * snapStep
    if abs(worldRotation - target) < rotationSnapTolerance {
      worldRotation = target
      setNeedsDisplay()
    }
    savedScale = worldScale
    savedRotation = worldRotation
    savedTx = worldTx
    savedTy = worldTy
    pinchTouchA = nil; pinchTouchB = nil
  }

  // ─── Image tool — image üzerinde pan + pinch + rotate ─────────────────
  private func startImageDrag(screen: CGPoint) {
    let w = screenToWorld(screen)
    imageDragStartWorld = w
    imageDragStartCenter = imageCenter
  }

  private func updateImageDrag(screen: CGPoint) {
    let w = screenToWorld(screen)
    imageCenter = CGPoint(
      x: imageDragStartCenter.x + (w.x - imageDragStartWorld.x),
      y: imageDragStartCenter.y + (w.y - imageDragStartWorld.y))
    setNeedsDisplay()
  }

  // Image pinch — aynı pointer-stable + jump-free re-baseline pattern
  private func resolveImagePinchTouches(from touches: Set<UITouch>) -> (UITouch, UITouch)? {
    if let a = imagePinchTouchA, let b = imagePinchTouchB,
       touches.contains(a), touches.contains(b) {
      return (a, b)
    }
    let arr = Array(touches.prefix(2))
    guard arr.count == 2 else { return nil }
    return (arr[0], arr[1])
  }

  private func baselineImagePinch(_ a: UITouch, _ b: UITouch) {
    imagePinchTouchA = a; imagePinchTouchB = b
    savedImageCenter = imageCenter
    savedImageScale = imageScale
    savedImageRotation = imageRotation
    let wp0 = screenToWorld(a.location(in: self))
    let wp1 = screenToWorld(b.location(in: self))
    imagePinchInitialDist = max(0.0001, hypot(wp1.x - wp0.x, wp1.y - wp0.y))
    imagePinchInitialAngle = atan2(wp1.y - wp0.y, wp1.x - wp0.x)
    imagePinchRotationActivated = false
    let focal = CGPoint(x: (wp0.x + wp1.x) / 2, y: (wp0.y + wp1.y) / 2)
    let sc = max(0.0001, savedImageScale)
    let cosR = cos(-savedImageRotation); let sinR = sin(-savedImageRotation)
    let dx = focal.x - savedImageCenter.x
    let dy = focal.y - savedImageCenter.y
    imageLockedLocal = CGPoint(
      x: (dx * cosR - dy * sinR) / sc,
      y: (dx * sinR + dy * cosR) / sc)
  }

  private func startImagePinch(touches: Set<UITouch>) {
    let arr = Array(touches.prefix(2))
    guard arr.count == 2 else { return }
    baselineImagePinch(arr[0], arr[1])
  }

  private func updateImagePinch(touches: Set<UITouch>) {
    guard let (ta, tb) = resolveImagePinchTouches(from: touches) else { return }
    if ta !== imagePinchTouchA || tb !== imagePinchTouchB {
      baselineImagePinch(ta, tb)
      return
    }
    if imagePinchInitialDist <= 0 { return }
    let wp0 = screenToWorld(ta.location(in: self))
    let wp1 = screenToWorld(tb.location(in: self))
    let dist = max(0.0001, hypot(wp1.x - wp0.x, wp1.y - wp0.y))
    let angle = atan2(wp1.y - wp0.y, wp1.x - wp0.x)
    let newScale = max(0.1, min(10, savedImageScale * (dist / imagePinchInitialDist)))
    // atan2 ±π wrap'ini normalize et
    var imgAngleDelta = angle - imagePinchInitialAngle
    while imgAngleDelta > .pi { imgAngleDelta -= 2 * .pi }
    while imgAngleDelta < -.pi { imgAngleDelta += 2 * .pi }
    // Deadzone: kasıtsız twist'i yok say, aktive olunca re-baseline
    if !imagePinchRotationActivated {
      if abs(imgAngleDelta) > rotationDeadzone {
        imagePinchRotationActivated = true
        imagePinchInitialAngle = angle
      }
      imgAngleDelta = 0
    }
    let newRot = savedImageRotation + imgAngleDelta
    let focal = CGPoint(x: (wp0.x + wp1.x) / 2, y: (wp0.y + wp1.y) / 2)
    let lx = imageLockedLocal.x * newScale
    let ly = imageLockedLocal.y * newScale
    let cosR = cos(newRot); let sinR = sin(newRot)
    let rx = lx * cosR - ly * sinR
    let ry = lx * sinR + ly * cosR
    imageScale = newScale
    imageRotation = newRot
    imageCenter = CGPoint(x: focal.x - rx, y: focal.y - ry)
    setNeedsDisplay()
  }

  private func endImagePinch() {
    // Cardinal snap: imageRotation π/2 katına ~10° yakınsa o açıya kilitle.
    // Image rotation imageCenter etrafında — pivot zaten merkez, görsel kaymа yok.
    let snapStep: CGFloat = .pi / 2
    let target = (imageRotation / snapStep).rounded() * snapStep
    if abs(imageRotation - target) < rotationSnapTolerance {
      imageRotation = target
      setNeedsDisplay()
    }
    imagePinchTouchA = nil; imagePinchTouchB = nil
  }

  // ─── 2-finger selection transform (rotate + scale) ────────────────────
  // Pointer-stable + jump-free re-baseline (canvas/image ile aynı pattern)
  private func resolveSelectionTouches(from touches: Set<UITouch>) -> (UITouch, UITouch)? {
    if let a = selectionTouchA, let b = selectionTouchB,
       touches.contains(a), touches.contains(b) {
      return (a, b)
    }
    let arr = Array(touches.prefix(2))
    guard arr.count == 2 else { return nil }
    return (arr[0], arr[1])
  }

  // Re-baseline: live transform'u şu anki tracked touches ile commit et,
  // sonra yeni iki parmağa göre baseline'ı sıfırla. Bu sayede parmak değiştiğinde
  // selection sıçramaz, mevcut rotate/scale state'i korunur.
  private func baselineSelectionTwoFinger(_ a: UITouch, _ b: UITouch, commitLive: Bool) {
    if commitLive {
      // Önce mevcut live rotate/scale'i selection'a uygula (endSelectionTwoFinger'ın yarısı)
      if resizeLiveScale != 1 { applyResizeTransform(resizeLiveScale) }
      if rotateLiveAngle != 0 {
        if let bnd = selectionBounds {
          rotateCenter = CGPoint(x: bnd.midX, y: bnd.midY)
        }
        applyRotationTransform(rotateLiveAngle)
      }
    }
    selectionTouchA = a; selectionTouchB = b
    let wp0 = screenToWorld(a.location(in: self))
    let wp1 = screenToWorld(b.location(in: self))
    twoFingerInitialDist = max(1, hypot(wp1.x - wp0.x, wp1.y - wp0.y))
    twoFingerInitialAngle = atan2(wp1.y - wp0.y, wp1.x - wp0.x)
    if let bnd = selectionBounds {
      rotateCenter = CGPoint(x: bnd.midX, y: bnd.midY)
    }
    resizeAnchor = rotateCenter
    rotateLiveAngle = 0
    resizeLiveScale = 1
    moveDelta = .zero
  }

  private func startSelectionTwoFinger(allTouches: Set<UITouch>) {
    let arr = Array(allTouches.prefix(2))
    guard arr.count == 2 else { return }
    baselineSelectionTwoFinger(arr[0], arr[1], commitLive: false)
  }

  private func updateSelectionTwoFinger(allTouches: Set<UITouch>) {
    guard let (ta, tb) = resolveSelectionTouches(from: allTouches) else { return }
    if ta !== selectionTouchA || tb !== selectionTouchB {
      baselineSelectionTwoFinger(ta, tb, commitLive: true)
      return
    }
    let wp0 = screenToWorld(ta.location(in: self))
    let wp1 = screenToWorld(tb.location(in: self))
    let dist = max(0.01, hypot(wp1.x - wp0.x, wp1.y - wp0.y))
    let angle = atan2(wp1.y - wp0.y, wp1.x - wp0.x)
    // atan2 ±π wrap'ini normalize et
    var selAngleDelta = angle - twoFingerInitialAngle
    while selAngleDelta > .pi { selAngleDelta -= 2 * .pi }
    while selAngleDelta < -.pi { selAngleDelta += 2 * .pi }
    rotateLiveAngle = selAngleDelta
    resizeLiveScale = max(0.2, min(6, dist / twoFingerInitialDist))
    setNeedsDisplay()
  }

  private func endSelectionTwoFinger() {
    let angle = rotateLiveAngle
    let scale = resizeLiveScale
    if scale != 1 { applyResizeTransform(scale) }
    if angle != 0 {
      // Scale sonrası bounds değişti, rotation pivot'unu yeniden hesapla
      if let b = selectionBounds {
        rotateCenter = CGPoint(x: b.midX, y: b.midY)
      }
      applyRotationTransform(angle)
    }
    rotateLiveAngle = 0
    resizeLiveScale = 1
    selectionTransforming = false
    selectMode = .idle
    selectionTouchA = nil; selectionTouchB = nil
    setNeedsDisplay()
  }

  // ─── Coord transform ──────────────────────────────────────────────────
  // Forward: world → screen
  //   screen = center + worldTx + R(rot) * worldScale * (world - center)
  // Inverse: screen → world
  //   world = center + (1/scale) * R(-rot) * (screen - center - worldTx)
  private func screenToWorld(_ s: CGPoint) -> CGPoint {
    let cx = bounds.midX
    let cy = bounds.midY
    let dx = s.x - cx - worldTx
    let dy = s.y - cy - worldTy
    let sc = max(0.0001, worldScale)
    let cosR = cos(-worldRotation); let sinR = sin(-worldRotation)
    let rx = dx * cosR - dy * sinR
    let ry = dx * sinR + dy * cosR
    return CGPoint(x: cx + rx / sc, y: cy + ry / sc)
  }

  private func worldToScreen(_ w: CGPoint) -> CGPoint {
    let cx = bounds.midX
    let cy = bounds.midY
    let dx = (w.x - cx) * worldScale
    let dy = (w.y - cy) * worldScale
    let cosR = cos(worldRotation); let sinR = sin(worldRotation)
    let rx = dx * cosR - dy * sinR
    let ry = dx * sinR + dy * cosR
    return CGPoint(x: cx + worldTx + rx, y: cy + worldTy + ry)
  }

  private func distance(_ a: CGPoint, _ b: CGPoint) -> CGFloat {
    let dx = a.x - b.x
    let dy = a.y - b.y
    return sqrt(dx * dx + dy * dy)
  }
}

struct WidthSample {
  let point: CGPoint
  let width: CGFloat
}

struct Stroke {
  let path: UIBezierPath
  let tool: CanvasTool
  let color: UIColor
  let width: CGFloat
  let opacity: CGFloat
  var isText: Bool = false
  // Apple Pencil basıncı — set ise renderStroke segment-by-segment width interp eder.
  // Eraser/text/parmak stroke'ları için nil; geriye dönük uyumlu.
  var variableWidthSamples: [WidthSample]? = nil
  // Serbest çizim polyline'ı (world koordinat). Silgi bu noktaları gerçekten
  // keser → silinen kısım VERİ olarak yok olur; taşıma/döndürme geri getiremez.
  // Text/şekil stroke'ları için boş (all-or-nothing silinir).
  var points: [CGPoint] = []
}
