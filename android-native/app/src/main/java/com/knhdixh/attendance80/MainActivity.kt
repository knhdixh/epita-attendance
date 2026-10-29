package com.knhdixh.attendance80

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.knhdixh.attendance80.ui.signin.SignInScreen
import com.knhdixh.attendance80.ui.signin.SignInViewModel
import com.knhdixh.attendance80.ui.theme.Attendance80Theme

class MainActivity : ComponentActivity() {
    private val signInViewModel: SignInViewModel by viewModels {
        viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as App
                SignInViewModel(
                    authRepository = app.container.authRepository,
                    savedStateHandle = createSavedStateHandle(),
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        (application as App).startData()
        if (savedInstanceState == null) {
            handleAuthIntent(intent)
        }

        setContent {
            Attendance80Theme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val app = application as App
                    val isLoaded by app.container.tokenStore.isLoaded.collectAsState()
                    val token by app.container.tokenStore.token.collectAsState()
                    if (isLoaded) {
                        AppNav(
                            token = token,
                            signInViewModel = signInViewModel,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthIntent(intent)
    }

    private fun handleAuthIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "com.knhdixh.attendance80") {
            if (data.path == "/auth") {
                val code = data.getQueryParameter("code")
                if (!code.isNullOrBlank()) {
                    signInViewModel.handleAuthCode(code)
                }
            }
        }
        intent.data = null
    }
}

@Composable
fun AppNav(
    token: String?,
    signInViewModel: SignInViewModel,
) {
    val navController = rememberNavController()
    val startDest = if (token.isNullOrBlank()) "signin" else "main"

    NavHost(navController = navController, startDestination = startDest) {
        composable("signin") {
            SignInScreen(viewModel = signInViewModel)
        }
        composable("main") {
            AuthenticatedPlaceholder()
        }
    }
}

@Composable
fun AuthenticatedPlaceholder() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Welcome to Attendance80", style = MaterialTheme.typography.titleMedium)
    }
}
