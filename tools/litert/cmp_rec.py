import sys, numpy as np, onnxruntime as ort
from ai_edge_litert.interpreter import Interpreter
onnx_path, tfl, W = sys.argv[1], sys.argv[2], int(sys.argv[3])
x = np.random.RandomState(0).randn(1,3,48,W).astype(np.float32)
ref = ort.InferenceSession(onnx_path, providers=["CPUExecutionProvider"]).run(None, {"x": x})[0]
it = Interpreter(model_path=tfl); it.allocate_tensors()
i = it.get_input_details()[0]; o = it.get_output_details()[0]
print("tflite in", i["shape"], "out", o["shape"], "ref", ref.shape)
it.set_tensor(i["index"], x.transpose(0,2,3,1)); it.invoke()
out = it.get_tensor(o["index"])
print("max abs diff", float(np.abs(out-ref).max()), "mean", float(np.abs(out-ref).mean()),
      "argmax agree", float((out.argmax(-1)==ref.argmax(-1)).mean()))
ops = {}
for d in it._get_ops_details(): ops[d["op_name"]] = ops.get(d["op_name"],0)+1
print(sorted(ops.items(), key=lambda t:-t[1]))
