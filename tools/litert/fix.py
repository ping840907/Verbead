import sys
import onnx
from onnx import shape_inference
# usage: fix.py src.onnx dst.onnx [N C H W]   (default 1 3 640 640 for the detector)
src, dst = sys.argv[1], sys.argv[2]
dims = [int(x) for x in sys.argv[3:7]] if len(sys.argv) >= 7 else [1, 3, 640, 640]
m = onnx.load(src)
d = m.graph.input[0].type.tensor_type.shape.dim
for dim, v in zip(d, dims):
    dim.ClearField("dim_param"); dim.dim_value = v
for o in list(m.graph.output) + list(m.graph.value_info):
    o.type.tensor_type.ClearField("shape")
del m.graph.value_info[:]
m = shape_inference.infer_shapes(m)
onnx.save(m, dst); print("ok", dims)
