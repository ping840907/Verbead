import onnx, sys, numpy as np
from onnx import helper, numpy_helper
src, dst = sys.argv[1], sys.argv[2]
m = onnx.load(src); g = m.graph
print("opsets", [(o.domain, o.version) for o in m.opset_import])
init = {i.name: i for i in g.initializer}
new = []; hs = ct = 0
def const(name, val): g.initializer.append(numpy_helper.from_array(np.array(val, np.float32), name))
for n in g.node:
    if n.op_type == "HardSigmoid":
        a = {x.name: x.f for x in n.attribute}
        alpha, beta = a.get("alpha", 0.2), a.get("beta", 0.5)
        p = f"hs{hs}_"; hs += 1
        # clip(alpha*x+beta, 0, 1) == relu6(6*alpha*x + 6*beta) / 6   (RELU6 is supported by the GPU delegate)
        const(p+"k", 6*alpha); const(p+"b", 6*beta); const(p+"lo", 0.0); const(p+"hi", 6.0); const(p+"inv6", 1/6)
        new += [helper.make_node("Mul", [n.input[0], p+"k"], [p+"m"]),
                helper.make_node("Add", [p+"m", p+"b"], [p+"a"]),
                helper.make_node("Clip", [p+"a", p+"lo", p+"hi"], [p+"c"]),
                helper.make_node("Mul", [p+"c", p+"inv6"], [n.output[0]])]
    elif n.op_type == "ConvTranspose":
        at = {x.name: (list(x.ints) if x.ints else x.i) for x in n.attribute}
        w = numpy_helper.to_array(init[n.input[1]])  # [Cin, Cout, 2, 2]
        assert at["kernel_shape"] == [2, 2] and at["strides"] == [2, 2] and at.get("pads", [0]*4) == [0]*4 and len(n.input) == 2
        assert at.get("group", 1) in (0, 1)
        cin, cout = w.shape[0], w.shape[1]
        w2 = np.zeros((4*cout, cin, 1, 1), np.float32)
        for kh in range(2):
            for kw in range(2):
                w2[(kh*2+kw)*cout:(kh*2+kw+1)*cout, :, 0, 0] = w[:, :, kh, kw].T
        p = f"ct{ct}_"; ct += 1
        g.initializer.append(numpy_helper.from_array(w2, p+"w"))
        new += [helper.make_node("Conv", [n.input[0], p+"w"], [p+"y"], kernel_shape=[1, 1]),
                helper.make_node("DepthToSpace", [p+"y"], [n.output[0]], blocksize=2, mode="DCR")]
    else:
        new.append(n)
del g.node[:]; g.node.extend(new); onnx.save(m, dst); print("hardsigmoid", hs, "convtranspose", ct)
