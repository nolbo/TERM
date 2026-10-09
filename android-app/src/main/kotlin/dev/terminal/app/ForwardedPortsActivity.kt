package dev.terminal.app

import android.os.Bundle
import dev.terminal.app.ui.TerminalActivity

/** Compatibility entry for existing tasks; there is only one dashboard implementation. */
class ForwardedPortsActivity : TerminalActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(LocalServicesActivity.intent(this, "AVF"))
        finish()
    }
}
