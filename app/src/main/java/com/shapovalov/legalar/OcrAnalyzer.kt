package com.shapovalov.legalar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.googlecode.tesseract.android.ResultIterator
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

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

private const val DEFAULT_MIN_INTERVAL_MILLIS = 700L

private const val REGEX_FLAGS = "(?iu)"

private const val LETTER_GUARD = "(?<![А-Яа-яЁёA-Za-z])"

private const val CODE_GROUP = "(?:КоАП|ГПК|АПК|УПК|УК|ГК|НК|ТК|СК|ЖК|ЗК|ВК|ЛК)\\s*РФ"

private const val PART_GROUP = "(?:подп|п|ч|абз)\\s*\\.\\s*(\\d{1,3}(?:\\.\\d{1,3})?|[а-яё]{1,3})"

private const val ARTICLE_GROUP =
    "(\\d{1,4}(?:\\.\\d{1,3})?(?:\\s*[-–—]\\s*\\d{1,4}(?:\\.\\d{1,3})?)?)"

private const val MARKER_GROUP = "(?:ст\\s*\\.\\s*|стать[иеяю]\\s*|ст\\s+)"

class OcrAnalyzer(
    private val context: Context,
    private val repository: LegalRepository,
    private val scope: CoroutineScope,
    private val onScanResult: (ScanResult) -> Unit,
    private val minIntervalMillis: Long = DEFAULT_MIN_INTERVAL_MILLIS
) : ImageAnalysis.Analyzer {

    private val busy = AtomicBoolean(false)

    private val lastStartMillis = AtomicLong(0L)

    private val cache = ConcurrentHashMap<String, LegalReference>()

    private val engineLock = ReentrantLock()

    @Volatile
    private var tessBaseApi: TessBaseAPI? = null

    @Volatile
    private var initFailed = false

    private var initAttempts = 0

    @Volatile
    private var released = false

    private val preprocessPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = buildColorFilter()
    }

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
        val now = SystemClock.elapsedRealtime()
        if (released || initFailed || busy.get() || now - lastStartMillis.get() < minIntervalMillis) {
            imageProxy.close()
            return
        }
        if (!busy.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }
        lastStartMillis.set(now)

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val captured = try {
            imageProxy.toBitmap()
        } catch (error: RuntimeException) {
            Log.e(TAG, "Frame conversion failed", error)
            null
        } finally {
            imageProxy.close()
        }
        if (captured == null) {
            busy.set(false)
            return
        }

        val prepared = prepare(captured, rotationDegrees)
        if (prepared !== captured) {
            captured.recycle()
        }

        val sourceWidth = prepared.width
        val sourceHeight = prepared.height
        val frameData = recognizeFrame(prepared)
        prepared.recycle()
        busy.set(false)
        releaseEngineIfReleased()

        if (frameData == null) {
            return
        }
        scope.launch {
            publish(frameData, sourceWidth, sourceHeight)
        }
    }

    fun shutdown() {
        released = true
        cache.clear()
        val locked = try {
            engineLock.tryLock(SHUTDOWN_LOCK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!locked) {
            return
        }
        try {
            recycleEngine()
        } finally {
            engineLock.unlock()
        }
    }

    private fun recognizeFrame(bitmap: Bitmap): FrameData? {
        engineLock.lock()
        try {
            val api = ensureInitialized() ?: return null
            return recognize(api, bitmap)
        } catch (error: Throwable) {
            Log.e(TAG, "Recognition failed", error)
            return null
        } finally {
            engineLock.unlock()
        }
    }

    private fun releaseEngineIfReleased() {
        if (!released) {
            return
        }
        engineLock.lock()
        try {
            recycleEngine()
        } finally {
            engineLock.unlock()
        }
    }

    private fun recycleEngine() {
        val api = tessBaseApi
        tessBaseApi = null
        api?.recycle()
    }

    private fun recognize(api: TessBaseAPI, bitmap: Bitmap): FrameData {
        api.setImage(bitmap)
        val fullText = api.getUTF8Text() ?: ""
        val blocks = collectText(api, TessBaseAPI.PageIteratorLevel.RIL_BLOCK)
        val lines = collectText(api, TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
        api.clear()
        return FrameData(fullText, blocks, collectRegions(lines, blocks))
    }

    private fun collectText(api: TessBaseAPI, level: Int): List<TextBlockBox> {
        val iterator: ResultIterator = api.getResultIterator() ?: return emptyList()
        val result = ArrayList<TextBlockBox>()
        try {
            var hasNext = true
            iterator.begin()
            while (hasNext) {
                val text = iterator.getUTF8Text(level)
                val rect = iterator.getBoundingRect(level)
                if (text != null && text.isNotBlank() && !rect.isEmpty) {
                    result.add(TextBlockBox(text.trim(), RectF(rect)))
                }
                hasNext = iterator.next(level)
            }
        } finally {
            iterator.delete()
        }
        return result
    }

    private fun collectRegions(
        lines: List<TextBlockBox>,
        blocks: List<TextBlockBox>
    ): List<MatchedRegion> {
        val regions = ArrayList<MatchedRegion>()
        val seen = HashSet<String>()
        for (line in lines) {
            addMatches(line.text, line.box, regions, seen)
        }
        for (block in blocks) {
            addMatches(block.text, block.box, regions, seen)
        }
        return regions
    }

    private fun addMatches(
        source: String,
        box: RectF,
        regions: MutableList<MatchedRegion>,
        seen: MutableSet<String>
    ) {
        for (match in findMatches(source)) {
            if (seen.add(match.cacheKey())) {
                regions.add(MatchedRegion(match, RectF(box)))
            }
        }
    }

    private suspend fun publish(frameData: FrameData, sourceWidth: Int, sourceHeight: Int) {
        val references = ArrayList<RecognizedReference>()
        val usedIds = HashSet<Long>()
        for (region in frameData.regions) {
            val reference = resolve(region.match) ?: continue
            if (!usedIds.add(reference.id)) {
                continue
            }
            references.add(RecognizedReference(reference, region.box, region.match.raw))
        }
        onScanResult(
            ScanResult(
                blocks = frameData.blocks,
                references = references,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                fullText = frameData.fullText,
                timestamp = System.currentTimeMillis()
            )
        )
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

    private fun ensureInitialized(): TessBaseAPI? {
        if (released) {
            return null
        }
        val existing = tessBaseApi
        if (existing != null) {
            return existing
        }
        if (initFailed) {
            return null
        }
        if (initAttempts >= MAX_INIT_ATTEMPTS) {
            initFailed = true
            Log.e(TAG, "TessBaseAPI init abandoned after " + MAX_INIT_ATTEMPTS + " attempts")
            return null
        }
        initAttempts++
        if (!prepareLanguageData(File(context.filesDir, TESSDATA_DIR_NAME))) {
            return null
        }
        val api = TessBaseAPI()
        if (!api.init(context.filesDir.absolutePath, LANGUAGE_CODE)) {
            api.recycle()
            Log.e(TAG, "TessBaseAPI init failed for language " + LANGUAGE_CODE)
            return null
        }
        api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
        api.setVariable(VAR_PRESERVE_INTERWORD_SPACES, VAR_VALUE_TRUE)
        tessBaseApi = api
        return api
    }

    private fun prepareLanguageData(targetDir: File): Boolean {
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            Log.e(TAG, "Cannot create directory " + targetDir.absolutePath)
            return false
        }
        val targetFile = File(targetDir, LANGUAGE_DATA_FILE)
        if (targetFile.exists() && targetFile.length() > 0L) {
            return true
        }
        return try {
            context.assets.open(ASSET_LANGUAGE_DATA_PATH).use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output, COPY_BUFFER_SIZE)
                    output.flush()
                }
            }
            targetFile.length() > 0L
        } catch (error: IOException) {
            targetFile.delete()
            Log.e(TAG, "Cannot copy " + ASSET_LANGUAGE_DATA_PATH, error)
            false
        }
    }

    private fun prepare(source: Bitmap, degrees: Int): Bitmap {
        val quarterTurn = degrees == QUARTER_TURN || degrees == THREE_QUARTER_TURN
        val targetWidth = if (quarterTurn) source.height else source.width
        val targetHeight = if (quarterTurn) source.width else source.height
        val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val matrix = Matrix()
        matrix.postRotate(degrees.toFloat(), source.width / 2f, source.height / 2f)
        matrix.postTranslate(
            (targetWidth - source.width) / 2f,
            (targetHeight - source.height) / 2f
        )
        canvas.drawBitmap(source, matrix, preprocessPaint)
        return output
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

    private class MatchedRegion(
        val match: LegalMatch,
        val box: RectF
    )

    private class FrameData(
        val fullText: String,
        val blocks: List<TextBlockBox>,
        val regions: List<MatchedRegion>
    )

    private companion object {

        const val TAG = "OcrAnalyzer"

        const val LANGUAGE_CODE = "rus"

        const val LANGUAGE_DATA_FILE = "rus.traineddata"

        const val ASSET_LANGUAGE_DATA_PATH = "tessdata/rus.traineddata"

        const val TESSDATA_DIR_NAME = "tessdata"

        const val COPY_BUFFER_SIZE = 8192

        const val VAR_PRESERVE_INTERWORD_SPACES = "preserve_interword_spaces"

        const val VAR_VALUE_TRUE = "1"

        const val GRAYSCALE_SATURATION = 0f

        const val CONTRAST = 1.25f

        const val CONTRAST_ANCHOR = 128f

        const val MAX_INIT_ATTEMPTS = 3

        const val QUARTER_TURN = 90

        const val THREE_QUARTER_TURN = 270

        const val SHUTDOWN_LOCK_TIMEOUT_MILLIS = 100L

        const val NOT_FOUND_ID = -1L

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

        fun buildColorFilter(): ColorMatrixColorFilter {
            val grayscale = ColorMatrix()
            grayscale.setSaturation(GRAYSCALE_SATURATION)
            val offset = CONTRAST_ANCHOR * (1f - CONTRAST)
            val contrast = ColorMatrix(
                floatArrayOf(
                    CONTRAST, 0f, 0f, 0f, offset,
                    0f, CONTRAST, 0f, 0f, offset,
                    0f, 0f, CONTRAST, 0f, offset,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            grayscale.postConcat(contrast)
            return ColorMatrixColorFilter(grayscale)
        }
    }
}
