package com.shapovalov.legalar

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LegalRepository(
    private val legalDao: LegalDao,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    suspend fun loadCatalog(): List<LegalReference> {
        return withContext(dispatcher) {
            if (legalDao.count() == 0) {
                legalDao.insertAll(SeedCatalog.references())
            }
            legalDao.findAll()
        }
    }

    suspend fun resolve(code: String?, article: String, part: String?): LegalReference? {
        return withContext(dispatcher) {
            val normalizedArticle = normalizeArticle(article)
            if (normalizedArticle.isEmpty()) {
                return@withContext null
            }
            val normalizedCode = normalizeCode(code)
            val normalizedPart = normalizePart(part)
            for (candidate in articleCandidates(normalizedArticle)) {
                val reference = lookup(normalizedCode, candidate, normalizedPart)
                if (reference != null) {
                    return@withContext reference
                }
            }
            null
        }
    }

    private suspend fun lookup(
        code: String?,
        article: String,
        part: String?
    ): LegalReference? {
        if (code != null) {
            if (part != null) {
                val exact = legalDao.findByCodeArticlePart(code, article, part)
                if (exact != null) {
                    return exact
                }
            }
            return legalDao.findByCodeArticle(code, article)
        }
        return legalDao.findByArticle(article)
    }

    private fun articleCandidates(article: String): List<String> {
        val candidates = ArrayList<String>()
        candidates.add(article)
        if (article.contains('-')) {
            for (piece in article.split('-')) {
                val trimmed = piece.trim()
                if (trimmed.isNotEmpty() && !candidates.contains(trimmed)) {
                    candidates.add(trimmed)
                }
            }
        }
        return candidates
    }

    private fun normalizeCode(code: String?): String? {
        if (code == null) {
            return null
        }
        val compact = WHITESPACE.replace(code, " ").trim()
        if (compact.isEmpty()) {
            return null
        }
        return when (compact.uppercase()) {
            "ГК РФ" -> "ГК РФ"
            "УК РФ" -> "УК РФ"
            "КОАП РФ" -> "КоАП РФ"
            "ГПК РФ" -> "ГПК РФ"
            "АПК РФ" -> "АПК РФ"
            "УПК РФ" -> "УПК РФ"
            "НК РФ" -> "НК РФ"
            "ТК РФ" -> "ТК РФ"
            "СК РФ" -> "СК РФ"
            "ЖК РФ" -> "ЖК РФ"
            "ЗК РФ" -> "ЗК РФ"
            "ВК РФ" -> "ВК РФ"
            "ЛК РФ" -> "ЛК РФ"
            else -> compact
        }
    }

    private fun normalizeArticle(article: String): String {
        val compact = WHITESPACE.replace(article, "")
        val unified = compact.replace('–', '-').replace('—', '-').replace('−', '-')
        return unified.trim('.')
    }

    private fun normalizePart(part: String?): String? {
        if (part == null) {
            return null
        }
        val trimmed = part.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        val digits = NON_DIGIT.replace(trimmed, "")
        return if (digits.isNotEmpty()) digits else trimmed.lowercase()
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
        val NON_DIGIT = Regex("[^0-9.]")
    }
}
