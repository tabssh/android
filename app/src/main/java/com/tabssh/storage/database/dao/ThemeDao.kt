package io.github.tabssh.storage.database.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import io.github.tabssh.storage.database.entities.ThemeDefinition
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for terminal themes
 */
@Dao
interface ThemeDao {
    
    @Query("SELECT * FROM themes ORDER BY is_built_in DESC, name")
    fun getAllThemes(): Flow<List<ThemeDefinition>>
    
    @Query("SELECT * FROM themes ORDER BY is_built_in DESC, name")
    fun getAllThemesLiveData(): LiveData<List<ThemeDefinition>>
    
    @Query("SELECT * FROM themes WHERE themeId = :themeId")
    suspend fun getThemeById(themeId: String): ThemeDefinition?
    
    @Query("SELECT * FROM themes WHERE name = :name")
    suspend fun getThemeByName(name: String): ThemeDefinition?
    
    @Query("SELECT * FROM themes WHERE is_built_in = 1 ORDER BY name")
    suspend fun getBuiltInThemes(): List<ThemeDefinition>
    
    @Query("SELECT * FROM themes WHERE is_built_in = 0 ORDER BY name")
    suspend fun getCustomThemes(): List<ThemeDefinition>
    
    @Query("SELECT * FROM themes WHERE is_dark = :isDark ORDER BY name")
    suspend fun getThemesByDarkMode(isDark: Boolean): List<ThemeDefinition>
    
    @Query("SELECT * FROM themes ORDER BY usage_count DESC LIMIT :limit")
    suspend fun getPopularThemes(limit: Int = 5): List<ThemeDefinition>
    
    @Query("SELECT COUNT(*) FROM themes")
    suspend fun getThemeCount(): Int
    
    @Query("SELECT COUNT(*) FROM themes WHERE is_built_in = 0")
    suspend fun getCustomThemeCount(): Int
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTheme(theme: ThemeDefinition)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertThemes(themes: List<ThemeDefinition>)
    
    @Update
    suspend fun updateTheme(theme: ThemeDefinition)
    
    @Delete
    suspend fun deleteTheme(theme: ThemeDefinition)
    
    @Query("DELETE FROM themes WHERE themeId = :themeId")
    suspend fun deleteThemeById(themeId: String)
    
    @Query("DELETE FROM themes WHERE is_built_in = 0")
    suspend fun deleteAllCustomThemes()
    
    @Query("UPDATE themes SET usage_count = usage_count + 1, last_modified = :timestamp WHERE themeId = :themeId")
    suspend fun incrementUsageCount(themeId: String, timestamp: Long = System.currentTimeMillis())
    
    @Query("SELECT * FROM themes WHERE name LIKE :query OR author LIKE :query ORDER BY name")
    suspend fun searchThemes(query: String): List<ThemeDefinition>
    
    @Query("SELECT DISTINCT author FROM themes WHERE author IS NOT NULL ORDER BY author")
    suspend fun getAllAuthors(): List<String>
}