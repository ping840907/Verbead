import onnx, sys, numpy as np
from onnx import helper, numpy_helper
src, dst = sys.argv[1], sys.argv[2]
m = onnx.load(src); g = m.graph
new = []; k = 0
for n in g.node:
    if n.op_type != "Erf": new.append(n); continue
    z, o = n.input[0], n.output[0]; p = f"erfx{k}_"; k += 1
    g.initializer.extend([numpy_helper.from_array(np.array(1.1283792, np.float32), p+"c1"),
                          numpy_helper.from_array(np.array(0.100913, np.float32), p+"c2")])
    new += [helper.make_node("Mul",[z,z],[p+"z2"]), helper.make_node("Mul",[p+"z2",z],[p+"z3"]),
            helper.make_node("Mul",[z,p+"c1"],[p+"a"]), helper.make_node("Mul",[p+"z3",p+"c2"],[p+"b"]),
            helper.make_node("Add",[p+"a",p+"b"],[p+"s"]), helper.make_node("Tanh",[p+"s"],[o])]
del g.node[:]; g.node.extend(new); onnx.save(m, dst); print("replaced", k)
