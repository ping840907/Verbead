# LiteRT investigation — outcome (branch `experiment/litert`)

## What shipped
- **PP-OCRv6 (Small and Tiny) runs on LiteRT, GPU first with CPU fallback**, replacing the Microsoft ONNX Runtime
  Java API. The recipe is in `tools/litert/README.md`; the engine is `ocr/PpOcrEngine.kt`.
- Models are downloaded from the repository's GitHub release `ocr-models-v1` (`engine/OcrModelManifest.kt` holds the
  URLs and SHA-256 sums). Staged copies of the 28 files are in `ocr-release-assets/` next to the repository
  (not tracked by git) — they have to be uploaded to that release before the in-app download works.
- Microsoft ONNX Runtime and the `alignSherpaAar` patch are gone: sherpa-onnx runs on the `libonnxruntime.so` bundled in
  its own AAR again (the unpatched `sherpa-onnx-2.0.1.aar`; its JNI and bundled ORT both use symbol version
  `VERS_1.28.2`, the patch only existed to match Microsoft's 1.28.0 build).

## Numbers (Snapdragon 8 Elite Gen 5, includes reading the result back)
| model | CPU | GPU |
|---|---|---|
| detector 640x640 | ~113 ms | ~13 ms |
| recognizer 160 / 320 / 640 / 1280 | 3.6 / 7.9 / 13.9 / 28.5 ms | 2.6 / 3.3 / 5.8 / 10.2 ms |

Whole 1080x2400 page in the app (37 lines): detection 14–42 ms, recognition of all boxes 200–900 ms.

## Fidelity of the conversion (against the original ONNX models, same inputs)
- Detector, 3 sizes x light/dark pages: mask IoU 0.997–0.999 (Small and Tiny).
- Recognizer, own 24 strips: decoded text identical in 24/24 for both models with zero ("normalized-zero") right padding.
  Edge-repeat and white padding dropped a space in one of Google's fixtures. Small is robust to a 2x gap between width
  buckets; **Tiny is not** (20/24 with 2x buckets), so it has 16 buckets.
- Google's own fixtures (`litert-community/PP-OCRv6-Small-LiteRT`): detector IoU >= 0.9987 on all five non-blank ones,
  recognizer 14/14 (Small) and 13/14 (Tiny, equal to what the Tiny ONNX model itself produces).
- On the phone, through the real engine: Small 36/38 strips identical to the ONNX text with the GPU at default precision
  **and** with explicit FP32 (Google reports 27/37 tensor cases at default precision for *their* fp32 files; not seen here).
  The two differences are the engine returning an empty string for a 2-character "OK" strip.
- The Tiny recognizer is a weaker model (Japanese kana are often misread); through the engine 26/38 strips match the Tiny
  ONNX text, the differences are not explained.

## Dropped
- **Qwen3-ASR and X-ASR on LiteRT**: no speed gain. LiteRT CPU (0.6B, int8) 620–780 ms per 5 s vs sherpa-onnx 650–880 ms with
  identical text; GPU/NPU/hybrid did not help (decoding is memory-bandwidth bound, ~23 ms/token); the SoC-specific NPU file
  is ~7x slower per token. X-ASR's streaming encoder did not convert with onnx2tf (timed out twice). Not pursued.
- NPU for PP-OCR: works with QAIRT 2.51 (QNN System >= 1.14) but is ~2x slower than the GPU for these models.
- Using Google's `PP-OCRv6-Small-LiteRT` instead of our own conversion: not adopted. It is Small only (Tiny still needs
  our pipeline), the detector exists only at 640x640 (a 1080x2400 screenshot would be shrunk to 0.27x instead of 0.4x),
  the recognizer goes up to 960 wide, the files are fp32 (77 MB vs 57 MB) and it requires explicit GPU FP32.
