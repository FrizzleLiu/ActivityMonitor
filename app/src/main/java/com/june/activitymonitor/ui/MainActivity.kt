package com.june.activitymonitor.ui

import android.Manifest
import android.os.Bundle
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayoutMediator
import com.june.activitymonitor.R
import com.june.activitymonitor.data.UsageStatsReader
import com.june.activitymonitor.data.PrivilegeShell
import com.june.activitymonitor.privileged.HelperClient
import com.june.activitymonitor.privileged.adb.WirelessAdb
import com.june.activitymonitor.privileged.adb.WirelessAdbPortStore
import com.june.activitymonitor.privileged.adb.WirelessConnectDiscovery
import com.june.activitymonitor.privileged.adb.WirelessPairingNotifications
import com.june.activitymonitor.privileged.adb.WirelessPairingService
import com.june.activitymonitor.privileged.adb.WirelessPairingStore
import com.june.activitymonitor.databinding.ActivityMainBinding
import com.june.activitymonitor.model.MonitorUiState
import com.june.activitymonitor.model.SortOption
import com.june.activitymonitor.ui.adapter.AppPagerAdapter
import com.june.activitymonitor.util.DeveloperSettings
import com.june.activitymonitor.util.Formatters
import com.june.activitymonitor.util.LocaleManager
import com.june.activitymonitor.util.ThemeManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MonitorViewModel by viewModels()
    private var pendingNotificationAction: (() -> Unit)? = null
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val action = pendingNotificationAction
        pendingNotificationAction = null
        if (granted) {
            action?.invoke()
        } else {
            toast(getString(R.string.pair_notification_permission_denied))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.helperBanner.setOnClickListener { showHelperDialog() }
        binding.helperBannerAction.setOnClickListener { showHelperDialog() }

        binding.pager.adapter = AppPagerAdapter(this)
        binding.pager.offscreenPageLimit = 1
        TabLayoutMediator(binding.tabs, binding.pager) { tab, position ->
            tab.setText(
                getString(
                    R.string.tab_title_count,
                    getString(if (position == 0) R.string.tab_system else R.string.tab_user),
                    if (position == 0) {
                        viewModel.state.value.systemApps.size
                    } else {
                        viewModel.state.value.userApps.size
                    },
                )
            )
        }.attach()

        // Detect a helper that is already running (e.g. started before this launch).
        viewModel.refreshHelperState()

        // LADB keeps connect-port discovery running while the user is in Settings. The system can
        // stop advertising the connect service when the pairing dialog opens, so discover it early.
        WirelessAdb.startConnectDiscovery(this)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    updateStatus(state)
                    invalidateOptionsMenu()
                }
            }
        }

        if (savedInstanceState == null) {
            viewModel.refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        // Pick up a freshly started helper (async) / granted "Usage access" (sync, cheap).
        val state = viewModel.state.value
        if (!state.privileged) {
            viewModel.refreshHelperState()
        }
        if (!state.privileged && !state.usageAccessGranted && UsageStatsReader.hasUsageAccess(this)) {
            viewModel.refresh()
        }
    }

    private fun updateStatus(state: MonitorUiState) {
        val cpu = if (state.totalCpuPercent >= 0f) {
            Formatters.percent(state.totalCpuPercent)
        } else {
            getString(R.string.value_unknown)
        }
        val memory = if (state.totalMemoryPercent >= 0f) {
            Formatters.percent(state.totalMemoryPercent)
        } else {
            getString(R.string.value_unknown)
        }
        val temperature = temperatureLabel(state)
        val temperatureMetricLabel = when {
            state.batteryTempC >= 0f -> R.string.overview_battery
            state.socTempC >= 0f -> R.string.overview_soc
            else -> R.string.label_temperature
        }
        setMetricValue(
            binding.metricCpuValue,
            cpu,
            if (state.totalCpuPercent >= 0f) levelColor(state.totalCpuPercent) else null,
        )
        setMetricValue(
            binding.metricMemoryValue,
            memory,
            if (state.totalMemoryPercent >= 0f) levelColor(state.totalMemoryPercent) else null,
        )
        binding.metricTemperatureLabel.setText(temperatureMetricLabel)
        setMetricValue(binding.metricTemperatureValue, temperature, temperatureColor(state))
        binding.helperBanner.isVisible = !state.privileged
        updateTabCounts(state)
    }

    private fun setMetricValue(view: TextView, value: String, colorRes: Int?) {
        view.text = value
        view.setTextColor(
            colorRes?.let { ContextCompat.getColor(this, it) }
                ?: MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurface),
        )
    }

    /** Appends the running app count to each tab label, e.g. "System & Services (12)". */
    private fun updateTabCounts(state: MonitorUiState) {
        binding.tabs.getTabAt(0)?.text =
            getString(R.string.tab_title_count, getString(R.string.tab_system), state.systemApps.size)
        binding.tabs.getTabAt(1)?.text =
            getString(R.string.tab_title_count, getString(R.string.tab_user), state.userApps.size)
    }

    /** Prefers the battery temperature (the usual "phone temperature"), falling back to the SoC. */
    private fun temperatureLabel(state: MonitorUiState): String {
        val value = if (state.batteryTempC >= 0f) state.batteryTempC else state.socTempC
        return Formatters.temperature(value)
    }

    /** Colors the temperature: <45°C green, 45-55°C amber, >=55°C red. */
    private fun temperatureColor(state: MonitorUiState): Int? {
        val value = if (state.batteryTempC >= 0f) state.batteryTempC else state.socTempC
        return when {
            value < 0f -> null
            value < 45f -> R.color.level_low
            value < 55f -> R.color.level_mid
            else -> R.color.level_high
        }
    }

    private fun levelColor(value: Float): Int = when {
        value < 60f -> R.color.level_low
        value < 80f -> R.color.level_mid
        else -> R.color.level_high
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val state = viewModel.state.value
        menu.findItem(R.id.action_usage_access)?.isVisible =
            !state.privileged && !state.usageAccessGranted
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_refresh -> {
            viewModel.refresh()
            true
        }

        R.id.action_sort -> {
            showSortDialog()
            true
        }

        R.id.action_language -> {
            showLanguageDialog()
            true
        }

        R.id.action_appearance -> {
            showAppearanceDialog()
            true
        }

        R.id.action_usage_access -> {
            openUsageAccessSettings()
            true
        }

        R.id.action_helper -> {
            showHelperDialog()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    /** Explains how to start the embedded helper and offers a no-PC wireless path. */
    fun showHelperDialog() {
        lifecycleScope.launch {
            val running = withContext(Dispatchers.IO) {
                PrivilegeShell.isHelperRunning()
            }
            if (running) {
                viewModel.refreshHelperState()
                toast(getString(R.string.helper_ready_toast))
                return@launch
            }
            showHelperOptionsDialog()
        }
    }

    private fun showHelperOptionsDialog() {
        val command = HelperClient.pcAdbCommand()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.helper_guide_title)
            .setMessage(R.string.helper_guide_message)
            .setPositiveButton(R.string.helper_wireless_start) { _, _ -> startWirelessFlow() }
            .setNeutralButton(R.string.helper_copy_command) { _, _ ->
                copyToClipboard(command)
                toast(getString(R.string.helper_copied))
            }
            .setNegativeButton(R.string.kill_cancel, null)
            .show()
    }

    /**
     * No-PC path: reuse an existing Wireless-debugging pairing when possible, otherwise ask for the
     * system pairing code and pair with the device's own adb daemon.
     */
    private fun startWirelessFlow() {
        val status = DeveloperSettings.read(this)
        when {
            !status.wirelessDebuggingSupported -> {
                showWirelessDebuggingUnsupportedDialog()
                return
            }

            !status.developerOptionsEnabled -> {
                showDeveloperOptionsRequiredDialog()
                return
            }

            !status.wirelessDebuggingEnabled -> {
                showWirelessDebuggingRequiredDialog()
                return
            }
        }
        WirelessAdb.startConnectDiscovery(this)
        if (WirelessPairingStore.isPaired(this)) {
            startExistingConnection()
        } else {
            startNotificationPairing()
        }
    }

    private fun startExistingConnection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingNotificationAction = { startExistingConnection() }
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val hasKnownPort = WirelessAdbPortStore.port(this) != null ||
            WirelessConnectDiscovery.currentPort() != null
        WirelessPairingNotifications.showProgress(this, getString(R.string.helper_connecting))
        ContextCompat.startForegroundService(
            this,
            Intent(this, WirelessPairingService::class.java)
                .setAction(WirelessPairingService.ACTION_RETRY_CONNECT),
        )
        if (!hasKnownPort) openWirelessDebuggingSettings()
    }

    private fun startNotificationPairing() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingNotificationAction = { postPairingNotificationAndOpenSettings() }
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        postPairingNotificationAndOpenSettings()
    }

    private fun postPairingNotificationAndOpenSettings() {
        WirelessPairingNotifications.showFreshPairInput(this)
        toast(getString(R.string.pair_notification_posted))
        openWirelessDebuggingSettings()
    }

    private fun showDeveloperOptionsRequiredDialog() {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pair_developer_options_required_title)
            .setMessage(R.string.pair_developer_options_required_steps)
            .setPositiveButton(R.string.pair_open_device_info, null)
            .setNegativeButton(R.string.kill_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                if (!DeveloperSettings.openDeviceInfoSettings(this)) {
                    toast(getString(R.string.pair_open_settings_failed))
                }
            }
        }
        dialog.show()
    }

    private fun showWirelessDebuggingRequiredDialog() {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pair_wireless_debugging_required_title)
            .setMessage(R.string.pair_wireless_debugging_required_steps)
            .setPositiveButton(R.string.pair_open_developer_options, null)
            .setNegativeButton(R.string.kill_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                openWirelessDebuggingSettings()
            }
        }
        dialog.show()
    }

    private fun showWirelessDebuggingUnsupportedDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pair_wireless_not_supported_title)
            .setMessage(R.string.pair_wireless_not_supported_message)
            .setPositiveButton(R.string.helper_copy_command) { _, _ ->
                copyToClipboard(HelperClient.pcAdbCommand())
                toast(getString(R.string.helper_copied))
            }
            .setNegativeButton(R.string.kill_cancel, null)
            .show()
    }

    private fun openWirelessDebuggingSettings() {
        when (DeveloperSettings.openWirelessDebugging(this)) {
            DeveloperSettings.OpenResult.DEVELOPER_OPTIONS ->
                toast(getString(R.string.pair_wireless_settings_fallback))

            DeveloperSettings.OpenResult.FAILED ->
                toast(getString(R.string.pair_open_settings_failed))

            DeveloperSettings.OpenResult.WIRELESS_DEBUGGING -> Unit
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("adb", text))
    }

    private fun openUsageAccessSettings() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            // Some OEM builds expose a different entry point; fall back to the app details page.
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.parse("package:$packageName"))
            )
        }
    }

    private fun showSortDialog() {
        val values = SortOption.entries.toTypedArray()
        val labels = arrayOf(
            getString(R.string.sort_cpu),
            getString(R.string.sort_memory),
            getString(R.string.sort_name),
        )
        val checked = values.indexOf(viewModel.state.value.sortOption).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.menu_sort)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                viewModel.setSortOption(values[which])
                dialog.dismiss()
            }
            .show()
    }

    private fun showLanguageDialog() {
        val values = arrayOf(
            LocaleManager.LANG_SYSTEM,
            LocaleManager.LANG_CHINESE,
            LocaleManager.LANG_ENGLISH,
        )
        val labels = arrayOf(
            getString(R.string.language_system),
            getString(R.string.language_chinese),
            getString(R.string.language_english),
        )
        val checked = values.indexOf(LocaleManager.savedLanguage(this)).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.menu_language)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                LocaleManager.setLanguage(this, values[which])
                dialog.dismiss()
                recreate()
            }
            .show()
    }

    private fun showAppearanceDialog() {
        val values = intArrayOf(
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            AppCompatDelegate.MODE_NIGHT_NO,
            AppCompatDelegate.MODE_NIGHT_YES,
        )
        val labels = arrayOf(
            getString(R.string.appearance_system),
            getString(R.string.appearance_light),
            getString(R.string.appearance_dark),
        )
        val checked = values.indexOf(ThemeManager.savedMode(this)).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.menu_appearance)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                ThemeManager.setMode(this, values[which])
                dialog.dismiss()
            }
            .show()
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }
}
