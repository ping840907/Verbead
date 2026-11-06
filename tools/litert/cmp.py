import numpy as np, onnxruntime as ort
from ai_edge_litert.interpreter import Interpreter
x = np.random.RandomState(0).rand(1,3,640,640).astype(np.float32)
s = ort.InferenceSession("pp_ocrv6_small_det.onnx", providers=["CPUExecutionProvider"])
ref = s.run(None, {"x": x})[0]
for f in ["det4_tf/det_gpu_fixed_float32.tflite"]:
    it = Interpreter(model_path=f); it.allocate_tensors()
    i = it.get_input_details()[0]; o = it.get_output_details()[0]
    print("tflite in", i["shape"], "out", o["shape"], "ref", ref.shape)
    xi = x if i["shape"][1]==3 else x.transpose(0,2,3,1)
    it.set_tensor(i["index"], xi); it.invoke()
    out = it.get_tensor(o["index"])
    if out.shape != ref.shape: out = out.transpose(0,3,1,2)
    print("max abs diff", float(np.abs(out-ref).max()), "mean", float(np.abs(out-ref).mean()))
    ops = {}
    for d in it._get_ops_details(): ops[d["op_name"]] = ops.get(d["op_name"],0)+1
    print(sorted(ops.items(), key=lambda t:-t[1]))
