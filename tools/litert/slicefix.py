import sys, numpy as np, onnx
from onnx import helper, numpy_helper, shape_inference
# Rewrite the qkv split of the SVTR attention blocks so no 5-D StridedSlice (TFLite v4, unsupported on GPU) is needed:
#   Add[1,T,3*H*D] -> Reshape[1,T,3,H,D] -> Slice(axis2) -> (Transpose(2,0,3,1,4)+Squeeze(0)) | Squeeze(2)
# becomes Slice(last axis) -> Reshape[1,T,H,D] -> Transpose(0,2,1,3) | (identity).
m = shape_inference.infer_shapes(onnx.load(sys.argv[1])); g = m.graph
init = {i.name: numpy_helper.to_array(i) for i in g.initializer}
vi = {v.name: [d.dim_value for d in v.type.tensor_type.shape.dim] for v in list(g.value_info) + list(g.input) + list(g.output)}
prod = {o: n for n in g.node for o in n.output}
cons = {}
for n in g.node:
    for i in n.input: cons.setdefault(i, []).append(n)
def attr(n, k): return next((list(a.ints) for a in n.attribute if a.name == k), None)
def i64(name, v): g.initializer.append(numpy_helper.from_array(np.array(v, np.int64), name))

remove, add, rename = set(), [], {}
k = 0
for n in list(g.node):
    if n.op_type != "Slice": continue
    shp = vi.get(n.input[0])
    if not shp or len(shp) != 5: continue
    axes = init[n.input[3]].tolist(); st = init[n.input[1]].tolist()[0]; en = init[n.input[2]].tolist()[0]
    if axes != [2] or en - st != 1: continue
    rs = prod[n.input[0]]
    assert rs.op_type == "Reshape" and vi[rs.input[0]][-1] == 3 * shp[3] * shp[4], "unexpected qkv structure"
    _, T, _, H, D = shp
    x3 = rs.input[0]; hd = H * D; p = f"qs{k}_"; k += 1
    i64(p+"s", [st*hd]); i64(p+"e", [en*hd]); i64(p+"a", [2]); i64(p+"shape", [1, T, H, D])
    r4 = p + "r4"
    add += [helper.make_node("Slice", [x3, p+"s", p+"e", p+"a"], [p+"sl"]),
            helper.make_node("Reshape", [p+"sl", p+"shape"], [r4])]
    remove.add(id(n))
    for c in cons[n.output[0]]:
        if c.op_type == "Squeeze" and attr(c, "axes") == [2]:        # k branch: already [1,T,H,D]
            rename[c.output[0]] = r4; remove.add(id(c))
        elif c.op_type == "Transpose" and attr(c, "perm") == [2, 0, 3, 1, 4]:   # q / v branch
            sq = cons[c.output[0]][0]; assert sq.op_type == "Squeeze" and attr(sq, "axes") == [0]
            add.append(helper.make_node("Transpose", [r4], [sq.output[0]], perm=[0, 2, 1, 3]))
            remove.add(id(c)); remove.add(id(sq))
        else:
            raise SystemExit(f"unhandled consumer {c.op_type}")

nodes = [x for x in g.node if id(x) not in remove] + add
for x in nodes:
    for j, i in enumerate(x.input):
        if i in rename: x.input[j] = rename[i]
# prune dead nodes (the old 5-D Reshape)
while True:
    used = {i for x in nodes for i in x.input} | {o.name for o in g.output}
    keep = [x for x in nodes if any(o in used for o in x.output)]
    if len(keep) == len(nodes): break
    nodes = keep
del g.node[:]; g.node.extend(nodes)
# topological order for safety
done = {i.name for i in g.input} | {i.name for i in g.initializer}; ordered = []; pend = list(g.node)
while pend:
    prog = False
    for x in list(pend):
        if all((i in done) or i == "" for i in x.input):
            ordered.append(x); done.update(x.output); pend.remove(x); prog = True
    if not prog:
        print("STUCK:", [(x.op_type, x.name, [i for i in x.input if i not in done and i != ""]) for x in pend][:6]); raise SystemExit(1)
del g.node[:]; g.node.extend(ordered)
del g.value_info[:]
onnx.save(m, sys.argv[2]); print("rewrote", k, "slices")
