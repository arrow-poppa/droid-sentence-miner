"""Desktop reference of DSM's PaddleOCR ONNX pipeline (ocr/PaddleOcr.kt, ocr/PaddleDecoder.kt).

Same contract as the Kotlin port: BGR input; detector resized so both sides are multiples of 32
(longest side <= --limit), ImageNet mean/std on 0..1; DB map -> threshold -> connected components
-> axis-aligned boxes -> score filter -> unclip; recognizer crops resized to height 48, (x/255-0.5)/0.5,
CTC greedy decode with blank = 0 and ' ' as the last class. Validates the model contract on synthetic
fixtures; it does not establish Android accuracy or speed.

  python -I tools/paddle-ocr/reference.py --models ~/dsm-refs/hf --out /tmp/paddle-fixtures
"""
import argparse
import json
import math
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort
import yaml
from PIL import Image, ImageDraw, ImageFont

FONTS = {
    'latin': '/usr/share/fonts/google-noto/NotoSans-Regular.ttf',
    'cjk': '/usr/share/fonts/google-noto-sans-cjk-vf-fonts/NotoSansCJK-VF.ttc',
    'arabic': '/usr/share/fonts/google-noto-vf/NotoSansArabic[wght].ttf',
}

# (fixture id, recognizer, font, lines)
FIXTURES = [
    ('en', 'rec_tiny', 'latin', ['The quick brown fox jumps', 'over the lazy dog.']),
    ('pt', 'rec_tiny', 'latin', ['Não sei se ação é café.', 'Olá, coração!']),
    ('zh-Hans', 'rec_small', 'cjk', ['我们学习中文', '今天天气很好']),
    ('zh-Hant', 'rec_small', 'cjk', ['我們學習中文']),
    ('ja', 'rec_small', 'cjk', ['今日は学校へ行きました']),
    ('ru', 'rec_cyrillic', 'latin', ['Она говорила по-русски.']),
    ('el', 'rec_el', 'latin', ['Καλημέρα κόσμε']),
    ('ko', 'rec_korean', 'cjk', ['안녕하세요 한국어']),
    ('ar', 'rec_arabic', 'arabic', ['مرحبا بالعالم']),
]


def render(lines, font_path, size=40):
    font = ImageFont.truetype(font_path, size)
    probe = ImageDraw.Draw(Image.new('RGB', (10, 10)))
    widths = [probe.textbbox((0, 0), t, font=font)[2] for t in lines]
    image = Image.new('RGB', (max(widths) + 80, len(lines) * (size + 30) + 60), 'white')
    draw = ImageDraw.Draw(image)
    truth = []
    for i, text in enumerate(lines):
        x, y = 40, 30 + i * (size + 30)
        draw.text((x, y), text, font=font, fill='black')
        truth.append({'text': text, 'box': list(draw.textbbox((x, y), text, font=font))})
    return image, truth


def session(path, threads=2):
    options = ort.SessionOptions()
    options.intra_op_num_threads = threads
    options.log_severity_level = 3
    return ort.InferenceSession(str(path), options, providers=['CPUExecutionProvider'])


def det_input(bgr, limit):
    h, w = bgr.shape[:2]
    scale = min(1.0, limit / max(h, w))
    rh = max(32, int(round(h * scale / 32)) * 32)
    rw = max(32, int(round(w * scale / 32)) * 32)
    resized = np.asarray(Image.fromarray(bgr[:, :, ::-1]).resize((rw, rh), Image.BILINEAR))[:, :, ::-1]
    x = resized.astype(np.float32) / 255.0
    x = (x - np.array([0.485, 0.456, 0.406], np.float32)) / np.array([0.229, 0.224, 0.225], np.float32)
    return x.transpose(2, 0, 1)[None].copy(), w / rw, h / rh


def components(mask):
    """4-connected components -> list of (x0, y0, x1, y1, pixel list)."""
    h, w = mask.shape
    seen = np.zeros_like(mask, dtype=bool)
    out = []
    for y in range(h):
        for x in range(w):
            if not mask[y, x] or seen[y, x]:
                continue
            stack = [(y, x)]
            seen[y, x] = True
            pts = []
            while stack:
                cy, cx = stack.pop()
                pts.append((cy, cx))
                for ny, nx in ((cy - 1, cx), (cy + 1, cx), (cy, cx - 1), (cy, cx + 1)):
                    if 0 <= ny < h and 0 <= nx < w and mask[ny, nx] and not seen[ny, nx]:
                        seen[ny, nx] = True
                        stack.append((ny, nx))
            ys, xs = zip(*pts)
            out.append((min(xs), min(ys), max(xs) + 1, max(ys) + 1, len(pts)))
    return out


def db_boxes(prob, thresh, box_thresh, unclip_ratio, min_size=3):
    boxes = []
    for x0, y0, x1, y1, _ in components(prob > thresh):
        bw, bh = x1 - x0, y1 - y0
        if min(bw, bh) < min_size:
            continue
        score = float(prob[y0:y1, x0:x1].mean())
        if score < box_thresh:
            continue
        d = bw * bh * unclip_ratio / (2 * (bw + bh))
        boxes.append((x0 - d, y0 - d, x1 + d, y1 + d, score))
    return boxes


def rec_input(bgr_crop, height=48):
    h, w = bgr_crop.shape[:2]
    rw = max(8, int(math.ceil(height * w / h)))
    resized = np.asarray(Image.fromarray(bgr_crop[:, :, ::-1]).resize((rw, height), Image.BILINEAR))[:, :, ::-1]
    x = (resized.astype(np.float32) / 255.0 - 0.5) / 0.5
    return x.transpose(2, 0, 1)[None].copy()


def ctc(probs, chars):
    out, previous, confidences = [], -1, []
    for t in range(probs.shape[0]):
        index = int(probs[t].argmax())
        if index != previous and index != 0:
            out.append(chars[index])
            confidences.append(float(probs[t, index]))
        previous = index
    return ''.join(out), (float(np.mean(confidences)) if confidences else 0.0)


def restore_arabic(text):
    groups, latin = [], ''
    for ch in text:
        if ch.isascii() and (ch.isalnum() or ch in ' :*./%+-'):
            latin += ch
        else:
            if latin:
                groups.append(latin)
                latin = ''
            groups.append(ch)
    if latin:
        groups.append(latin)
    return ''.join(reversed(groups))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--models', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--det', default='det_tiny')
    parser.add_argument('--limit', type=int, default=960)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    det = session(args.models / args.det / 'inference.onnx')
    report = []
    for fid, rec_name, font, lines in FIXTURES:
        image, truth = render(lines, FONTS[font])
        image.save(args.out / f'{fid}.png')
        bgr = np.asarray(image)[:, :, ::-1]
        started = time.perf_counter()
        x, sx, sy = det_input(bgr, args.limit)
        prob = det.run(None, {'x': x})[0][0, 0]
        det_ms = (time.perf_counter() - started) * 1000
        boxes = sorted(db_boxes(prob, 0.3, 0.6, 1.5), key=lambda b: (b[1], b[0]))
        cfg = yaml.safe_load((args.models / rec_name / 'inference.yml').read_text())
        chars = [''] + cfg['PostProcess']['character_dict'] + [' ']
        rec = session(args.models / rec_name / 'inference.onnx')
        found = []
        rec_started = time.perf_counter()
        for x0, y0, x1, y1, score in boxes:
            l, t = max(0, int(x0 * sx)), max(0, int(y0 * sy))
            r, b = min(bgr.shape[1], int(math.ceil(x1 * sx))), min(bgr.shape[0], int(math.ceil(y1 * sy)))
            text, conf = ctc(rec.run(None, {'x': rec_input(bgr[t:b, l:r])})[0][0], chars)
            if rec_name == 'rec_arabic':
                text = restore_arabic(text)
            found.append({'text': text, 'box': [l, t, r, b], 'confidence': round(conf, 3), 'detScore': round(score, 3)})
        rec_ms = (time.perf_counter() - rec_started) * 1000
        exact = sum(1 for g in truth if any(f['text'].strip() == g['text'] for f in found))
        report.append({'fixture': fid, 'recognizer': rec_name, 'truth': truth, 'found': found,
                       'exactLines': f'{exact}/{len(truth)}', 'detMs': round(det_ms, 1), 'recMs': round(rec_ms, 1)})
        print(f"{fid:8} {rec_name:12} {exact}/{len(truth)} det {det_ms:6.1f} ms rec {rec_ms:6.1f} ms  ", ' | '.join(f['text'] for f in found))
    (args.out / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=1))


if __name__ == '__main__':
    main()
