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

    fun saveGmailAccountSettings(
        email: String,
        senderName: String,
        signature: String,
        subjectTemplate: String
    ) {
        viewModelScope.launch {
            val cleanEmail = email.trim()
            val current = uiState.value.gmailConfig
            val isConnected = current.isConnected && !current.accessToken.isNullOrBlank()
            val updated = current.copy(
                accountEmail = cleanEmail,
                senderName = senderName.trim(),
                signature = signature,
                defaultSubjectTemplate = subjectTemplate,
                isConnected = isConnected,
                authStatus = if (isConnected) current.authStatus else if (cleanEmail.isNotBlank()) {
                    com.example.data.local.entity.GmailAuthStatus.AUTH_REQUIRED
                } else {
                    com.example.data.local.entity.GmailAuthStatus.NOT_CONFIGURED
                }
            )
            configRepo.saveGmailConfig(updated)
        }
    }

    fun applyVerifiedOAuthTokens(
        accessToken: String,
        refreshToken: String?,
        expiresInSeconds: Long = 3600L
    ) {
        viewModelScope.launch {
            val cleanAccess = accessToken.trim()
            if (cleanAccess.isBlank()) {
                configRepo.updateGmailAuthStatus(
                    com.example.data.local.entity.GmailAuthStatus.FAILED,
                    "Cannot authorize: Received empty OAuth access token."
                )
                return@launch
            }

            val cleanRefresh = refreshToken?.trim()?.takeIf { it.isNotBlank() }
            val expiresAt = System.currentTimeMillis() + (expiresInSeconds * 1000L)
            configRepo.updateGmailTokens(
                newAccessToken = cleanAccess,
                newRefreshToken = cleanRefresh,
                expiresAt = expiresAt,
                authStatus = com.example.data.local.entity.GmailAuthStatus.AUTH_REQUIRED,
                lastError = null
            )
        }
    }

    fun disconnectGmail() {
        viewModelScope.launch {
            val current = uiState.value.gmailConfig
            val updated = current.copy(
                isConnected = false,
                authStatus = com.example.data.local.entity.GmailAuthStatus.NOT_CONFIGURED,
                accessToken = null,
                refreshToken = null,
                expiresAt = 0L,
                lastError = null
            )
            configRepo.saveGmailConfig(updated)
        }
    }

    fun refreshGmailAuth(onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val current = uiState.value.gmailConfig
            if (current.refreshToken.isNullOrBlank()) {
                onResult(false, "Cannot refresh: Google OAuth refresh token is missing. Authorization required.")
                return@launch
            }

            val refreshed = app.gmailService.refreshAccessToken(current)
            if (refreshed != null && !refreshed.accessToken.isNullOrBlank()) {
                onResult(true, "Authentication refreshed successfully for ${current.accountEmail}")
            } else {
                val errorMsg = uiState.value.gmailConfig.lastError ?: "OAuth token refresh failed with Google servers."
                onResult(false, errorMsg)
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
