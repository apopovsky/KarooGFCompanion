package com.karoogfcompanion.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.karoogfcompanion.data.GfSettingsStore
import com.karoogfcompanion.domain.DemoPacingProvider
import com.karoogfcompanion.domain.GfStrings
import com.karoogfcompanion.domain.PowerSmoothingMode
import com.karoogfcompanion.domain.PowerTargetRange
import com.karoogfcompanion.domain.RoutePacingEngine
import com.karoogfcompanion.domain.TapActionPreference
import com.karoogfcompanion.domain.ThemePreference
import com.karoogfcompanion.extension.CueSheetPlusIntegration
import com.karoogfcompanion.render.GfCompanionRenderer
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private lateinit var settingsStore: GfSettingsStore
    private lateinit var kcsStatusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = GfSettingsStore.getInstance(this)
        if (intent?.getBooleanExtra("dump_screenshots", false) == true) {
            dumpHardwareScreenshots()
        }
        val strings = GfStrings.forLocale()
        val isEs = strings.langCode == "es"
        val isPt = strings.langCode == "pt"

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0B0D10"))
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(14)
            setPadding(pad, dp(16), pad, dp(76))
        }
        scroll.addView(root)

        // Header
        root.addView(TextView(this).apply {
            text = "KAROO GF COMPANION"
            setTextColor(Color.parseColor("#14C23A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = when {
                isEs -> "Guía gráfica de potencia por segmentos y próximo punto de ruta"
                isPt -> "Guia gráfico de potência por segmentos e próximo ponto da rota"
                else -> "Graphical route power pacing gauge & next waypoint guide"
            }
            setTextColor(Color.parseColor("#9CA3AF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(2), 0, dp(12))
        })

        // Settings Card
        val configCard = createCardContainer()
        configCard.addView(
            sectionHeader(
                when {
                    isEs -> "CONFIGURACIÓN DE LA EXTENSIÓN"
                    isPt -> "CONFIGURAÇÕES DA EXTENSÃO"
                    else -> "EXTENSION SETTINGS"
                }
            )
        )

        val smoothingValueText = addStepperRow(
            parent = configCard,
            title = when {
                isEs -> "Suavizado de potencia"
                isPt -> "Suavização de potência"
                else -> "Power averaging"
            },
            subtitle = when {
                isEs -> "Ventana usada para el valor actual y la aguja (3s recomendado)"
                isPt -> "Janela usada para potência atual e ponteiro (3s recomendado)"
                else -> "Averaging window for current power and gauge needle (3s recommended)"
            },
            onMinus = { cycleSmoothing(-1) },
            onPlus = { cycleSmoothing(+1) },
            minusLabel = "◀",
            plusLabel = "▶",
        )

        val themeValueText = addStepperRow(
            parent = configCard,
            title = when {
                isEs -> "Tema de color"
                isPt -> "Tema de cor"
                else -> "Color theme"
            },
            subtitle = when {
                isEs -> "Auto sigue el modo Día/Noche configurado en Karoo OS"
                isPt -> "Auto segue o modo Dia/Noite do perfil no Karoo OS"
                else -> "Auto follows Karoo OS Day/Night profile setting"
            },
            onMinus = { cycleTheme(-1) },
            onPlus = { cycleTheme(+1) },
            minusLabel = "◀",
            plusLabel = "▶",
        )

        val approachingValueText = addStepperRow(
            parent = configCard,
            title = when {
                isEs -> "Aviso de próximo punto cercano"
                isPt -> "Aviso de próximo ponto próximo"
                else -> "Approaching point distance"
            },
            subtitle = when {
                isEs -> "Resalta la tarjeta NEXT cuando falta menos de esta distancia"
                isPt -> "Destaca o cartão PRÓXIMO abaixo desta distância"
                else -> "Highlights the NEXT card within this remaining distance"
            },
            onMinus = {
                settingsStore.update { s ->
                    s.copy(approachingDistanceMeters = (s.approachingDistanceMeters - 250).coerceIn(250, 1500))
                }
            },
            onPlus = {
                settingsStore.update { s ->
                    s.copy(approachingDistanceMeters = (s.approachingDistanceMeters + 250).coerceIn(250, 1500))
                }
            },
        )

        val toleranceValueText = addStepperRow(
            parent = configCard,
            title = when {
                isEs -> "Tolerancia de objetivo único"
                isPt -> "Tolerância de alvo único"
                else -> "Single-value target tolerance"
            },
            subtitle = when {
                isEs -> "Margen +/- cuando el waypoint indica un solo valor, ej. (200W)"
                isPt -> "Margem +/- quando o ponto define um valor único, ex. (200W)"
                else -> "+/- band applied when a waypoint has a single value, e.g. (200W)"
            },
            onMinus = {
                settingsStore.update { s ->
                    s.copy(singleTargetToleranceWatts = (s.singleTargetToleranceWatts - 5).coerceIn(5, 25))
                }
            },
            onPlus = {
                settingsStore.update { s ->
                    s.copy(singleTargetToleranceWatts = (s.singleTargetToleranceWatts + 5).coerceIn(5, 25))
                }
            },
        )

        val zoneBgSwitch = addSwitchRow(
            parent = configCard,
            title = when {
                isEs -> "Fondos por zona (FC / CAD / PASO)"
                isPt -> "Fundos por zona (FC / CAD / PASSO)"
                else -> "Zone-colored backgrounds"
            },
            subtitle = when {
                isEs -> "Colorea FC y Prom. Paso según tus zonas de Karoo, y cadencia (80–95 rpm verde)"
                isPt -> "Colore FC e Méd. Passo conforme zonas do Karoo, e cadência (80–95 rpm verde)"
                else -> "Colors HR & Step Avg using Karoo zones, and Cadence (80–95 rpm green)"
            },
            initial = settingsStore.settings.value.zoneColorBackgrounds,
        ) { checked ->
            settingsStore.update { it.copy(zoneColorBackgrounds = checked) }
        }

        val soundSwitch = addSwitchRow(
            parent = configCard,
            title = when {
                isEs -> "Sonido al cambiar de segmento"
                isPt -> "Som na mudança de segmento"
                else -> "Segment change sound"
            },
            subtitle = when {
                isEs -> "Emite un tono breve al entrar en un nuevo objetivo de potencia"
                isPt -> "Emite um alerta sonoro ao iniciar um novo alvo de potência"
                else -> "Plays a brief double tone when entering a new power target segment"
            },
            initial = settingsStore.settings.value.segmentAlertSound,
        ) { checked ->
            settingsStore.update { it.copy(segmentAlertSound = checked) }
        }

        val popupSwitch = addSwitchRow(
            parent = configCard,
            title = when {
                isEs -> "Alerta visual emergente"
                isPt -> "Alerta visual pop-up"
                else -> "Segment change banner"
            },
            subtitle = when {
                isEs -> "Muestra un aviso temporal en pantalla al cambiar el objetivo"
                isPt -> "Mostra um banner temporário ao mudar o alvo de potência"
                else -> "Displays a brief in-ride banner when the power target changes"
            },
            initial = settingsStore.settings.value.segmentAlertPopup,
        ) { checked ->
            settingsStore.update { it.copy(segmentAlertPopup = checked) }
        }

        val tapActionValueText = addStepperRow(
            parent = configCard,
            title = when {
                isEs -> "Al tocar la tarjeta NEXT"
                isPt -> "Ao tocar no cartão PRÓXIMO"
                else -> "Tap NEXT card action"
            },
            subtitle = when {
                isEs -> "Abre el detalle y ETA en CueSheet+ (si está instalada) o el plan en campo"
                isPt -> "Abre o detalhe e ETA no CueSheet+ (se instalado) ou o plano no campo"
                else -> "Opens waypoint detail & ETA in CueSheet+ (if installed) or in-field plan"
            },
            onMinus = { cycleTapAction(-1) },
            onPlus = { cycleTapAction(+1) },
            minusLabel = "◀",
            plusLabel = "▶",
        )

        kcsStatusText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(8), 0, dp(2))
        }
        configCard.addView(kcsStatusText)
        refreshKcsStatus()

        root.addView(configCard)

        // Setup Help Card
        val helpCard = createCardContainer().apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(12)
        }
        helpCard.addView(
            sectionHeader(
                when {
                    isEs -> "CÓMO AÑADIR A TUS PANTALLAS EN KAROO"
                    isPt -> "COMO ADICIONAR ÀS TELAS DO KAROO"
                    else -> "HOW TO ADD TO YOUR KAROO PAGES"
                }
            )
        )
        helpCard.addView(TextView(this).apply {
            text = when {
                isEs ->
                    "1. Ve a Perfiles → Páginas → Añadir Campo.\n" +
                        "2. Selecciona Karoo GF Companion → \"Guía Potencia GF\" en página completa (1 campo) o medio campo (2 campos).\n" +
                        "3. Carga una ruta cuyos waypoints incluyan rangos como (190-205W)."
                isPt ->
                    "1. Vá em Perfis → Páginas → Adicionar Campo.\n" +
                        "2. Selecione Karoo GF Companion → \"Guia Potência GF\" em tela cheia ou metade da tela.\n" +
                        "3. Carregue uma rota com pontos contendo faixas como (190-205W)."
                else ->
                    "1. Go to Profiles → Pages → Add Field.\n" +
                        "2. Choose Karoo GF Companion → \"GF Pacing Guide\" in a 1-field (full) or 2-field (half) layout.\n" +
                        "3. Load a route whose waypoints embed power targets such as (190-205W)."
            }
            setTextColor(Color.parseColor("#D1D5DB"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(6), 0, 0)
        })
        root.addView(helpCard)

        // Root container + Hardware Back FAB
        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0B0D10"))
        }
        container.addView(
            scroll,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        )

        val backFab = FrameLayout(this).apply {
            val fabW = dp(52)
            val fabH = dp(50)
            layoutParams = FrameLayout.LayoutParams(fabW, fabH).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                bottomMargin = dp(12)
                leftMargin = 0
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                val r = dp(25).toFloat()
                cornerRadii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
                setStroke(dp(1), Color.parseColor("#38BDF8"))
            }
            isClickable = true
            isFocusable = true
            elevation = dp(8).toFloat()

            addView(TextView(context).apply {
                text = "←"
                setTextColor(Color.parseColor("#14C23A"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ).apply { rightMargin = dp(4) }
            })
            setOnClickListener { finish() }
        }
        container.addView(backFab)
        setContentView(container)

        lifecycleScope.launch {
            settingsStore.settings.collect { s ->
                val curStrings = GfStrings.forLocale()
                smoothingValueText.text = curStrings.smoothingLabel(s.powerSmoothingMode)
                themeValueText.text = curStrings.themeLabel(s.themePreference)
                approachingValueText.text = "${s.approachingDistanceMeters} m"
                toleranceValueText.text = "±${s.singleTargetToleranceWatts} W"
                if (zoneBgSwitch.isChecked != s.zoneColorBackgrounds) zoneBgSwitch.isChecked = s.zoneColorBackgrounds
                if (soundSwitch.isChecked != s.segmentAlertSound) soundSwitch.isChecked = s.segmentAlertSound
                if (popupSwitch.isChecked != s.segmentAlertPopup) popupSwitch.isChecked = s.segmentAlertPopup
                tapActionValueText.text = curStrings.tapActionLabel(s.tapActionPreference)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshKcsStatus()
    }

    private fun refreshKcsStatus() {
        val strings = GfStrings.forLocale()
        val isEs = strings.langCode == "es"
        val isPt = strings.langCode == "pt"
        val installed = CueSheetPlusIntegration.isInstalled(this)
        val supportsLive = installed && CueSheetPlusIntegration.supportsLiveActivity(this)
        val version = CueSheetPlusIntegration.getInstalledVersion(this)
        val verSuffix = if (!version.isNullOrBlank()) " (v$version)" else ""

        when {
            supportsLive -> {
                kcsStatusText.setTextColor(Color.parseColor("#14C23A"))
                kcsStatusText.text = when {
                    isEs -> "✓ Karoo Cue Sheet+ detectada$verSuffix — navegación directa a detalle y ETA"
                    isPt -> "✓ Karoo Cue Sheet+ detectado$verSuffix — navegação direta para detalhe e ETA"
                    else -> "✓ Karoo Cue Sheet+ detected$verSuffix — direct detail & ETA navigation ready"
                }
            }
            installed -> {
                kcsStatusText.setTextColor(Color.parseColor("#FACC15"))
                kcsStatusText.text = when {
                    isEs -> "⚠ Karoo Cue Sheet+$verSuffix detectada sin vista directa — usará Plan en campo"
                    isPt -> "⚠ Karoo Cue Sheet+$verSuffix sem vista direta — usará Plano no campo"
                    else -> "⚠ Karoo Cue Sheet+$verSuffix lacks live view — falling back to Segment Plan"
                }
            }
            else -> {
                kcsStatusText.setTextColor(Color.parseColor("#9CA3AF"))
                kcsStatusText.text = when {
                    isEs -> "Karoo Cue Sheet+ no detectada — usará Plan en campo automáticamente"
                    isPt -> "Karoo Cue Sheet+ não detectado — usará Plano no campo automaticamente"
                    else -> "Karoo Cue Sheet+ not detected — will fall back to Segment Plan"
                }
            }
        }
    }

    private fun cycleSmoothing(delta: Int) {
        val values = PowerSmoothingMode.entries
        settingsStore.update { s ->
            val nextIdx = (s.powerSmoothingMode.ordinal + delta + values.size) % values.size
            s.copy(powerSmoothingMode = values[nextIdx])
        }
    }

    private fun cycleTheme(delta: Int) {
        val values = ThemePreference.entries
        settingsStore.update { s ->
            val nextIdx = (s.themePreference.ordinal + delta + values.size) % values.size
            s.copy(themePreference = values[nextIdx])
        }
    }

    private fun cycleTapAction(delta: Int) {
        val values = TapActionPreference.entries
        settingsStore.update { s ->
            val nextIdx = (s.tapActionPreference.ordinal + delta + values.size) % values.size
            s.copy(tapActionPreference = values[nextIdx])
        }
    }

    private fun createCardContainer(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            val pad = dp(14)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#161A20"))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.parseColor("#2A303B"))
            }
        }
    }

    private fun sectionHeader(title: String): TextView {
        return TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    private fun addSwitchRow(
        parent: LinearLayout,
        title: String,
        subtitle: String,
        initial: Boolean,
        onChanged: (Boolean) -> Unit,
    ): Switch {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(8))
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(10)
            }
        }
        textCol.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
        })
        textCol.addView(TextView(this).apply {
            text = subtitle
            setTextColor(Color.parseColor("#9CA3AF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })
        val sw = Switch(this).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, checked -> onChanged(checked) }
        }
        row.addView(textCol)
        row.addView(sw)
        parent.addView(row)
        parent.addView(dividerView())
        return sw
    }

    private fun addStepperRow(
        parent: LinearLayout,
        title: String,
        subtitle: String,
        onMinus: () -> Unit,
        onPlus: () -> Unit,
        minusLabel: String = "−",
        plusLabel: String = "+",
    ): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(10))
        }
        row.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
        })
        row.addView(TextView(this).apply {
            text = subtitle
            setTextColor(Color.parseColor("#9CA3AF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(2), 0, dp(8))
        })

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val btnMinus = pillButton(minusLabel, onMinus)
        val valView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FACC15"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val btnPlus = pillButton(plusLabel, onPlus)

        controls.addView(btnMinus)
        controls.addView(valView)
        controls.addView(btnPlus)
        row.addView(controls)
        parent.addView(row)
        parent.addView(dividerView())
        return valView
    }

    private fun pillButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(42))
            setPadding(0, 0, 0, 0)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#252B36"))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#4B5563"))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun dividerView(): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(Color.parseColor("#242933"))
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).roundToInt()

    private fun dumpHardwareScreenshots() {
        val en = GfStrings.forLocale(Locale.ENGLISH)
        val lightSnap = DemoPacingProvider.buildDemoSnapshot(strings = en)

        val darkPts = lightSnap.allPoints.mapIndexed { idx, pt ->
            val rem = (pt.distanceAlongRouteM - 28_200.0).coerceAtLeast(0.0)
            pt.copy(
                remainingDistanceM = rem,
                isPassed = idx <= 3,
                isActiveSegmentStart = idx == 3,
                isNextPoint = idx == 4,
            )
        }
        val darkTarget = PowerTargetRange(195, 210)
        val darkGauge = RoutePacingEngine.evaluateGauge(216, isPowerAvailable = true, target = darkTarget)
        val darkSnap = lightSnap.copy(
            riderDistanceAlongRouteM = 28_200.0,
            isDemoMode = false,
            currentPowerWatts = 216,
            heartRateBpm = 161,
            cadenceRpm = 82,
            lapAvgPowerWatts = 204,
            activeSegmentPoint = darkPts[3],
            currentTarget = darkTarget,
            gaugeZone = darkGauge.zone,
            deviationStatus = darkGauge.status,
            needleAngleDeg = darkGauge.needleAngleDeg,
            nextPoint = darkPts[4],
            allPoints = darkPts,
        )

        val cleanLightSnap = lightSnap.copy(isDemoMode = false)
        val planSnap = cleanLightSnap.copy(isSegmentOverlayOpen = true, overlayRemainingSeconds = 8)

        fun savePng(name: String, bmp: Bitmap) {
            File(filesDir, name).outputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }

        savePng("shot_light.png", GfCompanionRenderer.renderFullPage(cleanLightSnap, 420, 680, isNightMode = false))
        savePng("shot_dark.png", GfCompanionRenderer.renderFullPage(darkSnap, 420, 680, isNightMode = true))
        savePng("shot_plan.png", GfCompanionRenderer.renderFullPage(planSnap, 420, 680, isNightMode = true))
        savePng("shot_half.png", GfCompanionRenderer.renderHalfPage(cleanLightSnap, 420, 340, isNightMode = false))
    }
}
