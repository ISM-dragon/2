package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entity.FinancialAnalysisEntity
import com.example.data.local.entity.FinancingScenarioEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FinancialDao {
    @Query("SELECT * FROM financial_analyses WHERE propertyId = :propertyId LIMIT 1")
    fun getAnalysisFlow(propertyId: String): Flow<FinancialAnalysisEntity?>

    @Query("SELECT * FROM financial_analyses WHERE propertyId = :propertyId LIMIT 1")
    suspend fun getAnalysis(propertyId: String): FinancialAnalysisEntity?

    @Query("SELECT * FROM financial_analyses")
    fun getAllAnalysesFlow(): Flow<List<FinancialAnalysisEntity>>

    @Query("SELECT COUNT(*) FROM financial_analyses")
    fun getAnalysesCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM financial_analyses")
    suspend fun getAnalysesCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnalysis(analysis: FinancialAnalysisEntity)

    // Scenarios
    @Query("SELECT * FROM financing_scenarios WHERE propertyId = :propertyId")
    fun getScenariosForProperty(propertyId: String): Flow<List<FinancingScenarioEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScenarios(scenarios: List<FinancingScenarioEntity>)

    @Query("SELECT * FROM financing_scenarios WHERE propertyId = :propertyId")
    suspend fun getScenariosListForProperty(propertyId: String): List<FinancingScenarioEntity>

    @Query("SELECT * FROM financial_analyses")
    suspend fun getAllAnalysesList(): List<FinancialAnalysisEntity>

    @Query("SELECT * FROM financing_scenarios")
    suspend fun getAllScenariosList(): List<FinancingScenarioEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnalyses(analyses: List<FinancialAnalysisEntity>)

    @Query("DELETE FROM financing_scenarios WHERE propertyId = :propertyId")
    suspend fun clearScenariosForProperty(propertyId: String)
}
