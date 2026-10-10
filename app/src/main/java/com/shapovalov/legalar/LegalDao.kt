package com.shapovalov.legalar

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LegalDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(references: List<LegalReference>): List<Long>

    @Query(
        "SELECT * FROM legal_references " +
            "WHERE code = :code AND article = :article AND part = :part LIMIT 1"
    )
    suspend fun findByCodeArticlePart(code: String, article: String, part: String): LegalReference?

    @Query(
        "SELECT * FROM legal_references " +
            "WHERE code = :code AND article = :article ORDER BY part LIMIT 1"
    )
    suspend fun findByCodeArticle(code: String, article: String): LegalReference?

    @Query(
        "SELECT * FROM legal_references WHERE article = :article ORDER BY code, part LIMIT 1"
    )
    suspend fun findByArticle(article: String): LegalReference?

    @Query("SELECT * FROM legal_references ORDER BY code, article, part")
    suspend fun findAll(): List<LegalReference>

    @Query("SELECT COUNT(*) FROM legal_references")
    suspend fun count(): Int
}
