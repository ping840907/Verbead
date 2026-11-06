"""Convert a PP-OCRv6 detector or recognizer ONNX into fixed-shape GPU-friendly TFLite files.
usage: convert_ocr.py det|rec <src.onnx> <prefix> <out_dir> <size>...
  det sizes are WxH (e.g. 640x640 480x960 960x480); rec sizes are widths (e.g. 160 320 640 1280; height is 48).
Each output is <out_dir>/<prefix>_<size>.tflite (float16 weights). Needs the scripts next to this file and onnx2tf on PATH."""
import os, shutil, subprocess, sys
PY = sys.executable
HERE = os.path.dirname(os.path.abspath(__file__))
kind, src, prefix, out, *sizes = sys.argv[1:]
os.makedirs(out, exist_ok=True)
tmp = os.path.join(out, "_tmp"); os.makedirs(tmp, exist_ok=True)
def py(script, *a): subprocess.run([PY, "-X", "utf8", "-I", os.path.join(HERE, script), *a], check=True)
noerf = os.path.join(tmp, f"{prefix}_noerf.onnx"); gpu = os.path.join(tmp, f"{prefix}_gpu.onnx")
py("noerf.py", src, noerf); py("gpufix.py", noerf, gpu)
for s in sizes:
    dims = ["1", "3", "640", "640"]
    if kind == "det":
        w, h = s.split("x"); dims = ["1", "3", h, w]
    else:
        dims = ["1", "3", "48", s]
    fixed = os.path.join(tmp, f"{prefix}_{s}_fixed.onnx"); py("fix.py", gpu, fixed, *dims)
    final = fixed
    if kind == "rec":
        folded = os.path.join(tmp, f"{prefix}_{s}_fold.onnx"); py("fold.py", fixed, folded)
        sf0 = os.path.join(tmp, f"{prefix}_{s}_sf0.onnx"); py("slicefix.py", folded, sf0)
        final = os.path.join(tmp, f"{prefix}_{s}_sq.onnx"); py("squeezefix.py", sf0, final)
    tfdir = os.path.join(tmp, f"{prefix}_{s}_tf")
    env = dict(os.environ, PYTHONIOENCODING="utf-8")
    subprocess.run([shutil.which("onnx2tf") or os.path.join(os.path.dirname(PY), "onnx2tf.exe"), "-i", final, "-o", tfdir, "-n"], check=True, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    base = os.path.splitext(os.path.basename(final))[0]
    shutil.copy(os.path.join(tfdir, f"{base}_float16.tflite"), os.path.join(out, f"{prefix}_{s}.tflite"))
    print("done", prefix, s, flush=True)
