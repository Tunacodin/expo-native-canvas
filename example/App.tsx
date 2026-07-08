import { useRef, useState } from 'react';
import {
  Pressable,
  SafeAreaView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { StatusBar } from 'expo-status-bar';
import {
  ExpoNativeCanvasView,
  recognizeShape,
  type CanvasTool,
  type CanvasOverlay,
  type ExpoNativeCanvasViewHandle,
} from 'expo-native-canvas';

const COLORS = ['#111827', '#ef4444', '#2563eb', '#16a34a', '#f59e0b'];
const OVERLAYS: CanvasOverlay[] = ['none', 'grid', 'lines'];

export default function App() {
  const canvas = useRef<ExpoNativeCanvasViewHandle>(null);

  const [tool, setTool] = useState<CanvasTool>('pen');
  const [color, setColor] = useState(COLORS[0]);
  const [overlay, setOverlay] = useState<CanvasOverlay>('grid');
  const [strokes, setStrokes] = useState(0);
  const [snap, setSnap] = useState(true);

  return (
    <SafeAreaView style={styles.root}>
      <StatusBar style="dark" />

      <View style={styles.header}>
        <Text style={styles.title}>expo-native-canvas</Text>
        <Text style={styles.meta}>{strokes} strokes</Text>
      </View>

      <ExpoNativeCanvasView
        ref={canvas}
        style={styles.canvas}
        tool={tool}
        strokeColor={color}
        strokeWidth={4}
        canvasOverlay={overlay}
        selectionMode="lasso"
        onStrokeCountChange={(e) => {
          const { totalStrokes, points, durationMs } = e.nativeEvent;
          setStrokes(totalStrokes);

          // Apple-Notes-style snap: only for deliberate (slow) strokes.
          if (!snap || tool !== 'pen') return;
          if (!points.length || (durationMs ?? 0) < 250) return;

          const pts: { x: number; y: number }[] = [];
          for (let i = 0; i < points.length; i += 2) {
            pts.push({ x: points[i], y: points[i + 1] });
          }
          const shape = recognizeShape(pts);
          if (shape) canvas.current?.replaceLastStrokeWithShape(shape);
        }}
      />

      {/* Tools */}
      <Row>
        <Chip label="Pen" active={tool === 'pen'} onPress={() => setTool('pen')} />
        <Chip label="Eraser" active={tool === 'eraser'} onPress={() => setTool('eraser')} />
        <Chip label="Select" active={tool === 'select'} onPress={() => setTool('select')} />
        <Chip label={`Snap ${snap ? 'on' : 'off'}`} active={snap} onPress={() => setSnap((s) => !s)} />
      </Row>

      {/* Colors */}
      <Row>
        {COLORS.map((c) => (
          <Pressable
            key={c}
            onPress={() => setColor(c)}
            style={[
              styles.swatch,
              { backgroundColor: c },
              color === c && styles.swatchActive,
            ]}
          />
        ))}
        <Chip
          label={`Grid: ${overlay}`}
          onPress={() =>
            setOverlay(OVERLAYS[(OVERLAYS.indexOf(overlay) + 1) % OVERLAYS.length])
          }
        />
      </Row>

      {/* Commands */}
      <Row>
        <Chip label="Undo" onPress={() => canvas.current?.undo()} />
        <Chip label="Redo" onPress={() => canvas.current?.redo()} />
        <Chip label="Text" onPress={() => canvas.current?.insertText('abc', 48, color)} />
        <Chip label="Reset" onPress={() => canvas.current?.resetCanvasTransform()} />
        <Chip label="Clear" onPress={() => canvas.current?.clear()} />
      </Row>
    </SafeAreaView>
  );
}

function Row({ children }: { children: React.ReactNode }) {
  return <View style={styles.row}>{children}</View>;
}

function Chip({
  label,
  active,
  onPress,
}: {
  label: string;
  active?: boolean;
  onPress: () => void;
}) {
  return (
    <Pressable onPress={onPress} style={[styles.chip, active && styles.chipActive]}>
      <Text style={[styles.chipText, active && styles.chipTextActive]}>{label}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: '#f8fafc' },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingVertical: 10,
  },
  title: { fontSize: 16, fontWeight: '700', color: '#0f172a' },
  meta: { fontSize: 13, color: '#64748b' },
  canvas: {
    flex: 1,
    marginHorizontal: 12,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: '#e2e8f0',
    overflow: 'hidden',
    backgroundColor: '#ffffff',
  },
  row: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
    paddingHorizontal: 12,
    paddingTop: 10,
    alignItems: 'center',
  },
  chip: {
    paddingHorizontal: 14,
    paddingVertical: 8,
    borderRadius: 999,
    borderWidth: 1,
    borderColor: '#e2e8f0',
    backgroundColor: '#ffffff',
  },
  chipActive: { backgroundColor: '#0f172a', borderColor: '#0f172a' },
  chipText: { fontSize: 13, color: '#334155', fontWeight: '600' },
  chipTextActive: { color: '#ffffff' },
  swatch: {
    width: 30,
    height: 30,
    borderRadius: 999,
    borderWidth: 2,
    borderColor: 'transparent',
  },
  swatchActive: { borderColor: '#0f172a' },
});
