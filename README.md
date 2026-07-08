# expo-native-canvas

A **fully-native drawing canvas** for Expo / React Native.

Drawing, panning, pinch-to-zoom and rotation all run on the **UI thread in native code** — there is **no JS-bridge traffic per touch event**. On Android it uses `android.graphics.Canvas`; on iOS it uses Core Graphics. The JavaScript side only sends prop updates and imperative commands (undo, clear, …), never the touch stream.

> Built for and battle-tested in a production exam-prep app where students solve questions by hand on tablets with a stylus.

---

## Why another canvas?

Most React Native drawing libraries render through Skia (`@shopify/react-native-skia`) or SVG. Both are excellent, but the touch handling and per-frame path updates cross the JS↔native boundary, which shows up as latency and dropped frames on lower-end tablets — exactly where handwriting needs to feel instant.

`expo-native-canvas` takes a different trade-off:

| | `expo-native-canvas` | Skia / SVG canvases |
|---|---|---|
| Touch → render path | 100% native, UI thread | Touch/gesture often routed via JS |
| Per-touch JS bridge cost | None | Per event / per frame |
| Pan / pinch / rotate | Native gesture math | Usually RN Gesture Handler + JS |
| Eraser | True **vector** erase (splits/trims strokes) | Often raster clear or blend |
| Stylus / pressure | Apple Pencil + S-Pen, variable width | Varies |
| Shape recognition | Built in (pure TS classifier) | Bring your own |
| Rendering backend | Platform-native only | Skia (bundled) |

**Pick this** when you want native-feeling ink latency and don't need Skia's cross-platform shader/graphics features. **Pick Skia** when you need advanced 2D graphics, filters, or web support.

---

## Features

- ✍️ **Pen** with density-aware width, color and opacity
- 🩹 **True vector eraser** — trims and splits strokes at the point level, not a raster wipe
- 🖼️ **Image background** (local, remote or bundled) with independent pan/zoom/rotate
- 🔍 **Pan / pinch-zoom / rotate** with configurable min/max scale, computed natively
- 🎯 **Selection tool** — rectangular or lasso; move, scale, rotate and recolor strokes
- 🔤 **Text insertion** as editable, erasable, transformable glyph-outline paths
- 🧠 **On-device shape recognition** (line / rect / rotated quad / triangle / circle / ellipse) — Apple-Notes-style snap
- ↩️ **Undo / redo / clear** with a native command stack
- 🖊️ **Stylus support** — Apple Pencil & Samsung S-Pen pressure and palm rejection
- 📐 **Backdrop guides** — blank, grid or ruled

---

## Installation

```bash
npx expo install expo-native-canvas
```

This is a **native module**, so it does **not** run in Expo Go. Use a
development build:

```bash
npx expo prebuild
npx expo run:ios      # or
npx expo run:android
```

**Requirements:** Expo SDK 50+ · iOS 13.4+ · Android minSdk 24+.

---

## Quick start

```tsx
import { useRef, useState } from 'react';
import { View, Button } from 'react-native';
import {
  ExpoNativeCanvasView,
  type ExpoNativeCanvasViewHandle,
  type CanvasTool,
} from 'expo-native-canvas';

export default function Draw() {
  const canvas = useRef<ExpoNativeCanvasViewHandle>(null);
  const [tool, setTool] = useState<CanvasTool>('pen');

  return (
    <View style={{ flex: 1 }}>
      <ExpoNativeCanvasView
        ref={canvas}
        style={{ flex: 1 }}
        tool={tool}
        strokeColor="#ef4444"
        strokeWidth={4}
        canvasOverlay="grid"
        onStrokeCountChange={(e) =>
          console.log('strokes:', e.nativeEvent.totalStrokes)
        }
      />

      <View style={{ flexDirection: 'row' }}>
        <Button title="Pen" onPress={() => setTool('pen')} />
        <Button title="Eraser" onPress={() => setTool('eraser')} />
        <Button title="Undo" onPress={() => canvas.current?.undo()} />
        <Button title="Redo" onPress={() => canvas.current?.redo()} />
        <Button title="Clear" onPress={() => canvas.current?.clear()} />
      </View>
    </View>
  );
}
```

### Shape recognition (Apple-Notes-style snap)

Recognition is a pure-TS classifier you run yourself when a stroke is
committed, so you stay in control of *when* to snap (e.g. only for slow,
deliberate strokes).

```tsx
import { recognizeShape } from 'expo-native-canvas';

<ExpoNativeCanvasView
  ref={canvas}
  onStrokeCountChange={(e) => {
    const { points, durationMs } = e.nativeEvent;
    if (!points.length || (durationMs ?? 0) < 250) return;

    // points is [x0, y0, x1, y1, ...]
    const pts = [];
    for (let i = 0; i < points.length; i += 2) {
      pts.push({ x: points[i], y: points[i + 1] });
    }

    const shape = recognizeShape(pts);
    if (shape) canvas.current?.replaceLastStrokeWithShape(shape);
  }}
/>
```

---

## API

### `<ExpoNativeCanvasView />` props

| Prop | Type | Default | Description |
|---|---|---|---|
| `tool` | `'pen' \| 'eraser' \| 'select' \| 'image'` | `'pen'` | Active tool |
| `strokeColor` | `string` (hex) | `#000000` | Pen color |
| `strokeWidth` | `number` | `4` | Pen width (CSS px, density-aware) |
| `strokeOpacity` | `number` | `1` | Drawing layer opacity (0–1) |
| `imageUri` | `string \| null` | `null` | Background image URI |
| `minZoom` / `maxZoom` | `number` | `1` / `3` | Pinch bounds |
| `canvasBackgroundColor` | `string` (hex) | `#ffffff` | Canvas background |
| `canvasOverlay` | `'none' \| 'grid' \| 'lines'` | `'none'` | Backdrop guides |
| `selectionMode` | `'rect' \| 'lasso'` | `'rect'` | How the select tool gathers strokes |
| `onStrokeCountChange` | `(e) => void` | — | Fires on draw/undo/redo/clear; carries the committed point list |
| `onZoomChange` | `(e) => void` | — | Fires on zoom/rotation change |

Plus all standard `ViewProps`.

### Ref handle (`ExpoNativeCanvasViewHandle`)

| Method | Description |
|---|---|
| `undo()` / `redo()` / `clear()` | Native command stack |
| `replaceLastStrokeWithShape(shape)` | Snap the last stroke to a recognized shape |
| `resetCanvasTransform()` | Reset pan/zoom/rotation |
| `resetImageTransform()` | Reset the background image transform |
| `insertText(text, fontSize, color)` | Insert erasable, transformable text |
| `setSelectedStrokeColor(color)` | Recolor the current selection |
| `deleteSelection()` | Delete the current selection (undoable) |

### Exports

```ts
import {
  ExpoNativeCanvasView,   // the component
  recognizeShape,         // (points: Point[]) => RecognizedShape | null
} from 'expo-native-canvas';

import type {
  ExpoNativeCanvasViewProps,
  ExpoNativeCanvasViewHandle,
  CanvasTool,
  CanvasOverlay,
  SelectionPickMode,
  StrokeCountChangeEvent,
  ZoomChangeEvent,
  RecognizedShape,
  RecognizedShapeForNative,
  Point,
} from 'expo-native-canvas';
```

---

## Example app

A runnable demo lives in [`example/`](./example):

```bash
cd example
npm install
npx expo run:ios      # or run:android
```

---

## Coordinate system

All points reported by `onStrokeCountChange` are in **world coordinates**
(before pan/zoom/rotation), so recognized shapes and stored strokes stay
stable regardless of the current viewport transform.

---

## Roadmap

- [ ] Export the canvas to an image (PNG / base64)
- [ ] Serialize / restore stroke documents (JSON)
- [ ] English translation of the remaining native-side inline comments
- [ ] Configurable palm-rejection threshold
- [ ] Web fallback (Canvas 2D)

Contributions welcome — see [issues](https://github.com/Tunacodin/expo-native-canvas/issues).

---

## License

[MIT](./LICENSE) © Tunacodin
