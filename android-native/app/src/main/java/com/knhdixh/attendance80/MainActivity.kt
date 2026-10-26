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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.knhdixh.attendance80.ui.signin.SignInScreen
import com.knhdixh.attendance80.ui.signin.SignInViewModel
import com.knhdixh.attendance80.ui.theme.Attendance80Theme

class MainActivity : ComponentActivity() {
    private val signInViewModel: SignInViewModel by viewModels {
        val app = application as App
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SignInViewModel(
                    authRepository = app.container.authRepository,
                    savedStateHandle = SavedStateHandle(),
                ) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleAuthIntent(intent)

        setContent {
            Attendance80Theme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val app = application as App
                    val token by app.container.tokenStore.token.collectAsState()
                    AppNav(
                        token = token,
                        signInViewModel = signInViewModel,
                    )
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
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Welcome to Attendance80",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}
