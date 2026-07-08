package expo.modules.nativecanvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.viewevent.EventDispatcher
import expo.modules.kotlin.views.ExpoView
import java.io.InputStream
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sin

/**
 * Native canvas view.
 *
 * Tüm draw / touch / pan / pinch UI thread'de Android Canvas API ile yapılır.
 * JS bridge sadece prop güncellemesi + komut (undo/redo/clear) + commit event'inde devreye girer.
 */
class ExpoNativeCanvasView(context: Context, appContext: AppContext) : ExpoView(context, appContext) {

  private val onStrokeCountChange by EventDispatcher()
  private val onZoomChange by EventDispatcher()

  private val contentView: CanvasContentView

  init {
    contentView = CanvasContentView(context).apply {
      onCountChanged = { total, redo, points, durationMs, idleMs ->
        onStrokeCountChange(mapOf(
          "totalStrokes" to total,
          "redoCount" to redo,
          "points" to points,
          "durationMs" to durationMs,
          "idleMs" to idleMs,
        ))
      }
      onZoomChanged = { z ->
        onZoomChange(mapOf("zoom" to z, "rotation" to contentView.currentRotation()))
      }
    }
    addView(contentView, ViewGroup.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT,
      ViewGroup.LayoutParams.MATCH_PARENT
    ))
  }

  fun setImageUri(uri: String?) { contentView.setImageUri(uri) }

  fun setTool(tool: String) {
    val newTool = when (tool) {
      "eraser" -> CanvasTool.ERASER
      "select" -> CanvasTool.SELECT
      "image" -> CanvasTool.IMAGE
      else -> CanvasTool.PEN
    }
    // SELECT'ten çıkarken seçimi temizle
    if (contentView.activeTool == CanvasTool.SELECT && newTool != CanvasTool.SELECT) {
      contentView.clearSelection()
    }
    // SELECT/IMAGE'a girerken aktif çizimi iptal et
    if (newTool == CanvasTool.SELECT || newTool == CanvasTool.IMAGE) {
      contentView.cancelLiveStrokePublic()
    }
    contentView.activeTool = newTool
  }

  fun setStrokeColor(color: String) { contentView.strokeColor = parseColor(color) }
  fun setStrokeWidth(width: Float) { contentView.strokeWidth = dp(width) }
  fun setStrokeOpacity(opacity: Float) { contentView.strokeOpacity = opacity.coerceIn(0f, 1f) }
  fun setMinZoom(z: Float) { contentView.minZoom = z }
  fun setMaxZoom(z: Float) { contentView.maxZoom = z }
  fun setCanvasBackgroundColor(color: String) {
    contentView.canvasBgColor = parseColor(color)
    contentView.invalidate()
  }
  fun setCanvasOverlay(overlay: String) {
    contentView.canvasOverlay = overlay
    contentView.invalidate()
  }
  fun setSelectionMode(mode: String) { contentView.setSelectionMode(mode) }

  fun runUndo() { contentView.undo() }
  fun runRedo() { contentView.redo() }
  fun runClear() { contentView.clear() }
  fun runReplaceLastStrokeWithShape(shape: Map<String, Any>) {
    contentView.replaceLastStrokeWithShape(shape)
  }
  fun runResetCanvasTransform() { contentView.resetCanvasTransform() }
  fun runResetImageTransform() { contentView.resetImageTransform() }
  fun runInsertText(text: String, fontSize: Float, color: String) {
    contentView.insertText(text, dp(fontSize), parseColor(color))
  }
  fun runSetSelectedStrokeColor(color: String) {
    contentView.setSelectedStrokeColor(parseColor(color))
  }
  fun runDeleteSelection() { contentView.deleteSelection() }

  private fun parseColor(hex: String): Int =
    try { Color.parseColor(hex) } catch (e: Throwable) { Color.BLACK }

  private fun dp(value: Float): Float =
    value * context.resources.displayMetrics.density
}

enum class CanvasTool { PEN, ERASER, SELECT, IMAGE }

enum class SelectMode { IDLE, DRAWING_RECT, DRAWING_LASSO, MOVING, RESIZING, ROTATING, ROTATING_HANDLE }

enum class SelectionPickMode { RECT, LASSO }

/**
 * Gerçek çizim canvas'ı. Tüm render, touch ve transform burada.
 */
class CanvasContentView(context: Context) : View(context) {

  // ─── Render state ──────────────────────────────────────────────────────
  private val strokes = ArrayList<Stroke>()
  // Snapshot tabanlı geçmiş — silgi artık birden çok stroke'u mutate ettiği için
  // (gerçek vektörel kesme) tek-stroke pop yeterli değil; tüm diziyi snapshot'la.
  private val undoStack = ArrayList<List<Stroke>>()
  private val redoStack = ArrayList<List<Stroke>>()
  private val maxHistory = 80
  private val livePath = Path()
  private var liveActive = false
  private var liveLastX = 0f
  private var liveLastY = 0f
  private var liveTool: CanvasTool = CanvasTool.PEN
  private var liveColor: Int = Color.BLACK
  private var liveWidth: Float = 8f
  private var liveOpacity: Float = 1f
  private var livePoints = ArrayList<Float>()
  // S-Pen / stylus — aktif çizimi yapan pointer ID. Set ise, palm rejection
  // devrede: parmak ile gelen sonraki ACTION_POINTER_DOWN'lar yok sayılır.
  private var stylusDrawPointerId: Int = MotionEvent.INVALID_POINTER_ID
  private var stylusStrokeActive: Boolean = false
  // Stylus basıncı ile değişken kalınlık — her örnek için sample biriktirilir,
  // drawStrokeToCanvas variableWidthSamples varsa segment-by-segment width interp eder.
  private val liveWidthSamples = ArrayList<WidthSample>()
  private var liveBaseWidth: Float = 8f  // strokeWidth (basınçsız taban)

  private var committedBitmap: Bitmap? = null
  private var committedCanvas: Canvas? = null

  private var imageBitmap: Bitmap? = null
  private var imageUri: String? = null

  // Transform (world coords)
  private var worldScale = 1f
  private var worldRotation = 0f  // radians
  private var worldTx = 0f
  private var worldTy = 0f
  private var savedScale = 1f
  private var savedRotation = 0f
  private var savedTx = 0f
  private var savedTy = 0f

  // Image item — interactive transform üzerinde uygulanır (image tool)
  // imageCenter: world coords; imageScale: baseFit'e ek katsayı; imageRotation: radians.
  private var imageCenterX = 0f
  private var imageCenterY = 0f
  private var imageScale = 1f
  private var imageRotation = 0f
  // Pinch sırasında saklanan değerler
  private var savedImageCenterX = 0f
  private var savedImageCenterY = 0f
  private var savedImageScale = 1f
  private var savedImageRotation = 0f
  // İlk yüklenmede tek seferlik fit yapılması için
  private var imageInitialized = false

  var activeTool: CanvasTool = CanvasTool.PEN
  var strokeColor: Int = Color.BLACK
  var strokeWidth: Float = 8f
  var strokeOpacity: Float = 1f
  var minZoom: Float = 0.5f
  var maxZoom: Float = 6f
  var canvasBgColor: Int = Color.WHITE
  var canvasOverlay: String = "none"
  var selectionPickMode: SelectionPickMode = SelectionPickMode.RECT

  // Pinch state
  private var pinchInitialDist = 0f
  private var pinchInitialFocalX = 0f
  private var pinchInitialFocalY = 0f
  private var lockedWorldX = 0f
  private var lockedWorldY = 0f
  // Pinch boyunca KULLANILAN iki parmağın stable pointer ID'leri.
  // event.getX(0)/getX(1) pointer INDEX kullanır — parmak kalkıp eklenince index'ler
  // yeniden numaralanır. Stable olan ID; her frame ID ile bul, biri yoksa re-baseline.
  private var pinchPointerId0 = MotionEvent.INVALID_POINTER_ID
  private var pinchPointerId1 = MotionEvent.INVALID_POINTER_ID
  private var imagePinchPointerId0 = MotionEvent.INVALID_POINTER_ID
  private var imagePinchPointerId1 = MotionEvent.INVALID_POINTER_ID
  private var selectionPointerId0 = MotionEvent.INVALID_POINTER_ID
  private var selectionPointerId1 = MotionEvent.INVALID_POINTER_ID

  var onCountChanged: ((Int, Int, List<Float>, Long, Long) -> Unit)? = null
  var onZoomChanged: ((Float) -> Unit)? = null

  // Stroke timing — şekil tanıma için
  private var liveStartMs: Long = 0L
  private var lastMoveMs: Long = 0L

  // ─── Selection state ───────────────────────────────────────────────────
  private var selectMode = SelectMode.IDLE
  private val selectedIndices = mutableSetOf<Int>()
  private var selectionRect: RectF? = null   // world coords, çizilirken
  private var selectionBounds: RectF? = null  // world coords, bounding box
  // Canlı preview için ayrılmış bitmaplar
  private var selectionBaseBitmap: Bitmap? = null      // seçili olmayan strokes
  private var selectionStrokesBitmap: Bitmap? = null   // sadece seçili strokes
  // Move state
  private var moveStartWorldX = 0f
  private var moveStartWorldY = 0f
  private var moveDeltaX = 0f
  private var moveDeltaY = 0f
  // Resize state
  private var resizeCorner = -1  // 0=TL, 1=TR, 2=BL, 3=BR
  private var resizeAnchorX = 0f
  private var resizeAnchorY = 0f
  private var resizeStartDist = 1f
  private var resizeLiveScale = 1f
  // Lasso state (world coords poligonu)
  private var lassoPoints = ArrayList<Float>() // x0,y0,x1,y1,...
  // Rotation state
  private var rotateCenterX = 0f
  private var rotateCenterY = 0f
  private var rotateStartAngle = 0f
  private var rotateLiveAngle = 0f  // radians, current rotation delta
  // 2-finger gestures üzerinde seçili strokes (transform 2-finger ile)
  private var twoFingerSavedScale = 1f
  private var twoFingerSavedAngle = 0f
  private var twoFingerSavedTx = 0f
  private var twoFingerSavedTy = 0f
  private var twoFingerInitialDist = 0f
  private var twoFingerInitialAngle = 0f
  private var twoFingerInitialFocalX = 0f
  private var twoFingerInitialFocalY = 0f
  private var selectionTransforming = false

  private val density = context.resources.displayMetrics.density

  // ─── Paints ────────────────────────────────────────────────────────────
  private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
  }
  private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
    xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
  }
  private val textFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.FILL
  }
  private val cursiveTypeface: Typeface = Typeface.create("cursive", Typeface.NORMAL)
  private val bgPaint = Paint().apply { isAntiAlias = false }
  private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
  }

  // Selection UI paints
  private val selRectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    color = Color.parseColor("#6366f1")
  }
  private val selBoundsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    color = Color.parseColor("#6366f1")
  }
  private val selFillPaint = Paint().apply {
    style = Paint.Style.FILL
    color = Color.argb(20, 99, 102, 241)
  }
  private val selHandleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.FILL
    color = Color.parseColor("#6366f1")
  }
  private val selHandleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    color = Color.WHITE
  }

  // Bounded canvas — world coords içinde sabit drawable alan
  // Layout sırasında view boyutunda sabitlenir (square ya da view bbox)
  private val canvasRect = RectF()
  private val outsideOverlayPaint = Paint().apply {
    color = Color.parseColor("#33000000") // soft gray semi-transparent
  }

  init {
    setLayerType(LAYER_TYPE_HARDWARE, null)
  }

  fun setSelectionMode(mode: String) {
    val newMode = if (mode == "lasso") SelectionPickMode.LASSO else SelectionPickMode.RECT
    if (selectionPickMode == newMode) return
    selectionPickMode = newMode
    // Mode değişince devam eden draw'ı bırak
    if (selectMode == SelectMode.DRAWING_RECT || selectMode == SelectMode.DRAWING_LASSO) {
      selectionRect = null
      lassoPoints.clear()
      selectMode = SelectMode.IDLE
      invalidate()
    }
  }

  // ─── Image loading ─────────────────────────────────────────────────────
  fun setImageUri(uri: String?) {
    if (uri == imageUri) return
    imageUri = uri
    if (uri.isNullOrEmpty()) { imageBitmap = null; invalidate(); return }
    Thread {
      val bmp = loadBitmap(uri)
      post { imageBitmap = bmp; invalidate() }
    }.start()
  }

  private fun loadBitmap(uri: String): Bitmap? = try {
    val stream: InputStream? = when {
      uri.startsWith("file://") || uri.startsWith("content://") ->
        context.contentResolver.openInputStream(Uri.parse(uri))
      uri.startsWith("http://") || uri.startsWith("https://") ->
        java.net.URL(uri).openStream()
      else -> context.contentResolver.openInputStream(Uri.parse(uri))
    }
    stream?.use { BitmapFactory.decodeStream(it) }
  } catch (e: Throwable) { null }

  // ─── Layout ───────────────────────────────────────────────────────────
  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    if (w > 0 && h > 0) {
      committedBitmap?.recycle()
      committedBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
      committedCanvas = Canvas(committedBitmap!!)
      // Bounded canvas — view alanını tamamen kapsar (100% açılış)
      // World coords içinde sabit drawable bölge. Pan/zoom bunu boyut olarak değiştirmez.
      canvasRect.set(0f, 0f, w.toFloat(), h.toFloat())
      // Bounds her değiştiğinde (ilk init + orientation rotation) image'ı yeniden fit et.
      // computeImageBaseFit aspect'i otomatik korur; burada sadece center/scale/rotation'ı sıfırlıyoruz.
      imageCenterX = canvasRect.centerX()
      imageCenterY = canvasRect.centerY()
      imageScale = 1f
      imageRotation = 0f
      imageInitialized = true
      clearSelection()
      redrawCommittedBitmap()
      invalidate()
    }
  }

  // Image item için baseline fit — kullanıcı transform'u (imageScale, imageRotation)
  // bu base'in üzerine binişir.
  private fun computeImageBaseFit(bmp: Bitmap): Pair<Float, Float> {
    val iw = bmp.width.toFloat(); val ih = bmp.height.toFloat()
    if (iw <= 0f || ih <= 0f) return 0f to 0f
    val targetW = canvasRect.width() * 0.9f
    val targetH = canvasRect.height() * 0.9f
    val fit = minOf(targetW / iw, targetH / ih)
    return (iw * fit) to (ih * fit)
  }

  fun resetCanvasTransform() {
    worldScale = 1f; worldRotation = 0f; worldTx = 0f; worldTy = 0f
    savedScale = 1f; savedRotation = 0f; savedTx = 0f; savedTy = 0f
    invalidate()
    onZoomChanged?.invoke(worldScale)
  }

  fun resetImageTransform() {
    imageCenterX = canvasRect.centerX()
    imageCenterY = canvasRect.centerY()
    imageScale = 1f
    imageRotation = 0f
    invalidate()
  }

  // ─── Drawing ──────────────────────────────────────────────────────────
  override fun onDraw(canvas: Canvas) {
    // Tüm ekran açık gri (canvas-dışı alan)
    canvas.drawColor(Color.parseColor("#e5e7eb"))

    canvas.save()
    val cx = width / 2f
    val cy = height / 2f
    canvas.translate(cx + worldTx, cy + worldTy)
    if (worldRotation != 0f) canvas.rotate(worldRotation * 180f / Math.PI.toFloat())
    canvas.scale(worldScale, worldScale)
    canvas.translate(-cx, -cy)

    // Bounded canvas — sadece bu alana çizim renderlenecek
    canvas.save()
    canvas.clipRect(canvasRect)

    // Canvas BG (sadece çizim alanı)
    bgPaint.color = canvasBgColor
    canvas.drawRect(canvasRect, bgPaint)

    // Overlay (grid / lines) — IMAGE'DAN ÖNCE çiz ki image üzerine basmasın.
    // Sıra: bg → overlay → image → strokes. Image overlay'i kapatır,
    // image yokken grid boş kanvasta görünür.
    drawCanvasOverlay(canvas)

    // Arka plan görseli — image item kendi transform'unu kullanır
    imageBitmap?.let { bmp ->
      val (baseW, baseH) = computeImageBaseFit(bmp)
      if (baseW > 0f && baseH > 0f) {
        canvas.save()
        canvas.translate(imageCenterX, imageCenterY)
        if (imageRotation != 0f) canvas.rotate(imageRotation * 180f / Math.PI.toFloat())
        canvas.scale(imageScale, imageScale)
        canvas.drawBitmap(
          bmp, null,
          RectF(-baseW / 2f, -baseH / 2f, baseW / 2f, baseH / 2f),
          bgPaint,
        )
        canvas.restore()
      }
    }

    // Committed strokes — seçim transformasyonu sırasında vektörel preview
    // (bitmap scale yapmayız → stroke kalınlığı sabit kalır)
    val previewActive = activeTool == CanvasTool.SELECT &&
      (selectMode == SelectMode.MOVING ||
        selectMode == SelectMode.RESIZING ||
        selectMode == SelectMode.ROTATING ||
        selectMode == SelectMode.ROTATING_HANDLE)
    if (previewActive) {
      // Zoom aktif iken selectionBaseBitmap upsample edilirse pixelation olur →
      // non-selected stroke'ları vektörel render et.
      val zoomed = abs(worldScale - 1f) > 0.001f
      if (zoomed) {
        val sc = canvas.saveLayer(null, null)
        for (i in strokes.indices) {
          if (i !in selectedIndices) drawStrokeToCanvas(strokes[i], canvas)
        }
        canvas.restoreToCount(sc)
      } else {
        selectionBaseBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
      }
      // Seçili strokes'u vektörel olarak yeniden render et
      val m = Matrix()
      when (selectMode) {
        SelectMode.MOVING -> m.setTranslate(moveDeltaX, moveDeltaY)
        SelectMode.RESIZING -> m.setScale(resizeLiveScale, resizeLiveScale, resizeAnchorX, resizeAnchorY)
        SelectMode.ROTATING,
        SelectMode.ROTATING_HANDLE ->
          m.setRotate(rotateLiveAngle * 180f / Math.PI.toFloat(), rotateCenterX, rotateCenterY)
        else -> {}
      }
      val tmp = Path()
      // Seçili stroke'ları kendi offscreen layer'inde render et — eraser CLEAR
      // bu layer dışına (base bitmap'e) dokunmaz, sadece seçilen pen pixel'lerini siler.
      val previewLayer = canvas.saveLayer(null, null)
      for (i in selectedIndices) {
        val s = strokes[i]
        s.path.transform(m, tmp)
        if (s.tool == CanvasTool.ERASER) {
          eraserPaint.strokeWidth = s.width
          canvas.drawPath(tmp, eraserPaint)
        } else if (s.isText) {
          textFillPaint.color = s.color
          textFillPaint.alpha = (s.opacity * 255).toInt().coerceIn(0, 255)
          canvas.drawPath(tmp, textFillPaint)
        } else {
          strokePaint.color = s.color
          strokePaint.strokeWidth = s.width
          strokePaint.alpha = (s.opacity * 255).toInt().coerceIn(0, 255)
          canvas.drawPath(tmp, strokePaint)
        }
      }
      canvas.restoreToCount(previewLayer)
    } else if (abs(worldScale - 1f) > 0.001f) {
      // Zoom aktif → committed bitmap upsample/downsample edilince pixelation oluyor.
      // Doğrudan vektör path'leri çiz; eraser CLEAR'ın bg image'e dokunmaması için
      // offscreen layer'a render et.
      val sc = canvas.saveLayer(null, null)
      for (s in strokes) drawStrokeToCanvas(s, canvas)
      canvas.restoreToCount(sc)
    } else {
      committedBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }

    // Canlı çizim (parmak veya stylus basılı)
    if (liveActive) {
      if (liveTool == CanvasTool.ERASER) {
        strokePaint.color = Color.GRAY
        strokePaint.strokeWidth = liveWidth
        strokePaint.alpha = 80
        canvas.drawPath(livePath, strokePaint)
        strokePaint.alpha = 255
      } else if (liveWidthSamples.size >= 2) {
        // Stylus pressure-aware preview — anlık variable width
        strokePaint.color = liveColor
        strokePaint.alpha = (liveOpacity * 255).toInt().coerceIn(0, 255)
        for (i in 1 until liveWidthSamples.size) {
          val a = liveWidthSamples[i - 1]
          val b = liveWidthSamples[i]
          strokePaint.strokeWidth = (a.width + b.width) / 2f
          canvas.drawLine(a.x, a.y, b.x, b.y, strokePaint)
        }
      } else {
        strokePaint.color = liveColor
        strokePaint.strokeWidth = liveWidth
        strokePaint.alpha = (liveOpacity * 255).toInt().coerceIn(0, 255)
        canvas.drawPath(livePath, strokePaint)
      }
    }

    // IMAGE tool UI — image üzerinde transform tutaçları (rotated bounding box)
    if (activeTool == CanvasTool.IMAGE) {
      drawImageBoundsUI(canvas)
    }

    // SELECT tool UI
    if (activeTool == CanvasTool.SELECT) {
      // Seçim dikdörtgeni çizilirken
      if (selectMode == SelectMode.DRAWING_RECT) {
        selectionRect?.let { drawSelectionRectUI(canvas, it) }
      }
      // Lasso çizimi
      if (selectMode == SelectMode.DRAWING_LASSO && lassoPoints.size >= 4) {
        drawLassoUI(canvas, lassoPoints)
      }
      // Bounding box + handles
      if (selectedIndices.isNotEmpty()) {
        val effectiveBounds: RectF? = when (selectMode) {
          SelectMode.MOVING ->
            selectionBounds?.let { RectF(it).apply { offset(moveDeltaX, moveDeltaY) } }
          SelectMode.RESIZING ->
            selectionBounds?.let { b ->
              val m = Matrix()
              m.setScale(resizeLiveScale, resizeLiveScale, resizeAnchorX, resizeAnchorY)
              RectF(b).also { m.mapRect(it) }
            }
          SelectMode.ROTATING,
          SelectMode.ROTATING_HANDLE -> selectionBounds  // bounds dönmez, sadece preview rotate olur
          else -> selectionBounds
        }
        effectiveBounds?.let { drawSelectionBoundsUI(canvas, it) }
      }
    }

    canvas.restore() // clipRect
    canvas.restore() // world transform

    // Canvas-dışı alanı vurgulamak için ekstra overlay (clip dışında)
    drawOutsideOverlay(canvas)
  }

  private fun drawOutsideOverlay(canvas: Canvas) {
    // Canvas rect'in 4 köşesinin screen-space karşılığı (rotation + scale + pan dahil)
    val tl = worldToScreen(canvasRect.left, canvasRect.top)
    val tr = worldToScreen(canvasRect.right, canvasRect.top)
    val br = worldToScreen(canvasRect.right, canvasRect.bottom)
    val bl = worldToScreen(canvasRect.left, canvasRect.bottom)
    // EvenOdd: outer rect (tüm view) − inner polygon (rotate edilmiş canvas) = sadece dış alan
    val path = Path()
    path.fillType = Path.FillType.EVEN_ODD
    path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
    path.moveTo(tl.first, tl.second)
    path.lineTo(tr.first, tr.second)
    path.lineTo(br.first, br.second)
    path.lineTo(bl.first, bl.second)
    path.close()
    canvas.drawPath(path, outsideOverlayPaint)
  }

  // Image item'i bağlayan rotated quad — bu quad'ı screen space'de çiz
  private fun drawImageBoundsUI(canvas: Canvas) {
    val bmp = imageBitmap ?: return
    val (baseW, baseH) = computeImageBaseFit(bmp)
    if (baseW <= 0f || baseH <= 0f) return
    val halfW = baseW / 2f * imageScale
    val halfH = baseH / 2f * imageScale
    // World-coords içinde dört köşe (image local → image world)
    val cosR = cos(imageRotation); val sinR = sin(imageRotation)
    fun localToWorld(lx: Float, ly: Float): Pair<Float, Float> =
      Pair(imageCenterX + lx * cosR - ly * sinR, imageCenterY + lx * sinR + ly * cosR)
    val (x0, y0) = localToWorld(-halfW, -halfH)
    val (x1, y1) = localToWorld(halfW, -halfH)
    val (x2, y2) = localToWorld(halfW, halfH)
    val (x3, y3) = localToWorld(-halfW, halfH)
    val sw = 2f / worldScale
    selBoundsPaint.strokeWidth = sw
    selBoundsPaint.pathEffect = DashPathEffect(
      floatArrayOf(12f / worldScale, 6f / worldScale), 0f)
    val p = Path()
    p.moveTo(x0, y0); p.lineTo(x1, y1); p.lineTo(x2, y2); p.lineTo(x3, y3); p.close()
    canvas.drawPath(p, selFillPaint)
    canvas.drawPath(p, selBoundsPaint)
    selBoundsPaint.pathEffect = null
  }

  private fun drawLassoUI(canvas: Canvas, pts: ArrayList<Float>) {
    val sw = 2f / worldScale
    selRectPaint.strokeWidth = sw
    selRectPaint.pathEffect = DashPathEffect(
      floatArrayOf(12f / worldScale, 6f / worldScale), 0f)
    val p = Path()
    p.moveTo(pts[0], pts[1])
    var i = 2
    while (i < pts.size - 1) { p.lineTo(pts[i], pts[i + 1]); i += 2 }
    p.close()
    canvas.drawPath(p, selFillPaint)
    canvas.drawPath(p, selRectPaint)
  }

  // ─── Overlay ──────────────────────────────────────────────────────────
  private fun drawCanvasOverlay(canvas: Canvas) {
    if (canvasOverlay == "none") return
    val lum = colorLuminance(canvasBgColor)
    val alpha = if (lum > 0.5f) 35 else 22
    overlayPaint.color = if (lum > 0.5f) Color.argb(alpha, 0, 0, 0)
    else Color.argb(alpha, 255, 255, 255)
    overlayPaint.strokeWidth = 1f / worldScale

    // canvasRect tüm range'i — clip zaten kanvas dışını keser; rotation altında robust
    val wx0 = canvasRect.left; val wy0 = canvasRect.top
    val wx1 = canvasRect.right; val wy1 = canvasRect.bottom

    when (canvasOverlay) {
      "grid" -> {
        val sp = 40f * density
        val sx = floor((wx0 / sp).toDouble()).toFloat() * sp
        val sy = floor((wy0 / sp).toDouble()).toFloat() * sp
        var x = sx; while (x <= wx1 + sp) { canvas.drawLine(x, wy0, x, wy1, overlayPaint); x += sp }
        var y = sy; while (y <= wy1 + sp) { canvas.drawLine(wx0, y, wx1, y, overlayPaint); y += sp }
      }
      "lines" -> {
        val sp = 32f * density
        val sy = floor((wy0 / sp).toDouble()).toFloat() * sp
        var y = sy; while (y <= wy1 + sp) { canvas.drawLine(wx0, y, wx1, y, overlayPaint); y += sp }
      }
    }
  }

  private fun colorLuminance(color: Int): Float {
    val r = Color.red(color) / 255f
    val g = Color.green(color) / 255f
    val b = Color.blue(color) / 255f
    return 0.299f * r + 0.587f * g + 0.114f * b
  }

  // ─── Selection UI drawing ──────────────────────────────────────────────
  private fun drawSelectionRectUI(canvas: Canvas, rect: RectF) {
    val sw = 2f / worldScale
    selRectPaint.strokeWidth = sw
    selRectPaint.pathEffect = DashPathEffect(
      floatArrayOf(12f / worldScale, 6f / worldScale), 0f)
    canvas.drawRect(normalizedRect(rect), selRectPaint)
  }

  private fun drawSelectionBoundsUI(canvas: Canvas, bounds: RectF) {
    val sw = 2f / worldScale
    selBoundsPaint.strokeWidth = sw
    selHandleBorderPaint.strokeWidth = sw
    // Yarı saydam dolgu
    canvas.drawRect(bounds, selFillPaint)
    // Kenar çizgisi
    canvas.drawRect(bounds, selBoundsPaint)
    // Köşe tutaçları
    val hr = 8f * density / worldScale
    val corners = listOf(
      bounds.left to bounds.top,
      bounds.right to bounds.top,
      bounds.left to bounds.bottom,
      bounds.right to bounds.bottom
    )
    for ((x, y) in corners) {
      canvas.drawCircle(x, y, hr, selHandleFillPaint)
      canvas.drawCircle(x, y, hr, selHandleBorderPaint)
    }
    // Rotate handle: üst kenarın ortasından yukarı bir çizgi + circle
    val topCenterX = (bounds.left + bounds.right) / 2f
    val topCenterY = bounds.top
    val handleOffset = ROTATE_HANDLE_OFFSET_DP * density / worldScale
    var handleX = topCenterX
    var handleY = topCenterY - handleOffset
    // Tek-parmak rotate sırasında handle'ı parmakla birlikte döndür
    if (selectMode == SelectMode.ROTATING_HANDLE && rotateLiveAngle != 0f) {
      val cx = rotateCenterX; val cy = rotateCenterY
      val cos = kotlin.math.cos(rotateLiveAngle); val sin = kotlin.math.sin(rotateLiveAngle)
      val dx0 = handleX - cx; val dy0 = handleY - cy
      handleX = cx + (dx0 * cos - dy0 * sin)
      handleY = cy + (dx0 * sin + dy0 * cos)
    }
    // Bağlantı çizgisi (top-center → handle)
    val connectorStartX: Float
    val connectorStartY: Float
    if (selectMode == SelectMode.ROTATING_HANDLE && rotateLiveAngle != 0f) {
      val cx = rotateCenterX; val cy = rotateCenterY
      val cos = kotlin.math.cos(rotateLiveAngle); val sin = kotlin.math.sin(rotateLiveAngle)
      val dx0 = topCenterX - cx; val dy0 = topCenterY - cy
      connectorStartX = cx + (dx0 * cos - dy0 * sin)
      connectorStartY = cy + (dx0 * sin + dy0 * cos)
    } else {
      connectorStartX = topCenterX
      connectorStartY = topCenterY
    }
    canvas.drawLine(connectorStartX, connectorStartY, handleX, handleY, selBoundsPaint)
    // Rotate handle dolgu + kenar
    val rhr = 10f * density / worldScale
    canvas.drawCircle(handleX, handleY, rhr, selHandleFillPaint)
    canvas.drawCircle(handleX, handleY, rhr, selHandleBorderPaint)
  }

  companion object {
    private const val ROTATE_HANDLE_OFFSET_DP = 28f
  }

  // ─── Committed bitmap ─────────────────────────────────────────────────
  private fun redrawCommittedBitmap() {
    val canvas = committedCanvas ?: return
    canvas.drawColor(0, PorterDuff.Mode.CLEAR)
    for (s in strokes) drawStrokeToCanvas(s, canvas)
  }

  private fun drawStrokeToCanvas(s: Stroke, canvas: Canvas) {
    if (s.tool == CanvasTool.ERASER) {
      eraserPaint.strokeWidth = s.width
      canvas.drawPath(s.path, eraserPaint)
    } else if (s.isText) {
      textFillPaint.color = s.color
      textFillPaint.alpha = (s.opacity * 255).toInt().coerceIn(0, 255)
      canvas.drawPath(s.path, textFillPaint)
    } else {
      val samples = s.variableWidthSamples
      if (samples != null && samples.size >= 2) {
        // Pressure-aware render: ardışık örnekleri ayrı segment'lere böl,
        // her segmentin width'i iki uç sample'ın ortalaması.
        strokePaint.color = s.color
        strokePaint.alpha = (s.opacity * 255).toInt().coerceIn(0, 255)
        for (i in 1 until samples.size) {
          val a = samples[i - 1]
          val b = samples[i]
          strokePaint.strokeWidth = (a.width + b.width) / 2f
          canvas.drawLine(a.x, a.y, b.x, b.y, strokePaint)
        }
      } else {
        strokePaint.color = s.color
        strokePaint.strokeWidth = s.width
        strokePaint.alpha = (s.opacity * 255).toInt().coerceIn(0, 255)
        canvas.drawPath(s.path, strokePaint)
      }
    }
  }

  // ─── History ───────────────────────────────────────────────────────────
  // Her mutasyondan ÖNCE çağrılır: mevcut strokes'u snapshot'layıp undo'ya iter,
  // redo'yu temizler. Stroke.path hiçbir yerde in-place mutate edilmez (transform
  // daima yeni Path üretir) → shallow snapshot güvenli.
  private fun pushHistory() {
    undoStack.add(ArrayList(strokes))
    if (undoStack.size > maxHistory) undoStack.removeAt(0)
    redoStack.clear()
  }

  // ─── Commands ──────────────────────────────────────────────────────────
  fun undo() {
    if (undoStack.isEmpty()) return
    clearSelection()
    redoStack.add(ArrayList(strokes))
    val prev = undoStack.removeAt(undoStack.size - 1)
    strokes.clear(); strokes.addAll(prev)
    redrawCommittedBitmap()
    invalidate()
    emitCount()
  }

  fun redo() {
    if (redoStack.isEmpty()) return
    clearSelection()
    undoStack.add(ArrayList(strokes))
    val next = redoStack.removeAt(redoStack.size - 1)
    strokes.clear(); strokes.addAll(next)
    redrawCommittedBitmap()
    invalidate()
    emitCount()
  }

  fun clear() {
    if (strokes.isEmpty()) return
    clearSelection()
    pushHistory()
    strokes.clear()
    redrawCommittedBitmap()
    invalidate()
    emitCount()
  }

  fun replaceLastStrokeWithShape(shapeMap: Map<String, Any>) {
    if (strokes.isEmpty()) return
    val last = strokes[strokes.size - 1]
    if (last.tool != CanvasTool.PEN || last.isText) return
    val newPath = buildPathFromShape(shapeMap) ?: return
    val lastIdx = strokes.size - 1
    // Şekle dönüşen stroke artık serbest çizim değil → points temizlenir
    // (silgi all-or-nothing davranır, freehand gibi parçalanmaz).
    strokes[lastIdx] = last.copy(path = newPath, points = emptyList())
    redrawCommittedBitmap()
    // Auto-select recognized shape — üst handle ile rotate, corner ile resize
    // mümkün hale gelir. JS taraf bunu görüp tool'u 'select'e çevirir.
    selectedIndices.clear()
    selectedIndices.add(lastIdx)
    val pb = RectF()
    newPath.computeBounds(pb, true)
    selectionBounds = pb
    selectionRect = null
    lassoPoints.clear()
    moveDeltaX = 0f; moveDeltaY = 0f
    resizeLiveScale = 1f; resizeCorner = -1
    rotateLiveAngle = 0f
    selectionTransforming = false
    selectMode = SelectMode.IDLE
    buildSelectionBitmaps()
    invalidate()
    emitCount()
  }

  /**
   * Text item ekle — glyph outline'ları Path olarak doldurulur, normal Stroke
   * pipeline'ına girer. Eraser CLEAR, selection, rotate/scale otomatik çalışır.
   */
  fun insertText(text: String, fontSize: Float, color: Int) {
    val clean = text.trim()
    if (clean.isEmpty()) return
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.FILL
      typeface = cursiveTypeface
      textSize = fontSize.coerceAtLeast(8f)
    }
    val rawPath = Path()
    textPaint.getTextPath(clean, 0, clean.length, 0f, 0f, rawPath)
    val bounds = RectF()
    rawPath.computeBounds(bounds, true)
    if (bounds.isEmpty) return
    // Canvas merkezine taşı (text path baseline = 0,0; bounds göreceli)
    val targetX = canvasRect.centerX() - bounds.centerX()
    val targetY = canvasRect.centerY() - bounds.centerY()
    val placedPath = Path()
    val m = Matrix().apply { setTranslate(targetX, targetY) }
    rawPath.transform(m, placedPath)

    val stroke = Stroke(placedPath, CanvasTool.PEN, color, fontSize, 1f, isText = true)
    pushHistory()
    strokes.add(stroke)
    drawStrokeToCanvas(stroke, committedCanvas ?: return)

    // Auto-select — JS taraf insertText sonrası 'select' tool'una geçer
    selectedIndices.clear()
    selectedIndices.add(strokes.size - 1)
    val pb = RectF()
    placedPath.computeBounds(pb, true)
    selectionBounds = pb
    selectionRect = null
    lassoPoints.clear()
    moveDeltaX = 0f; moveDeltaY = 0f
    resizeLiveScale = 1f; resizeCorner = -1
    rotateLiveAngle = 0f
    selectionTransforming = false
    selectMode = SelectMode.IDLE
    buildSelectionBitmaps()
    invalidate()
    emitCount()
  }

  // ─── SELECT tool işlemleri ─────────────────────────────────────────────
  fun clearSelection() {
    selectedIndices.clear()
    selectionBounds = null
    selectionRect = null
    lassoPoints.clear()
    selectionBaseBitmap?.recycle(); selectionBaseBitmap = null
    selectionStrokesBitmap?.recycle(); selectionStrokesBitmap = null
    moveDeltaX = 0f; moveDeltaY = 0f
    resizeLiveScale = 1f; resizeCorner = -1
    rotateLiveAngle = 0f
    selectionTransforming = false
    selectMode = SelectMode.IDLE
    invalidate()
  }

  fun cancelLiveStrokePublic() { cancelLiveStroke() }

  fun currentRotation(): Float = worldRotation

  private fun handleSelectTouchDown(wx: Float, wy: Float) {
    val bounds = selectionBounds
    if (bounds != null && selectedIndices.isNotEmpty()) {
      // Rotate handle önce kontrol et — köşelerden uzakta, üstte
      if (hitTestRotateHandle(wx, wy, bounds)) {
        rotateCenterX = (bounds.left + bounds.right) / 2f
        rotateCenterY = (bounds.top + bounds.bottom) / 2f
        rotateStartAngle = atan2(wy - rotateCenterY, wx - rotateCenterX)
        rotateLiveAngle = 0f
        selectMode = SelectMode.ROTATING_HANDLE
        invalidate()
        return
      }
      val corner = hitTestCorner(wx, wy, bounds)
      if (corner >= 0) {
        resizeCorner = corner
        resizeAnchorX = if (corner == 0 || corner == 2) bounds.right else bounds.left
        resizeAnchorY = if (corner == 0 || corner == 1) bounds.bottom else bounds.top
        val origCornerX = if (corner == 0 || corner == 2) bounds.left else bounds.right
        val origCornerY = if (corner == 0 || corner == 1) bounds.top else bounds.bottom
        resizeStartDist = hypot(origCornerX - resizeAnchorX, origCornerY - resizeAnchorY)
          .coerceAtLeast(1f)
        resizeLiveScale = 1f
        selectMode = SelectMode.RESIZING
        return
      }
      if (bounds.contains(wx, wy)) {
        moveStartWorldX = wx; moveStartWorldY = wy
        moveDeltaX = 0f; moveDeltaY = 0f
        selectMode = SelectMode.MOVING
        return
      }
      // Boş alana tap → seçimi temizle ve dur. Yeni rect/lasso başlatma —
      // kullanıcı ikinci bir tap/drag ile yeni seçim başlatabilir.
      clearSelection()
      return
    }
    if (selectionPickMode == SelectionPickMode.LASSO) {
      lassoPoints.clear()
      lassoPoints.add(wx); lassoPoints.add(wy)
      selectMode = SelectMode.DRAWING_LASSO
    } else {
      selectionRect = RectF(wx, wy, wx, wy)
      selectMode = SelectMode.DRAWING_RECT
    }
    invalidate()
  }

  private fun handleSelectTouchMove(wx: Float, wy: Float) {
    when (selectMode) {
      SelectMode.DRAWING_RECT -> {
        selectionRect?.apply { right = wx; bottom = wy }
        invalidate()
      }
      SelectMode.DRAWING_LASSO -> {
        val lastIdx = lassoPoints.size - 2
        if (lastIdx >= 0) {
          val dx = wx - lassoPoints[lastIdx]
          val dy = wy - lassoPoints[lastIdx + 1]
          if (dx * dx + dy * dy >= 4f) {
            lassoPoints.add(wx); lassoPoints.add(wy)
            invalidate()
          }
        }
      }
      SelectMode.MOVING -> {
        moveDeltaX = wx - moveStartWorldX
        moveDeltaY = wy - moveStartWorldY
        invalidate()
      }
      SelectMode.RESIZING -> {
        val dist = hypot(wx - resizeAnchorX, wy - resizeAnchorY)
        resizeLiveScale = (dist / resizeStartDist).coerceIn(0.1f, 10f)
        invalidate()
      }
      SelectMode.ROTATING_HANDLE -> {
        val current = atan2(wy - rotateCenterY, wx - rotateCenterX)
        // atan2 ±π wrap'ini normalize et
        var handleDelta = current - rotateStartAngle
        val twoPiH = (2.0 * Math.PI).toFloat()
        while (handleDelta > Math.PI.toFloat()) handleDelta -= twoPiH
        while (handleDelta < -Math.PI.toFloat()) handleDelta += twoPiH
        rotateLiveAngle = handleDelta
        invalidate()
      }
      else -> {}
    }
  }

  private fun handleSelectTouchUp() {
    when (selectMode) {
      SelectMode.DRAWING_RECT -> finalizeSelection()
      SelectMode.DRAWING_LASSO -> finalizeLassoSelection()
      SelectMode.MOVING -> {
        if (moveDeltaX != 0f || moveDeltaY != 0f) applyMoveTransform(moveDeltaX, moveDeltaY)
        selectMode = SelectMode.IDLE
      }
      SelectMode.RESIZING -> {
        if (resizeLiveScale != 1f) applyResizeTransform(resizeLiveScale)
        selectMode = SelectMode.IDLE
      }
      SelectMode.ROTATING_HANDLE -> {
        if (rotateLiveAngle != 0f) applyRotationTransform(rotateLiveAngle)
        rotateLiveAngle = 0f
        selectMode = SelectMode.IDLE
      }
      else -> {}
    }
    invalidate()
  }

  private fun finalizeSelection() {
    val r = selectionRect ?: run { selectMode = SelectMode.IDLE; return }
    val norm = normalizedRect(r)
    selectionRect = null
    if (norm.width() < 5f || norm.height() < 5f) { selectMode = SelectMode.IDLE; return }

    selectedIndices.clear()
    val pathBounds = RectF()
    // Önce pen / text stroke'larını topla.
    for ((i, stroke) in strokes.withIndex()) {
      if (stroke.tool == CanvasTool.ERASER) continue
      stroke.path.computeBounds(pathBounds, true)
      if (RectF.intersects(norm, pathBounds)) selectedIndices.add(i)
    }

    if (selectedIndices.isEmpty()) { selectMode = SelectMode.IDLE; return }

    // Eraser stroke'ları: bounds'u tamamen selection rect içinde kalanlar
    // seçime dahil edilir. "Çiz → bir kısmını sil → seç ve taşı" akışında
    // eraser cut'ları pen stroke'uyla birlikte taşınır; aksi halde eraser
    // yerinde kalır ve taşınan pen "geri görünüyormuş" gibi olur.
    for ((i, stroke) in strokes.withIndex()) {
      if (stroke.tool != CanvasTool.ERASER) continue
      stroke.path.computeBounds(pathBounds, true)
      if (norm.contains(pathBounds)) selectedIndices.add(i)
    }

    var combined: RectF? = null
    for (i in selectedIndices) {
      strokes[i].path.computeBounds(pathBounds, true)
      if (combined == null) combined = RectF(pathBounds) else combined.union(pathBounds)
    }
    selectionBounds = combined
    buildSelectionBitmaps()
    selectMode = SelectMode.IDLE
  }

  private fun finalizeLassoSelection() {
    if (lassoPoints.size < 6) { lassoPoints.clear(); selectMode = SelectMode.IDLE; return }
    // Path olarak inşa et + kullan
    val lassoPath = Path()
    lassoPath.moveTo(lassoPoints[0], lassoPoints[1])
    var i = 2
    while (i < lassoPoints.size - 1) { lassoPath.lineTo(lassoPoints[i], lassoPoints[i + 1]); i += 2 }
    lassoPath.close()

    selectedIndices.clear()
    val pathBounds = RectF()
    val lassoBounds = RectF()
    lassoPath.computeBounds(lassoBounds, true)
    for ((idx, stroke) in strokes.withIndex()) {
      if (stroke.tool == CanvasTool.ERASER) continue
      stroke.path.computeBounds(pathBounds, true)
      if (!RectF.intersects(lassoBounds, pathBounds)) continue
      // Centroid-in-polygon yeterli — düşük cost
      val cx = (pathBounds.left + pathBounds.right) / 2f
      val cy = (pathBounds.top + pathBounds.bottom) / 2f
      if (pointInPolygon(cx, cy, lassoPoints)) selectedIndices.add(idx)
    }
    // Eraser stroke'ları: bounds'u tamamen lasso bbox'ı içinde kalanları
    // seçime ekle — rect modu ile aynı mantık (eraser cut'ları pen ile
    // birlikte taşınsın).
    if (selectedIndices.isNotEmpty()) {
      for ((idx, stroke) in strokes.withIndex()) {
        if (stroke.tool != CanvasTool.ERASER) continue
        stroke.path.computeBounds(pathBounds, true)
        if (lassoBounds.contains(pathBounds)) selectedIndices.add(idx)
      }
    }
    lassoPoints.clear()
    if (selectedIndices.isEmpty()) { selectMode = SelectMode.IDLE; return }
    var combined: RectF? = null
    for (idx in selectedIndices) {
      strokes[idx].path.computeBounds(pathBounds, true)
      if (combined == null) combined = RectF(pathBounds) else combined.union(pathBounds)
    }
    selectionBounds = combined
    buildSelectionBitmaps()
    selectMode = SelectMode.IDLE
  }

  private fun pointInPolygon(px: Float, py: Float, poly: ArrayList<Float>): Boolean {
    var inside = false; val n = poly.size / 2
    var j = n - 1
    for (i in 0 until n) {
      val xi = poly[i * 2]; val yi = poly[i * 2 + 1]
      val xj = poly[j * 2]; val yj = poly[j * 2 + 1]
      val intersect = (yi > py) != (yj > py) &&
        (px < (xj - xi) * (py - yi) / ((yj - yi).takeIf { it != 0f } ?: 0.0001f) + xi)
      if (intersect) inside = !inside
      j = i
    }
    return inside
  }

  private fun applyRotationTransform(angleRad: Float) {
    val matrix = Matrix().apply { setRotate(angleRad * 180f / Math.PI.toFloat(), rotateCenterX, rotateCenterY) }
    pushHistory()
    for (i in selectedIndices) {
      val stroke = strokes[i]
      val newPath = Path(); stroke.path.transform(matrix, newPath)
      strokes[i] = stroke.copy(
        path = newPath,
        variableWidthSamples = transformSamples(stroke.variableWidthSamples, matrix),
        points = transformPoints(stroke.points, matrix),
      )
    }
    // Rotation sonrası bounds yeniden bbox olarak hesaplanır
    val tmpBounds = RectF(); var combined: RectF? = null
    for (i in selectedIndices) {
      strokes[i].path.computeBounds(tmpBounds, true)
      if (combined == null) combined = RectF(tmpBounds) else combined.union(tmpBounds)
    }
    if (combined != null) selectionBounds = combined
    rotateLiveAngle = 0f
    redrawCommittedBitmap()
    buildSelectionBitmaps()
    emitCount()
  }

  private fun buildSelectionBitmaps() {
    val w = width; val h = height
    if (w <= 0 || h <= 0) return

    selectionBaseBitmap?.recycle()
    selectionStrokesBitmap?.recycle()

    val baseBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val selBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val baseC = Canvas(baseBmp)
    val selC = Canvas(selBmp)

    for ((i, stroke) in strokes.withIndex()) {
      // Seçili olan tüm stroke'lar (pen + eraser) base'den çıkarılır. Eraser
      // bounds'u selection rect içinde kalan stroke'lar finalize sırasında
      // selectedIndices'e eklendiği için base'de kalmaz; taşıma sırasında pen
      // ile birlikte hareket eder (eraser bounds'u rect dışındaki strokes
      // hâlâ base'de kalır ve "ghost" olmaz).
      val toBase = i !in selectedIndices
      drawStrokeToCanvas(stroke, if (toBase) baseC else selC)
    }

    selectionBaseBitmap = baseBmp
    selectionStrokesBitmap = selBmp
  }

  // Serbest çizim noktalarına ([x0,y0,...]) Matrix uygular — silginin taşınan
  // stroke üzerinde doğru çalışması için points de path ile birlikte taşınır.
  private fun transformPoints(points: List<Float>, matrix: Matrix): List<Float> {
    if (points.isEmpty()) return points
    val arr = points.toFloatArray()
    matrix.mapPoints(arr)
    return arr.toList()
  }

  // variableWidthSamples'a Matrix uygulayan helper. scaleFactor null ise width
  // değişmez (translate/rotate); resize'da scale ile çarp.
  private fun transformSamples(
    samples: List<WidthSample>?, matrix: Matrix, scaleFactor: Float? = null,
  ): List<WidthSample>? {
    if (samples == null) return null
    val pt = FloatArray(2)
    return samples.map { s ->
      pt[0] = s.x; pt[1] = s.y
      matrix.mapPoints(pt)
      WidthSample(pt[0], pt[1], if (scaleFactor != null) s.width * scaleFactor else s.width)
    }
  }

  private fun applyMoveTransform(dx: Float, dy: Float) {
    val matrix = Matrix().apply { setTranslate(dx, dy) }
    pushHistory()
    for (i in selectedIndices) {
      val stroke = strokes[i]
      val newPath = Path(); stroke.path.transform(matrix, newPath)
      strokes[i] = stroke.copy(
        path = newPath,
        variableWidthSamples = transformSamples(stroke.variableWidthSamples, matrix),
        points = transformPoints(stroke.points, matrix),
      )
    }
    selectionBounds?.offset(dx, dy)
    moveDeltaX = 0f; moveDeltaY = 0f
    redrawCommittedBitmap()
    buildSelectionBitmaps()
    emitCount()
  }

  private fun applyResizeTransform(scale: Float) {
    val matrix = Matrix().apply {
      setScale(scale, scale, resizeAnchorX, resizeAnchorY)
    }
    pushHistory()
    for (i in selectedIndices) {
      val stroke = strokes[i]
      val newPath = Path(); stroke.path.transform(matrix, newPath)
      strokes[i] = stroke.copy(
        path = newPath,
        width = stroke.width * scale,
        variableWidthSamples = transformSamples(stroke.variableWidthSamples, matrix, scale),
        points = transformPoints(stroke.points, matrix),
      )
    }
    selectionBounds?.let { matrix.mapRect(it) }
    resizeLiveScale = 1f
    redrawCommittedBitmap()
    buildSelectionBitmaps()
    emitCount()
  }

  fun setSelectedStrokeColor(color: Int) {
    if (selectedIndices.isEmpty()) return
    pushHistory()
    for (i in selectedIndices) {
      if (i >= strokes.size) continue
      strokes[i] = strokes[i].copy(color = color)
    }
    redrawCommittedBitmap()
    buildSelectionBitmaps()
    invalidate()
  }

  // ─── Seçili bölgeyi sil ────────────────────────────────────────────────
  fun deleteSelection() {
    if (selectedIndices.isEmpty()) return
    pushHistory()
    val kept = strokes.filterIndexed { idx, _ -> idx !in selectedIndices }
    strokes.clear(); strokes.addAll(kept)
    clearSelection()
    redrawCommittedBitmap()
    invalidate()
    emitCount()
  }

  private fun hitTestCorner(wx: Float, wy: Float, bounds: RectF): Int {
    val touchR = 24f * density / worldScale
    val corners = listOf(
      bounds.left to bounds.top,
      bounds.right to bounds.top,
      bounds.left to bounds.bottom,
      bounds.right to bounds.bottom
    )
    for ((i, c) in corners.withIndex()) {
      val dx = wx - c.first; val dy = wy - c.second
      if (dx * dx + dy * dy <= touchR * touchR) return i
    }
    return -1
  }

  private fun hitTestRotateHandle(wx: Float, wy: Float, bounds: RectF): Boolean {
    val cx = (bounds.left + bounds.right) / 2f
    val cy = bounds.top - ROTATE_HANDLE_OFFSET_DP * density / worldScale
    val touchR = 28f * density / worldScale
    val dx = wx - cx; val dy = wy - cy
    return dx * dx + dy * dy <= touchR * touchR
  }

  private fun normalizedRect(r: RectF) = RectF(
    minOf(r.left, r.right), minOf(r.top, r.bottom),
    maxOf(r.left, r.right), maxOf(r.top, r.bottom)
  )

  // ─── Shape recognition ────────────────────────────────────────────────
  private fun buildPathFromShape(shape: Map<String, Any>): Path? {
    val type = shape["type"] as? String ?: return null
    val p = Path()
    when (type) {
      "line" -> {
        val x1 = num(shape["x1"]) ?: return null; val y1 = num(shape["y1"]) ?: return null
        val x2 = num(shape["x2"]) ?: return null; val y2 = num(shape["y2"]) ?: return null
        p.moveTo(x1, y1); p.lineTo(x2, y2)
      }
      "rect" -> {
        val minX = num(shape["minX"]) ?: return null; val minY = num(shape["minY"]) ?: return null
        val maxX = num(shape["maxX"]) ?: return null; val maxY = num(shape["maxY"]) ?: return null
        p.moveTo(minX, minY); p.lineTo(maxX, minY); p.lineTo(maxX, maxY)
        p.lineTo(minX, maxY); p.close()
      }
      "triangle" -> {
        @Suppress("UNCHECKED_CAST")
        val vertices = shape["vertices"] as? List<Map<String, Any>> ?: return null
        if (vertices.size < 3) return null
        for ((i, v) in vertices.withIndex()) {
          val vx = num(v["x"]) ?: return null; val vy = num(v["y"]) ?: return null
          if (i == 0) p.moveTo(vx, vy) else p.lineTo(vx, vy)
        }
        p.close()
      }
      "quad" -> {
        @Suppress("UNCHECKED_CAST")
        val vertices = shape["vertices"] as? List<Map<String, Any>> ?: return null
        if (vertices.size < 4) return null
        for ((i, v) in vertices.withIndex()) {
          val vx = num(v["x"]) ?: return null; val vy = num(v["y"]) ?: return null
          if (i == 0) p.moveTo(vx, vy) else p.lineTo(vx, vy)
        }
        p.close()
      }
      "circle" -> {
        val cx = num(shape["cx"]) ?: return null; val cy = num(shape["cy"]) ?: return null
        val r = num(shape["r"]) ?: return null
        for (i in 0..48) {
          val a = (i / 48.0 * Math.PI * 2).toFloat()
          val px = cx + r * Math.cos(a.toDouble()).toFloat()
          val py = cy + r * Math.sin(a.toDouble()).toFloat()
          if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
        }
        p.close()
      }
      "ellipse" -> {
        val cx = num(shape["cx"]) ?: return null; val cy = num(shape["cy"]) ?: return null
        val rx = num(shape["rx"]) ?: return null; val ry = num(shape["ry"]) ?: return null
        for (i in 0..48) {
          val a = (i / 48.0 * Math.PI * 2).toFloat()
          val px = cx + rx * Math.cos(a.toDouble()).toFloat()
          val py = cy + ry * Math.sin(a.toDouble()).toFloat()
          if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
        }
        p.close()
      }
      else -> return null
    }
    return p
  }

  private fun num(v: Any?): Float? = when (v) { is Number -> v.toFloat(); else -> null }

  private fun emitCount(
    pointsForRecognition: List<Float> = emptyList(),
    durationMs: Long = 0L,
    idleMs: Long = 0L,
  ) {
    // 1. param JS'te canUndo türetir. Snapshot modelde undo edilebilirlik
    // strokes.size değil undoStack doluluğuyla belirlenir (örn. "hepsini sil"
    // sonrası strokes=0 ama undo mümkün). Bu yüzden undoStack.size gönderiyoruz.
    onCountChanged?.invoke(undoStack.size, redoStack.size, pointsForRecognition, durationMs, idleMs)
  }

  // ─── Touch / gesture handling ─────────────────────────────────────────
  // S-Pen / stylus + parmak ayrımı (iOS Pencil ile parite):
  //  - Stylus pointer her zaman çizer (pen/eraser tool için).
  //  - Stylus aktifken (stylusDrawPointerId != INVALID), yeni parmak pointer'ları
  //    palm/yanlışlıkla dokunma sayılır ve YOK SAYILIR.
  //  - Parmak çizimi yine geçerli (stylus yokken) → geriye dönük uyumlu.
  private fun isStylus(event: MotionEvent, pointerIndex: Int): Boolean {
    val t = event.getToolType(pointerIndex)
    return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER
  }

  private fun stylusPressure(event: MotionEvent, pointerIndex: Int): Float? {
    if (!isStylus(event, pointerIndex)) return null
    val p = event.getPressure(pointerIndex)
    return if (p > 0f) p.coerceIn(0f, 1f) else null
  }

  override fun onTouchEvent(event: MotionEvent): Boolean {
    val action = event.actionMasked
    when (action) {
      MotionEvent.ACTION_DOWN -> {
        val isPenOrEraser = activeTool == CanvasTool.PEN || activeTool == CanvasTool.ERASER
        if (isStylus(event, 0) && isPenOrEraser) {
          stylusDrawPointerId = event.getPointerId(0)
          stylusStrokeActive = true
          startStroke(event.x, event.y, stylusPressure(event, 0))
          return true
        }
        when (activeTool) {
          CanvasTool.SELECT -> {
            val (wx, wy) = screenToWorld(event.x, event.y)
            handleSelectTouchDown(wx, wy)
          }
          CanvasTool.IMAGE -> {
            if (imageBitmap != null) startImageDrag(event.x, event.y)
          }
          else -> {
            stylusStrokeActive = false
            startStroke(event.x, event.y, null)
          }
        }
        return true
      }
      MotionEvent.ACTION_POINTER_DOWN -> {
        // Stylus aktif çizimi varsa yeni parmak/pointer'ı yok say (palm rejection)
        if (stylusDrawPointerId != MotionEvent.INVALID_POINTER_ID) {
          // Yeni gelen pointer'ın kendisi de stylus mu? (nadir, multi-stylus) — yine yok say
          return true
        }
        // Yeni gelen pointer stylus + pen/eraser tool'unda → mevcut parmak stroke'unu
        // iptal et, stylus devralır
        val newIdx = event.actionIndex
        val isPenOrEraser = activeTool == CanvasTool.PEN || activeTool == CanvasTool.ERASER
        if (isStylus(event, newIdx) && isPenOrEraser) {
          if (liveActive && !stylusStrokeActive) cancelLiveStroke()
          stylusDrawPointerId = event.getPointerId(newIdx)
          stylusStrokeActive = true
          startStroke(event.getX(newIdx), event.getY(newIdx), stylusPressure(event, newIdx))
          return true
        }
        if (event.pointerCount == 2) {
          when (activeTool) {
            CanvasTool.SELECT -> {
              // Devam eden tek-parmak işlemi iptal et
              moveDeltaX = 0f; moveDeltaY = 0f; resizeLiveScale = 1f
              selectionRect = null; lassoPoints.clear()
              // Seçim varsa: 2-parmak = ROTATE + SCALE selection
              if (selectedIndices.isNotEmpty()) {
                startSelectionTwoFinger(event)
                selectMode = SelectMode.ROTATING
                selectionTransforming = true
                return true
              }
              selectMode = SelectMode.IDLE
              startPinch(event)
            }
            CanvasTool.IMAGE -> {
              if (imageBitmap != null) startImagePinch(event)
            }
            else -> {
              cancelLiveStroke()
              startPinch(event)
            }
          }
        }
        return true
      }
      MotionEvent.ACTION_MOVE -> {
        // Stylus aktif — sadece tracked pointer'ı işle, getHistoricalX/Y/Pressure
        // ile yüksek frekans örnekle (iOS coalescedTouches eşdeğeri)
        if (stylusDrawPointerId != MotionEvent.INVALID_POINTER_ID) {
          val idx = event.findPointerIndex(stylusDrawPointerId)
          if (idx >= 0 && liveActive) {
            val histSize = event.historySize
            for (h in 0 until histSize) {
              val hx = event.getHistoricalX(idx, h)
              val hy = event.getHistoricalY(idx, h)
              val hp = event.getHistoricalPressure(idx, h).takeIf { it > 0f }?.coerceIn(0f, 1f)
              continueStroke(hx, hy, hp)
            }
            continueStroke(event.getX(idx), event.getY(idx), stylusPressure(event, idx))
          }
          return true
        }
        if (event.pointerCount == 1) {
          when (activeTool) {
            CanvasTool.SELECT -> {
              val (wx, wy) = screenToWorld(event.x, event.y)
              handleSelectTouchMove(wx, wy)
            }
            CanvasTool.IMAGE -> {
              if (imageBitmap != null) updateImageDrag(event.x, event.y)
            }
            else -> if (liveActive) continueStroke(event.x, event.y, null)
          }
        } else if (event.pointerCount == 2) {
          when (activeTool) {
            CanvasTool.SELECT -> {
              if (selectionTransforming) updateSelectionTwoFinger(event)
              else updatePinch(event)
            }
            CanvasTool.IMAGE -> {
              if (imageBitmap != null) updateImagePinch(event)
            }
            else -> updatePinch(event)
          }
        }
        return true
      }
      MotionEvent.ACTION_POINTER_UP -> {
        // Stylus kalktıysa stroke'u commit et
        val upIdx = event.actionIndex
        if (stylusDrawPointerId != MotionEvent.INVALID_POINTER_ID &&
            event.getPointerId(upIdx) == stylusDrawPointerId) {
          if (liveActive) finishStroke()
          stylusDrawPointerId = MotionEvent.INVALID_POINTER_ID
          stylusStrokeActive = false
          return true
        }
        // Stylus aktif ama başka pointer kalkıyor — yok say
        if (stylusDrawPointerId != MotionEvent.INVALID_POINTER_ID) return true
        if (event.pointerCount == 2) {
          when (activeTool) {
            CanvasTool.SELECT ->
              if (selectionTransforming) endSelectionTwoFinger() else endPinch()
            CanvasTool.IMAGE -> endImagePinch()
            else -> endPinch()
          }
        }
        return true
      }
      MotionEvent.ACTION_UP -> {
        // Son pointer kalkıyor — stylus mu kontrol et
        if (stylusDrawPointerId != MotionEvent.INVALID_POINTER_ID) {
          if (liveActive) finishStroke()
          stylusDrawPointerId = MotionEvent.INVALID_POINTER_ID
          stylusStrokeActive = false
          return true
        }
        when (activeTool) {
          CanvasTool.SELECT -> {
            if (selectionTransforming) endSelectionTwoFinger() else handleSelectTouchUp()
          }
          CanvasTool.IMAGE -> { /* drag/pinch state stateless — no-op */ }
          else -> if (liveActive) finishStroke()
        }
        return true
      }
      MotionEvent.ACTION_CANCEL -> {
        if (stylusDrawPointerId != MotionEvent.INVALID_POINTER_ID) {
          cancelLiveStroke()  // pointer id ve samples temizler
          return true
        }
        when (activeTool) {
          CanvasTool.SELECT -> {
            selectMode = SelectMode.IDLE
            moveDeltaX = 0f; moveDeltaY = 0f; resizeLiveScale = 1f
            selectionRect = null; lassoPoints.clear()
            selectionTransforming = false; rotateLiveAngle = 0f
          }
          CanvasTool.IMAGE -> { /* no-op */ }
          else -> cancelLiveStroke()
        }
        return true
      }
    }
    return false
  }

  // ─── Stroke ───────────────────────────────────────────────────────────
  // Basınç (S-Pen / stylus): variableWidthSamples ile her örnek için bir width
  // kaydedilir. Parmak: pressure null → tek-width klasik stroke (geriye dönük uyumlu).
  // Pressure → width mapping: 0.4x..1.4x base width arasında lineer (iOS parite).
  private fun widthFromPressure(pressure: Float?): Float {
    if (pressure == null) return liveBaseWidth
    val factor = 0.4f + pressure.coerceIn(0f, 1f) * 1.0f
    return liveBaseWidth * factor
  }

  private fun startStroke(screenX: Float, screenY: Float, pressure: Float? = null) {
    val (wx, wy) = screenToWorld(screenX, screenY)
    livePath.reset(); livePath.moveTo(wx, wy)
    liveLastX = wx; liveLastY = wy; liveActive = true
    liveTool = activeTool; liveColor = strokeColor
    // Scale-aware: ekran-px sabit kalsın diye width'i worldScale ile böl
    liveBaseWidth = (strokeWidth / worldScale.coerceAtLeast(0.001f))
    val initialWidth = widthFromPressure(pressure)
    liveWidth = initialWidth  // legacy single-width fallback
    liveOpacity = strokeOpacity
    livePoints = ArrayList(); livePoints.add(wx); livePoints.add(wy)
    liveWidthSamples.clear()
    if (pressure != null) {
      liveWidthSamples.add(WidthSample(wx, wy, initialWidth))
    }
    liveStartMs = SystemClock.uptimeMillis()
    lastMoveMs = liveStartMs
    invalidate()
  }

  private fun continueStroke(screenX: Float, screenY: Float, pressure: Float? = null) {
    val (wx, wy) = screenToWorld(screenX, screenY)
    val dx = wx - liveLastX; val dy = wy - liveLastY
    if (dx * dx + dy * dy < 0.5f) return
    val midX = (liveLastX + wx) / 2f; val midY = (liveLastY + wy) / 2f
    livePath.quadTo(liveLastX, liveLastY, midX, midY)
    liveLastX = wx; liveLastY = wy
    livePoints.add(wx); livePoints.add(wy)
    if (pressure != null) {
      liveWidthSamples.add(WidthSample(wx, wy, widthFromPressure(pressure)))
    }
    lastMoveMs = SystemClock.uptimeMillis()
    invalidate()
  }

  private fun finishStroke() {
    val endMs = SystemClock.uptimeMillis()
    val durationMs = (endMs - liveStartMs).coerceAtLeast(0L)
    val idleMs = (endMs - lastMoveMs).coerceAtLeast(0L)
    val worldPoints = livePoints.toList()

    if (liveTool == CanvasTool.ERASER) {
      // Silgi artık kalıcı bir stroke olarak SAKLANMAZ — altındaki serbest çizim
      // noktalarını gerçekten keser. "Üstünü boyama" hilesi biter.
      liveActive = false; livePath.reset()
      liveWidthSamples.clear()
      applyEraser(worldPoints, (liveWidth / 2f).coerceAtLeast(1f))
      invalidate()
      emitCount()
      return
    }

    livePath.lineTo(liveLastX, liveLastY)
    val committedPath = Path(livePath)
    val samples = if (liveWidthSamples.isEmpty()) null else liveWidthSamples.toList()
    val stroke = Stroke(
      path = committedPath, tool = liveTool, color = liveColor,
      width = liveWidth, opacity = liveOpacity,
      variableWidthSamples = samples,
      points = worldPoints,
    )
    pushHistory()
    strokes.add(stroke)
    drawStrokeToCanvas(stroke, committedCanvas ?: return)
    liveActive = false; livePath.reset()
    liveWidthSamples.clear()
    invalidate()
    val pointsToEmit = if (liveTool == CanvasTool.PEN) worldPoints else emptyList()
    emitCount(pointsToEmit, durationMs, idleMs)
  }

  // ─── Gerçek vektörel silme ─────────────────────────────────────────────
  // Silgi polyline'ının yakınından geçtiği serbest-çizim noktalarını çıkarır ve
  // stroke'u kalan parçalara böler. Text/şekil (points boş) all-or-nothing silinir.
  private fun applyEraser(eraserPts: List<Float>, radius: Float) {
    if (eraserPts.size < 2) return
    val newStrokes = ArrayList<Stroke>()
    var changed = false
    for (s in strokes) {
      val hitR = radius + s.width / 2f
      if (s.points.size >= 2) {
        val subs = splitStrokeByErase(s, eraserPts, hitR)
        if (subs.size == 1 && subs[0].points.size == s.points.size) {
          newStrokes.add(s)                    // dokunulmadı
        } else {
          changed = true
          newStrokes.addAll(subs)              // parçalandı / tamamen silindi
        }
      } else {
        // Şekil / yazı: nokta yok → dokunulduysa tümü silinir.
        val flat = flattenPath(s.path)
        if (polylineHit(flat, eraserPts, hitR)) {
          changed = true                       // stroke düşürülür
        } else {
          newStrokes.add(s)
        }
      }
    }
    if (!changed) return
    pushHistory()
    strokes.clear(); strokes.addAll(newStrokes)
    redrawCommittedBitmap()
  }

  private fun splitStrokeByErase(s: Stroke, eraserPts: List<Float>, hitR: Float): List<Stroke> {
    val r2 = hitR * hitR
    val result = ArrayList<Stroke>()
    val run = ArrayList<Float>()
    fun flush() {
      if (run.size >= 4) {   // en az 2 nokta
        result.add(s.copy(
          path = buildPathFromPoints(run),
          variableWidthSamples = null,
          points = run.toList(),
        ))
      }
      run.clear()
    }
    var i = 0
    while (i + 1 < s.points.size) {
      val px = s.points[i]; val py = s.points[i + 1]
      if (pointNearPolyline(px, py, eraserPts, r2)) flush()
      else { run.add(px); run.add(py) }
      i += 2
    }
    flush()
    return result
  }

  // Serbest çizim noktalarından (live pipeline ile aynı) düz path üret.
  private fun buildPathFromPoints(pts: List<Float>): Path {
    val p = Path()
    if (pts.size < 2) return p
    p.moveTo(pts[0], pts[1])
    if (pts.size < 4) { p.lineTo(pts[0], pts[1]); return p }
    var lastX = pts[0]; var lastY = pts[1]
    var i = 2
    while (i + 1 < pts.size) {
      val wx = pts[i]; val wy = pts[i + 1]
      val midX = (lastX + wx) / 2f; val midY = (lastY + wy) / 2f
      p.quadTo(lastX, lastY, midX, midY)
      lastX = wx; lastY = wy
      i += 2
    }
    p.lineTo(lastX, lastY)
    return p
  }

  private fun pointNearPolyline(px: Float, py: Float, poly: List<Float>, r2: Float): Boolean {
    if (poly.size == 2) {
      val dx = px - poly[0]; val dy = py - poly[1]
      return dx * dx + dy * dy <= r2
    }
    var i = 0
    while (i + 3 < poly.size) {
      if (distToSegmentSq(px, py, poly[i], poly[i + 1], poly[i + 2], poly[i + 3]) <= r2) return true
      i += 2
    }
    return false
  }

  private fun polylineHit(poly: List<Float>, eraserPts: List<Float>, hitR: Float): Boolean {
    val r2 = hitR * hitR
    var i = 0
    while (i + 1 < poly.size) {
      if (pointNearPolyline(poly[i], poly[i + 1], eraserPts, r2)) return true
      i += 2
    }
    return false
  }

  private fun distToSegmentSq(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = bx - ax; val dy = by - ay
    val len2 = dx * dx + dy * dy
    if (len2 <= 0f) {
      val qx = px - ax; val qy = py - ay
      return qx * qx + qy * qy
    }
    var t = ((px - ax) * dx + (py - ay) * dy) / len2
    if (t < 0f) t = 0f else if (t > 1f) t = 1f
    val projX = ax + t * dx; val projY = ay + t * dy
    val ex = px - projX; val ey = py - projY
    return ex * ex + ey * ey
  }

  // Path'i hit-test için örnek noktalara indirger (şekil/yazı silme).
  private fun flattenPath(path: Path): List<Float> {
    val out = ArrayList<Float>()
    val pm = PathMeasure(path, false)
    val pos = FloatArray(2)
    do {
      val len = pm.length
      if (len <= 0f) continue
      val step = 6f              // ~6px örnekleme
      var d = 0f
      while (d <= len) {
        if (pm.getPosTan(d, pos, null)) { out.add(pos[0]); out.add(pos[1]) }
        d += step
      }
      if (pm.getPosTan(len, pos, null)) { out.add(pos[0]); out.add(pos[1]) }
    } while (pm.nextContour())
    return out
  }

  private fun cancelLiveStroke() {
    liveActive = false
    livePath.reset()
    liveWidthSamples.clear()
    // Tool switch sırasında bu fonksiyon public wrapper ile çağrılır;
    // stylus pointer'ı bırakmazsak sonraki gesture'da palm rejection takılı kalır.
    stylusDrawPointerId = MotionEvent.INVALID_POINTER_ID
    stylusStrokeActive = false
    invalidate()
  }

  // ─── Pan/Pinch ─────────────────────────────────────────────────────────
  // Tüm canvas için 2-parmak: scale + rotate + pan (focal-locked).
  // lockedWorld = pinch başlangıcında focal'ın altına denk gelen world point.
  // Sürekli güncellemede yeni transform bu world point'i yeni focal'a getirir.
  private var pinchInitialAngle = 0f
  // Rotation deadzone — iOS parite: küçük kasıtsız twist'i yok say, eşik aşılınca aktive et + re-baseline
  private var pinchRotationActivated = false
  private var imagePinchRotationActivated = false
  private val rotationDeadzone = 0.14f   // ~8°
  private val rotationSnapTolerance = 0.175f  // ~10° — release'te cardinal snap
  // ID-stable: ilk iki pointer ID'sini al, sonra her update'te findPointerIndex ile bul.
  // Tracked çiftin biri kaybolursa kalan iki parmağa göre re-baseline (jump yok).
  private fun resolvePinchIndices(event: MotionEvent): Pair<Int, Int>? {
    val i0 = if (pinchPointerId0 != MotionEvent.INVALID_POINTER_ID)
      event.findPointerIndex(pinchPointerId0) else -1
    val i1 = if (pinchPointerId1 != MotionEvent.INVALID_POINTER_ID)
      event.findPointerIndex(pinchPointerId1) else -1
    if (i0 >= 0 && i1 >= 0) return i0 to i1
    if (event.pointerCount < 2) return null
    // Tracked çift bozuk — mevcut ilk iki pointer'la baseline'lanmalı
    return null
  }

  private fun baselinePinch(event: MotionEvent, ix0: Int, ix1: Int) {
    pinchPointerId0 = event.getPointerId(ix0)
    pinchPointerId1 = event.getPointerId(ix1)
    val x0 = event.getX(ix0); val y0 = event.getY(ix0)
    val x1 = event.getX(ix1); val y1 = event.getY(ix1)
    savedScale = worldScale; savedRotation = worldRotation
    savedTx = worldTx; savedTy = worldTy
    pinchInitialDist = hypot(x1 - x0, y1 - y0).coerceAtLeast(0.0001f)
    pinchInitialAngle = atan2(y1 - y0, x1 - x0)
    pinchRotationActivated = false
    pinchInitialFocalX = (x0 + x1) / 2f
    pinchInitialFocalY = (y0 + y1) / 2f
    val cx = width / 2f; val cy = height / 2f
    val dx = pinchInitialFocalX - cx - savedTx
    val dy = pinchInitialFocalY - cy - savedTy
    val s = savedScale.coerceAtLeast(0.0001f)
    val cosR = cos(-savedRotation); val sinR = sin(-savedRotation)
    lockedWorldX = cx + (dx * cosR - dy * sinR) / s
    lockedWorldY = cy + (dx * sinR + dy * cosR) / s
  }

  private fun startPinch(event: MotionEvent) {
    if (event.pointerCount < 2) return
    baselinePinch(event, 0, 1)
  }

  private fun updatePinch(event: MotionEvent) {
    val pair = resolvePinchIndices(event)
    if (pair == null) {
      // Tracked çift kayıp ama ≥2 parmak var → re-baseline ve frame'i atla
      if (event.pointerCount >= 2) baselinePinch(event, 0, 1)
      return
    }
    val (i0, i1) = pair
    if (pinchInitialDist <= 0f) return
    val x0 = event.getX(i0); val y0 = event.getY(i0)
    val x1 = event.getX(i1); val y1 = event.getY(i1)
    val currentDist = hypot(x1 - x0, y1 - y0).coerceAtLeast(0.0001f)
    val damped = 1f + (currentDist / pinchInitialDist - 1f) * 0.8f
    val newScale = (savedScale * damped).coerceIn(minZoom, maxZoom)
    val currentAngle = atan2(y1 - y0, x1 - x0)
    // atan2 ±π'de wrap eder; delta'yı [-π, π]'ye normalize ederek "tepe taklak" sıçramayı önle.
    var angleDelta = currentAngle - pinchInitialAngle
    val twoPi = (2.0 * Math.PI).toFloat()
    while (angleDelta > Math.PI.toFloat()) angleDelta -= twoPi
    while (angleDelta < -Math.PI.toFloat()) angleDelta += twoPi
    // Deadzone: eşik aşılana kadar rotation 0, aşılınca re-baseline (intent kanıtlandı)
    if (!pinchRotationActivated) {
      if (abs(angleDelta) > rotationDeadzone) {
        pinchRotationActivated = true
        pinchInitialAngle = currentAngle
      }
      angleDelta = 0f
    }
    val newRotation = savedRotation + angleDelta
    val focalX = (x0 + x1) / 2f
    val focalY = (y0 + y1) / 2f
    val cx = width / 2f; val cy = height / 2f
    // worldTx = focal - center - R(rot) * scale * (locked - center)
    val ldx = (lockedWorldX - cx) * newScale
    val ldy = (lockedWorldY - cy) * newScale
    val cosR = cos(newRotation); val sinR = sin(newRotation)
    val rx = ldx * cosR - ldy * sinR
    val ry = ldx * sinR + ldy * cosR
    worldScale = newScale
    worldRotation = newRotation
    worldTx = focalX - cx - rx
    worldTy = focalY - cy - ry
    invalidate()
    onZoomChanged?.invoke(newScale)
  }

  private fun endPinch() {
    // Cardinal snap: worldRotation π/2 katına ~10° yakınsa o açıya kilitle.
    // tx/ty dokunulmaz; rotation pivot'u (cx+tx, cy+ty) — küçük delta'da görsel sıçrama minimal.
    val snapStep = (Math.PI / 2.0).toFloat()
    val target = round(worldRotation / snapStep) * snapStep
    if (abs(worldRotation - target) < rotationSnapTolerance) {
      worldRotation = target
      invalidate()
    }
    savedScale = worldScale; savedRotation = worldRotation
    savedTx = worldTx; savedTy = worldTy
    pinchPointerId0 = MotionEvent.INVALID_POINTER_ID
    pinchPointerId1 = MotionEvent.INVALID_POINTER_ID
  }

  // ─── Image tool — image üzerinde pan + pinch + rotate ─────────────────
  // 1-parmak: image'ı pan; 2-parmak: focal-locked scale + rotate
  private var imageDragStartWorldX = 0f
  private var imageDragStartWorldY = 0f
  private var imageDragStartCenterX = 0f
  private var imageDragStartCenterY = 0f
  private var imagePinchInitialDist = 0f
  private var imagePinchInitialAngle = 0f
  private var imageLockedLocalX = 0f  // image-local coords
  private var imageLockedLocalY = 0f

  private fun startImageDrag(screenX: Float, screenY: Float) {
    val (wx, wy) = screenToWorld(screenX, screenY)
    imageDragStartWorldX = wx; imageDragStartWorldY = wy
    imageDragStartCenterX = imageCenterX; imageDragStartCenterY = imageCenterY
  }

  private fun updateImageDrag(screenX: Float, screenY: Float) {
    val (wx, wy) = screenToWorld(screenX, screenY)
    imageCenterX = imageDragStartCenterX + (wx - imageDragStartWorldX)
    imageCenterY = imageDragStartCenterY + (wy - imageDragStartWorldY)
    invalidate()
  }

  private fun resolveImagePinchIndices(event: MotionEvent): Pair<Int, Int>? {
    val i0 = if (imagePinchPointerId0 != MotionEvent.INVALID_POINTER_ID)
      event.findPointerIndex(imagePinchPointerId0) else -1
    val i1 = if (imagePinchPointerId1 != MotionEvent.INVALID_POINTER_ID)
      event.findPointerIndex(imagePinchPointerId1) else -1
    if (i0 >= 0 && i1 >= 0) return i0 to i1
    return null
  }

  private fun baselineImagePinch(event: MotionEvent, ix0: Int, ix1: Int) {
    imagePinchPointerId0 = event.getPointerId(ix0)
    imagePinchPointerId1 = event.getPointerId(ix1)
    savedImageCenterX = imageCenterX; savedImageCenterY = imageCenterY
    savedImageScale = imageScale; savedImageRotation = imageRotation
    val (wx0, wy0) = screenToWorld(event.getX(ix0), event.getY(ix0))
    val (wx1, wy1) = screenToWorld(event.getX(ix1), event.getY(ix1))
    imagePinchInitialDist = hypot(wx1 - wx0, wy1 - wy0).coerceAtLeast(0.0001f)
    imagePinchInitialAngle = atan2(wy1 - wy0, wx1 - wx0)
    imagePinchRotationActivated = false
    val focalWX = (wx0 + wx1) / 2f
    val focalWY = (wy0 + wy1) / 2f
    val s = savedImageScale.coerceAtLeast(0.0001f)
    val cosR = cos(-savedImageRotation); val sinR = sin(-savedImageRotation)
    val dx = focalWX - savedImageCenterX
    val dy = focalWY - savedImageCenterY
    imageLockedLocalX = (dx * cosR - dy * sinR) / s
    imageLockedLocalY = (dx * sinR + dy * cosR) / s
  }

  private fun startImagePinch(event: MotionEvent) {
    if (event.pointerCount < 2) return
    baselineImagePinch(event, 0, 1)
  }

  private fun updateImagePinch(event: MotionEvent) {
    val pair = resolveImagePinchIndices(event)
    if (pair == null) {
      if (event.pointerCount >= 2) baselineImagePinch(event, 0, 1)
      return
    }
    val (i0, i1) = pair
    if (imagePinchInitialDist <= 0f) return
    val (wx0, wy0) = screenToWorld(event.getX(i0), event.getY(i0))
    val (wx1, wy1) = screenToWorld(event.getX(i1), event.getY(i1))
    val dist = hypot(wx1 - wx0, wy1 - wy0).coerceAtLeast(0.0001f)
    val angle = atan2(wy1 - wy0, wx1 - wx0)
    val newScale = (savedImageScale * (dist / imagePinchInitialDist)).coerceIn(0.1f, 10f)
    // atan2 ±π wrap'ini normalize et
    var imgAngleDelta = angle - imagePinchInitialAngle
    val twoPiImg = (2.0 * Math.PI).toFloat()
    while (imgAngleDelta > Math.PI.toFloat()) imgAngleDelta -= twoPiImg
    while (imgAngleDelta < -Math.PI.toFloat()) imgAngleDelta += twoPiImg
    // Deadzone: kasıtsız twist'i yok say, aktive olunca re-baseline
    if (!imagePinchRotationActivated) {
      if (abs(imgAngleDelta) > rotationDeadzone) {
        imagePinchRotationActivated = true
        imagePinchInitialAngle = angle
      }
      imgAngleDelta = 0f
    }
    val newRot = savedImageRotation + imgAngleDelta
    // imageCenter_new = focalWorld - R(newRot) * newScale * lockedLocal
    val focalWX = (wx0 + wx1) / 2f
    val focalWY = (wy0 + wy1) / 2f
    val lx = imageLockedLocalX * newScale
    val ly = imageLockedLocalY * newScale
    val cosR = cos(newRot); val sinR = sin(newRot)
    val rx = lx * cosR - ly * sinR
    val ry = lx * sinR + ly * cosR
    imageScale = newScale
    imageRotation = newRot
    imageCenterX = focalWX - rx
    imageCenterY = focalWY - ry
    invalidate()
  }

  private fun endImagePinch() {
    // Cardinal snap: imageRotation π/2 katına ~10° yakınsa o açıya kilitle.
    // Image rotation imageCenter etrafında — pivot zaten merkez, görsel kayma yok.
    val snapStep = (Math.PI / 2.0).toFloat()
    val target = round(imageRotation / snapStep) * snapStep
    if (abs(imageRotation - target) < rotationSnapTolerance) {
      imageRotation = target
      invalidate()
    }
    imagePinchPointerId0 = MotionEvent.INVALID_POINTER_ID
    imagePinchPointerId1 = MotionEvent.INVALID_POINTER_ID
  }

  // ─── 2-finger selection transform (rotate + scale + translate) ─────────
  private fun resolveSelectionIndices(event: MotionEvent): Pair<Int, Int>? {
    val i0 = if (selectionPointerId0 != MotionEvent.INVALID_POINTER_ID)
      event.findPointerIndex(selectionPointerId0) else -1
    val i1 = if (selectionPointerId1 != MotionEvent.INVALID_POINTER_ID)
      event.findPointerIndex(selectionPointerId1) else -1
    if (i0 >= 0 && i1 >= 0) return i0 to i1
    return null
  }

  // Live rotate/scale'i commit edip yeni iki parmağa baseline'la — jump yok
  private fun baselineSelectionTwoFinger(event: MotionEvent, ix0: Int, ix1: Int, commitLive: Boolean) {
    if (commitLive) {
      if (resizeLiveScale != 1f) applyResizeTransform(resizeLiveScale)
      if (rotateLiveAngle != 0f) {
        selectionBounds?.let {
          rotateCenterX = (it.left + it.right) / 2f
          rotateCenterY = (it.top + it.bottom) / 2f
        }
        applyRotationTransform(rotateLiveAngle)
      }
    }
    selectionPointerId0 = event.getPointerId(ix0)
    selectionPointerId1 = event.getPointerId(ix1)
    val (wx0, wy0) = screenToWorld(event.getX(ix0), event.getY(ix0))
    val (wx1, wy1) = screenToWorld(event.getX(ix1), event.getY(ix1))
    twoFingerInitialDist = hypot(wx1 - wx0, wy1 - wy0).coerceAtLeast(1f)
    twoFingerInitialAngle = atan2(wy1 - wy0, wx1 - wx0)
    twoFingerInitialFocalX = (wx0 + wx1) / 2f
    twoFingerInitialFocalY = (wy0 + wy1) / 2f
    selectionBounds?.let {
      rotateCenterX = (it.left + it.right) / 2f
      rotateCenterY = (it.top + it.bottom) / 2f
    }
    resizeAnchorX = rotateCenterX; resizeAnchorY = rotateCenterY
    twoFingerSavedScale = 1f
    twoFingerSavedAngle = 0f
    twoFingerSavedTx = 0f
    twoFingerSavedTy = 0f
    rotateLiveAngle = 0f
    resizeLiveScale = 1f
    moveDeltaX = 0f; moveDeltaY = 0f
  }

  private fun startSelectionTwoFinger(event: MotionEvent) {
    if (event.pointerCount < 2) return
    baselineSelectionTwoFinger(event, 0, 1, commitLive = false)
  }

  private fun updateSelectionTwoFinger(event: MotionEvent) {
    if (event.pointerCount < 2) return
    val pair = resolveSelectionIndices(event)
    if (pair == null) {
      baselineSelectionTwoFinger(event, 0, 1, commitLive = true)
      return
    }
    val (i0, i1) = pair
    val (wx0, wy0) = screenToWorld(event.getX(i0), event.getY(i0))
    val (wx1, wy1) = screenToWorld(event.getX(i1), event.getY(i1))
    val dist = hypot(wx1 - wx0, wy1 - wy0).coerceAtLeast(0.01f)
    val angle = atan2(wy1 - wy0, wx1 - wx0)
    // atan2 ±π wrap'ini normalize et
    var selAngleDelta = angle - twoFingerInitialAngle
    val twoPiSel = (2.0 * Math.PI).toFloat()
    while (selAngleDelta > Math.PI.toFloat()) selAngleDelta -= twoPiSel
    while (selAngleDelta < -Math.PI.toFloat()) selAngleDelta += twoPiSel
    rotateLiveAngle = selAngleDelta
    resizeLiveScale = (dist / twoFingerInitialDist).coerceIn(0.2f, 6f)
    invalidate()
  }

  private fun endSelectionTwoFinger() {
    val angle = rotateLiveAngle
    val scale = resizeLiveScale
    // Sırayla uygula: önce scale (anchor=center), sonra rotate (center=center)
    if (scale != 1f) applyResizeTransform(scale)
    if (angle != 0f) {
      // Scale sonrası bounds değişti, rotation pivot'unu yeniden hesapla
      selectionBounds?.let {
        rotateCenterX = (it.left + it.right) / 2f
        rotateCenterY = (it.top + it.bottom) / 2f
      }
      applyRotationTransform(angle)
    }
    rotateLiveAngle = 0f
    resizeLiveScale = 1f
    selectionTransforming = false
    selectMode = SelectMode.IDLE
    selectionPointerId0 = MotionEvent.INVALID_POINTER_ID
    selectionPointerId1 = MotionEvent.INVALID_POINTER_ID
    invalidate()
  }

  // ─── Coordinate transform ─────────────────────────────────────────────
  // Forward: world → screen
  //   screen = center + worldTx + R(rot) * worldScale * (world - center)
  // Inverse: screen → world
  //   world = center + (1/scale) * R(-rot) * (screen - center - worldTx)
  private fun screenToWorld(sx: Float, sy: Float): Pair<Float, Float> {
    val cx = width / 2f; val cy = height / 2f
    val dx = sx - cx - worldTx
    val dy = sy - cy - worldTy
    val s = worldScale.coerceAtLeast(0.0001f)
    val cosR = cos(-worldRotation); val sinR = sin(-worldRotation)
    val rx = dx * cosR - dy * sinR
    val ry = dx * sinR + dy * cosR
    return Pair(cx + rx / s, cy + ry / s)
  }

  private fun worldToScreen(wx: Float, wy: Float): Pair<Float, Float> {
    val cx = width / 2f; val cy = height / 2f
    val dx = (wx - cx) * worldScale
    val dy = (wy - cy) * worldScale
    val cosR = cos(worldRotation); val sinR = sin(worldRotation)
    val rx = dx * cosR - dy * sinR
    val ry = dx * sinR + dy * cosR
    return Pair(cx + worldTx + rx, cy + worldTy + ry)
  }

  private fun distance(event: MotionEvent): Float {
    if (event.pointerCount < 2) return 0f
    val dx = event.getX(0) - event.getX(1); val dy = event.getY(0) - event.getY(1)
    return hypot(dx, dy)
  }
}

data class WidthSample(val x: Float, val y: Float, val width: Float)

data class Stroke(
  val path: Path,
  val tool: CanvasTool,
  val color: Int,
  val width: Float,
  val opacity: Float,
  val isText: Boolean = false,
  // Stylus / S-Pen basıncı — set ise drawStrokeToCanvas segment-by-segment
  // width interp eder. Eraser/text/parmak stroke'ları için null; geriye dönük uyumlu.
  val variableWidthSamples: List<WidthSample>? = null,
  // Serbest çizim polyline'ı (world) [x0,y0,x1,y1,...]. Silgi bu noktaları GERÇEKTEN
  // keser → silinen kısım veri olarak yok olur. Text/şekil için boş (all-or-nothing).
  val points: List<Float> = emptyList(),
)
