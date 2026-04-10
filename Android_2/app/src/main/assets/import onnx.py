import onnx

m = onnx.load("ppocr_rec.onnx")
out = m.graph.output[0]
print(out.type.tensor_type.shape)
