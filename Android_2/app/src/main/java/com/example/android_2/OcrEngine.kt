package com.example.android_2

import android.content.Context
import android.graphics.*
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.util.*
import kotlin.math.max
import kotlin.math.min

class OcrEngine(private val context: Context) {
    private val env = OrtEnvironment.getEnvironment()
    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private val labelList = mutableListOf<String>()

    init {
        try {
            detSession = env.createSession(context.assets.open("det.onnx").readBytes())
            recSession = env.createSession(context.assets.open("ppocr_rec.onnx").readBytes())
            loadLabels()
        } catch (e: Exception) {
            android.util.Log.e("OcrEngine", "Model load error", e)
        }
    }

    private fun loadLabels() {
        context.assets.open("dict.txt").bufferedReader().useLines { lines ->
            labelList.add("blank")
            labelList.addAll(lines)
            labelList.add(" ")
        }
    }

    private fun extract2DArray(value: Any): Array<FloatArray>? {
        try {
            var current = value
            while (current is Array<*>) {
                if (current.isEmpty()) return null
                val first = current[0]
                if (first is FloatArray) return current as Array<FloatArray>
                current = first as Any
            }
        } catch (e: Exception) {}
        return null
    }

    private fun preprocessForNeon(bitmap: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint()
        val contrast = 2.0f
        val brightness = -100f
        val cm = ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, brightness,
            0f, contrast, 0f, 0f, brightness,
            0f, 0f, contrast, 0f, brightness,
            0f, 0f, 0f, 1f, 0f
        ))
        cm.setSaturation(1.5f)
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return out
    }

    fun runFullOcr(originalBitmap: Bitmap): Pair<Bitmap, List<MainActivity.OcrResult>> {
        val rawBoxes = detectTextActual(originalBitmap)
        val mergedBoxes = mergeRects(rawBoxes)

        val overlayBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(overlayBitmap)

        val results = mutableListOf<MainActivity.OcrResult>()
        for (box in mergedBoxes) {
            var cropped = cropBitmap(originalBitmap, box)
            if (cropped.height > cropped.width * 1.2) {
                val matrix = Matrix().apply { postRotate(90f) }
                cropped = Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, matrix, true)
            }

            val preprocessed = preprocessForNeon(cropped)
            val res = runRecognition(preprocessed)
            results.add(res)

            // 確信度に応じた枠線の色決定 (緑 -> 黄 -> 赤)
            val confidence = res.confidence
            val color = getColorForConfidence(confidence)

            val shadowPaint = Paint().apply {
                this.color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 28f
            }
            val paint = Paint().apply {
                this.color = color
                style = Paint.Style.STROKE
                strokeWidth = 20f
                strokeJoin = Paint.Join.ROUND
            }

            canvas.drawRect(box, shadowPaint)
            canvas.drawRect(box, paint)
        }
        return Pair(overlayBitmap, results)
    }

    private fun getColorForConfidence(confidence: Float): Int {
        // 1.0 (100%) -> 緑 (0, 255, 0)
        // 0.5 (50%)  -> 黄 (255, 255, 0)
        // 0.0 (0%)   -> 赤 (255, 0, 0)
        val r: Int
        val g: Int
        if (confidence > 0.5f) {
            r = (510 * (1.0f - confidence)).toInt()
            g = 255
        } else {
            r = 255
            g = (510 * confidence).toInt()
        }
        return Color.rgb(min(255, r), min(255, g), 0)
    }

    private fun mergeRects(rects: List<Rect>): List<Rect> {
        if (rects.isEmpty()) return emptyList()
        val result = mutableListOf<Rect>()
        val sorted = rects.sortedBy { it.top }
        for (rect in sorted) {
            var merged = false
            for (res in result) {
                val expanded = Rect(res).apply { inset(-50, -50) }
                if (Rect.intersects(expanded, rect)) {
                    res.union(rect)
                    merged = true
                    break
                }
            }
            if (!merged) result.add(Rect(rect))
        }
        return result.filter { it.width() > 20 && it.height() > 20 }
    }

    private fun detectTextActual(bitmap: Bitmap): List<Rect> {
        val session = detSession ?: return emptyList()
        val detSize = 640
        val resized = Bitmap.createScaledBitmap(bitmap, detSize, detSize, true)
        val imgData = FloatBuffer.allocate(1 * 3 * detSize * detSize)
        for (c in 0 until 3) {
            for (y in 0 until detSize) {
                for (x in 0 until detSize) {
                    val p = resized.getPixel(x, y)
                    val v = when(c){ 0->Color.red(p); 1->Color.green(p); else->Color.blue(p) } / 255f
                    imgData.put((v - 0.485f) / 0.229f)
                }
            }
        }
        imgData.rewind()
        val inputName = session.inputNames.iterator().next()
        val inputTensor = OnnxTensor.createTensor(env, imgData, longArrayOf(1, 3, detSize.toLong(), detSize.toLong()))
        val boxes = mutableListOf<Rect>()
        session.run(Collections.singletonMap(inputName, inputTensor)).use { results ->
            val heatMap = extract2DArray(results[0].value) ?: return emptyList()
            val threshold = 0.35f
            val step = 10
            val visited = Array(detSize) { BooleanArray(detSize) }
            for (y in 0 until detSize step step) {
                for (x in 0 until detSize step step) {
                    if (heatMap[y][x] > threshold && !visited[y][x]) {
                        var minX = x; var maxX = x; var minY = y; var maxY = y
                        for (dy in -20..20 step 5) {
                            for (dx in -50..50 step 5) {
                                val ny = y + dy; val nx = x + dx
                                if (ny in 0 until detSize && nx in 0 until detSize && heatMap[ny][nx] > threshold) {
                                    minX = min(minX, nx); maxX = max(maxX, nx)
                                    minY = min(minY, ny); maxY = max(maxY, ny)
                                    visited[ny][nx] = true
                                }
                            }
                        }
                        val scaleX = bitmap.width.toFloat() / detSize
                        val scaleY = bitmap.height.toFloat() / detSize
                        boxes.add(Rect(
                            (max(0, minX - 10) * scaleX).toInt(),
                            (max(0, minY - 5) * scaleY).toInt(),
                            (min(detSize, maxX + 10) * scaleX).toInt(),
                            (min(detSize, maxY + 5) * scaleY).toInt()
                        ))
                    }
                }
            }
        }
        return boxes
    }

    private fun cropBitmap(bitmap: Bitmap, rect: Rect): Bitmap {
        val x = max(0, rect.left); val y = max(0, rect.top)
        val w = min(rect.width(), bitmap.width - x); val h = min(rect.height(), bitmap.height - y)
        return if (w > 0 && h > 0) Bitmap.createBitmap(bitmap, x, y, w, h) else bitmap
    }

    fun runRecognition(bitmap: Bitmap): MainActivity.OcrResult {
        val session = recSession ?: return MainActivity.OcrResult("Error", 0f, 0f, 0f)
        val targetH = 48; val targetW = 320
        val resized = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        val imgData = FloatBuffer.allocate(1 * 3 * targetH * targetW)
        for (c in 0 until 3) {
            for (y in 0 until targetH) {
                for (x in 0 until targetW) {
                    val p = resized.getPixel(x, y)
                    val v = (when(c){0->Color.red(p); 1->Color.green(p); else->Color.blue(p)} / 255f)
                    imgData.put((v - 0.5f) / 0.5f)
                }
            }
        }
        imgData.rewind()
        val inputName = session.inputNames.iterator().next()
        val inputTensor = OnnxTensor.createTensor(env, imgData, longArrayOf(1, 3, targetH.toLong(), targetW.toLong()))
        session.run(Collections.singletonMap(inputName, inputTensor)).use { results ->
            val output = extract2DArray(results[0].value) ?: return MainActivity.OcrResult("FormatErr", 0f, 0f, 0f)
            return decode(output)
        }
    }

    private fun decode(probabilities: Array<FloatArray>): MainActivity.OcrResult {
        val sb = StringBuilder()
        var lastIdx = -1
        var totalScore = 0f
        var count = 0
        var maxConf = 0f
        var minConf = 1.0f

        for (probs in probabilities) {
            val maxIdx = probs.indices.maxByOrNull { probs[it] } ?: 0
            val conf = probs[maxIdx]

            if (maxIdx > 0 && maxIdx != lastIdx && maxIdx < labelList.size) {
                sb.append(labelList[maxIdx])
                totalScore += conf
                count++
                if (conf > maxConf) maxConf = conf
                if (conf < minConf) minConf = conf
            }
            lastIdx = maxIdx
        }
        val avg = if (count > 0) totalScore / count else 0f
        return MainActivity.OcrResult(sb.toString(), avg, if (count > 0) maxConf else 0f, if (count > 0) minConf else 0f)
    }

    fun close() {
        detSession?.close(); recSession?.close(); env.close()
    }
}
