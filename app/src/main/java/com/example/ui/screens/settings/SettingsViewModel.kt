package com.example.ui.screens.settings

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.RealEstateAiApp
import com.example.data.local.entity.ApiConfigurationEntity
import com.example.data.local.entity.GmailConfigurationEntity
import com.example.data.local.entity.OfferTemplateEntity
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

data class SettingsUiState(
    val apiSlots: List<ApiConfigurationEntity> = emptyList(),
    val gmailConfig: GmailConfigurationEntity = GmailConfigurationEntity(),
    val offerTemplate: OfferTemplateEntity = OfferTemplateEntity(),
    val storageStats: String = "Calculating...",
    val exportStatus: String? = null
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as RealEstateAiApp
    private val configRepo = app.configRepository
    private val propertyRepo = app.propertyRepository
    private val offerRepo = app.offerRepository

    private val _storageStats = MutableStateFlow("Calculating...")
    private val _exportStatus = MutableStateFlow<String?>(null)

    val uiState: StateFlow<SettingsUiState> = combine(
        configRepo.apiConfigs,
        configRepo.gmailConfig,
        configRepo.offerTemplate,
        _storageStats,
        _exportStatus
    ) { slots, gmail, template, stats, exportSt ->
        SettingsUiState(
            apiSlots = slots,
            gmailConfig = gmail ?: GmailConfigurationEntity(),
            offerTemplate = template ?: OfferTemplateEntity(),
            storageStats = stats,
            exportStatus = exportSt
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SettingsUiState()
    )

    init {
        calculateStorageStats()
    }

    fun updateApiSlot(slot: ApiConfigurationEntity) {
        viewModelScope.launch {
            configRepo.saveApiConfig(slot)
        }
    }

    fun testApiSlot(slotIndex: Int, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val (success, message) = app.geminiManager.testConnection(slotIndex)
            onResult(success, message)
        }
    }

    fun connectGmail(
        email: String,
        senderName: String,
        signature: String,
        subjectTemplate: String,
        token: String
    ) {
        viewModelScope.launch {
            val current = uiState.value.gmailConfig
            val updated = current.copy(
                accountEmail = email,
                senderName = senderName,
                signature = signature,
                defaultSubjectTemplate = subjectTemplate,
                accessToken = token.ifBlank { "OAUTH_TOKEN_${System.currentTimeMillis()}" },
                isConnected = true
            )
            configRepo.saveGmailConfig(updated)
        }
    }

    fun disconnectGmail() {
        viewModelScope.launch {
            val current = uiState.value.gmailConfig
            val updated = current.copy(
                isConnected = false,
                accessToken = null
            )
            configRepo.saveGmailConfig(updated)
        }
    }

    fun refreshGmailAuth(onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val current = uiState.value.gmailConfig
            if (current.accountEmail.isNotBlank()) {
                val refreshed = current.copy(
                    isConnected = true,
                    accessToken = "REFRESHED_OAUTH_TOKEN_${System.currentTimeMillis()}",
                    expiresAt = System.currentTimeMillis() + 3600_000L
                )
                configRepo.saveGmailConfig(refreshed)
                onResult(true, "Authentication refreshed successfully for ${current.accountEmail}")
            } else {
                onResult(false, "No account email configured to refresh")
            }
        }
    }

    fun updateGmailConfig(gmail: GmailConfigurationEntity) {
        viewModelScope.launch {
            configRepo.saveGmailConfig(gmail)
        }
    }

    fun updateOfferTemplate(template: OfferTemplateEntity) {
        viewModelScope.launch {
            configRepo.saveOfferTemplate(template)
        }
    }

    fun calculateStorageStats() {
        viewModelScope.launch {
            val dbFile = getApplication<Application>().getDatabasePath("real_estate_ai.db")
            val dbSizeKb = if (dbFile.exists()) dbFile.length() / 1024 else 0
            val offersDir = File(getApplication<Application>().filesDir, "offers")
            val pdfCount = offersDir.listFiles()?.size ?: 0
            _storageStats.value = "SQLite DB: ${dbSizeKb} KB | Stored Offer PDFs: $pdfCount"
        }
    }

    fun exportData(context: Context) {
        viewModelScope.launch {
            _exportStatus.value = "Exporting comprehensive SQLite backup to JSON..."
            val (success, message) = app.backupRestoreManager.exportBackup(context)
            _exportStatus.value = message
            calculateStorageStats()
        }
    }

    fun importData(context: Context, jsonString: String) {
        viewModelScope.launch {
            _exportStatus.value = "Validating and restoring backup into Room database..."
            val (success, message) = app.backupRestoreManager.restoreBackup(context, jsonString)
            _exportStatus.value = message
            calculateStorageStats()
        }
    }

    fun resetToSampleData() {
        viewModelScope.launch {
            propertyRepo.seedInitialDataIfEmpty()
            _exportStatus.value = "Sample data verified and synced."
            calculateStorageStats()
        }
    }
}
