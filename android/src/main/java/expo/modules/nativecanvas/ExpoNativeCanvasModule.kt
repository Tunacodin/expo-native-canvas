package expo.modules.nativecanvas

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

/**
 * Native canvas modülü — View tabanlı.
 *
 * View propları:
 *  - imageUri: arka plan görseli
 *  - tool: 'pen' | 'eraser'
 *  - strokeColor, strokeWidth, strokeOpacity: pen ayarları
 *  - minZoom, maxZoom: pinch sınırları
 *  - canvasBackgroundColor: canvas BG
 *
 * View fonksiyonları (JS'ten ref ile çağırılır):
 *  - undo(), redo(), clear()
 *
 * Tüm draw / pan / pinch UI thread'de native olarak. JS bridge sadece
 * prop güncellemesinde + komut çağrısında devreye girer (touch event'lerinde değil).
 */
class ExpoNativeCanvasModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("ExpoNativeCanvas")

    View(ExpoNativeCanvasView::class) {
      Events("onStrokeCountChange", "onZoomChange")

      Prop("imageUri") { view: ExpoNativeCanvasView, uri: String? ->
        view.setImageUri(uri)
      }
      Prop("tool") { view: ExpoNativeCanvasView, tool: String? ->
        view.setTool(tool ?: "pen")
      }
      Prop("strokeColor") { view: ExpoNativeCanvasView, color: String? ->
        view.setStrokeColor(color ?: "#000000")
      }
      Prop("strokeWidth") { view: ExpoNativeCanvasView, width: Double? ->
        view.setStrokeWidth(width?.toFloat() ?: 4f)
      }
      Prop("strokeOpacity") { view: ExpoNativeCanvasView, opacity: Double? ->
        view.setStrokeOpacity(opacity?.toFloat() ?: 1f)
      }
      Prop("minZoom") { view: ExpoNativeCanvasView, z: Double? ->
        view.setMinZoom(z?.toFloat() ?: 1f)
      }
      Prop("maxZoom") { view: ExpoNativeCanvasView, z: Double? ->
        view.setMaxZoom(z?.toFloat() ?: 3f)
      }
      Prop("canvasBackgroundColor") { view: ExpoNativeCanvasView, color: String? ->
        view.setCanvasBackgroundColor(color ?: "#ffffff")
      }
      Prop("canvasOverlay") { view: ExpoNativeCanvasView, overlay: String? ->
        view.setCanvasOverlay(overlay ?: "none")
      }
      Prop("selectionMode") { view: ExpoNativeCanvasView, mode: String? ->
        view.setSelectionMode(mode ?: "rect")
      }

      // View functions — ref.current.undo() vb. ile çağrılır
      AsyncFunction("undo") { view: ExpoNativeCanvasView ->
        view.runUndo()
      }
      AsyncFunction("redo") { view: ExpoNativeCanvasView ->
        view.runRedo()
      }
      AsyncFunction("clear") { view: ExpoNativeCanvasView ->
        view.runClear()
      }
      // Shape recognition: JS recognize eder, native side'a "son stroke'u şu
      // geometrik şekille değiştir" diye komut yollar.
      // shape map'i: { type: "line"|"rect"|"triangle"|"circle"|"ellipse", ... }
      AsyncFunction("replaceLastStrokeWithShape") { view: ExpoNativeCanvasView, shape: Map<String, Any> ->
        view.runReplaceLastStrokeWithShape(shape)
      }
      AsyncFunction("resetCanvasTransform") { view: ExpoNativeCanvasView ->
        view.runResetCanvasTransform()
      }
      AsyncFunction("resetImageTransform") { view: ExpoNativeCanvasView ->
        view.runResetImageTransform()
      }
      // Text item ekleme — el yazısı stilinde, dolgu (FILL) path olarak eklenir.
      // Eraser, selection, rotate/scale mevcut Stroke pipeline'ında çalışır.
      AsyncFunction("insertText") { view: ExpoNativeCanvasView, text: String, fontSize: Double, color: String ->
        view.runInsertText(text, fontSize.toFloat(), color)
      }
      // Seçili stroke'ların rengini değiştir (select tool aktif + seçim varken).
      AsyncFunction("setSelectedStrokeColor") { view: ExpoNativeCanvasView, color: String ->
        view.runSetSelectedStrokeColor(color)
      }
      // Seçili bölgeyi (stroke'ları) tamamen sil.
      AsyncFunction("deleteSelection") { view: ExpoNativeCanvasView ->
        view.runDeleteSelection()
      }
    }
  }
}
