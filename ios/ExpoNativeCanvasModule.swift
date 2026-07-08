import ExpoModulesCore

/**
 * Native canvas modülü (iOS) — View tabanlı.
 *
 * View propları + view fonksiyonları Android ile API parite:
 *  - tool: 'pen' | 'eraser' | 'select'
 *  - canvasOverlay: 'none' | 'grid' | 'lines'
 *  - selectionMode: 'rect' | 'lasso'
 *  - undo/redo/clear + replaceLastStrokeWithShape (line/rect/triangle/quad/circle/ellipse)
 *
 * Stroke event'i artık duration/idle taşır → JS 1sn şekil tanıma gating'i.
 */
public class ExpoNativeCanvasModule: Module {
  public func definition() -> ModuleDefinition {
    Name("ExpoNativeCanvas")

    View(ExpoNativeCanvasView.self) {
      Events("onStrokeCountChange", "onZoomChange")

      Prop("imageUri") { (view: ExpoNativeCanvasView, uri: String?) in
        view.setImageUri(uri)
      }
      Prop("tool") { (view: ExpoNativeCanvasView, tool: String?) in
        view.setTool(tool ?? "pen")
      }
      Prop("strokeColor") { (view: ExpoNativeCanvasView, color: String?) in
        view.setStrokeColor(color ?? "#000000")
      }
      Prop("strokeWidth") { (view: ExpoNativeCanvasView, width: Double?) in
        view.setStrokeWidth(CGFloat(width ?? 4.0))
      }
      Prop("strokeOpacity") { (view: ExpoNativeCanvasView, opacity: Double?) in
        view.setStrokeOpacity(CGFloat(opacity ?? 1.0))
      }
      Prop("minZoom") { (view: ExpoNativeCanvasView, z: Double?) in
        view.setMinZoom(CGFloat(z ?? 0.75))
      }
      Prop("maxZoom") { (view: ExpoNativeCanvasView, z: Double?) in
        view.setMaxZoom(CGFloat(z ?? 3.0))
      }
      Prop("canvasBackgroundColor") { (view: ExpoNativeCanvasView, color: String?) in
        view.setCanvasBackgroundColor(color ?? "#ffffff")
      }
      Prop("canvasOverlay") { (view: ExpoNativeCanvasView, overlay: String?) in
        view.setCanvasOverlay(overlay ?? "none")
      }
      Prop("selectionMode") { (view: ExpoNativeCanvasView, mode: String?) in
        view.setSelectionMode(mode ?? "rect")
      }

      // View functions — JS ref'ten çağrılır
      AsyncFunction("undo") { (view: ExpoNativeCanvasView) in
        view.runUndo()
      }
      AsyncFunction("redo") { (view: ExpoNativeCanvasView) in
        view.runRedo()
      }
      AsyncFunction("clear") { (view: ExpoNativeCanvasView) in
        view.runClear()
      }
      AsyncFunction("replaceLastStrokeWithShape") { (view: ExpoNativeCanvasView, shape: [String: Any]) in
        view.runReplaceLastStrokeWithShape(shape)
      }
      AsyncFunction("resetCanvasTransform") { (view: ExpoNativeCanvasView) in
        view.runResetCanvasTransform()
      }
      AsyncFunction("resetImageTransform") { (view: ExpoNativeCanvasView) in
        view.runResetImageTransform()
      }
      // Text item ekleme — el yazısı stilinde, dolgu path olarak eklenir.
      // Eraser CLEAR, selection, rotate/scale mevcut Stroke pipeline'ında çalışır.
      AsyncFunction("insertText") { (view: ExpoNativeCanvasView, text: String, fontSize: Double, color: String) in
        view.runInsertText(text, fontSize: CGFloat(fontSize), color: color)
      }
      // Seçili stroke'ların rengini değiştir (select tool aktif + seçim varken).
      AsyncFunction("setSelectedStrokeColor") { (view: ExpoNativeCanvasView, color: String) in
        view.runSetSelectedStrokeColor(color)
      }
      // Seçili bölgeyi (stroke'ları) tamamen sil.
      AsyncFunction("deleteSelection") { (view: ExpoNativeCanvasView) in
        view.runDeleteSelection()
      }
    }
  }
}
