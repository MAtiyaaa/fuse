package io.github.matiyaaa.fuse.ui.shell.app

import io.github.matiyaaa.fuse.model.ConnectionState
import io.github.matiyaaa.fuse.model.PerformanceMetric
import io.github.matiyaaa.fuse.model.SystemStatus
import io.github.matiyaaa.fuse.ui.shell.store.StorageSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatusGaugesTest {
    private val wifi = SystemStatus(wifi = ConnectionState.CONNECTED, wifiStrength = 4)

    @Test
    fun androidReportsTemperatureThermalStateAndFreeMemory() {
        val gauges = statusGauges(
            listOf(
                PerformanceMetric("cpu_temp", "CPU", "52 °C", 0.52f, source = "thermal"),
                PerformanceMetric("thermal", "Thermal state", "Warm", 0.5f, source = "PowerManager"),
                PerformanceMetric("memory", "Memory", "3.1 of 8.0 GB free", 0.61f, source = "ActivityManager"),
            ),
            wifi, null,
        )
        assertEquals(listOf(GaugeKind.PROCESSOR, GaugeKind.MEMORY, GaugeKind.WIFI), gauges.map { it.kind })
        val cpu = gauges[0]
        assertEquals("52 °C", cpu.value)
        assertEquals("Running warm", cpu.detail)
        assertEquals("52°", cpu.centre)
        assertTrue(cpu.warn, "a warm thermal state warns")
        val memory = gauges[1]
        assertEquals("61% used", memory.value)
        assertEquals("3.1 of 8.0 GB free", memory.detail)
        assertEquals("Excellent signal", gauges[2].detail)
    }

    @Test
    fun computersReportLoadAndUsedMemory() {
        val gauges = statusGauges(
            listOf(
                PerformanceMetric("cpu", "Processor", "34%", 0.34f, source = "/proc/stat"),
                PerformanceMetric("cpu-temp", "CPU temperature", "48 °C", 0.48f, source = "hwmon"),
                PerformanceMetric("memory", "Memory", "5.2 / 16.0 GB", 0.33f, source = "/proc/meminfo"),
            ),
            wifi, StorageSummary("Games", freeBytes = 4_000_000_000, totalBytes = 100_000_000_000),
        )
        val cpu = gauges.first { it.kind == GaugeKind.PROCESSOR }
        assertEquals("34% busy", cpu.value)
        assertEquals("48 °C", cpu.detail)
        assertEquals("34%", cpu.centre)
        assertFalse(cpu.warn)
        assertEquals("5.2 of 16.0 GB", gauges.first { it.kind == GaugeKind.MEMORY }.detail)
        val storage = gauges.first { it.kind == GaugeKind.STORAGE }
        assertEquals("96%", storage.centre)
        assertTrue(storage.warn, "under 5% free warns")
    }

    @Test
    fun onlyWifiWhenNothingIsMeasured() {
        val gauges = statusGauges(emptyList(), SystemStatus(wifi = ConnectionState.OFF), null)
        assertEquals(listOf(GaugeKind.WIFI), gauges.map { it.kind })
        assertEquals("Off", gauges.single().value)
        assertEquals(0f, gauges.single().fraction)
        assertNull(gauges.single().centre)
    }
}
