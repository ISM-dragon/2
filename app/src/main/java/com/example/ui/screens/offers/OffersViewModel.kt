package com.example.ui.screens.offers

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.RealEstateAiApp
import com.example.data.local.entity.OfferEntity
import com.example.data.local.entity.PropertyEntity
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

data class OffersUiState(
    val selectedStatus: String = "ALL", // "ALL", "READY", "SENT", "OPENED", "SIGNED", "FAILED"
    val offers: List<OfferEntity> = emptyList(),
    val propertyMap: Map<String, PropertyEntity> = emptyMap(),
    val selectedOffer: OfferEntity? = null,
    val isSendingOffer: Boolean = false,
    val sendResultStatus: String? = null
)

class OffersViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as RealEstateAiApp
    private val offerRepo = app.offerRepository
    private val propertyRepo = app.propertyRepository

    private val _selectedStatus = MutableStateFlow("ALL")
    private val _selectedOffer = MutableStateFlow<OfferEntity?>(null)
    private val _isSending = MutableStateFlow(false)
    private val _sendResultStatus = MutableStateFlow<String?>(null)

    private val sendStateFlow = combine(_isSending, _sendResultStatus) { isSending, sendStatus ->
        Pair(isSending, sendStatus)
    }

    val uiState: StateFlow<OffersUiState> = combine(
        offerRepo.allOffers,
        propertyRepo.allProperties,
        _selectedStatus,
        _selectedOffer,
        sendStateFlow
    ) { allOffers, allProps, status, selected, sendState ->
        val propMap = allProps.associateBy { it.id }
        val filtered = if (status == "ALL") {
            allOffers
        } else {
            allOffers.filter { it.status.equals(status, ignoreCase = true) }
        }

        OffersUiState(
            selectedStatus = status,
            offers = filtered,
            propertyMap = propMap,
            selectedOffer = selected,
            isSendingOffer = sendState.first,
            sendResultStatus = sendState.second
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = OffersUiState()
    )

    fun selectStatus(status: String) {
        _selectedStatus.value = status
    }

    fun selectOffer(offer: OfferEntity?) {
        _selectedOffer.value = offer
    }

    fun sendOffer(context: Context, offerId: String) {
        viewModelScope.launch {
            _isSending.value = true
            _sendResultStatus.value = "Sending via Gmail..."
            val success = offerRepo.sendOffer(context, offerId)
            _isSending.value = false
            _sendResultStatus.value = if (success) "Sent successfully via Gmail!" else "Sending failed. Check Gmail config."
            // Update selected offer if active
            _selectedOffer.value = offerRepo.getOfferById(offerId)
        }
    }

    fun updateOfferStatus(offerId: String, status: String) {
        viewModelScope.launch {
            offerRepo.updateStatus(offerId, status)
            _selectedOffer.value = offerRepo.getOfferById(offerId)
        }
    }

    fun openPdf(context: Context, pdfPath: String) {
        try {
            val file = File(pdfPath)
            if (file.exists()) {
                val uri: Uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/pdf")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            // Error opening PDF
        }
    }
}
