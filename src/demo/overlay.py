"""Kreslení overlaye do snímku videa (stejný vzhled jako aplikace, OverlayView.java).

Používá Pillow kvůli české diakritice (cv2.putText háčky a čárky neumí).
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field

import numpy as np
from PIL import Image, ImageDraw, ImageFont

GREEN, ORANGE, RED = (34, 197, 94), (245, 158, 11), (239, 68, 68)
YELLOW, BLUE, WHITE = (250, 204, 21), (59, 130, 246), (255, 255, 255)
LAMP_COLORS = [GREEN, ORANGE, RED]


def _font(size: int, bold: bool = True) -> ImageFont.FreeTypeFont:
    from matplotlib import font_manager

    path = font_manager.findfont(font_manager.FontProperties(family="DejaVu Sans", weight="bold" if bold else "normal"))
    return ImageFont.truetype(path, size)


@dataclass
class FrameState:
    detections: list = field(default_factory=list)      # lamp.Det
    det_names: tuple = ("díra", "trhlina")
    pothole: tuple = (True, False)
    surface_text: str = "povrch: —"
    surface_source: str = ""
    lamp: int = 0
    reason: str = "OK"
    speed_kmh: float = math.nan
    advice: object | None = None
    map_status: str = ""
    status: str = ""
    # minimapa
    net: object | None = None
    path: object | None = None
    radii: np.ndarray | None = None
    pos: tuple[float, float] | None = None
    heading: float = math.nan
    trail: np.ndarray | None = None       # (N, 2) v lokálních souřadnicích
    lean_deg: float = 25
    radius_threshold: float = 150
    lookahead: float = 300


class OverlayRenderer:
    def __init__(self, width: int, height: int):
        self.w, self.h = width, height
        self.s = height / 1080 * 2.2          # měřítko (odpovídá ~dp na telefonu)
        s = self.s
        self.f_big, self.f_title = _font(int(40 * s)), _font(int(22 * s))
        self.f_mid, self.f_small, self.f_tiny = _font(int(15 * s)), _font(int(13 * s)), _font(int(11 * s), False)

    def _text(self, d: ImageDraw.ImageDraw, xy, text, font, fill=WHITE, anchor="la"):
        d.text(xy, text, font=font, fill=fill, anchor=anchor, stroke_width=max(1, int(self.s)), stroke_fill=(0, 0, 0))

    def draw(self, frame_rgb: np.ndarray, st: FrameState) -> np.ndarray:
        base = Image.fromarray(frame_rgb).convert("RGBA")
        layer = Image.new("RGBA", base.size, (0, 0, 0, 0))
        d = ImageDraw.Draw(layer)
        s, w, h = self.s, self.w, self.h

        # rámečky detekcí
        for det in st.detections:
            hole = det.cls < len(st.pothole) and st.pothole[det.cls]
            color = (RED if det.in_corridor() else ORANGE) if hole else YELLOW
            box = [det.x1 * w, det.y1 * h, det.x2 * w, det.y2 * h]
            d.rectangle(box, outline=color, width=max(2, int(3 * s)))
            name = st.det_names[det.cls] if det.cls < len(st.det_names) else "objekt"
            label = f"{name} {round(det.conf * 100)} %"
            tw = d.textlength(label, font=self.f_small)
            top = max(0, box[1] - 20 * s)
            d.rectangle([box[0], top, box[0] + tw + 8 * s, top + 20 * s], fill=color)
            self._text(d, (box[0] + 4 * s, top + 2 * s), label, self.f_small)

        # povrch vlevo nahoře
        tw = max(d.textlength(st.surface_text, font=self.f_title), 110 * s)
        d.rounded_rectangle([10 * s, 10 * s, 26 * s + tw, 64 * s], 10 * s, fill=(0, 0, 0, 150))
        self._text(d, (18 * s, 14 * s), st.surface_text, self.f_title)
        d.text((18 * s, 44 * s), st.surface_source, font=self.f_tiny, fill=(200, 200, 200))

        # kontrolka vpravo nahoře
        r = 34 * s
        cx, cy = w - 22 * s - r, 16 * s + r
        d.ellipse([cx - r - 6 * s, cy - r - 6 * s, cx + r + 6 * s, cy + r + 6 * s], fill=(0, 0, 0, 150))
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=LAMP_COLORS[st.lamp], outline=WHITE, width=max(1, int(2 * s)))
        self._text(d, (w - 16 * s, cy + r + 12 * s), st.reason, self.f_mid, anchor="ra")

        # panel vlevo dole
        left, top = 10 * s, h - 132 * s
        d.rounded_rectangle([left, top, left + 250 * s, h - 26 * s], 12 * s, fill=(0, 0, 0, 160))
        speed = "--" if not np.isfinite(st.speed_kmh) else str(round(st.speed_kmh))
        self._text(d, (left + 12 * s, top + 4 * s), speed, self.f_big)
        sw = d.textlength(speed, font=self.f_big)
        self._text(d, (left + 18 * s + sw, top + 28 * s), "km/h", self.f_small)
        a = st.advice
        color = WHITE
        if a is None:
            l1, l2 = st.map_status, ""
        elif a.curve is None:
            l1, l2 = "Žádná ostrá zatáčka", f"v dalších {st.lookahead:.0f} m"
        else:
            arrow = "vlevo" if a.curve.direction > 0 else "vpravo"
            l1 = (f"V zatáčce  R {a.curve.min_r:.0f} m" if a.distance_m <= 0
                  else f"Zatáčka {arrow} za {a.distance_m:.0f} m  R {a.curve.min_r:.0f} m")
            l2 = f"Doporučeno: {a.v_max_kmh:.0f} km/h"
            color = RED if a.level == 2 else ORANGE if a.level == 1 else WHITE
        self._text(d, (left + 12 * s, top + 56 * s), l1, self.f_mid)
        self._text(d, (left + 12 * s, top + 78 * s), l2, self.f_mid, fill=color)

        self._minimap(d, st)
        self._text(d, (w / 2, h - 8 * s), st.status, self.f_small, anchor="md")
        out = Image.alpha_composite(base, layer).convert("RGB")
        return np.asarray(out)

    def _minimap(self, d: ImageDraw.ImageDraw, st: FrameState):
        s, w, h = self.s, self.w, self.h
        size = min(190 * s, h * 0.45)
        left, top = w - size - 10 * s, h - size - 26 * s
        d.rounded_rectangle([left, top, left + size, top + size], 12 * s, fill=(15, 23, 42, 170))
        if st.net is None or st.pos is None:
            d.text((left + size / 2, top + size / 2), "mapa", font=self.f_small, fill=WHITE, anchor="mm")
            return
        cx, cy = left + size / 2, top + size * 0.72
        scale = size * 0.66 / st.lookahead
        heading = st.heading if np.isfinite(st.heading) else math.pi / 2
        rot = math.pi / 2 - heading
        cr, sr = math.cos(rot), math.sin(rot)
        px, py = st.pos

        def tr(x, y):
            dx, dy = np.asarray(x) - px, np.asarray(y) - py
            return cx + (dx * cr - dy * sr) * scale, cy - (dx * sr + dy * cr) * scale

        def inside(x, y):
            return left <= x <= left + size and top <= y <= top + size

        def seg(x1, y1, x2, y2, fill, width):
            if inside(x1, y1) or inside(x2, y2):
                d.line([(x1, y1), (x2, y2)], fill=fill, width=width)

        reach = st.lookahead * 1.6
        net = st.net
        for way in net.ways:
            xs, ys = net.nx[way.nodes], net.ny[way.nodes]
            if np.all(np.abs(xs - px) > reach) or np.all(np.abs(ys - py) > reach):
                continue
            sx, sy = tr(xs, ys)
            for k in range(len(sx) - 1):
                seg(sx[k], sy[k], sx[k + 1], sy[k + 1], (148, 163, 184, 140), max(1, int(2 * s)))
        if st.trail is not None and len(st.trail) > 1:
            sx, sy = tr(st.trail[:, 0], st.trail[:, 1])
            for k in range(len(sx) - 1):
                seg(sx[k], sy[k], sx[k + 1], sy[k + 1], BLUE, max(1, int(3 * s)))
        p = st.path
        if p is not None and st.radii is not None:
            from src.fusion.curves import v_max

            v = st.speed_kmh / 3.6 if np.isfinite(st.speed_kmh) else 0
            sx, sy = tr(p.x, p.y)
            for i in range(max(1, p.zero_idx), len(p.x)):
                r = abs(st.radii[i])
                color = WHITE
                if r < st.radius_threshold:
                    vm = v_max(r, st.lean_deg)
                    color = RED if v > vm else ORANGE if v > 0.8 * vm else YELLOW
                seg(sx[i - 1], sy[i - 1], sx[i], sy[i], color, max(2, int(5 * s)))
        tri = [(cx, cy - 10 * s), (cx - 7 * s, cy + 8 * s), (cx, cy + 4 * s), (cx + 7 * s, cy + 8 * s)]
        d.polygon(tri, fill=BLUE, outline=WHITE)
