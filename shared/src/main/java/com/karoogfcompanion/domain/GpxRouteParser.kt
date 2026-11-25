package com.karoogfcompanion.domain

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.io.InputStreamReader
import javax.xml.parsers.SAXParserFactory

data class ParsedGpxRoute(
    val routeName: String,
    val indexedPolyline: RouteGeometryEngine.IndexedPolyline,
    val elevationProfile: List<ElevationSample>,
    val routePois: List<RawRoutePoi>,
) {
    val totalDistanceM: Double get() = indexedPolyline.totalDistanceM
}

/**
 * SAX-based GPX parser used for unit testing and simulator GPX loading.
 * Extracts track points, smoothed elevation samples, and only the waypoints present in the GPX.
 */
object GpxRouteParser {

    fun parse(inputStream: InputStream, fallbackName: String = "GPX Route"): ParsedGpxRoute {
        val raw = parseXml(inputStream)
        val trackPoints = if (raw.trkPoints.isNotEmpty()) raw.trkPoints else raw.rtePoints
        val latLngs = trackPoints.map { LatLngPoint(it.lat, it.lng) }
        val indexed = RouteGeometryEngine.buildIndexedPolyline(latLngs)
        val elevationProfile = buildElevationProfile(trackPoints, indexed.cumulativeDistM)

        val pois = raw.waypoints.mapIndexedNotNull { index, wpt ->
            val passDistances = RouteGeometryEngine.findDistancesAlongRoute(
                indexed = indexed,
                target = LatLngPoint(wpt.lat, wpt.lng),
            )
            if (passDistances.isEmpty()) {
                null
            } else {
                RawRoutePoi(
                    id = "gpx_wpt_$index",
                    lat = wpt.lat,
                    lng = wpt.lng,
                    type = wpt.type.ifBlank { wpt.sym },
                    name = wpt.name.ifBlank { null },
                    description = wpt.desc.ifBlank { null },
                    distancesAlongRouteM = passDistances,
                )
            }
        }

        val resolvedName = raw.metadataName.ifBlank {
            raw.trackName.ifBlank { fallbackName }
        }

        return ParsedGpxRoute(
            routeName = resolvedName,
            indexedPolyline = indexed,
            elevationProfile = elevationProfile,
            routePois = pois,
        )
    }

    fun interpolateElevationAt(profile: List<ElevationSample>, distanceM: Double): Double {
        if (profile.isEmpty()) return 0.0
        if (distanceM <= profile.first().distanceM) return profile.first().elevationM
        if (distanceM >= profile.last().distanceM) return profile.last().elevationM

        var lo = 0
        var hi = profile.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val midDist = profile[mid].distanceM
            when {
                midDist < distanceM -> lo = mid + 1
                midDist > distanceM -> hi = mid - 1
                else -> return profile[mid].elevationM
            }
        }
        val right = lo.coerceIn(1, profile.lastIndex)
        val left = right - 1
        val span = profile[right].distanceM - profile[left].distanceM
        if (span <= 0.001) return profile[left].elevationM
        val t = ((distanceM - profile[left].distanceM) / span).coerceIn(0.0, 1.0)
        return profile[left].elevationM + t * (profile[right].elevationM - profile[left].elevationM)
    }

    fun gradeAtDistance(profile: List<ElevationSample>, distanceM: Double): Double {
        if (profile.size < 2) return 0.0
        val total = profile.last().distanceM
        if (total <= 10.0) return 0.0
        val d0 = (distanceM - 60.0).coerceIn(0.0, total)
        val d1 = (distanceM + 60.0).coerceIn(0.0, total)
        val span = d1 - d0
        if (span < 10.0) return 0.0
        val e0 = interpolateElevationAt(profile, d0)
        val e1 = interpolateElevationAt(profile, d1)
        return (((e1 - e0) / span) * 100.0).coerceIn(-25.0, 25.0)
    }

    private fun buildElevationProfile(
        points: List<RawTrkPt>,
        cumulativeDistM: DoubleArray,
    ): List<ElevationSample> {
        if (points.isEmpty() || cumulativeDistM.size != points.size) return emptyList()
        if (points.none { it.ele != null }) return emptyList()

        val result = ArrayList<ElevationSample>(points.size)
        var lastEle = points.firstOrNull { it.ele != null }?.ele ?: 0.0
        for (i in points.indices) {
            val ele = points[i].ele ?: lastEle
            lastEle = ele
            if (result.isEmpty() || cumulativeDistM[i] - result.last().distanceM >= 25.0 || i == points.lastIndex) {
                result.add(ElevationSample(cumulativeDistM[i], ele))
            }
        }
        return result
    }

    private data class RawWpt(
        val lat: Double,
        val lng: Double,
        var name: String = "",
        var type: String = "",
        var sym: String = "",
        var desc: String = "",
    )

    private data class RawTrkPt(
        val lat: Double,
        val lng: Double,
        var ele: Double? = null,
    )

    private class RawGpxData(
        var metadataName: String = "",
        var trackName: String = "",
        val waypoints: MutableList<RawWpt> = ArrayList(),
        val trkPoints: MutableList<RawTrkPt> = ArrayList(),
        val rtePoints: MutableList<RawTrkPt> = ArrayList(),
    )

    private fun parseXml(inputStream: InputStream): RawGpxData {
        val result = RawGpxData()
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val parser = factory.newSAXParser()

        val handler = object : DefaultHandler() {
            private val tagStack = ArrayDeque<String>()
            private val textBuf = StringBuilder()

            private var currentWpt: RawWpt? = null
            private var currentTrkPt: RawTrkPt? = null
            private var currentRtePt: RawTrkPt? = null

            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                val tag = (localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty()).lowercase()
                tagStack.addLast(tag)
                textBuf.setLength(0)

                when (tag) {
                    "wpt" -> {
                        val lat = attributes.getValue("lat")?.toDoubleOrNull()
                        val lon = attributes.getValue("lon")?.toDoubleOrNull()
                        if (lat != null && lon != null) {
                            currentWpt = RawWpt(lat, lon)
                        }
                    }
                    "trkpt" -> {
                        val lat = attributes.getValue("lat")?.toDoubleOrNull()
                        val lon = attributes.getValue("lon")?.toDoubleOrNull()
                        if (lat != null && lon != null) {
                            currentTrkPt = RawTrkPt(lat, lon)
                        }
                    }
                    "rtept" -> {
                        val lat = attributes.getValue("lat")?.toDoubleOrNull()
                        val lon = attributes.getValue("lon")?.toDoubleOrNull()
                        if (lat != null && lon != null) {
                            currentRtePt = RawTrkPt(lat, lon)
                        }
                    }
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                textBuf.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                val tag = (localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty()).lowercase()
                val text = textBuf.toString().trim()
                val parent = if (tagStack.size >= 2) tagStack.elementAt(tagStack.size - 2) else ""

                when {
                    currentWpt != null -> {
                        val w = currentWpt!!
                        when (tag) {
                            "name" -> w.name = text
                            "type" -> w.type = text
                            "sym" -> w.sym = text
                            "desc", "cmt" -> if (w.desc.isEmpty()) w.desc = text
                            "wpt" -> {
                                result.waypoints.add(w)
                                currentWpt = null
                            }
                        }
                    }
                    currentTrkPt != null -> {
                        val pt = currentTrkPt!!
                        when (tag) {
                            "ele" -> pt.ele = text.toDoubleOrNull()
                            "trkpt" -> {
                                result.trkPoints.add(pt)
                                currentTrkPt = null
                            }
                        }
                    }
                    currentRtePt != null -> {
                        val pt = currentRtePt!!
                        when (tag) {
                            "ele" -> pt.ele = text.toDoubleOrNull()
                            "rtept" -> {
                                result.rtePoints.add(pt)
                                currentRtePt = null
                            }
                        }
                    }
                    tag == "name" && parent == "metadata" -> {
                        if (result.metadataName.isEmpty()) result.metadataName = text
                    }
                    tag == "name" && (parent == "trk" || parent == "rte") -> {
                        if (result.trackName.isEmpty()) result.trackName = text
                    }
                }

                if (tagStack.isNotEmpty()) {
                    tagStack.removeLast()
                }
                textBuf.setLength(0)
            }
        }

        parser.parse(InputSource(InputStreamReader(inputStream, Charsets.UTF_8)), handler)
        return result
    }
}
