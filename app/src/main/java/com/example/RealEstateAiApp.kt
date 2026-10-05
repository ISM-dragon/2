package com.example

import android.app.Application
import com.example.data.adapter.OffMarketWholesaleAdapter
import com.example.data.adapter.OnMarketMlsAdapter
import com.example.data.adapter.PropertySourceManager
import com.example.data.local.AppDatabase
import com.example.data.repository.*
import com.example.domain.ai.GeminiManager
import com.example.domain.automation.AutomationEngine
import com.example.domain.gmail.GmailService
import com.example.util.NetworkMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class RealEstateAiApp : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var networkMonitor: NetworkMonitor
        private set

    lateinit var propertyRepository: PropertyRepository
        private set

    lateinit var financialRepository: FinancialRepository
        private set

    lateinit var configRepository: ConfigRepository
        private set

    lateinit var geminiManager: GeminiManager
        private set

    lateinit var gmailService: GmailService
        private set

    lateinit var offerRepository: OfferRepository
        private set

    lateinit var aiChatRepository: AiChatRepository
        private set

    lateinit var automationEngine: AutomationEngine
        private set

    lateinit var automationRepository: AutomationRepository
        private set

    lateinit var backupRestoreManager: BackupRestoreManager
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
        networkMonitor = NetworkMonitor(this)
        backupRestoreManager = BackupRestoreManager(database)

        val propertySourceManager = PropertySourceManager(
            listOf(
                OnMarketMlsAdapter(),
                OffMarketWholesaleAdapter()
            )
        )

        propertyRepository = PropertyRepository(database.propertyDao(), propertySourceManager)
        financialRepository = FinancialRepository(database.financialDao(), database.propertyDao(), database.automationDao())
        configRepository = ConfigRepository(database.configDao())

        geminiManager = GeminiManager(database.configDao())
        gmailService = GmailService(configRepository)

        offerRepository = OfferRepository(
            offerDao = database.offerDao(),
            propertyDao = database.propertyDao(),
            configRepository = configRepository,
            geminiManager = geminiManager,
            gmailService = gmailService
        )

        aiChatRepository = AiChatRepository(
            aiChatDao = database.aiChatDao(),
            propertyDao = database.propertyDao(),
            financialDao = database.financialDao(),
            geminiManager = geminiManager
        )

        automationEngine = AutomationEngine(
            context = this,
            automationDao = database.automationDao(),
            propertyDao = database.propertyDao(),
            propertySourceManager = propertySourceManager,
            financialRepository = financialRepository,
            offerRepository = offerRepository,
            geminiManager = geminiManager,
            networkMonitor = networkMonitor
        )

        automationRepository = AutomationRepository(
            automationDao = database.automationDao(),
            automationEngine = automationEngine
        )

        // Seed default dataset, configs, and pre-underwrite
        CoroutineScope(Dispatchers.IO).launch {
            configRepository.seedDefaultsIfEmpty()
            automationRepository.seedDefaultsIfEmpty()
            propertyRepository.seedInitialDataIfEmpty()

            // Pre-calculate finances for initial seed properties so Dashboard is rich immediately
            val allProps = propertyRepository.allProperties.first()
            for (prop in allProps) {
                try {
                    financialRepository.analyzeProperty(prop.id)
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
    }
}
