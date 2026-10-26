package com.knhdixh.attendance80.ui.signin

import android.app.Activity
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knhdixh.attendance80.BuildConfig
import com.knhdixh.attendance80.auth.AuthRepository
import com.knhdixh.attendance80.auth.Pkce
import com.knhdixh.attendance80.net.AuthConfigResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SignInUiState(
    val config: AuthConfigResponse? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val showCreateAccountDialog: Boolean = false,
)

class SignInViewModel(
    private val authRepository: AuthRepository,
    private val savedStateHandle: SavedStateHandle,
    private val baseUrl: String = BuildConfig.BASE_URL,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SignInUiState())
    val uiState: StateFlow<SignInUiState> = _uiState.asStateFlow()

    private var verifier: String?
        get() = savedStateHandle["verifier"] ?: authRepository.pendingVerifier
        set(value) {
            savedStateHandle["verifier"] = value
            authRepository.pendingVerifier = value
        }

    init {
        loadConfig()
    }

    fun loadConfig() {
        viewModelScope.launch {
            try {
                val cfg = authRepository.loadConfig()
                _uiState.value = _uiState.value.copy(config = cfg)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(errorMessage = e.message ?: "Failed to connect")
            }
        }
    }

    fun startGoogleSignIn(openCustomTab: (Uri) -> Unit) {
        val v = Pkce.generateVerifier()
        val c = Pkce.computeChallenge(v)
        verifier = v
        val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val url = "${base}auth/app/google?challenge=$c"
        openCustomTab(url.toUri())
    }

    fun startDevSignIn(email: String, openCustomTab: (Uri) -> Unit) {
        val v = Pkce.generateVerifier()
        val c = Pkce.computeChallenge(v)
        verifier = v
        val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val url = "${base}auth/app/dev?email=$email&challenge=$c"
        openCustomTab(url.toUri())
    }

    fun handleAuthCode(code: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            val result = authRepository.exchangeCode(code, verifier)
            verifier = null
            result.onFailure {
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = it.message ?: "Sign-in failed")
            }
            result.onSuccess {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    fun signInWithPasskey(activity: Activity) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            val res = authRepository.signInWithPasskey(activity)
            res.onFailure {
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = it.message)
            }
            res.onSuccess {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    fun registerPasskey(activity: Activity, name: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null, showCreateAccountDialog = false)
            val res = authRepository.registerPasskey(activity, name)
            res.onFailure {
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = it.message)
            }
            res.onSuccess {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    fun showCreateAccountDialog() {
        _uiState.value = _uiState.value.copy(showCreateAccountDialog = true)
    }

    fun dismissCreateAccountDialog() {
        _uiState.value = _uiState.value.copy(showCreateAccountDialog = false)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }
}
