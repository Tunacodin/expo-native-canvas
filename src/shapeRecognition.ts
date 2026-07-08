/**
 * Shape Recognition (Apple Notes / Samsung Notes / OneNote style).
 *
 * Multi-feature classifier:
 *  1. Bounding box → bw, bh, diag, aspect ratio
 *  2. Path length (perimeter)
 *  3. Shoelace area → circularity (Polsby-Popper test)
 *      Circle ≈ 1.0, Square ≈ 0.785, Triangle ≈ 0.604, Line ≈ 0
 *  4. Closure detection (start-end distance + path length combined)
 *  5. RDP simplification → extracts the critical vertices
 *  6. Vertex angle analysis → triangle vs. rectangle
 *  7. Scoring system → pick the highest-scoring shape
 *
 * All functions are pure (no dependency beyond Math/Array).
 */

// Pure TypeScript — no Skia or React Native dependency.
// The native side converts the recognized shape into a Path.

export type Point = { x: number; y: number };

export type RecognizedShape =
    | { type: 'line'; x1: number; y1: number; x2: number; y2: number }
    | { type: 'rect'; minX: number; minY: number; maxX: number; maxY: number }
    | { type: 'quad'; vertices: Point[] } // rotated/non-axis-aligned rectangle (4 vertices in order)
    | { type: 'triangle'; vertices: Point[] }
    | { type: 'circle'; cx: number; cy: number; r: number }
    | { type: 'ellipse'; cx: number; cy: number; rx: number; ry: number };

// ─── Geometry helpers ──────────────────────────────────────────────────────

const dist = (a: Point, b: Point): number => {
    const dx = a.x - b.x;
    const dy = a.y - b.y;
    return Math.sqrt(dx * dx + dy * dy);
};

const perpendicularDistance = (p: Point, a: Point, b: Point): number => {
    const dx = b.x - a.x;
    const dy = b.y - a.y;
    const mag = Math.sqrt(dx * dx + dy * dy);
    if (mag < 0.0001) return dist(p, a);
    return Math.abs((p.x - a.x) * dy - (p.y - a.y) * dx) / mag;
};

// Ramer-Douglas-Peucker
const rdpSimplify = (points: Point[], epsilon: number): Point[] => {
    if (points.length < 3) return points.slice();
    let maxDist = 0;
    let maxIdx = 0;
    const start = points[0]!;
    const end = points[points.length - 1]!;
    for (let i = 1; i < points.length - 1; i++) {
        const d = perpendicularDistance(points[i]!, start, end);
        if (d > maxDist) {
            maxDist = d;
            maxIdx = i;
        }
    }
    if (maxDist > epsilon) {
        const left = rdpSimplify(points.slice(0, maxIdx + 1), epsilon);
        const right = rdpSimplify(points.slice(maxIdx), epsilon);
        return left.slice(0, -1).concat(right);
    }
    return [start, end];
};

// Shoelace formula — polygon area (assumes points form a polygon)
const polygonArea = (points: Point[]): number => {
    let area = 0;
    const n = points.length;
    for (let i = 0; i < n; i++) {
        const j = (i + 1) % n;
        area += points[i]!.x * points[j]!.y;
        area -= points[j]!.x * points[i]!.y;
    }
    return Math.abs(area) / 2;
};

// İki vector arasındaki açı (radyan), [0, π]
const angleBetween = (ax: number, ay: number, bx: number, by: number): number => {
    const mag = Math.sqrt(ax * ax + ay * ay) * Math.sqrt(bx * bx + by * by);
    if (mag < 0.0001) return 0;
    const cos = Math.max(-1, Math.min(1, (ax * bx + ay * by) / mag));
    return Math.acos(cos);
};

// Komşu vertex'leri birleştir (RDP artifactları için)
const mergeCloseVertices = (vertices: Point[], minDist: number): Point[] => {
    if (vertices.length < 2) return vertices.slice();
    const out: Point[] = [vertices[0]!];
    for (let i = 1; i < vertices.length; i++) {
        const v = vertices[i]!;
        const lastV = out[out.length - 1]!;
        if (dist(v, lastV) >= minDist) out.push(v);
    }
    return out;
};

// Her vertex'te köşe açısı hesapla (kapalı polygon varsayar)
const vertexAngles = (vertices: Point[]): number[] => {
    const n = vertices.length;
    const angles: number[] = [];
    for (let i = 0; i < n; i++) {
        const prev = vertices[(i - 1 + n) % n]!;
        const curr = vertices[i]!;
        const next = vertices[(i + 1) % n]!;
        const ax = prev.x - curr.x;
        const ay = prev.y - curr.y;
        const bx = next.x - curr.x;
        const by = next.y - curr.y;
        angles.push(angleBetween(ax, ay, bx, by));
    }
    return angles;
};

// En "köşeli" N vertex'i seç (en küçük açılar = en keskin köşeler)
const pickSharpestVertices = (vertices: Point[], n: number): Point[] => {
    if (vertices.length <= n) return vertices.slice();
    const angles = vertexAngles(vertices);
    // Index'leri açıya göre sırala (küçükten büyüğe = keskinden geniş)
    const indices = vertices.map((_, i) => i);
    indices.sort((a, b) => angles[a]! - angles[b]!);
    // İlk N en keskin, ama orijinal sıraya göre tekrar diz
    const picked = indices.slice(0, n).sort((a, b) => a - b);
    return picked.map((i) => vertices[i]!);
};

const edgeLen = (a: Point, b: Point): number => dist(a, b);

// 4 vertex'i polygon traversal sırasında olduğu gibi bırak; sadece convex hull
// sırasına çevirme yerine, mevcut sırayı koruyoruz çünkü RDP zaten sırayı korur.
// Burada yapılan tek şey: ilk vertex'i en sol-üst yapacak şekilde rotate etmek.
const orderQuadVertices = (vertices: Point[]): Point[] => {
    if (vertices.length !== 4) return vertices.slice();
    let topLeftIdx = 0;
    let bestScore = Infinity;
    for (let i = 0; i < 4; i++) {
        const v = vertices[i]!;
        const score = v.x + v.y;
        if (score < bestScore) { bestScore = score; topLeftIdx = i; }
    }
    return [
        vertices[topLeftIdx]!,
        vertices[(topLeftIdx + 1) % 4]!,
        vertices[(topLeftIdx + 2) % 4]!,
        vertices[(topLeftIdx + 3) % 4]!,
    ];
};

// ─── Main recognizer ───────────────────────────────────────────────────────

export const recognizeShape = (points: Point[]): RecognizedShape | null => {
    if (points.length < 6) return null;

    // ── 1. Bbox ──
    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
    for (let i = 0; i < points.length; i++) {
        const p = points[i]!;
        if (p.x < minX) minX = p.x;
        if (p.y < minY) minY = p.y;
        if (p.x > maxX) maxX = p.x;
        if (p.y > maxY) maxY = p.y;
    }
    const bw = maxX - minX;
    const bh = maxY - minY;
    const cx = (minX + maxX) / 2;
    const cy = (minY + maxY) / 2;
    const diag = Math.sqrt(bw * bw + bh * bh);

    if (diag < 30) return null;

    // ── 2. Path length ──
    let length = 0;
    for (let i = 1; i < points.length; i++) {
        length += dist(points[i]!, points[i - 1]!);
    }

    // ── 3. Closure ──
    const first = points[0]!;
    const last = points[points.length - 1]!;
    const startEndDist = dist(first, last);
    // Hem mesafe yakın, hem path uzunluğu diag'ı geçmiş (geri dönen path).
    // Eşikler ölçülü gevşetildi (0.35→0.42, 1.2→1.1): elle çizilen şekiller
    // çoğunlukla tam kapanmıyordu → "kapalı değil" sayılıp freehand kalıyordu.
    const isClosed = startEndDist < diag * 0.42 && length > diag * 1.1;

    // ── 4. LINE: açık + düz (length ≈ start-end mesafesi) ──
    if (!isClosed) {
        const straightness = startEndDist / Math.max(length, 1);
        // Length ile başlangıç-son mesafesi yakınsa (oran > 0.85) düz çizgi
        if (straightness > 0.85) {
            return { type: 'line', x1: first.x, y1: first.y, x2: last.x, y2: last.y };
        }
        // Açık ama düz değilse tanıma — kullanıcının çizdiği gibi kalsın
        return null;
    }

    // ── 5. Closed shape: area + circularity ──
    const area = polygonArea(points);
    // Polsby-Popper: 4π·A / P²
    // Circle ≈ 1.0, Square ≈ 0.785, Triangle ≈ 0.604, Star ≈ 0.4, çok düşük → çizgisel
    const circularity = (4 * Math.PI * area) / (length * length);

    const aspect = bw / Math.max(bh, 1);
    const isSquareish = aspect > 0.8 && aspect < 1.25;

    // ── 6. Yüksek circularity → kesin DAİRE/ELİPS ──
    // Bu vertex sayısından önce çalışır — noisy circle'lar 6-12 vertex döndürebilir
    if (circularity > 0.82) {
        if (isSquareish) {
            const r = (bw + bh) / 4;
            return { type: 'circle', cx, cy, r };
        }
        return { type: 'ellipse', cx, cy, rx: bw / 2, ry: bh / 2 };
    }

    // ── 7. RDP simplification + vertex merging ──
    const epsilon = diag * 0.07;
    let simplified = rdpSimplify(points, epsilon);

    // Closed loop'ta son vertex genellikle ilkin yakını → çıkar
    if (simplified.length > 2) {
        if (dist(simplified[simplified.length - 1]!, simplified[0]!) < diag * 0.12) {
            simplified.pop();
        }
    }

    // RDP artifact'ları için yakın vertex'leri birleştir
    simplified = mergeCloseVertices(simplified, diag * 0.1);

    const v = simplified.length;

    // ── 8. Vertex count + circularity ile karar ──

    // 3 vertex → kesin üçgen
    if (v === 3) {
        return { type: 'triangle', vertices: simplified };
    }

    // 4 vertex → dikdörtgen/kare (axis-aligned vs rotated quad)
    if (v === 4) {
        const ordered = orderQuadVertices(simplified);
        const angles = vertexAngles(ordered);
        // Tüm köşeler ≈ 90° (±18°) → dikdörtgensel quad
        const isRectangular = angles.every((a) => Math.abs(a - Math.PI / 2) < (18 * Math.PI / 180));
        if (isRectangular) {
            // Karşılıklı kenarlar paralel & uzunluk benzer mi?
            const e0 = edgeLen(ordered[0]!, ordered[1]!);
            const e2 = edgeLen(ordered[2]!, ordered[3]!);
            const e1 = edgeLen(ordered[1]!, ordered[2]!);
            const e3 = edgeLen(ordered[3]!, ordered[0]!);
            const parallel = Math.abs(e0 - e2) / Math.max(e0, e2, 1) < 0.25
                && Math.abs(e1 - e3) / Math.max(e1, e3, 1) < 0.25;
            if (parallel) {
                // Axis-aligned mı? İlk kenar yatay/dikey ise → 'rect' bbox bazlı
                const dx = ordered[1]!.x - ordered[0]!.x;
                const dy = ordered[1]!.y - ordered[0]!.y;
                const angle = Math.atan2(dy, dx);
                const angleDeg = (angle * 180 / Math.PI + 360) % 90; // [0,90)
                const offFromAxis = Math.min(angleDeg, 90 - angleDeg);
                if (offFromAxis < 8) {
                    return { type: 'rect', minX, minY, maxX, maxY };
                }
                return { type: 'quad', vertices: ordered };
            }
        }
        // Düzgün dikdörtgen değil → bbox güvenli fallback
        return { type: 'rect', minX, minY, maxX, maxY };
    }

    // 5+ vertex: circularity ile karar
    // Orta-yüksek circularity (0.7-0.82) → daire/elips (sloppy circle)
    if (v >= 5 && circularity > 0.7) {
        if (isSquareish) {
            const r = (bw + bh) / 4;
            return { type: 'circle', cx, cy, r };
        }
        return { type: 'ellipse', cx, cy, rx: bw / 2, ry: bh / 2 };
    }

    // 5-6 vertex, düşük circularity → muhtemelen üçgen/dörtgen + noise
    if (v === 5 || v === 6) {
        // Circularity ile üçgen mi dörtgen mi karar ver
        // Triangle ≈ 0.5-0.65, Square ≈ 0.7-0.85
        if (circularity < 0.62) {
            // Üçgen: en keskin 3 vertex'i seç
            const tri = pickSharpestVertices(simplified, 3);
            return { type: 'triangle', vertices: tri };
        }
        // Dörtgen: en keskin 4 vertex (rotated rect olabilir ama bbox güvenli)
        return { type: 'rect', minX, minY, maxX, maxY };
    }

    // 7+ vertex, düşük circularity → unknown shape (yıldız, kalp vs.) — tanıma
    if (v >= 7) {
        // Yine de medium circularity varsa elips deneyebiliriz
        if (circularity > 0.55) {
            if (isSquareish) {
                const r = (bw + bh) / 4;
                return { type: 'circle', cx, cy, r };
            }
            return { type: 'ellipse', cx, cy, rx: bw / 2, ry: bh / 2 };
        }
        return null;
    }

    // 2 vertex → çizgi (kapalı sayılmıştı ama emin değiliz)
    if (v === 2) {
        return { type: 'line', x1: simplified[0]!.x, y1: simplified[0]!.y, x2: simplified[1]!.x, y2: simplified[1]!.y };
    }

    return null;
};

// Path builder native side'da (Android Canvas Path / iOS UIBezierPath) yapılır.
// JS sadece geometric data döner.
