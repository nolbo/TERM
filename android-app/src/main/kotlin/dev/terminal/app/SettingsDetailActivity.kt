package dev.terminal.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatRadioButton
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.CompoundButtonCompat
import androidx.core.widget.doAfterTextChanged
import dev.terminal.app.databinding.ActivitySettingsDetailBinding
import dev.terminal.app.ui.GridFieldView
import dev.terminal.app.ui.TerminalMotion
import dev.terminal.app.ui.TerminalActivity
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.lifecycle.VmRuntimeService

class SettingsDetailActivity : TerminalActivity() {
    private lateinit var binding: ActivitySettingsDetailBinding
    private val terminalPreferences by lazy { getSharedPreferences(TERMINAL_PREFERENCES, Context.MODE_PRIVATE) }
    private val runtimePreferences by lazy { getSharedPreferences(RUNTIME_PREFERENCES, Context.MODE_PRIVATE) }
    private var runtime: IRuntimeService? = null
    private var bound = false
    private var currentSection: LinearLayout? = null
    private var sectionHasItem = false
    private val connection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            runtime = IRuntimeService.Stub.asInterface(service); bound = true
            if (category == AVF || category == ROOTLESS) render()
        }
        override fun onServiceDisconnected(name: ComponentName) { runtime = null; bound = false }
    }
    private val category get() = intent.getStringExtra(EXTRA_CATEGORY) ?: APPEARANCE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        installRootInsets(binding.root, includeIme = true)
        binding.toolbar.setNavigationOnClickListener { finishWithMotion() }
        render()
    }
    override fun onStart() { super.onStart(); bindService(Intent(this, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE) }
    override fun onStop() { if (bound) unbindService(connection); bound = false; runtime = null; super.onStop() }

    private fun render() {
        TerminalMotion.fadeThrough(binding.settingsRows)
        binding.settingsRows.removeAllViews()
        currentSection = null
        sectionHasItem = false
        when (category) {
            APPEARANCE -> renderAppearance()
            AVF -> renderAvf()
            ROOTLESS -> renderRootless()
            else -> renderNative()
        }
    }

    private fun renderAppearance() {
        binding.toolbar.title = "터미널 모양"
        binding.description.text = "콘솔로 돌아가면 저장된 글꼴과 팔레트가 적용됩니다."
        addSection("글자")
        addIntegerInput("글자 크기", "8~72 범위의 값을 입력하세요.", "sp", terminalPreferences.getInt(KEY_FONT_SIZE, 16), 8..72) {
            terminalPreferences.edit().putInt(KEY_FONT_SIZE, it).apply()
        }
        addSingleChoice("글꼴", "터미널에 사용할 고정폭 글꼴입니다.", listOf("기본 고정폭" to "MONOSPACE", "Sans 고정폭" to "SANS_MONOSPACE"), terminalPreferences.getString(KEY_FONT_FAMILY, "MONOSPACE") ?: "MONOSPACE") {
            terminalPreferences.edit().putString(KEY_FONT_FAMILY, it).apply()
        }
        addSwitch("고정 문자 폭", "모든 글리프를 동일한 셀 폭으로 렌더링합니다.", terminalPreferences.getBoolean(KEY_FIXED_WIDTH, true)) {
            terminalPreferences.edit().putBoolean(KEY_FIXED_WIDTH, it).apply()
        }
        addSection("팔레트")
        addSingleChoice("색상 팔레트", "전경, 배경과 커서 색상을 함께 변경합니다.", listOf("중성 다크" to "DROID_DARK", "솔라라이즈드 다크" to "SOLARIZED_DARK", "AMOLED 블랙" to "AMOLED"), terminalPreferences.getString(KEY_PALETTE, "DROID_DARK") ?: "DROID_DARK") {
            terminalPreferences.edit().putString(KEY_PALETTE, it).apply()
        }
    }

    private fun renderAvf() {
        binding.toolbar.title = "가상 머신 (AVF)"
        binding.description.text = "VM 리소스 변경은 다음 시작부터 적용됩니다. 맞춤 VM에는 시스템 권한이 필요합니다."
        addSection("리소스")
        addIntegerInput("메모리", "게스트에 할당할 메모리입니다.", "MiB", runtimePreferences.getInt(KEY_AVF_MEMORY, 2048), 512..32768) {
            runtimePreferences.edit().putInt(KEY_AVF_MEMORY, it).apply()
        }
        addIntegerInput("가상 CPU", "게스트가 사용할 vCPU 개수입니다.", "개", runtimePreferences.getInt(KEY_AVF_CPU, 2), 1..16) {
            runtimePreferences.edit().putInt(KEY_AVF_CPU, it).apply()
        }
        addSwitch("보호된 VM", "기기가 지원할 때 보호된 메모리 격리를 요청합니다.", runtimePreferences.getBoolean(KEY_AVF_PROTECTED, false)) {
            runtimePreferences.edit().putBoolean(KEY_AVF_PROTECTED, it).apply()
        }
        addSwitch("게스트 네트워크", "VM의 virtio 네트워크 장치를 활성화합니다.", runtimePreferences.getBoolean(KEY_AVF_NETWORK, true)) {
            runtimePreferences.edit().putBoolean(KEY_AVF_NETWORK, it).apply()
        }
        addSection("실행")
        val policies = listOf("배터리 최적화" to "BATTERY_OPTIMIZED", "작업 중 유지" to "KEEP_ACTIVE_WORK_RUNNING", "항상 켜기" to "ALWAYS_AWAKE")
        addSingleChoice("절전 정책", "백그라운드 실행과 wake lock 정책입니다.", policies, runtime?.wakePolicy ?: runtimePreferences.getString(KEY_WAKE_POLICY, "BATTERY_OPTIMIZED") ?: "BATTERY_OPTIMIZED") {
            runtimePreferences.edit().putString(KEY_WAKE_POLICY, it).apply(); runtime?.setWakePolicy(it)
        }
        addAction("Debian 이미지", "자동 설치 또는 검증된 로컬 이미지를 선택합니다.") { startActivityWithMotion(Intent(this, SetupActivity::class.java)) }
        addAction("상태 및 전달 포트", "이미지, 런타임, 공유 저장소와 포트를 확인합니다.") { startActivityWithMotion(Intent(this, DiagnosticsActivity::class.java)) }
    }

    private fun renderNative() {
        binding.toolbar.title = "네이티브 런타임"
        binding.description.text = "Termux 호환 PTY 세션을 만드는 기본값입니다. 새 세션부터 적용됩니다."
        addSection("셸")
        addTextInput("기본 셸", "실행할 절대 경로입니다.", runtimePreferences.getString(KEY_NATIVE_SHELL, "/system/bin/sh") ?: "/system/bin/sh") { value, field ->
            val valid = value.startsWith("/") && value.length <= 256
            field.setError(if (valid) null else "절대 경로를 입력하세요.")
            if (valid) runtimePreferences.edit().putString(KEY_NATIVE_SHELL, value).apply()
        }
        addSingleChoice("터미널 유형", "셸과 프로그램에 전달할 TERM 값입니다.", listOf("xterm-256color" to "xterm-256color", "xterm" to "xterm", "screen-256color" to "screen-256color"), runtimePreferences.getString(KEY_NATIVE_TERM, "xterm-256color") ?: "xterm-256color") {
            runtimePreferences.edit().putString(KEY_NATIVE_TERM, it).apply()
        }
        addSwitch("로그인 셸", "셸을 -l 옵션으로 실행합니다.", runtimePreferences.getBoolean(KEY_NATIVE_LOGIN, true)) {
            runtimePreferences.edit().putBoolean(KEY_NATIVE_LOGIN, it).apply()
        }
        addSection("터미널 기록")
        addIntegerInput("스크롤백", "각 탭이 보관할 최대 기록 행 수입니다.", "행", terminalPreferences.getInt(KEY_SCROLLBACK, 10_000), 1_000..100_000) {
            terminalPreferences.edit().putInt(KEY_SCROLLBACK, it).apply()
        }
        addAction("Android 권한", "알림과 파일 선택 등 시스템 권한을 확인합니다.") {
            startActivityWithMotion(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    private fun renderRootless() {
        binding.toolbar.title = "Rootless Debian"
        binding.description.text = "추가 Android 권한 없이 PRoot에서 Debian ARM64/glibc와 공식 apt를 사용합니다. AVF로 자동 전환되지 않습니다."
        val status = runCatching {
            org.json.JSONObject(runtime?.runtimeStatusJson.orEmpty()).optJSONObject("ROOTLESS")
        }.getOrNull()
        addSection("상태")
        addAction(
            if (status?.optBoolean("available") == true) "사용 가능" else "설치 필요",
            status?.optString("detail").orEmpty().ifBlank { "서비스에 연결하면 rootfs와 APK PRoot 상태를 확인합니다." },
        ) { render() }
        addSection("환경")
        addAction("공유 작업공간", "Android와 Rootless Debian의 /workspace만 공유합니다. rootfs는 AVF와 공유하지 않습니다.") { }
        addAction("제약 사항", "PRoot는 실제 root, systemd, namespace/cgroup, 커널 mount를 제공하지 않으며 syscall과 파일 I/O 오버헤드가 있습니다.") { }
    }

    private fun addSection(title: String) {
        binding.settingsRows.addView(TextView(this).apply {
            text = title
            setTextAppearance(R.style.Text_TerminalGrid_Label)
            setPadding(20.dp(), 24.dp(), 20.dp(), 8.dp())
        })
        val rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 0)
            }
        }
        binding.settingsRows.addView(rows)
        currentSection = rows
        sectionHasItem = false
    }

    private fun addIntegerInput(title: String, helper: String, suffix: String, value: Int, range: IntRange, save: (Int) -> Unit) {
        val field = gridField(title, helper, suffix).apply { useNumberKeyboard(); input.setText(value.toString()); input.selectAll() }
        addControl(field)
        field.input.doAfterTextChanged {
            val parsed = it?.toString()?.toIntOrNull(); val valid = parsed != null && parsed in range
            field.setError(if (valid) null else "${range.first}~${range.last} 범위로 입력하세요.")
            if (valid) save(parsed!!)
        }
    }

    private fun addTextInput(title: String, helper: String, value: String, changed: (String, GridFieldView) -> Unit) {
        val field = gridField(title, helper).apply { useTextKeyboard(); input.setText(value); input.setSelection(input.text?.length ?: 0) }
        addControl(field)
        field.input.doAfterTextChanged { changed(it?.toString().orEmpty().trim(), field) }
    }

    private fun addSingleChoice(title: String, helper: String, options: List<Pair<String, String>>, selected: String, save: (String) -> Unit) {
        val currentLabel = options.firstOrNull { it.second == selected }?.first ?: options.first().first
        addToSection(selectionRow(title, helper, currentLabel).apply {
            setOnClickListener { showChoiceDialog(title, options, selected, save) }
        })
    }

    private fun addSwitch(title: String, summary: String, checked: Boolean, save: (Boolean) -> Unit) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(20.dp(), 12.dp(), 16.dp(), 12.dp()); minimumHeight = 76.dp()
            background = AppCompatResources.getDrawable(this@SettingsDetailActivity, R.drawable.bg_grid_row)
            isClickable = true
            isFocusable = true
        }
        container.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@SettingsDetailActivity).apply { text = title; setTextAppearance(R.style.Text_TerminalGrid_Title) })
            addView(summaryView(summary))
        })
        val toggle = SwitchCompat(this).apply {
            showText = false
            thumbTintList = AppCompatResources.getColorStateList(this@SettingsDetailActivity, R.color.grid_switch_thumb)
            trackTintList = AppCompatResources.getColorStateList(this@SettingsDetailActivity, R.color.grid_switch_track)
            isChecked = checked
            contentDescription = title
            minimumWidth = 52.dp()
            minimumHeight = 48.dp()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 48.dp()).apply { marginStart = 12.dp() }
            setOnCheckedChangeListener { _, value -> save(value) }
        }
        container.addView(toggle)
        container.setOnClickListener { toggle.isChecked = !toggle.isChecked }
        addToSection(container)
    }

    private fun addAction(title: String, summary: String, action: () -> Unit) {
        addToSection(preferenceRow(title, summary).apply { setOnClickListener { action() } })
    }

    private fun gridField(title: String, helper: String, suffix: String = "") = GridFieldView(this, title, helper, suffix).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(12.dp(), 8.dp(), 12.dp(), 8.dp()) }
    }
    private fun addControl(view: View) = addToSection(view)
    private fun addToSection(view: View) {
        val section = checkNotNull(currentSection) { "설정 섹션이 먼저 생성되어야 합니다." }
        if (sectionHasItem) section.addView(dividerView())
        section.addView(view)
        sectionHasItem = true
    }
    private fun summaryView(value: String) = TextView(this).apply {
        text = value
        setTextAppearance(R.style.Text_TerminalGrid_Caption)
        setPadding(0, 4.dp(), 0, 0)
    }
    private fun preferenceRow(title: String, summary: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = 76.dp()
        setPadding(20.dp(), 12.dp(), 12.dp(), 12.dp())
        background = AppCompatResources.getDrawable(this@SettingsDetailActivity, R.drawable.bg_grid_row)
        isClickable = true
        isFocusable = true
        addView(LinearLayout(this@SettingsDetailActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@SettingsDetailActivity).apply { text = title; setTextAppearance(R.style.Text_TerminalGrid_Title) })
            addView(summaryView(summary))
        })
        addView(chevron())
    }
    private fun selectionRow(title: String, summary: String, selectedLabel: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = 76.dp()
        setPadding(20.dp(), 12.dp(), 12.dp(), 12.dp())
        background = AppCompatResources.getDrawable(this@SettingsDetailActivity, R.drawable.bg_grid_row)
        isClickable = true
        isFocusable = true
        addView(LinearLayout(this@SettingsDetailActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@SettingsDetailActivity).apply { text = title; setTextAppearance(R.style.Text_TerminalGrid_Title) })
            addView(summaryView(summary))
        })
        addView(TextView(this@SettingsDetailActivity).apply {
            text = selectedLabel
            setTextAppearance(R.style.Text_TerminalGrid_Caption)
            setTextColor(ContextCompat.getColor(this@SettingsDetailActivity, R.color.grid_text))
            background = AppCompatResources.getDrawable(this@SettingsDetailActivity, R.drawable.bg_grid_value_chip)
            gravity = Gravity.CENTER
            maxWidth = 132.dp()
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(12.dp(), 6.dp(), 12.dp(), 6.dp())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = 12.dp() }
        })
        addView(chevron())
    }
    private fun chevron() = ImageView(this).apply {
        setImageResource(R.drawable.ic_chevron_right)
        contentDescription = null
        layoutParams = LinearLayout.LayoutParams(24.dp(), 24.dp()).apply { marginStart = 6.dp() }
    }
    private fun showChoiceDialog(title: String, options: List<Pair<String, String>>, selected: String, save: (String) -> Unit) {
        lateinit var dialog: AlertDialog
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = AppCompatResources.getDrawable(this@SettingsDetailActivity, R.drawable.bg_grid_panel)
            setPadding(16.dp(), 20.dp(), 16.dp(), 16.dp())
            addView(TextView(this@SettingsDetailActivity).apply {
                text = title
                setTextAppearance(R.style.Text_TerminalGrid_Title)
                setPadding(8.dp(), 0, 8.dp(), 12.dp())
            })
            options.forEach { option ->
                addView(AppCompatRadioButton(this@SettingsDetailActivity).apply {
                    text = option.first
                    setTextAppearance(R.style.Text_TerminalGrid_Body)
                    setTextColor(ContextCompat.getColor(this@SettingsDetailActivity, R.color.grid_text))
                    isChecked = option.second == selected
                    CompoundButtonCompat.setButtonTintList(this, AppCompatResources.getColorStateList(this@SettingsDetailActivity, R.color.grid_radio_tint))
                    minimumHeight = 52.dp()
                    background = AppCompatResources.getDrawable(this@SettingsDetailActivity, R.drawable.bg_grid_row)
                    setPadding(8.dp(), 0, 8.dp(), 0)
                    setOnClickListener {
                        save(option.second)
                        dialog.dismiss()
                        render()
                    }
                })
            }
            addView(Button(this@SettingsDetailActivity).apply {
                text = "취소"
                isAllCaps = false
                setTextAppearance(R.style.Text_TerminalGrid_Body)
                setTextColor(ContextCompat.getColor(this@SettingsDetailActivity, R.color.grid_text))
                background = AppCompatResources.getDrawable(this@SettingsDetailActivity, R.drawable.bg_grid_action_secondary)
                setOnClickListener { dialog.dismiss() }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 48.dp()).apply { topMargin = 12.dp() }
            })
        }
        dialog = AlertDialog.Builder(this).setView(panel).create()
        TerminalMotion.configureDialog(dialog)
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            dialog.window?.setLayout(resources.displayMetrics.widthPixels - 40.dp(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }
    private fun dividerView() = View(this).apply {
        setBackgroundColor(ContextCompat.getColor(this@SettingsDetailActivity, R.color.grid_line))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1.dp()).apply { marginStart = 20.dp(); marginEnd = 20.dp() }
    }
    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_CATEGORY = "category"; const val APPEARANCE = "appearance"; const val AVF = "avf"; const val NATIVE = "native"; const val ROOTLESS = "rootless"
        const val TERMINAL_PREFERENCES = "terminal-ui"; const val RUNTIME_PREFERENCES = "runtime-settings"
        const val KEY_FONT_SIZE = "font-size-sp"; const val KEY_FONT_FAMILY = "font-family"; const val KEY_PALETTE = "palette"; const val KEY_FIXED_WIDTH = "fixed-width"; const val KEY_SCROLLBACK = "scrollback-rows"
        const val KEY_AVF_MEMORY = "avf-memory-mib"; const val KEY_AVF_CPU = "avf-cpu-count"; const val KEY_AVF_PROTECTED = "avf-protected"; const val KEY_AVF_NETWORK = "avf-network"; const val KEY_WAKE_POLICY = "wake-policy"
        const val KEY_NATIVE_SHELL = "native-shell"; const val KEY_NATIVE_TERM = "native-term"; const val KEY_NATIVE_LOGIN = "native-login-shell"
    }
}
