package io.github.ralbra.randomize_the_gathering

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.activity.SystemBarStyle
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.ralbra.randomize_the_gathering.ui.isDark
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import io.github.ralbra.randomize_the_gathering.ui.MainViewModel
import io.github.ralbra.randomize_the_gathering.ui.MomirScreen

class MainActivity : ComponentActivity() {
    private val vm by viewModels<MainViewModel>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        vm.syncPrinterState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestBtPermissions()

        setContent {
            val ui by vm.ui.collectAsState()
            val darkTheme = ui.themeMode.isDark()

            // Status and navigation bar icons follow the app theme, not only the system one.
            DisposableEffect(darkTheme) {
                val style = if (darkTheme) {
                    SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            val scheme = if (darkTheme) {
                darkColorScheme(
                    primary = Color(0xFF88D4C2),
                    secondary = Color(0xFFA8C6FF),
                    tertiary = Color(0xFFFFD089),
                    background = Color(0xFF0E1625),
                    surface = Color(0xFF1A2537),
                    onPrimary = Color(0xFF06261D),
                    onSecondary = Color(0xFF0D254C),
                    onBackground = Color(0xFFE7EDF8),
                    onSurface = Color(0xFFE7EDF8),
                )
            } else {
                lightColorScheme(
                    primary = Color(0xFF0B6E4F),
                    secondary = Color(0xFF1D3557),
                    tertiary = Color(0xFFFFB703),
                    background = Color(0xFFF4F8FF),
                    surface = Color(0xFFFFFFFF),
                    onPrimary = Color(0xFFFFFFFF),
                    onSecondary = Color(0xFFFFFFFF),
                    onBackground = Color(0xFF142033),
                    onSurface = Color(0xFF142033),
                )
            }

            MaterialTheme(colorScheme = scheme) {
                MomirScreen(vm, onRequestPermissions = { requestBtPermissions() })
            }
        }
    }

    private fun requestBtPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        permissionLauncher.launch(perms.toTypedArray())
    }
}