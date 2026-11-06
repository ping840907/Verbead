import sys, numpy as np, onnx
from onnx import helper, numpy_helper, shape_inference
# Squeeze/Unsqueeze -> Reshape with the static output shape. onnx2tf loses track of the axis when it has
# already moved a tensor from NCHW to NHWC (seen on the tiny recognizer), a Reshape survives that.
m = shape_inference.infer_shapes(onnx.load(sys.argv[1])); g = m.graph
vi = {v.name: [d.dim_value for d in v.type.tensor_type.shape.dim] for v in list(g.value_info) + list(g.output)}
new = []; n = 0
for node in g.node:
    if node.op_type in ("Squeeze", "Unsqueeze") and node.output[0] in vi and all(vi[node.output[0]]):
        shp = vi[node.output[0]]
        name = f"sqfix{n}_shape"; n += 1
        g.initializer.append(numpy_helper.from_array(np.array(shp, np.int64), name))
        new.append(helper.make_node("Reshape", [node.input[0], name], [node.output[0]]))
    else:
        new.append(node)
del g.node[:]; g.node.extend(new); del g.value_info[:]
onnx.save(m, sys.argv[2]); print("squeeze/unsqueeze -> reshape:", n)
