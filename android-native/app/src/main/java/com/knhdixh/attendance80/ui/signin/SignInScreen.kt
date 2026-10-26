package com.knhdixh.attendance80.ui.signin

import android.app.Activity
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.knhdixh.attendance80.BuildConfig
import com.knhdixh.attendance80.ui.theme.LocalExtraColors

@Composable
fun SignInScreen(
    viewModel: SignInViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activity = context as? Activity
    val extraColors = LocalExtraColors.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    val openCustomTab: (Uri) -> Unit = { uri ->
        val intent = CustomTabsIntent.Builder().build()
        intent.launchUrl(context, uri)
    }

    var devEmail by remember { mutableStateOf("dev@example.com") }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 400.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "Attendance80",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Keep your university attendance above 80%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = extraColors.text2,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(32.dp))

                if (uiState.isLoading) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                } else {
                    val config = uiState.config

                    // Continue with Google
                    if (config?.google == true) {
                        Button(
                            onClick = { viewModel.startGoogleSignIn(openCustomTab) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = MaterialTheme.shapes.small,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        ) {
                            Text("Continue with Google")
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // Sign in with a passkey (always available)
                    OutlinedButton(
                        onClick = {
                            if (activity != null) {
                                viewModel.signInWithPasskey(activity)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text("Sign in with a passkey")
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    // Create an account with a passkey (when signup is enabled)
                    if (config?.signup == true) {
                        OutlinedButton(
                            onClick = { viewModel.showCreateAccountDialog() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text("Create an account with a passkey")
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // Dev sign-in (only in debug builds when dev is enabled)
                    if (BuildConfig.DEBUG && config?.dev == true) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Developer Login",
                            style = MaterialTheme.typography.titleMedium,
                            color = extraColors.text2,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = devEmail,
                            onValueChange = { devEmail = it },
                            label = { Text("Email") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { viewModel.startDevSignIn(devEmail, openCustomTab) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text("Sign in with dev account")
                        }
                    }
                }
            }
        }
    }

    if (uiState.showCreateAccountDialog) {
        var nameInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { viewModel.dismissCreateAccountDialog() },
            title = { Text("Create account") },
            text = {
                Column {
                    Text(
                        text = "Enter your name to register a passkey:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = extraColors.text2,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (activity != null && nameInput.isNotBlank()) {
                            viewModel.registerPasskey(activity, nameInput.trim())
                        }
                    },
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.dismissCreateAccountDialog() },
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text("Cancel")
                }
            },
            shape = MaterialTheme.shapes.large,
        )
    }
}
