package com.example

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.service.NetShieldVpnService
import com.example.ui.components.BottomNavBar
import com.example.ui.components.NavItem
import com.example.ui.screens.ActivityLogScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.DnsSettingsScreen
import com.example.ui.screens.FiltersScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.ThreatScreen
import com.example.ui.theme.NetShieldTheme
import com.example.viewmodel.NetShieldViewModel

/**
 * Activity utama & entry point navigasi Compose aplikasi NetShield.
 * Bertanggung jawab meminta izin VpnService & mensinkronkan status
 * proteksi (ViewModel) dengan NetShieldVpnService.
 *
 * === CHANGELOG ===
 * [Fase 0 - 2026-08-07]
 *  - Efek start/stop VPN dipindah dari body composable ke
 *    LaunchedEffect(isProtectionActive) — mencegah efek terpanggil ulang
 *    di setiap recomposition (berisiko dialog izin VPN berulang & ANR).
 *  - viewModel() diganti viewModel(factory = NetShieldViewModel.Factory)
 *    agar memakai DnsEngineRepository singleton dari NetShieldApplication.
 *  - startVpnService()/stopVpnService() dibungkus try-catch
 *    IllegalStateException (Android 8+ background execution limits).
 *  Lihat CHANGELOG.md & RENCANA_PRODUKSI_NETSHIELD.md §Fase 0.
 * [Fase 6.3 - 2026-08-07]
 *  - Tambah alur permintaan battery optimization exemption (opsional,
 *    diminta secara eksplisit oleh user lewat Settings, BUKAN otomatis
 *    saat app dibuka) agar service VPN tidak mudah dibunuh sistem
 *    (khususnya OEM agresif seperti MIUI/ColorOS). Status exemption
 *    dicek via PowerManager.isIgnoringBatteryOptimizations() saat
 *    onCreate() & onResume() (supaya ter-refresh saat user kembali dari
 *    Settings sistem), dan dialog sistem resmi
 *    (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) dipicu lewat
 *    requestBatteryOptimizationExemption() — dengan fallback ke
 *    ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS jika OEM tidak
 *    menyediakan dialog langsung. Lihat CHANGELOG.md &
 *    RENCANA_PRODUKSI_NETSHIELD.md §Fase 6.3.
 */
class MainActivity : ComponentActivity() {

    private val vpnPrepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Permission result handled gracefully */ }

    // Fase 6.3: hasil dialog sistem tidak perlu ditangani langsung di sini —
    // status sebenarnya selalu di-refresh via isIgnoringBatteryOptimizations()
    // di onResume() (dipanggil otomatis begitu user kembali dari dialog/Settings).
    private val batteryOptimizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* status di-refresh di onResume() */ }

    private val isBatteryOptimizationExempt = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestNotificationPermission()
        isBatteryOptimizationExempt.value = checkIgnoringBatteryOptimizations()

        setContent {
            NetShieldTheme {
                val viewModel: NetShieldViewModel = viewModel(factory = NetShieldViewModel.Factory)
                val isProtectionActive by viewModel.isProtectionActive.collectAsState()
                val batteryExempt by isBatteryOptimizationExempt

                // PENTING: sinkronisasi start/stop VpnService HARUS dilakukan
                // sebagai side-effect terkontrol (LaunchedEffect), BUKAN
                // dipanggil langsung di body composable. Dipanggil langsung
                // di body akan membuatnya terpanggil ulang di SETIAP
                // recomposition (bisa berkali-kali per detik), memicu
                // dialog izin VPN berulang, IPC berlebihan, bahkan ANR.
                // LaunchedEffect hanya menjalankan efek ini saat nilai
                // `isProtectionActive` benar-benar berubah.
                LaunchedEffect(isProtectionActive) {
                    if (isProtectionActive) {
                        prepareAndStartVpn()
                    } else {
                        stopVpnService()
                    }
                }

                NetShieldMainApp(
                    viewModel = viewModel,
                    isBatteryOptimizationExempt = batteryExempt,
                    onRequestBatteryOptimizationExemption = { requestBatteryOptimizationExemption() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // User mungkin baru saja kembali dari dialog sistem/Settings baterai —
        // refresh statusnya supaya UI (SettingsScreen) selalu akurat.
        isBatteryOptimizationExempt.value = checkIgnoringBatteryOptimizations()
    }

    private fun checkIgnoringBatteryOptimizations(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    /**
     * Memicu dialog sistem resmi untuk meminta user mengecualikan NetShield
     * dari battery optimization/doze. Ini SELALU merupakan permintaan
     * eksplisit (dipicu dari tombol di SettingsScreen) — tidak pernah
     * dipanggil otomatis, dan user tetap bebas menolak lewat dialog OS.
     * Alasannya dijelaskan transparan ke user di UI: tanpa exemption ini,
     * OEM agresif (mis. MIUI/ColorOS) bisa membunuh service VPN di
     * background sehingga proteksi DNS berhenti tanpa disadari user.
     */
    @Suppress("BatteryLife")
    private fun requestBatteryOptimizationExemption() {
        if (checkIgnoringBatteryOptimizations()) return
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        try {
            batteryOptimizationLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            // Sebagian ROM custom tidak menyediakan dialog per-app langsung;
            // fallback ke halaman daftar battery optimization umum agar user
            // masih bisa mengatur secara manual.
            try {
                batteryOptimizationLauncher.launch(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                )
            } catch (e2: ActivityNotFoundException) {
                Log.e(TAG, "Tidak ada activity untuk pengaturan battery optimization", e2)
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun prepareAndStartVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPrepareLauncher.launch(intent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val serviceIntent = Intent(this, NetShieldVpnService::class.java).apply {
            action = NetShieldVpnService.ACTION_CONNECT
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: IllegalStateException) {
            // Bisa terjadi di Android 8+ jika sistem menolak permintaan
            // startForegroundService karena batasan eksekusi background
            // (mis. dipanggil saat app baru saja berpindah ke background).
            Log.e(TAG, "Gagal memulai NetShieldVpnService", e)
        }
    }

    private fun stopVpnService() {
        val serviceIntent = Intent(this, NetShieldVpnService::class.java).apply {
            action = NetShieldVpnService.ACTION_DISCONNECT
        }
        try {
            startService(serviceIntent)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Gagal menghentikan NetShieldVpnService", e)
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}

@Composable
fun NetShieldMainApp(
    viewModel: NetShieldViewModel,
    isBatteryOptimizationExempt: Boolean = false,
    onRequestBatteryOptimizationExemption: () -> Unit = {}
) {
    var currentRoute by remember { mutableStateOf(NavItem.Dashboard.route) }

    Scaffold(
        bottomBar = {
            if (currentRoute != "settings") {
                BottomNavBar(
                    currentRoute = currentRoute,
                    onNavigate = { route -> currentRoute = route }
                )
            }
        }
    ) { innerPadding ->
        val modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)

        when (currentRoute) {
            NavItem.Dashboard.route -> DashboardScreen(
                viewModel = viewModel,
                onOpenSettings = { currentRoute = "settings" },
                onViewAllAlerts = { currentRoute = NavItem.Logs.route },
                modifier = modifier
            )
            NavItem.Logs.route -> ActivityLogScreen(
                viewModel = viewModel,
                modifier = modifier
            )
            NavItem.Filters.route -> FiltersScreen(
                viewModel = viewModel,
                modifier = modifier
            )
            NavItem.DnsSettings.route -> DnsSettingsScreen(
                viewModel = viewModel,
                modifier = modifier
            )
            NavItem.Threats.route -> ThreatScreen(
                viewModel = viewModel,
                modifier = modifier
            )
            "settings" -> SettingsScreen(
                viewModel = viewModel,
                onBack = { currentRoute = NavItem.Dashboard.route },
                isBatteryOptimizationExempt = isBatteryOptimizationExempt,
                onRequestBatteryOptimizationExemption = onRequestBatteryOptimizationExemption,
                modifier = modifier
            )
        }
    }
}
