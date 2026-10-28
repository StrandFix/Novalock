package pl.iskri.pocketlock

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

class SetupActivity : Activity() {

    private var tab = TAB_OPTIONS

    // Values of the "Screen off after" spinner, in seconds; 0 means "never".
    private val screenTimeoutValues = intArrayOf(5, 10, 15, 30, 60, 120, 0)

    // Values of the "Unlock animation" spinner.
    private val animationValues = arrayOf(
        Prefs.ANIMATION_SLIDE_DOWN,
        Prefs.ANIMATION_SLIDE_UP,
        Prefs.ANIMATION_FADE,
        Prefs.ANIMATION_ZOOM_OUT,
        Prefs.ANIMATION_ZOOM_IN,
        Prefs.ANIMATION_FADE_SLIDE
    )

    // Values of the "Animation duration" spinner, in milliseconds.
    private val animationDurationValues = intArrayOf(150, 250, 350, 500, 750)

    private val enabledListener = CompoundButton.OnCheckedChangeListener { _, checked ->
        Prefs.setEnabled(this, checked)
        if (checked) LockService.start(this) else LockService.stop(this)
        updateStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.applyTopBarDefaultsAfterUpdate(this)
        Prefs.applyNovaDotDefaultsAfterUpdate(this)
        Prefs.applyV6StatusDefaults(this)
        setContentView(R.layout.activity_setup)

        findViewById<Button>(R.id.btnTabPermissions).setOnClickListener { setTab(TAB_PERMISSIONS) }
        findViewById<Button>(R.id.btnTabOptions).setOnClickListener { setTab(TAB_OPTIONS) }

        findViewById<ImageButton>(R.id.btnInfo).setOnClickListener { showInstructions() }

        findViewById<Button>(R.id.btnNovaAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            }
        }

        findViewById<Button>(R.id.btnUsage).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (_: Exception) {
            }
        }

        findViewById<Button>(R.id.btnHideSystemNotification).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                })
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")))
            }
        }

        findViewById<Button>(R.id.btnNotification).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }

        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

        findViewById<Button>(R.id.btnAdmin).setOnClickListener { requestAdmin() }

        findViewById<Switch>(R.id.swEnabled).setOnCheckedChangeListener(enabledListener)

        findViewById<Button>(R.id.btnAppearance).setOnClickListener {
            startActivity(Intent(this, AppearanceActivity::class.java))
        }

        findViewById<Button>(R.id.btnButtons).setOnClickListener {
            startActivity(Intent(this, ButtonsActivity::class.java))
        }

        val cbBadge = findViewById<CheckBox>(R.id.cbNovaBadge)
        cbBadge.isChecked = Prefs.isPreviewBadgeEnabled(this)
        cbBadge.setOnCheckedChangeListener { _, checked ->
            Prefs.setPreviewBadgeEnabled(this, checked)
        }

        val speedChoices = longArrayOf(
            Prefs.CAPTURE_SAVER_MS,
            Prefs.CAPTURE_BALANCED_MS,
            Prefs.CAPTURE_RESPONSIVE_MS
        )
        val captureSpeed = findViewById<Spinner>(R.id.spNovaCaptureSpeed)
        captureSpeed.adapter = ArrayAdapter(this, R.layout.spinner_item, arrayOf(
            "Battery saver  ·  every 25 seconds",
            "Balanced  ·  every 11 seconds",
            "Responsive  ·  every 6 seconds"
        )).apply { setDropDownViewResource(R.layout.spinner_dropdown_item) }
        captureSpeed.setSelection(
            speedChoices.indexOf(Prefs.captureIntervalMs(this)).coerceAtLeast(1)
        )
        captureSpeed.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                val selected = speedChoices[position]
                if (Prefs.captureIntervalMs(this@SetupActivity) == selected) return
                Prefs.setCaptureIntervalMs(this@SetupActivity, selected)
                NovaCaptureService.onCapturePreferenceChanged()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        val cocoonModes = arrayOf(
            Prefs.COCOON_MODE_LIVE,
            Prefs.COCOON_MODE_HERO,
            Prefs.COCOON_MODE_ICON,
            Prefs.COCOON_MODE_LOGO
        )
        val cocoonSpinner = findViewById<Spinner>(R.id.spCocoonArtworkMode)
        cocoonSpinner.adapter = ArrayAdapter(this, R.layout.spinner_item, arrayOf(
            "Live screenshot (default)",
            "Cocoon hero · screenshot if no game match",
            "Cocoon game icon · screenshot fallback",
            "Cocoon game logo · screenshot fallback"
        )).apply { setDropDownViewResource(R.layout.spinner_dropdown_item) }
        cocoonSpinner.setSelection(
            cocoonModes.indexOf(Prefs.cocoonImageMode(this)).coerceAtLeast(0)
        )
        cocoonSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                val mode = cocoonModes[position]
                if (Prefs.cocoonImageMode(this@SetupActivity) == mode) return
                Prefs.setCocoonImageMode(this@SetupActivity, mode)
                CocoonArtworkStore.ensureIndexed(this@SetupActivity)
                updateCocoonArtStatus()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        findViewById<Button>(R.id.btnCocoonFolder).setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQUEST_COCOON_FOLDER)
            } catch (_: Exception) {
                Toast.makeText(this, "Folder picker is unavailable", Toast.LENGTH_LONG).show()
            }
        }
        findViewById<Button>(R.id.btnCocoonRescan).setOnClickListener {
            CocoonArtworkStore.rebuildIndex(this) {
                runOnUiThread { if (!isFinishing && !isDestroyed) updateCocoonArtStatus() }
            }
            updateCocoonArtStatus()
        }
        findViewById<Button>(R.id.btnCocoonDiagnostics).setOnClickListener {
            val report = CocoonArtworkStore.diagnostics(this)
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(
                "Novalock Cocoon diagnostics", report))
            Toast.makeText(this, "Cocoon diagnostics copied", Toast.LENGTH_LONG).show()
        }
        updateCocoonArtStatus()

        val cbCapture = findViewById<CheckBox>(R.id.cbNovaCapture)
        cbCapture.isChecked = Prefs.isCaptureEnabled(this)
        captureSpeed.isEnabled = cbCapture.isChecked
        cbCapture.setOnCheckedChangeListener { _, checked ->
            captureSpeed.isEnabled = checked
            Prefs.setCaptureEnabled(this, checked)
            NovaCaptureService.onCapturePreferenceChanged()
            updateStatus()
        }

        val cbFloat = findViewById<CheckBox>(R.id.cbNovaFloat)
        cbFloat.isChecked = Prefs.isFloatEnabled(this)
        cbFloat.setOnCheckedChangeListener { _, checked ->
            Prefs.setFloatEnabled(this, checked)
        }

        val cbSound = findViewById<CheckBox>(R.id.cbSound)
        cbSound.isChecked = Prefs.isSoundEnabled(this)
        cbSound.setOnCheckedChangeListener { _, checked -> Prefs.setSoundEnabled(this, checked) }

        val cbVibration = findViewById<CheckBox>(R.id.cbVibration)
        cbVibration.isChecked = Prefs.isVibrationEnabled(this)
        cbVibration.setOnCheckedChangeListener { _, checked ->
            Prefs.setVibrationEnabled(this, checked)
        }

        val cbTouch = findViewById<CheckBox>(R.id.cbTouch)
        cbTouch.isChecked = Prefs.isTouchEnabled(this)
        cbTouch.setOnCheckedChangeListener { _, checked ->
            Prefs.setTouchEnabled(this, checked)
        }

        val cbTriggers = findViewById<CheckBox>(R.id.cbTriggers)
        cbTriggers.isChecked = Prefs.isTriggersEnabled(this)
        cbTriggers.setOnCheckedChangeListener { _, checked ->
            Prefs.setTriggersEnabled(this, checked)
        }

        val cbRememberPresses = findViewById<CheckBox>(R.id.cbRememberPresses)
        cbRememberPresses.isChecked = Prefs.rememberPresses(this)
        cbRememberPresses.setOnCheckedChangeListener { _, checked ->
            Prefs.setRememberPresses(this, checked)
            if (!checked) {
                Prefs.setPressCount(this, 0)
            }
        }

        val cbNotification = findViewById<CheckBox>(R.id.cbNotification)
        cbNotification.isChecked = Prefs.isNotificationEnabled(this)
        cbNotification.setOnCheckedChangeListener { _, checked ->
            Prefs.setNotificationEnabled(this, checked)
            if (Prefs.isEnabled(this)) {
                LockService.start(this)
            }
        }

        val timeoutSpinner = findViewById<Spinner>(R.id.spScreenTimeout)
        val timeoutLabels = screenTimeoutValues.map { seconds ->
            when {
                seconds <= 0 -> getString(R.string.screen_timeout_never)
                seconds < 60 -> getString(R.string.screen_timeout_seconds, seconds)
                else -> getString(R.string.screen_timeout_minutes, seconds / 60)
            }
        }
        timeoutSpinner.adapter =
            ArrayAdapter(this, R.layout.spinner_item, timeoutLabels).apply {
                setDropDownViewResource(R.layout.spinner_dropdown_item)
            }
        val timeoutIndex = screenTimeoutValues.indexOf(Prefs.screenOffSeconds(this))
        timeoutSpinner.setSelection(
            if (timeoutIndex >= 0) timeoutIndex else screenTimeoutValues.lastIndex
        )
        timeoutSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                val seconds = screenTimeoutValues[position]
                if (Prefs.screenOffSeconds(this@SetupActivity) == seconds) return
                Prefs.setScreenOffSeconds(this@SetupActivity, seconds)
                if (seconds > 0 && !ScreenTimeout.isAdminActive(this@SetupActivity)) {
                    Toast.makeText(
                        this@SetupActivity,
                        R.string.screen_timeout_admin_needed,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
            }
        }

        val animationSpinner = findViewById<Spinner>(R.id.spAnimation)
        val animationLabels = animationValues.map { value ->
            when (value) {
                Prefs.ANIMATION_SLIDE_UP -> getString(R.string.animation_slide_up)
                Prefs.ANIMATION_FADE -> getString(R.string.animation_fade)
                Prefs.ANIMATION_ZOOM_OUT -> getString(R.string.animation_zoom_out)
                Prefs.ANIMATION_ZOOM_IN -> getString(R.string.animation_zoom_in)
                Prefs.ANIMATION_FADE_SLIDE -> getString(R.string.animation_fade_slide)
                else -> getString(R.string.animation_slide_down)
            }
        }
        animationSpinner.adapter =
            ArrayAdapter(this, R.layout.spinner_item, animationLabels).apply {
                setDropDownViewResource(R.layout.spinner_dropdown_item)
            }
        val animationIndex = animationValues.indexOf(Prefs.animationType(this))
        animationSpinner.setSelection(if (animationIndex >= 0) animationIndex else 0)
        animationSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                Prefs.setAnimationType(this@SetupActivity, animationValues[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
            }
        }

        val durationSpinner = findViewById<Spinner>(R.id.spAnimationDuration)
        val durationLabels = animationDurationValues.map { ms ->
            getString(R.string.animation_duration_ms, ms)
        }
        durationSpinner.adapter =
            ArrayAdapter(this, R.layout.spinner_item, durationLabels).apply {
                setDropDownViewResource(R.layout.spinner_dropdown_item)
            }
        val durationIndex = animationDurationValues.indexOf(Prefs.animationDuration(this))
        durationSpinner.setSelection(
            if (durationIndex >= 0) durationIndex else animationDurationValues.indexOf(350)
        )
        durationSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                Prefs.setAnimationDuration(this@SetupActivity, animationDurationValues[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
            }
        }

        if (Prefs.isEnabled(this)) {
            LockService.start(this)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val content = findViewById<FrameLayout>(R.id.setup_content)
            content.setOnApplyWindowInsetsListener { view, insets ->
                val gestureBottom = insets.getInsets(WindowInsets.Type.systemGestures()).bottom
                view.setPadding(
                    view.paddingLeft,
                    view.paddingTop,
                    view.paddingRight,
                    dp(72) + gestureBottom
                )
                insets
            }
        }

        setTab(if (Settings.canDrawOverlays(this)) TAB_OPTIONS else TAB_PERMISSIONS)
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        updateCocoonArtStatus()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (requestCode != REQUEST_COCOON_FOLDER) return
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            CocoonArtworkStore.connectFolder(this, uri) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) updateCocoonArtStatus()
                }
            }
            updateCocoonArtStatus()
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot access the selected folder", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateCocoonArtStatus() {
        findViewById<TextView>(R.id.tvCocoonArtStatus)?.text =
            if (!CocoonArtworkStore.selectedFolder(this)) {
                "No folder linked. Select the data folder configured in Cocoon."
            } else CocoonArtworkStore.status + "\n" + CocoonVisualMatcher.report
    }

    private fun setTab(newTab: Int) {
        tab = newTab
        findViewById<View>(R.id.tab_permissions).visibility =
            if (newTab == TAB_PERMISSIONS) View.VISIBLE else View.GONE
        findViewById<View>(R.id.tab_options).visibility =
            if (newTab == TAB_OPTIONS) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.btnTabPermissions).apply {
            alpha = 1f
            setBackgroundResource(if (newTab == TAB_PERMISSIONS)
                R.drawable.nova_tab_active else R.drawable.nova_tab_inactive)
        }
        findViewById<Button>(R.id.btnTabOptions).apply {
            alpha = 1f
            setBackgroundResource(if (newTab == TAB_OPTIONS)
                R.drawable.nova_tab_active else R.drawable.nova_tab_inactive)
        }
    }

    private fun requestAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(
                DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                ComponentName(this@SetupActivity, LockDeviceAdminReceiver::class.java)
            )
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                getString(R.string.device_admin_explanation)
            )
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
        }
    }

    private fun showInstructions() {
        AlertDialog.Builder(this)
            .setTitle(R.string.instructions_title)
            .setMessage(R.string.instructions)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun updateStatus() {
        findViewById<TextView>(R.id.tvNovaStatus).text =
            "Game preview: " + (if (NovaCaptureService.isConnected) "Service active. " else
                "Accessibility service is not running. ") + GamePreviewStore.captureStatus
        val overlay = Settings.canDrawOverlays(this)
        val notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        val battery = power.isIgnoringBatteryOptimizations(packageName)

        findViewById<TextView>(R.id.tvOverlayStatus).text =
            getString(R.string.status_overlay) + ": " + yesNo(overlay)
        findViewById<TextView>(R.id.tvUsageStatus).text =
            getString(R.string.status_usage_access) + ": " + yesNo(hasUsageAccess())
        findViewById<TextView>(R.id.tvNotificationStatus).text =
            getString(R.string.status_notifications) + ": " + yesNo(notifications)
        findViewById<TextView>(R.id.tvBatteryStatus).text =
            getString(R.string.status_battery) + ": " + yesNo(battery)

        findViewById<TextView>(R.id.tvAdminStatus).text =
            getString(R.string.status_device_admin) + ": " +
            yesNo(ScreenTimeout.isAdminActive(this))

        findViewById<TextView>(R.id.tvServiceStatus).text =
            getString(R.string.status_service) + ": " +
            if (LockService.isRunning) getString(R.string.yes) else getString(R.string.no)

        val sw = findViewById<Switch>(R.id.swEnabled)
        val enabled = Prefs.isEnabled(this)
        if (sw.isChecked != enabled) {
            sw.setOnCheckedChangeListener(null)
            sw.isChecked = enabled
            sw.setOnCheckedChangeListener(enabledListener)
        }
    }

    private fun yesNo(value: Boolean): String =
        if (value) getString(R.string.yes) else getString(R.string.no)

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            applicationInfo.uid,
            packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val TAB_PERMISSIONS = 0
        const val TAB_OPTIONS = 1
        const val REQUEST_COCOON_FOLDER = 554
    }
}
