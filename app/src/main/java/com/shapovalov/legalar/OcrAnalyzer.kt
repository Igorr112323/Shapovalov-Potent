package com.shapovalov.legalar

import android.graphics.RectF
import android.os.SystemClock
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class TextBlockBox(
    val text: String,
    val box: RectF
)

data class LegalMatch(
    val code: String?,
    val article: String,
    val part: String?,
    val raw: String
) {
    fun cacheKey(): String {
        val normalizedCode = code?.let { SPACES.replace(it, "").lowercase() } ?: ""
        val normalizedArticle = SPACES.replace(article, "")
        val normalizedPart = part?.let { SPACES.replace(it, "").lowercase() } ?: ""
        return normalizedCode + "|" + normalizedArticle + "|" + normalizedPart
    }

    private companion object {
        val SPACES = Regex("\\s+")
    }
}

data class RecognizedReference(
    val reference: LegalReference,
    val box: RectF,
    val sourceText: String
)

data class ScanResult(
    val blocks: List<TextBlockBox>,
    val references: List<RecognizedReference>,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val fullText: String,
    val timestamp: Long
) {
    companion object {
        fun empty(): ScanResult {
            return ScanResult(
                blocks = emptyList(),
                references = emptyList(),
                sourceWidth = 0,
                sourceHeight = 0,
                fullText = "",
                timestamp = 0L
            )
        }
    }
}

private const val DEFAULT_MIN_INTERVAL_MILLIS = 500L

private const val REGEX_FLAGS = "(?iu)"

private const val LETTER_GUARD = "(?<![А-Яа-яЁёA-Za-z])"

private const val CODE_GROUP = "(?:КоАП|ГПК|АПК|УПК|УК|ГК|НК|ТК|СК|ЖК|ЗК|ВК|ЛК)\\s*РФ"

private const val PART_GROUP = "(?:подп|п|ч|абз)\\s*\\.\\s*(\\d{1,3}(?:\\.\\d{1,3})?|[а-яё]{1,3})"

private const val ARTICLE_GROUP =
    "(\\d{1,4}(?:\\.\\d{1,3})?(?:\\s*[-–—]\\s*\\d{1,4}(?:\\.\\d{1,3})?)?)"

private const val MARKER_GROUP = "(?:ст\\s*\\.\\s*|стать[иеяю]\\s*|ст\\s+)"

class OcrAnalyzer(
    private val repository: LegalRepository,
    private val scope: CoroutineScope,
    private val onScanResult: (ScanResult) -> Unit,
    private val minIntervalMillis: Long = DEFAULT_MIN_INTERVAL_MILLIS
) : ImageAnalysis.Analyzer {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val busy = AtomicBoolean(false)

    private val lastStartMillis = AtomicLong(0L)

    private val cache = ConcurrentHashMap<String, LegalReference>()

    private val notFound = LegalReference(
        id = NOT_FOUND_ID,
        code = "",
        article = "",
        part = null,
        title = "",
        shortSummary = "",
        fullText = "",
        deadline = "",
        risk = "",
        riskLevel = LegalReference.RISK_LOW
    )

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (busy.get() || now - lastStartMillis.get() < minIntervalMillis) {
            imageProxy.close()
            return
        }
        if (!busy.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }
        lastStartMillis.set(now)

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val sourceWidth: Int
        val sourceHeight: Int
        if (rotationDegrees == QUARTER_TURN || rotationDegrees == THREE_QUARTER_TURN) {
            sourceWidth = imageProxy.height
            sourceHeight = imageProxy.width
        } else {
            sourceWidth = imageProxy.width
            sourceHeight = imageProxy.height
        }

        val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)
        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                scope.launch {
                    handleText(visionText, sourceWidth, sourceHeight)
                }
            }
            .addOnCompleteListener {
                busy.set(false)
                imageProxy.close()
            }
    }

    fun shutdown() {
        cache.clear()
        recognizer.close()
    }

    private suspend fun handleText(visionText: Text, sourceWidth: Int, sourceHeight: Int) {
        val blocks = ArrayList<TextBlockBox>(visionText.textBlocks.size)
        val references = ArrayList<RecognizedReference>()
        val usedIds = HashSet<Long>()

        for (block in visionText.textBlocks) {
            val blockBox = block.boundingBox ?: continue
            val blockRect = RectF(blockBox)
            blocks.add(TextBlockBox(block.text, blockRect))

            for (line in block.lines) {
                val lineBox = line.boundingBox ?: continue
                collectMatches(line.text, RectF(lineBox), references, usedIds)
            }

            collectMatches(block.text, blockRect, references, usedIds)
        }

        onScanResult(
            ScanResult(
                blocks = blocks,
                references = references,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                fullText = visionText.text,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    private suspend fun collectMatches(
        source: String,
        box: RectF,
        references: MutableList<RecognizedReference>,
        usedIds: MutableSet<Long>
    ) {
        for (match in findMatches(source)) {
            val reference = resolve(match) ?: continue
            if (!usedIds.add(reference.id)) {
                continue
            }
            references.add(RecognizedReference(reference, RectF(box), match.raw))
        }
    }

    private suspend fun resolve(match: LegalMatch): LegalReference? {
        val key = match.cacheKey()
        val cached = cache[key]
        if (cached != null) {
            return if (cached.id == NOT_FOUND_ID) null else cached
        }
        val resolved = repository.resolve(match.code, match.article, match.part)
        cache[key] = resolved ?: notFound
        return resolved
    }

    private fun findMatches(source: String): List<LegalMatch> {
        val text = NON_BREAKING_SPACE.replace(source, " ")
        val matches = ArrayList<LegalMatch>()
        val seen = HashSet<String>()

        for (result in ARTICLE_THEN_CODE.findAll(text)) {
            val article = result.groupOrNull(ARTICLE_GROUP_INDEX) ?: continue
            val match = LegalMatch(
                code = result.groupOrNull(CODE_AFTER_GROUP_INDEX),
                article = article,
                part = result.groupOrNull(PART_BEFORE_GROUP_INDEX),
                raw = result.value.trim()
            )
            if (seen.add(match.cacheKey())) {
                matches.add(match)
            }
        }

        for (result in CODE_THEN_ARTICLE.findAll(text)) {
            val article = result.groupOrNull(ARTICLE_AFTER_CODE_GROUP_INDEX) ?: continue
            val match = LegalMatch(
                code = result.groupOrNull(CODE_BEFORE_GROUP_INDEX),
                article = article,
                part = result.groupOrNull(PART_AFTER_GROUP_INDEX),
                raw = result.value.trim()
            )
            if (seen.add(match.cacheKey())) {
                matches.add(match)
            }
        }

        return matches
    }

    private fun MatchResult.groupOrNull(index: Int): String? {
        val value = groups[index]?.value?.trim()
        return if (value.isNullOrEmpty()) null else value
    }

    private companion object {

        const val NOT_FOUND_ID = -1L

        const val QUARTER_TURN = 90

        const val THREE_QUARTER_TURN = 270

        const val PART_BEFORE_GROUP_INDEX = 1

        const val ARTICLE_GROUP_INDEX = 2

        const val CODE_AFTER_GROUP_INDEX = 3

        const val CODE_BEFORE_GROUP_INDEX = 1

        const val ARTICLE_AFTER_CODE_GROUP_INDEX = 2

        const val PART_AFTER_GROUP_INDEX = 3

        val NON_BREAKING_SPACE = Regex("[\\u00A0\\u2007\\u202F]")

        val ARTICLE_THEN_CODE = Regex(
            REGEX_FLAGS + LETTER_GUARD +
                "(?:" + PART_GROUP + "\\s*)?" +
                MARKER_GROUP + ARTICLE_GROUP + "\\s*(" + CODE_GROUP + ")?"
        )

        val CODE_THEN_ARTICLE = Regex(
            REGEX_FLAGS + LETTER_GUARD +
                "(" + CODE_GROUP + ")[\\s,;:.]*" +
                MARKER_GROUP + ARTICLE_GROUP +
                "(?:[\\s,;:.]*" + PART_GROUP + ")?"
        )
    }
}
