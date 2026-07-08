import type { ViewProps } from 'react-native';

export type CanvasTool = 'pen' | 'eraser' | 'select' | 'image';

export type CanvasOverlay = 'none' | 'grid' | 'lines';

export type SelectionPickMode = 'rect' | 'lasso';

export type ExpoNativeCanvasViewProps = {
  /**
   * Background image (e.g. a photo or reference). Accepts file://, http(s)://,
   * content:// or a bundled asset URI. When null/undefined only the drawing
   * canvas is shown.
   */
  imageUri?: string | null;

  /**
   * Active tool. 'pen' draws, 'eraser' removes ink (true vector erase),
   * 'select' lets the user pick and transform strokes, 'image' manipulates
   * the background image transform.
   */
  tool?: CanvasTool;

  /**
   * Stroke color (hex, e.g. "#ef4444").
   */
  strokeColor?: string;

  /**
   * Stroke width (CSS-style px; scaled density-aware on the native side).
   */
  strokeWidth?: number;

  /**
   * Opacity of the drawing layer (0..1).
   */
  strokeOpacity?: number;

  /**
   * Min and max scale for pinch-to-zoom.
   */
  minZoom?: number;
  maxZoom?: number;

  /**
   * Canvas background color (hex). Defaults to white.
   */
  canvasBackgroundColor?: string;

  /**
   * Canvas backdrop guides. 'none' = blank, 'grid' = squares, 'lines' = ruled.
   */
  canvasOverlay?: CanvasOverlay;

  /**
   * How the select tool gathers a selection region.
   * 'rect' = rectangular marquee, 'lasso' = freehand.
   */
  selectionMode?: SelectionPickMode;

  /**
   * Fires whenever the committed stroke count changes (draw / undo / redo /
   * clear / recognize). Use the emitted point list to run shape recognition.
   */
  onStrokeCountChange?: (e: { nativeEvent: StrokeCountChangeEvent }) => void;

  /**
   * Fires when zoom (or rotation) changes.
   */
  onZoomChange?: (e: { nativeEvent: ZoomChangeEvent }) => void;
} & ViewProps;

export type StrokeCountChangeEvent = {
  /** Number of committed strokes on the native side. */
  totalStrokes: number;
  /** Length of the redo stack (populated after an undo). */
  redoCount: number;
  /**
   * Raw points of the stroke that was just committed, in world coordinates.
   * Format: [x0, y0, x1, y1, ...] (alternating). An empty array means no new
   * stroke (undo/redo/clear, or a recognize/replace). Feed this into shape
   * recognition.
   */
  points: number[];
  /**
   * Total duration of the stroke in ms. 0 for undo/redo/clear. Use it to
   * limit recognition to deliberate (slow) strokes so accidental fast marks
   * are not converted.
   */
  durationMs?: number;
  /**
   * Idle time (ms) after the last movement before the stroke ended. Lets you
   * trigger shape recognition when the user holds still for ~1s.
   */
  idleMs?: number;
};

export type ZoomChangeEvent = {
  /** Current zoom level (1.0 = identity). */
  zoom: number;
  /** Current canvas rotation in radians. 0 = identity. */
  rotation?: number;
};

/**
 * Imperative commands — call them through a ref.
 *   const ref = useRef<ExpoNativeCanvasViewHandle>(null);
 *   <ExpoNativeCanvasView ref={ref} />
 *   ref.current?.undo();
 */
export type ExpoNativeCanvasViewHandle = {
  undo: () => void;
  redo: () => void;
  clear: () => void;
  /**
   * Replace the last committed stroke with a clean geometric shape. For the
   * shape-recognition flow: run recognizeShape() on the committed point list,
   * and if it succeeds call this to snap the freehand stroke to the shape.
   */
  replaceLastStrokeWithShape: (shape: RecognizedShapeForNative) => void;
  /**
   * Reset the canvas world transform (rotation=0, scale=1, pan=0). Does not
   * touch the image transform while the image tool is active.
   */
  resetCanvasTransform: () => void;
  /**
   * Reset the active image item transform (initial fit + center + 0 rotation).
   */
  resetImageTransform: () => void;
  /**
   * Insert a handwriting-style text item onto the canvas. It enters the stroke
   * list as a filled glyph-outline path, which means it:
   *   - can be erased by the eraser like any other ink,
   *   - can be selected and rotated/scaled with two fingers via the select tool,
   *   - participates in the undo/redo stack.
   * The item is auto-selected after insertion; the JS side may switch the tool
   * to 'select' to let the user transform it immediately.
   *
   * @param text     Text content (Unicode).
   * @param fontSize CSS-style px; scaled density-aware on the native side.
   * @param color    Hex fill color (e.g. "#111827").
   */
  insertText: (text: string, fontSize: number, color: string) => void;
  /**
   * Recolor the currently selected strokes. No-op unless the select tool is
   * active with at least one item selected. Handy right after insertText,
   * since the new item is auto-selected.
   *
   * @param color Hex (e.g. "#ef4444").
   */
  setSelectedStrokeColor: (color: string) => void;
  /**
   * Delete the currently selected strokes. No-op unless the select tool is
   * active with a selection. Reversible with undo.
   */
  deleteSelection: () => void;
};

/**
 * Serializable shape payload sent to the native side — a flattened version of
 * {@link RecognizedShape} (TypeScript union to native Map<String, Any>).
 */
export type RecognizedShapeForNative =
  | { type: 'line'; x1: number; y1: number; x2: number; y2: number }
  | { type: 'rect'; minX: number; minY: number; maxX: number; maxY: number }
  | { type: 'quad'; vertices: Array<{ x: number; y: number }> }
  | { type: 'triangle'; vertices: Array<{ x: number; y: number }> }
  | { type: 'circle'; cx: number; cy: number; r: number }
  | { type: 'ellipse'; cx: number; cy: number; rx: number; ry: number };
