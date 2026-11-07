package app.absentmotion.export

import org.junit.Assert.*
import org.junit.Test

class CompatibleExportTest {
    @Test fun affectedFirmwareUsesSafePathOnlyOnTheReportedFamily() {
        assertTrue(needsCompatibleExport(34,"redwood"))
        assertTrue(needsCompatibleExport(34,"redwood_ru"))
        assertFalse(needsCompatibleExport(33,"redwood"))
        assertFalse(needsCompatibleExport(35,"redwood"))
        assertFalse(needsCompatibleExport(34,"other"))
    }
    @Test fun exportPreferenceRoundTripKeepsCompatibilityChoice() {
        val value=ExportSettings(1080,30,compatibleEncoder=true)
        assertEquals(value,settingsFromJson(settingsToJson(value),ExportSettings(720,24)))
    }
}
