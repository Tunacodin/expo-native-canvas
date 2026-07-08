export { default as ExpoNativeCanvasView } from './ExpoNativeCanvasView';
export type {
  ExpoNativeCanvasViewProps,
  ExpoNativeCanvasViewHandle,
  StrokeCountChangeEvent,
  ZoomChangeEvent,
  CanvasTool,
  CanvasOverlay,
  SelectionPickMode,
  RecognizedShapeForNative,
} from './ExpoNativeCanvas.types';
export { recognizeShape } from './shapeRecognition';
export type { Point, RecognizedShape } from './shapeRecognition';
