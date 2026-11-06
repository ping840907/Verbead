import sys, onnxruntime as ort
# constant-fold Shape/Gather/Reshape chains so every tensor has a static shape (onnxsim is incompatible here)
so = ort.SessionOptions()
so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_BASIC
so.optimized_model_filepath = sys.argv[2]
ort.InferenceSession(sys.argv[1], so, providers=["CPUExecutionProvider"])
print("folded ->", sys.argv[2])
