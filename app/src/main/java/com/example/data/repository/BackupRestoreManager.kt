package com.example.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.example.data.local.AppDatabase
import com.example.data.local.entity.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class BackupRestoreManager(
    private val database: AppDatabase
) {
    suspend fun exportBackup(context: Context): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val propertyDao = database.propertyDao()
            val financialDao = database.financialDao()
            val offerDao = database.offerDao()
            val automationDao = database.automationDao()
            val configDao = database.configDao()

            val root = JSONObject().apply {
                put("app", "Real Estate AI APK")
                put("version", 1)
                put("exportedAt", System.currentTimeMillis())

                // 1. Properties
                val propArray = JSONArray()
                for (p in propertyDao.getAllPropertiesList()) {
                    val pObj = JSONObject().apply {
                        put("id", p.id)
                        put("sourceType", p.sourceType)
                        put("title", p.title)
                        put("address", p.address)
                        put("city", p.city)
                        put("state", p.state)
                        put("zipCode", p.zipCode)
                        put("latitude", p.latitude)
                        put("longitude", p.longitude)
                        put("price", p.price)
                        put("propertyType", p.propertyType)
                        put("bedrooms", p.bedrooms)
                        put("bathrooms", p.bathrooms)
                        put("squareFeet", p.squareFeet)
                        put("yearBuilt", p.yearBuilt)
                        put("lotSizeSqFt", p.lotSizeSqFt)
                        put("description", p.description)
                        put("status", p.status)
                        put("primaryImageUrl", p.primaryImageUrl)
                        put("scannedAt", p.scannedAt)
                        put("isSaved", p.isSaved)
                        put("isSavedDeal", p.isSavedDeal)
                        put("dealScore", p.dealScore)
                    }
                    propArray.put(pObj)
                }
                put("properties", propArray)

                // 2. Financial Analyses
                val finArray = JSONArray()
                for (f in financialDao.getAllAnalysesList()) {
                    val fObj = JSONObject().apply {
                        put("propertyId", f.propertyId)
                        put("purchasePrice", f.purchasePrice)
                        put("closingCosts", f.closingCosts)
                        put("renovationCost", f.renovationCost)
                        put("monthlyRent", f.monthlyRent)
                        put("otherMonthlyIncome", f.otherMonthlyIncome)
                        put("grossRentalIncome", f.grossRentalIncome)
                        put("vacancyRatePct", f.vacancyRatePct)
                        put("effectiveRentalIncome", f.effectiveRentalIncome)
                        put("propertyTaxAnnual", f.propertyTaxAnnual)
                        put("insuranceAnnual", f.insuranceAnnual)
                        put("maintenancePct", f.maintenancePct)
                        put("managementPct", f.managementPct)
                        put("utilitiesMonthly", f.utilitiesMonthly)
                        put("operatingExpensesMonthly", f.operatingExpensesMonthly)
                        put("noiAnnual", f.noiAnnual)
                        put("downPaymentPct", f.downPaymentPct)
                        put("interestRatePct", f.interestRatePct)
                        put("loanTermYears", f.loanTermYears)
                        put("monthlyDebtService", f.monthlyDebtService)
                        put("monthlyCashFlow", f.monthlyCashFlow)
                        put("annualCashFlow", f.annualCashFlow)
                        put("totalCashRequired", f.totalCashRequired)
                        put("capRate", f.capRate)
                        put("cashOnCashReturn", f.cashOnCashReturn)
                        put("dscr", f.dscr)
                        put("breakEvenOccupancyPct", f.breakEvenOccupancyPct)
                        put("calculatedAt", f.calculatedAt)
                        put("isQualified", f.isQualified)
                        put("dealScore", f.dealScore)
                        put("qualificationSummary", f.qualificationSummary)
                    }
                    finArray.put(fObj)
                }
                put("financial_analyses", finArray)

                // 3. Offers
                val offerArray = JSONArray()
                for (o in offerDao.getAllOffersList()) {
                    val oObj = JSONObject().apply {
                        put("id", o.id)
                        put("propertyId", o.propertyId)
                        put("recipientName", o.recipientName)
                        put("recipientEmail", o.recipientEmail)
                        put("offerPrice", o.offerPrice)
                        put("earnestMoney", o.earnestMoney)
                        put("inspectionPeriodDays", o.inspectionPeriodDays)
                        put("closingPeriodDays", o.closingPeriodDays)
                        put("contingencies", o.contingencies)
                        put("terms", o.terms)
                        put("conditions", o.conditions)
                        put("expirationDate", o.expirationDate)
                        put("generatedLetterContent", o.generatedLetterContent)
                        put("pdfPath", o.pdfPath ?: "")
                        put("status", o.status)
                        put("createdAt", o.createdAt)
                        put("sentAt", o.sentAt ?: 0L)
                        put("lastError", o.lastError ?: "")
                    }
                    offerArray.put(oObj)
                }
                put("offers", offerArray)

                // 4. Offer Templates
                val template = configDao.getOfferTemplate()
                if (template != null) {
                    put("offer_template", JSONObject().apply {
                        put("id", template.id)
                        put("templateName", template.templateName)
                        put("headerTitle", template.headerTitle)
                        put("earnestMoneyPercent", template.earnestMoneyPercent)
                        put("defaultInspectionDays", template.defaultInspectionDays)
                        put("defaultClosingDays", template.defaultClosingDays)
                        put("standardTerms", template.standardTerms)
                        put("standardConditions", template.standardConditions)
                    })
                }

                // 5. Automation Rules
                val rules = automationDao.getRules()
                if (rules != null) {
                    put("automation_rules", JSONObject().apply {
                        put("maxPurchasePrice", rules.maxPurchasePrice)
                        put("minCashFlow", rules.minCashFlow)
                        put("minCapRate", rules.minCapRate)
                        put("minDscr", rules.minDscr)
                        put("minCashOnCash", rules.minCashOnCash)
                        put("allowedLocations", rules.allowedLocations)
                        put("allowedPropertyTypes", rules.allowedPropertyTypes)
                        put("maxRenovationCost", rules.maxRenovationCost)
                        put("minEstimatedRent", rules.minEstimatedRent)
                        put("maxRiskScore", rules.maxRiskScore)
                        put("offerDiscountPercent", rules.offerDiscountPercent)
                        put("scanIntervalMinutes", rules.scanIntervalMinutes)
                    })
                }
            }

            val backupsDir = File(context.filesDir, "backups").apply { mkdirs() }
            val backupFile = File(backupsDir, "real_estate_ai_backup_${System.currentTimeMillis()}.json")
            backupFile.writeText(root.toString(2))

            Pair(true, "Backup successfully exported to: ${backupFile.name} (${root.getJSONArray("properties").length()} properties, ${root.getJSONArray("offers").length()} offers)")
        } catch (e: Exception) {
            Pair(false, "Export failed: ${e.message}")
        }
    }

    suspend fun restoreBackup(context: Context, jsonString: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject(jsonString)
            if (!root.has("app") || !root.getString("app").contains("Real Estate AI")) {
                return@withContext Pair(false, "Invalid backup file: Missing app identity header")
            }

            database.withTransaction {
                val propertyDao = database.propertyDao()
                val financialDao = database.financialDao()
                val offerDao = database.offerDao()
                val automationDao = database.automationDao()
                val configDao = database.configDao()

                // Restore Properties
                if (root.has("properties")) {
                    val pArray = root.getJSONArray("properties")
                    val propertiesList = mutableListOf<PropertyEntity>()
                    for (i in 0 until pArray.length()) {
                        val obj = pArray.getJSONObject(i)
                        propertiesList.add(
                            PropertyEntity(
                                id = obj.getString("id"),
                                sourceType = obj.optString("sourceType", "ON_MARKET"),
                                title = obj.optString("title", ""),
                                address = obj.getString("address"),
                                city = obj.getString("city"),
                                state = obj.getString("state"),
                                zipCode = obj.getString("zipCode"),
                                latitude = obj.optDouble("latitude", 30.26),
                                longitude = obj.optDouble("longitude", -97.74),
                                price = obj.getDouble("price"),
                                propertyType = obj.optString("propertyType", "Single Family"),
                                bedrooms = obj.optInt("bedrooms", 3),
                                bathrooms = obj.optDouble("bathrooms", 2.0),
                                squareFeet = obj.optInt("squareFeet", 1500),
                                yearBuilt = obj.optInt("yearBuilt", 2010),
                                lotSizeSqFt = obj.optInt("lotSizeSqFt", 5000),
                                description = obj.optString("description", ""),
                                status = obj.optString("status", "Active"),
                                primaryImageUrl = obj.optString("primaryImageUrl", ""),
                                scannedAt = obj.optLong("scannedAt", System.currentTimeMillis()),
                                isSaved = obj.optBoolean("isSaved", false),
                                isSavedDeal = obj.optBoolean("isSavedDeal", false),
                                dealScore = obj.optInt("dealScore", 0)
                            )
                        )
                    }
                    if (propertiesList.isNotEmpty()) {
                        propertyDao.insertProperties(propertiesList)
                    }
                }

                // Restore Financial Analyses
                if (root.has("financial_analyses")) {
                    val fArray = root.getJSONArray("financial_analyses")
                    val finList = mutableListOf<FinancialAnalysisEntity>()
                    for (i in 0 until fArray.length()) {
                        val obj = fArray.getJSONObject(i)
                        finList.add(
                            FinancialAnalysisEntity(
                                propertyId = obj.getString("propertyId"),
                                purchasePrice = obj.getDouble("purchasePrice"),
                                closingCosts = obj.optDouble("closingCosts", 0.0),
                                renovationCost = obj.optDouble("renovationCost", 0.0),
                                monthlyRent = obj.getDouble("monthlyRent"),
                                otherMonthlyIncome = obj.optDouble("otherMonthlyIncome", 0.0),
                                grossRentalIncome = obj.optDouble("grossRentalIncome", 0.0),
                                vacancyRatePct = obj.optDouble("vacancyRatePct", 5.0),
                                effectiveRentalIncome = obj.optDouble("effectiveRentalIncome", 0.0),
                                propertyTaxAnnual = obj.optDouble("propertyTaxAnnual", 0.0),
                                insuranceAnnual = obj.optDouble("insuranceAnnual", 0.0),
                                maintenancePct = obj.optDouble("maintenancePct", 5.0),
                                managementPct = obj.optDouble("managementPct", 8.0),
                                utilitiesMonthly = obj.optDouble("utilitiesMonthly", 0.0),
                                operatingExpensesMonthly = obj.optDouble("operatingExpensesMonthly", 0.0),
                                noiAnnual = obj.optDouble("noiAnnual", 0.0),
                                downPaymentPct = obj.optDouble("downPaymentPct", 20.0),
                                interestRatePct = obj.optDouble("interestRatePct", 6.8),
                                loanTermYears = obj.optInt("loanTermYears", 30),
                                monthlyDebtService = obj.optDouble("monthlyDebtService", 0.0),
                                monthlyCashFlow = obj.optDouble("monthlyCashFlow", 0.0),
                                annualCashFlow = obj.optDouble("annualCashFlow", 0.0),
                                totalCashRequired = obj.optDouble("totalCashRequired", 0.0),
                                capRate = obj.optDouble("capRate", 0.0),
                                cashOnCashReturn = obj.optDouble("cashOnCashReturn", 0.0),
                                dscr = obj.optDouble("dscr", 1.0),
                                breakEvenOccupancyPct = obj.optDouble("breakEvenOccupancyPct", 0.0),
                                calculatedAt = obj.optLong("calculatedAt", System.currentTimeMillis()),
                                isQualified = obj.optBoolean("isQualified", false),
                                dealScore = obj.optInt("dealScore", 0),
                                qualificationSummary = obj.optString("qualificationSummary", "")
                            )
                        )
                    }
                    if (finList.isNotEmpty()) {
                        financialDao.insertAnalyses(finList)
                    }
                }

                // Restore Offers
                if (root.has("offers")) {
                    val oArray = root.getJSONArray("offers")
                    val offersList = mutableListOf<OfferEntity>()
                    for (i in 0 until oArray.length()) {
                        val obj = oArray.getJSONObject(i)
                        offersList.add(
                            OfferEntity(
                                id = obj.getString("id"),
                                propertyId = obj.getString("propertyId"),
                                recipientName = obj.optString("recipientName", "Agent"),
                                recipientEmail = obj.optString("recipientEmail", "agent@deals.com"),
                                offerPrice = obj.getDouble("offerPrice"),
                                earnestMoney = obj.getDouble("earnestMoney"),
                                inspectionPeriodDays = obj.optInt("inspectionPeriodDays", 10),
                                closingPeriodDays = obj.optInt("closingPeriodDays", 21),
                                contingencies = obj.optString("contingencies", ""),
                                terms = obj.optString("terms", ""),
                                conditions = obj.optString("conditions", ""),
                                expirationDate = obj.optString("expirationDate", ""),
                                generatedLetterContent = obj.optString("generatedLetterContent", ""),
                                pdfPath = obj.optString("pdfPath").takeIf { it.isNotBlank() },
                                status = obj.optString("status", "READY"),
                                createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                                sentAt = obj.optLong("sentAt").takeIf { it > 0 },
                                lastError = obj.optString("lastError").takeIf { it.isNotBlank() }
                            )
                        )
                    }
                    if (offersList.isNotEmpty()) {
                        offerDao.insertOffers(offersList)
                    }
                }

                // Restore Template
                if (root.has("offer_template")) {
                    val tObj = root.getJSONObject("offer_template")
                    configDao.saveOfferTemplate(
                        OfferTemplateEntity(
                            id = tObj.optString("id", "DEFAULT"),
                            templateName = tObj.optString("templateName", "Standard"),
                            headerTitle = tObj.optString("headerTitle", ""),
                            earnestMoneyPercent = tObj.optDouble("earnestMoneyPercent", 1.5),
                            defaultInspectionDays = tObj.optInt("defaultInspectionDays", 10),
                            defaultClosingDays = tObj.optInt("defaultClosingDays", 21),
                            standardTerms = tObj.optString("standardTerms", ""),
                            standardConditions = tObj.optString("standardConditions", "")
                        )
                    )
                }
            }

            Pair(true, "Backup successfully restored without errors. All relations preserved.")
        } catch (e: Exception) {
            Pair(false, "Restore failed: ${e.message}")
        }
    }
}
