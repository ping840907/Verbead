"""Fidelity check of the converted fixed-shape TFLite files against the original ONNX models, on identical inputs."""
import sys, numpy as np, onnxruntime as ort
from PIL import Image, ImageDraw, ImageFont
from ai_edge_litert.interpreter import Interpreter

which = sys.argv[1]                       # small | tiny
onnx_det = {"small": "pp_ocrv6_small_det.onnx", "tiny": "tiny_det.onnx"}[which]
onnx_rec = {"small": "pp_ocrv6_small_rec.onnx", "tiny": "tiny_rec.onnx"}[which]
mean = np.array([0.485, 0.456, 0.406], np.float32); std = np.array([0.229, 0.224, 0.225], np.float32)

# ---- detector: letterboxed synthetic phone pages
det = ort.InferenceSession(onnx_det, providers=["CPUExecutionProvider"])
for name in ("page_light.png", "page_dark.png"):
    page = Image.open(name).convert("RGB")
    for (W, H) in ((640, 640), (480, 960), (960, 480)):
        sc = min(W / page.width, H / page.height)
        cw, ch = max(1, round(page.width * sc)), max(1, round(page.height * sc))
        x = np.zeros((H, W, 3), np.float32)
        x[:ch, :cw] = (np.asarray(page.resize((cw, ch), Image.BILINEAR), np.float32) / 255 - mean) / std
        ref = det.run(None, {"x": x.transpose(2, 0, 1)[None]})[0][0, 0]
        it = Interpreter(model_path=f"out_ocr/{which}_det_{W}x{H}.tflite"); it.allocate_tensors()
        i, o = it.get_input_details()[0], it.get_output_details()[0]
        it.set_tensor(i["index"], x[None]); it.invoke(); out = it.get_tensor(o["index"])[0, :, :, 0]
        a, b = ref > 0.2, out > 0.2
        iou = (a & b).sum() / max(1, (a | b).sum())
        print(f"{which} det {W}x{H} {name[5:-4]:5s} max|diff|={np.abs(out-ref).max():.4f} mean|diff|={np.abs(out-ref).mean():.6f} mask IoU={iou:.4f} (ref px {a.sum()})")

# ---- recognizer: rendered strips vs variable-width ONNX, right-padded with normalized zero
texts = ["\u73e0\u7481\u8a9e\u97f3\u8f38\u5165 Verbead 2026", "https://example.com/path/file.html", "\u4eca\u5929\u5929\u6c23\u5f88\u597d\uff0c\u6211\u5011\u53bb\u516c\u5712\u6563\u6b65\u5427",
         "Hello World 12345 OCR test", "\u8a2d\u5b9a", "OK", "Battery 87%", "\u9019\u662f\u4e00\u6bb5\u6bd4\u8f03\u9577\u7684\u4e2d\u6587\u53e5\u5b50\uff0c\u7528\u4f86\u6e2c\u8a66\u8f03\u5bec\u7684\u6587\u5b57\u689d",
         "Settings and preferences 12345", "2026-11-06 12:34", "\u767b\u5165", "WiFi \u5df2\u9023\u7dda"]
font = ImageFont.truetype("C:/Windows/Fonts/kaiu.ttf", 36)
BUCKETS = [160, 320, 640, 1280] if which == "small" else [64, 96, 128, 160, 192, 224, 256, 320, 384, 448, 512, 640, 768, 896, 1024, 1280]
rec = ort.InferenceSession(onnx_rec, providers=["CPUExecutionProvider"])
nets = {b: Interpreter(model_path=f"out_ocr/{which}_rec_{b}.tflite") for b in BUCKETS}
for n in nets.values(): n.allocate_tensors()

def render(t, dark):
    bg, fg = ((20, 20, 24), (230, 230, 232)) if dark else ((250, 250, 250), (25, 25, 30))
    im = Image.new("RGB", (2400, 60), bg); ImageDraw.Draw(im).text((6, 6), t, fill=fg, font=font)
    d = np.abs(np.asarray(im, np.int16) - np.array(bg, np.int16)).sum(-1) > 60
    right = np.nonzero(d.any(0))[0].max()
    im = im.crop((0, 0, right + 8, 60)); w = int(round(im.width * 48 / 60 / 4)) * 4
    return im.resize((max(32, min(1280, w)), 48), Image.BILINEAR)

def collapse(idx):
    out, last = [], -1
    for v in idx:
        if v != 0 and v != last: out.append(int(v))
        last = v
    return out

same = tot = 0
for dark in (False, True):
    for t in texts:
        x = (np.asarray(render(t, dark), np.float32) / 255 - 0.5) / 0.5
        w = x.shape[1]; gt = collapse(rec.run(None, {"x": x.transpose(2, 0, 1)[None]})[0][0].argmax(-1))
        b = next((v for v in BUCKETS if v >= w), 1280)
        pad = np.concatenate([x, np.zeros((48, b - w, 3), np.float32)], 1) if w < b else x[:, :b]  # zero padding, as in the app
        it = nets[b]; i, o = it.get_input_details()[0], it.get_output_details()[0]
        it.set_tensor(i["index"], pad[None]); it.invoke()
        out = collapse(it.get_tensor(o["index"])[0].argmax(-1)); same += out == gt; tot += 1
print(f"{which} rec identical to variable-width ONNX: {same}/{tot}")
