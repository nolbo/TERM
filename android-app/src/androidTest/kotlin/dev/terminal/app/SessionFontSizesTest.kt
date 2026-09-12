package dev.terminal.app

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class SessionFontSizesTest {
    @Test fun zoomIsBoundedIndependentAndPersistsAcrossRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("zoom-test-${java.util.UUID.randomUUID()}", Context.MODE_PRIVATE)
        try {
            val sizes = SessionFontSizes(prefs)
            var applied = 0
            val input = TerminalInputCallbacks(onPinchScale = { factor ->
                sizes.scale("first", 16, factor) { applied = it }
            })
            assertEquals(1.05f, input.onScale(1.05f))
            assertEquals(0, applied)
            input.onScale(1.2f)
            assertEquals(17, applied)
            assertEquals(16, sizes.get("second", 16))
            sizes.scale("second", 16, 0.8f) {}
            val restored = SessionFontSizes(prefs)
            assertEquals(17, restored.get("first", 20))
            assertEquals(15, restored.get("second", 20))
            assertEquals(20, restored.get("new", 20))
            repeat(100) { input.onScale(2f) }
            assertEquals(72, sizes.get("first", 16))
            repeat(100) { input.onScale(0.5f) }
            assertEquals(4, sizes.get("first", 16))
            assertEquals(15, sizes.get("second", 16))
        } finally { prefs.edit().clear().commit() }
    }
}
