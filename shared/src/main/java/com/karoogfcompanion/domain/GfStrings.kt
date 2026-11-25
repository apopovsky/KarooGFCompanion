package com.karoogfcompanion.domain

import java.util.Locale

/**
 * Localized UI copy for English (default), Spanish, and Portuguese.
 */
data class GfStrings(
    val langCode: String,
    val hrLabel: String,
    val cadLabel: String,
    val lapAvgLabel: String,
    val nextLabel: String,
    val approachingTag: String,
    val statusLow: String,
    val statusHigh: String,
    val noPowerSensor: String,
    val noTargetHere: String,
    val noActiveRoute: String,
    val noNextPoint: String,
    val routePointsComplete: String,
    val pausedBadge: String,
    val offRouteBadge: String,
    val newTargetBadge: String,
    val demoBadge: String,
    val segmentListHeader: String,
    val noPowerTargetsInRoute: String,
    val unitWatts: String,
    val unitBpm: String,
    val unitRpm: String,
) {
    fun nextApproachingFormat(distStr: String): String = "$nextLabel • $distStr"

    fun backToGaugeFormat(seconds: Int): String = when (langCode) {
        "es" -> "← Volver (${seconds}s)"
        "pt" -> "← Voltar (${seconds}s)"
        else -> "← Back (${seconds}s)"
    }

    fun smoothingLabel(mode: PowerSmoothingMode): String = when (mode) {
        PowerSmoothingMode.INSTANT_1S -> if (langCode == "es") "1s (Inst.)" else if (langCode == "pt") "1s (Inst.)" else "1s (Instant)"
        PowerSmoothingMode.SMOOTHED_3S -> "3s"
        PowerSmoothingMode.SMOOTHED_5S -> "5s"
        PowerSmoothingMode.SMOOTHED_10S -> "10s"
    }

    fun themeLabel(theme: ThemePreference): String = when (theme) {
        ThemePreference.AUTO -> "Auto"
        ThemePreference.LIGHT -> when (langCode) {
            "es" -> "Día (Claro)"
            "pt" -> "Dia (Claro)"
            else -> "Light"
        }
        ThemePreference.DARK -> when (langCode) {
            "es" -> "Noche (Oscuro)"
            "pt" -> "Noite (Escuro)"
            else -> "Dark"
        }
    }

    fun tapActionLabel(action: TapActionPreference): String = when (action) {
        TapActionPreference.OPEN_CUESHEET_PLUS -> when (langCode) {
            "es" -> "KCS+ Detalle y ETA"
            "pt" -> "KCS+ Detalhe e ETA"
            else -> "KCS+ Detail & ETA"
        }
        TapActionPreference.OPEN_CUESHEET_LIST -> when (langCode) {
            "es", "pt" -> "KCS+ Lista Cue Sheet"
            else -> "KCS+ Cue Sheet List"
        }
        TapActionPreference.IN_FIELD_SEGMENT_LIST -> when (langCode) {
            "es" -> "Plan en campo"
            "pt" -> "Plano no campo"
            else -> "Segment Plan"
        }
        TapActionPreference.NONE -> when (langCode) {
            "es" -> "Desactivado"
            "pt" -> "Desativado"
            else -> "Disabled"
        }
    }

    fun nextCardKcsHint(action: TapActionPreference): String? = when (action) {
        TapActionPreference.OPEN_CUESHEET_PLUS -> "ETA • KCS+ ↗"
        TapActionPreference.OPEN_CUESHEET_LIST -> "KCS+ ↗"
        TapActionPreference.IN_FIELD_SEGMENT_LIST,
        TapActionPreference.NONE -> null
    }

    companion object {
        private val EN = GfStrings(
            langCode = "en",
            hrLabel = "HR",
            cadLabel = "CAD",
            lapAvgLabel = "STEP AVG",
            nextLabel = "NEXT",
            approachingTag = "APPROACHING",
            statusLow = "LOW",
            statusHigh = "HIGH",
            noPowerSensor = "NO POWER",
            noTargetHere = "NO TARGET",
            noActiveRoute = "No active route",
            noNextPoint = "No upcoming points",
            routePointsComplete = "All points passed",
            pausedBadge = "PAUSED",
            offRouteBadge = "OFF ROUTE",
            newTargetBadge = "NEW TARGET",
            demoBadge = "PREVIEW",
            segmentListHeader = "PACING PLAN",
            noPowerTargetsInRoute = "No power targets in route",
            unitWatts = "W",
            unitBpm = "bpm",
            unitRpm = "rpm",
        )

        private val ES = GfStrings(
            langCode = "es",
            hrLabel = "FC",
            cadLabel = "CAD",
            lapAvgLabel = "PROM PASO",
            nextLabel = "PRÓXIMO",
            approachingTag = "CERCA",
            statusLow = "BAJO",
            statusHigh = "ALTO",
            noPowerSensor = "SIN POTENCIA",
            noTargetHere = "SIN OBJETIVO",
            noActiveRoute = "Sin ruta activa",
            noNextPoint = "Sin próximos puntos",
            routePointsComplete = "Puntos completados",
            pausedBadge = "EN PAUSA",
            offRouteBadge = "FUERA DE RUTA",
            newTargetBadge = "NUEVO OBJETIVO",
            demoBadge = "VISTA PREVIA",
            segmentListHeader = "PLAN DE POTENCIA",
            noPowerTargetsInRoute = "Sin objetivos de potencia",
            unitWatts = "W",
            unitBpm = "bpm",
            unitRpm = "rpm",
        )

        private val PT = GfStrings(
            langCode = "pt",
            hrLabel = "FC",
            cadLabel = "CAD",
            lapAvgLabel = "MÉD PASSO",
            nextLabel = "PRÓXIMO",
            approachingTag = "PRÓXIMO",
            statusLow = "BAIXO",
            statusHigh = "ALTO",
            noPowerSensor = "SEM POTÊNCIA",
            noTargetHere = "SEM ALVO",
            noActiveRoute = "Sem rota ativa",
            noNextPoint = "Sem próximos pontos",
            routePointsComplete = "Pontos concluídos",
            pausedBadge = "PAUSADO",
            offRouteBadge = "FORA DA ROTA",
            newTargetBadge = "NOVO ALVO",
            demoBadge = "PRÉVIA",
            segmentListHeader = "PLANO DE POTÊNCIA",
            noPowerTargetsInRoute = "Sem alvos de potência",
            unitWatts = "W",
            unitRpm = "rpm",
            unitBpm = "bpm",
        )

        fun forLocale(locale: Locale = Locale.getDefault()): GfStrings {
            return when (locale.language.lowercase(Locale.ROOT)) {
                "es" -> ES
                "pt" -> PT
                else -> EN
            }
        }
    }
}
