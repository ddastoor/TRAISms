package com.example.domain.classifier

import android.content.Context
import com.example.data.db.SmsCategory
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale

/**
 * Layer 2 Local Bundled ML Classifier (`OfflineSmsMlClassifier`).
 * Checks `context.assets.list("models")` for `universal_en_sms_int8.tflite` + `vocab.txt`.
 * If present, runs LiteRT (`org.tensorflow.lite.Interpreter`) inference blended with regional
 * feature priors; if absent in initial build environment, seamlessly runs `EmbeddedKotlinSmsModel`.
 */
class OfflineSmsMlClassifier(context: Context) {

    private val appContext = context.applicationContext
    private val embeddedModel = EmbeddedKotlinSmsModel()
    private val lock = Any()

    @Volatile
    private var initialized = false
    private var tfliteInterpreter: Interpreter? = null
    private var vocabIndex: Map<String, Int> = emptyMap()

    data class MlClassificationResult(
        val predictedCategory: SmsCategory,
        val confidence: Float,
        val engineUsed: String
    )

    private val modelLabelOrder = listOf(
        SmsCategory.PERSONAL,
        SmsCategory.TRANSACTIONAL,
        SmsCategory.SERVICE,
        SmsCategory.OTP,
        SmsCategory.PROMOTIONAL,
        SmsCategory.GOVERNMENT
    )

    private fun ensureInitialized() {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            try {
                val modelAssets = appContext.assets.list(MODELS_DIR)?.toSet().orEmpty()
                if (VOCAB_FILENAME in modelAssets) {
                    vocabIndex = loadVocab()
                }
                if (TFLITE_MODEL_FILENAME in modelAssets && vocabIndex.isNotEmpty()) {
                    val buffer = loadModelFile("$MODELS_DIR/$TFLITE_MODEL_FILENAME")
                    val options = Interpreter.Options().apply {
                        setNumThreads(2)
                    }
                    tfliteInterpreter = Interpreter(buffer, options)
                }
            } catch (_: Throwable) {
                // Gracefully fallback to EmbeddedKotlinSmsModel if TFLite asset or native lib is unavailable
                tfliteInterpreter = null
            } finally {
                initialized = true
            }
        }
    }

    fun classify(
        senderHeader: String,
        body: String,
        region: RegionProfile
    ): MlClassificationResult {
        ensureInitialized()

        val priorPrediction = embeddedModel.predict(senderHeader, body, region)
        val interpreter = tfliteInterpreter

        if (interpreter != null) {
            val tfliteProbs = runLiteRtInference(interpreter, "$senderHeader $body")
            if (tfliteProbs != null) {
                // Blend 65% LiteRT INT8 neural probabilities + 35% regional lexicon priors
                var topCat = SmsCategory.OTHER
                var topScore = 0f
                for (i in modelLabelOrder.indices) {
                    val cat = modelLabelOrder[i]
                    val neuralProb = tfliteProbs.getOrElse(i) { 0f }
                    val regionalProb = priorPrediction.probabilities[cat] ?: 0f
                    val blended = 0.65f * neuralProb + 0.35f * regionalProb
                    if (blended > topScore) {
                        topScore = blended
                        topCat = cat
                    }
                }
                return MlClassificationResult(
                    predictedCategory = topCat,
                    confidence = topScore,
                    engineUsed = "LITERT_INT8+${region.code}"
                )
            }
        }

        return MlClassificationResult(
            predictedCategory = priorPrediction.topCategory,
            confidence = priorPrediction.topConfidence,
            engineUsed = "EMBEDDED_ML+${region.code}"
        )
    }

    private fun runLiteRtInference(interpreter: Interpreter, text: String): FloatArray? {
        return try {
            val maxSeqLen = 64
            val inputIds = IntArray(maxSeqLen) { 0 }
            val clsId = vocabIndex["[CLS]"] ?: 2
            val sepId = vocabIndex["[SEP]"] ?: 3
            val unkId = vocabIndex["[UNK]"] ?: 1

            inputIds[0] = clsId
            val words = text.lowercase(Locale.ROOT)
                .split(Regex("[^a-z0-9]+"))
                .filter { it.isNotEmpty() }

            var pos = 1
            for (word in words) {
                if (pos >= maxSeqLen - 1) break
                inputIds[pos] = vocabIndex[word] ?: unkId
                pos++
            }
            inputIds[pos] = sepId

            val inputBatch = arrayOf(inputIds)
            val outputBatch = Array(1) { FloatArray(modelLabelOrder.size) }
            synchronized(lock) {
                interpreter.run(inputBatch, outputBatch)
            }
            outputBatch[0]
        } catch (_: Throwable) {
            null
        }
    }

    private fun loadVocab(): Map<String, Int> {
        val map = HashMap<String, Int>()
        appContext.assets.open("$MODELS_DIR/$VOCAB_FILENAME").bufferedReader().useLines { lines ->
            lines.forEachIndexed { idx, token ->
                val trimmed = token.trim()
                if (trimmed.isNotEmpty()) {
                    map[trimmed] = idx
                }
            }
        }
        return map
    }

    private fun loadModelFile(assetPath: String): MappedByteBuffer {
        appContext.assets.openFd(assetPath).use { afd ->
            FileInputStream(afd.fileDescriptor).use { fis ->
                return fis.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    afd.startOffset,
                    afd.declaredLength
                )
            }
        }
    }

    companion object {
        private const val MODELS_DIR = "models"
        private const val TFLITE_MODEL_FILENAME = "universal_en_sms_int8.tflite"
        private const val VOCAB_FILENAME = "vocab.txt"
    }
}
