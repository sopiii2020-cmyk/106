package dev.voltguard.battery

import dev.voltguard.battery.engine.CommandBlocklist
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandBlocklistTest {

    @Test
    fun allowedNonRootCommandsPassBlocklist() {
        assertTrue(CommandBlocklist.isCommandAllowed("svc wifi disable"))
        assertTrue(CommandBlocklist.isCommandAllowed("svc bluetooth disable"))
        assertTrue(CommandBlocklist.isCommandAllowed("cmd location set-location-enabled false"))
        assertTrue(CommandBlocklist.isCommandAllowed("cmd connectivity airplane-mode disable"))
        assertTrue(CommandBlocklist.isCommandAllowed("cmd notification set_dnd off"))
        assertTrue(CommandBlocklist.isCommandAllowed("cmd uimode night no"))
        assertTrue(CommandBlocklist.isCommandAllowed("cmd power set-mode 0"))
        assertTrue(CommandBlocklist.isCommandAllowed("svc nfc disable"))
    }

    @Test
    fun forbiddenHotspotTetheringAndRotationCommandsAreBlocked() {
        assertFalse(CommandBlocklist.isCommandAllowed("cmd wifi stop-softap"))
        assertFalse(CommandBlocklist.isCommandAllowed("svc wifi tether disable"))
        assertFalse(CommandBlocklist.isCommandAllowed("settings put global hotspot_enabled 0"))
        assertFalse(CommandBlocklist.isCommandAllowed("settings put system accelerometer_rotation 0"))
        assertFalse(CommandBlocklist.isCommandAllowed("settings put system user_rotation 0"))
    }
}
