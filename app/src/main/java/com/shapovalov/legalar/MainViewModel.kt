package com.shapovalov.legalar

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = LegalRepository(AppDatabase.getInstance(application).legalDao())

    private val _scanResult = MutableStateFlow(ScanResult.empty())
    val scanResult: StateFlow<ScanResult> = _scanResult.asStateFlow()

    private val _foundReferences = MutableStateFlow<List<LegalReference>>(emptyList())
    val foundReferences: StateFlow<List<LegalReference>> = _foundReferences.asStateFlow()

    private val _catalog = MutableStateFlow<List<LegalReference>>(emptyList())
    val catalog: StateFlow<List<LegalReference>> = _catalog.asStateFlow()

    private val _overlayEnabled = MutableStateFlow(true)
    val overlayEnabled: StateFlow<Boolean> = _overlayEnabled.asStateFlow()

    private val found = LinkedHashMap<Long, LegalReference>()

    val legalRepository: LegalRepository
        get() = repository

    init {
        viewModelScope.launch {
            _catalog.value = repository.loadCatalog()
        }
    }

    fun onScanResult(result: ScanResult) {
        _scanResult.value = result
        if (result.references.isEmpty()) {
            return
        }
        for (item in result.references) {
            found[item.reference.id] = item.reference
        }
        while (found.size > MAX_FOUND_REFERENCES) {
            val iterator = found.keys.iterator()
            if (!iterator.hasNext()) {
                break
            }
            iterator.next()
            iterator.remove()
        }
        _foundReferences.value = found.values.toList()
    }

    fun toggleOverlay() {
        _overlayEnabled.value = !_overlayEnabled.value
    }

    fun clearOverlay() {
        _scanResult.value = ScanResult.empty()
    }

    private companion object {
        const val MAX_FOUND_REFERENCES = 8
    }
}
