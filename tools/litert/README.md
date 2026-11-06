# PP-OCR → LiteRT conversion

The app ships PP-OCRv6 (Small and Tiny) as fixed-shape LiteRT models in `app/src/main/assets/ocr/`.
These scripts rebuild them from the original ONNX files of `PaddlePaddle/PP-OCRv6_{small,tiny}_{det,rec}_onnx`
(commit hashes and SHA-256 sums are in `docs/litert-feasibility.md`).

## Environment (Windows, keep the path short)

Python 3.11 venv at `C:\lt` with exactly: `tensorflow-cpu==2.15.0 numpy==1.26.4 onnx==1.16.2 onnxruntime==1.18.1
tf_keras==2.15.0 onnx2tf onnx_graphsurgeon sng4onnx psutil ai-edge-litert pillow`.
`onnxsim` and newer `onnx` conflict with TensorFlow's `ml_dtypes`; do not install them.
onnx2tf downloads a calibration `.npy` into its working directory; if the network blocks it, create a same-named file
with `numpy.save` (random `20x128x128x3` float32 is enough, it is only used for its own accuracy check).

## Run

```
python convert_ocr.py det <det.onnx> <prefix> <out_dir> 640x640 480x960 960x480
python convert_ocr.py rec <rec.onnx> <prefix> <out_dir> 160 320 640 1280                      # Small
python convert_ocr.py rec <rec.onnx> <prefix> <out_dir> 64 96 128 160 192 224 256 320 384 448 512 640 768 896 1024 1280   # Tiny
python verify_ocr.py small|tiny            # compares every output with the ONNX model (needs the page_*.png test pages)
```

Outputs are `<prefix>_<size>.tflite` (float16 weights). Name them `small_*` / `tiny_*` and copy them to the assets folder.
The dictionaries are the `inference.yml` files of the rec models, stored as `small_dict.yml` / `tiny_dict.yml`.

## What each step is for

| script | why |
|---|---|
| `noerf.py` | `Erf` (GELU) has no TFLite builtin; replaced by a tanh approximation so the model has no Flex ops |
| `gpufix.py` | `HardSigmoid` → `relu6` form (TFLite emits `RELU_0_TO_1`, unsupported on GPU); 2x2/stride-2 `ConvTranspose` → 1x1 conv + `DepthToSpace` (GPU only supports `TRANSPOSE_CONV` v3) |
| `fix.py` | fixes the dynamic input to a static shape (NPU/GPU want static shapes) |
| `fold.py` | constant-folds the Shape/Gather/Reshape chains of the recognizer with onnxruntime |
| `slicefix.py` | recognizer attention: 5-D `StridedSlice` (v4, unsupported on GPU) → 3-D slice + reshape + 4-D transpose |
| `squeezefix.py` | Squeeze/Unsqueeze → Reshape; onnx2tf loses the axis after its NCHW→NHWC change (Tiny recognizer) |

`cmp.py` / `cmp_rec.py` are the first quick checks used while developing the recipe.
