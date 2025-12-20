package io.github.tabssh.storage.database.dao

import androidx.room.*
import io.github.tabssh.storage.database.entities.Snippet
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for command snippets
 */
@Dao
interface SnippetDao {

    /**
     * Get all snippets
     */
    @Query("SELECT * FROM snippets ORDER BY sort_order ASC, name ASC")
    fun getAllSnippets(): Flow<List<Snippet>>

    /**
     * Get snippets by category
     */
    @Query("SELECT * FROM snippets WHERE category = :category ORDER BY sort_order ASC, name ASC")
    fun getSnippetsByCategory(category: String): Flow<List<Snippet>>

    /**
     * Get favorite snippets
     */
    @Query("SELECT * FROM snippets WHERE is_favorite = 1 ORDER BY usage_count DESC, name ASC")
    fun getFavoriteSnippets(): Flow<List<Snippet>>

    /**
     * Get frequently used snippets (top N by usage count)
     */
    @Query("SELECT * FROM snippets WHERE usage_count > 0 ORDER BY usage_count DESC, name ASC LIMIT :limit")
    suspend fun getFrequentlyUsedSnippets(limit: Int = 10): List<Snippet>

    /**
     * Search snippets by name, command, or tags
     */
    @Query("""
        SELECT * FROM snippets
        WHERE name LIKE '%' || :query || '%'
           OR command LIKE '%' || :query || '%'
           OR tags LIKE '%' || :query || '%'
        ORDER BY usage_count DESC, name ASC
    """)
    fun searchSnippets(query: String): Flow<List<Snippet>>

    /**
     * Get a specific snippet by ID
     */
    @Query("SELECT * FROM snippets WHERE id = :snippetId")
    suspend fun getSnippetById(snippetId: String): Snippet?

    /**
     * Insert a new snippet
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSnippet(snippet: Snippet): Long

    /**
     * Insert multiple snippets
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSnippets(snippets: List<Snippet>)

    /**
     * Update an existing snippet
     */
    @Update
    suspend fun updateSnippet(snippet: Snippet)

    /**
     * Delete a snippet
     */
    @Delete
    suspend fun deleteSnippet(snippet: Snippet)

    /**
     * Delete a snippet by ID
     */
    @Query("DELETE FROM snippets WHERE id = :snippetId")
    suspend fun deleteSnippetById(snippetId: String)

    /**
     * Increment usage count for a snippet
     */
    @Query("UPDATE snippets SET usage_count = usage_count + 1 WHERE id = :snippetId")
    suspend fun incrementUsageCount(snippetId: String)

    /**
     * Toggle favorite status
     */
    @Query("UPDATE snippets SET is_favorite = NOT is_favorite WHERE id = :snippetId")
    suspend fun toggleFavorite(snippetId: String)

    /**
     * Get all unique categories
     */
    @Query("SELECT DISTINCT category FROM snippets ORDER BY category ASC")
    suspend fun getAllCategories(): List<String>

    /**
     * Get snippet count
     */
    @Query("SELECT COUNT(*) FROM snippets")
    suspend fun getSnippetCount(): Int

    /**
     * Get snippet count by category
     */
    @Query("SELECT COUNT(*) FROM snippets WHERE category = :category")
    suspend fun getSnippetCountByCategory(category: String): Int

    /**
     * Clear all snippets (for backup/restore)
     */
    @Query("DELETE FROM snippets")
    suspend fun clearAllSnippets()
}
