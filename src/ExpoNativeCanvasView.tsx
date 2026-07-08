import { requireNativeViewManager } from 'expo-modules-core';
import * as React from 'react';
import type {
  ExpoNativeCanvasViewProps,
  ExpoNativeCanvasViewHandle,
} from './ExpoNativeCanvas.types';

// View manager name = Module Name ('ExpoNativeCanvas').
const NativeView: React.ComponentType<any> = requireNativeViewManager('ExpoNativeCanvas');

/**
 * forwardRef exposes the native view functions (undo/redo/clear/...), which
 * come from the Expo Modules `AsyncFunction` definitions.
 *
 * Usage:
 *   const ref = useRef<ExpoNativeCanvasViewHandle>(null);
 *   <ExpoNativeCanvasView ref={ref} ... />
 *   ref.current?.undo();
 */
const ExpoNativeCanvasView = React.forwardRef<
  ExpoNativeCanvasViewHandle,
  ExpoNativeCanvasViewProps
>((props, ref) => {
  return <NativeView ref={ref} {...props} />;
});

ExpoNativeCanvasView.displayName = 'ExpoNativeCanvasView';

export default ExpoNativeCanvasView;
