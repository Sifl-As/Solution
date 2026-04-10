package com.example.android_2

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.widget.*
import kotlinx.coroutines.*

class MainActivity : Activity() {

    private lateinit var imageView: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var tvResult: TextView
    private lateinit var tvAccuracy: TextView
    
    private var imageIndex = 0
    private var imageFiles = listOf<String>()
    private var originalBitmap: Bitmap? = null
    
    private var ocrEngine: OcrEngine? = null
    private val mainScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        tvStatus = TextView(this).apply { textSize = 14f }
        root.addView(tvStatus)

        imageView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 600)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFFEEEEEE.toInt())
        }
        root.addView(imageView)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val resultContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        
        tvResult = TextView(this).apply {
            text = "認識結果: (準備中)"
            textSize = 18f
            setPadding(0, 16, 0, 8)
        }
        resultContainer.addView(tvResult)

        tvAccuracy = TextView(this).apply {
            text = "確信度: --"
            textSize = 14f
        }
        resultContainer.addView(tvAccuracy)
        
        scroll.addView(resultContainer)
        root.addView(scroll)

        val btnLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnNext = Button(this).apply {
            text = "次の画像"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { showNextImage() }
        }
        btnLayout.addView(btnNext)

        val btnRunOCR = Button(this).apply {
            text = "OCR実行"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { runOCR() }
        }
        btnLayout.addView(btnRunOCR)
        root.addView(btnLayout)

        setContentView(root)

        mainScope.launch {
            ocrEngine = withContext(Dispatchers.IO) { OcrEngine(this@MainActivity) }
            loadImageList()
            tvResult.text = "認識結果: (準備完了)"
        }
    }

    private fun loadImageList() {
        imageFiles = assets.list("Images")
            ?.filter { it.lowercase().endsWith(".jpg") || it.lowercase().endsWith(".png") }
            ?: emptyList()
        if (imageFiles.isNotEmpty()) showImage(0)
    }

    private fun showNextImage() {
        if (imageFiles.isEmpty()) return
        imageIndex = (imageIndex + 1) % imageFiles.size
        showImage(imageIndex)
    }

    private fun showImage(index: Int) {
        val fileName = imageFiles[index]
        try {
            assets.open("Images/$fileName").use { inputStream ->
                originalBitmap = BitmapFactory.decodeStream(inputStream)
                imageView.setImageBitmap(originalBitmap)
                tvStatus.text = "画像: $fileName"
                tvResult.text = "認識結果: (待機中)"
                tvAccuracy.text = "確信度: --"
            }
        } catch (e: Exception) {
            tvStatus.text = "エラー: ${e.message}"
        }
    }

    private fun runOCR() {
        val bitmap = originalBitmap ?: return
        val engine = ocrEngine ?: return
        
        tvResult.text = "解析中..."
        tvAccuracy.text = ""
        
        mainScope.launch {
            try {
                val (processedBitmap, results) = withContext(Dispatchers.Default) {
                    engine.runFullOcr(bitmap)
                }
                
                imageView.setImageBitmap(processedBitmap)
                
                if (results.isNotEmpty()) {
                    // 各結果ごとにテキストと確信度を表示
                    val displayText = results.joinToString("\n\n") { res ->
                        "${res.text}\n" +
                        "┗ 確信度: [最大] ${String.format("%.1f", res.maxConfidence * 100)}% / [最小] ${String.format("%.1f", res.minConfidence * 100)}%"
                    }
                    
                    tvResult.text = "認識結果:\n$displayText"
                    tvAccuracy.text = "検出数: ${results.size}"
                } else {
                    tvResult.text = "認識結果: 文字が見つかりませんでした"
                    tvAccuracy.text = "確信度: --"
                }
            } catch (e: Exception) {
                tvResult.text = "エラー: ${e.message}"
            }
        }
    }

    data class OcrResult(
        val text: String,
        val confidence: Float,
        val maxConfidence: Float = 0f,
        val minConfidence: Float = 0f,
        val rawLength: Int = 0
    )

    override fun onDestroy() {
        super.onDestroy()
        ocrEngine?.close()
        mainScope.cancel()
    }
}
