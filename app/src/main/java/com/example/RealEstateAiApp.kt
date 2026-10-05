package com.example

import android.app.Application
import com.example.data.adapter.OffMarketWholesaleAdapter
import com.example.data.adapter.OnMarketMlsAdapter
import com.example.data.adapter.PropertySourceManager
import com.example.data.adapter.PropertyUrlImportBridge
import com.example.data.local.AppDatabase
import com.example.data.repository.*
import com.example.domain.ai.GeminiManager
import com.example.domain.automation.AutomationEngine
import com.example.domain.propertyurl.pipeline.PropertyImportQueue
import com.example.domain.propertyurl.pipeline.PropertyUrlIntelligence
import com.example.domain.propertyurl.pipeline.PropertyUrlIntelligenceFactory
import com.example.domain.propertyurl.port.CredentialProvider
import com.example.domain.propertyurl.store.FilePropertyImportJobStore
import com.example.domain.gmail.GmailService
import com.example.util.NetworkMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

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

    /** Property URL Intelligence: turns a pasted listing link into a canonical property record. */
    lateinit var propertyUrlIntelligence: PropertyUrlIntelligence
        private set

    /** Data-layer bridge: canonical record → Room bundle (see [PropertyUrlImportBridge]). */
    lateinit var propertyUrlImporter: PropertyUrlImportBridge
        private set

    /** Bounded background worker for imports + deferred retries. */
    lateinit var propertyImportQueue: PropertyImportQueue
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

        // Property URL Intelligence. Jobs are persisted as JSON files under the app's private storage,
        // so in-flight imports survive process death; swapping in a Room-backed
        // PropertyImportJobStore later requires no other change. No credentials are embedded here:
        // sources that need them receive them at runtime through a CredentialProvider.
        val propertyImportJobStore = FilePropertyImportJobStore(File(filesDir, "property-url-intelligence"))
        propertyUrlIntelligence = PropertyUrlIntelligenceFactory.create(
            jobStore = propertyImportJobStore,
            options = PropertyUrlIntelligenceFactory.mobileOptions(),
            credentialProvider = CredentialProvider.NONE,
            useRobotsTxt = true
        )
        propertyUrlImporter = PropertyUrlImportBridge(propertyUrlIntelligence, propertyRepository)
        propertyImportQueue = PropertyUrlIntelligenceFactory.createQueue(
            intelligence = propertyUrlIntelligence,
            jobStore = propertyImportJobStore,
            clock = com.example.domain.propertyurl.port.SystemClock(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        )
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

        // Import retries and imports interrupted by a previous process death resume automatically.
        propertyImportQueue.start()

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
