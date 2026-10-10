package com.shapovalov.legalar

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = LegalReference.TABLE_NAME,
    indices = [Index(value = [LegalReference.COLUMN_CODE, LegalReference.COLUMN_ARTICLE], unique = true)]
)
data class LegalReference(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = LegalReference.COLUMN_ID)
    val id: Long = 0L,

    @ColumnInfo(name = LegalReference.COLUMN_CODE)
    val code: String,

    @ColumnInfo(name = LegalReference.COLUMN_ARTICLE)
    val article: String,

    @ColumnInfo(name = LegalReference.COLUMN_PART)
    val part: String? = null,

    @ColumnInfo(name = LegalReference.COLUMN_TITLE)
    val title: String,

    @ColumnInfo(name = LegalReference.COLUMN_SHORT_SUMMARY)
    val shortSummary: String,

    @ColumnInfo(name = LegalReference.COLUMN_FULL_TEXT)
    val fullText: String,

    @ColumnInfo(name = LegalReference.COLUMN_DEADLINE)
    val deadline: String,

    @ColumnInfo(name = LegalReference.COLUMN_RISK)
    val risk: String,

    @ColumnInfo(name = LegalReference.COLUMN_RISK_LEVEL)
    val riskLevel: Int
) {
    fun displayName(): String {
        if (part.isNullOrBlank()) {
            return code + ", ст. " + article
        }
        return code + ", ч. " + part + " ст. " + article
    }

    companion object {
        const val TABLE_NAME = "legal_references"
        const val COLUMN_ID = "id"
        const val COLUMN_CODE = "code"
        const val COLUMN_ARTICLE = "article"
        const val COLUMN_PART = "part"
        const val COLUMN_TITLE = "title"
        const val COLUMN_SHORT_SUMMARY = "shortSummary"
        const val COLUMN_FULL_TEXT = "fullText"
        const val COLUMN_DEADLINE = "deadline"
        const val COLUMN_RISK = "risk"
        const val COLUMN_RISK_LEVEL = "riskLevel"

        const val RISK_LOW = 1
        const val RISK_MEDIUM = 2
        const val RISK_HIGH = 3
    }
}
