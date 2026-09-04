package dev.terminal.app

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalViewClient

class TerminalCallbacks(private val redraw: () -> Unit) : TerminalSessionClient {
    override fun onTextChanged(s: TerminalSession) = redraw(); override fun onTitleChanged(s: TerminalSession) = redraw(); override fun onSessionFinished(s: TerminalSession) = redraw()
    override fun onCopyTextToClipboard(s: TerminalSession, t: String) = Unit; override fun onPasteTextFromClipboard(s: TerminalSession) = Unit; override fun onBell(s: TerminalSession) = Unit; override fun onColorsChanged(s: TerminalSession) = redraw(); override fun onTerminalCursorStateChange(s: Boolean) = redraw()
    override fun getTerminalCursorStyle(): Int? = null
    override fun logError(a:String,b:String)=Unit; override fun logWarn(a:String,b:String)=Unit; override fun logInfo(a:String,b:String)=Unit; override fun logDebug(a:String,b:String)=Unit; override fun logVerbose(a:String,b:String)=Unit; override fun logStackTraceWithMessage(a:String,b:String,e:Exception)=Unit; override fun logStackTrace(a:String,e:Exception)=Unit
}
class TerminalInputCallbacks(private val onModifierStateChanged: () -> Unit = {}) : TerminalViewClient {
    var controlPressed = false
    var altPressed = false
    override fun onScale(s: Float)=1f; override fun onSingleTapUp(e: MotionEvent)=Unit; override fun shouldBackButtonBeMappedToEscape()=true; override fun shouldEnforceCharBasedInput()=false; override fun shouldEnableImeComposing()=true; override fun shouldUseCtrlSpaceWorkaround()=false; override fun isTerminalViewSelected()=true; override fun copyModeChanged(c:Boolean)=Unit
    override fun onKeyDown(k:Int,e:KeyEvent,s:TerminalSession)=false; override fun onKeyUp(k:Int,e:KeyEvent)=false; override fun onLongPress(e:MotionEvent)=false
    override fun readControlKey() = controlPressed
    override fun readAltKey() = altPressed
    override fun readShiftKey()=false; override fun readFnKey()=false
    // Returning false delegates UTF-8, Enter, Ctrl/Alt and IME composition to
    // TerminalView's standard Termux/xterm input path.
    override fun onCodePoint(c:Int,ctrl:Boolean,s:TerminalSession):Boolean {
        consumeModifiersAfterDispatch()
        return false
    }
    override fun onEmulatorSet()=Unit
    override fun logError(a:String,b:String)=Unit; override fun logWarn(a:String,b:String)=Unit; override fun logInfo(a:String,b:String)=Unit; override fun logDebug(a:String,b:String)=Unit; override fun logVerbose(a:String,b:String)=Unit; override fun logStackTraceWithMessage(a:String,b:String,e:Exception)=Unit; override fun logStackTrace(a:String,e:Exception)=Unit

    private fun consumeModifiersAfterDispatch() {
        if (!controlPressed && !altPressed) return
        Handler(Looper.getMainLooper()).post {
            controlPressed = false
            altPressed = false
            onModifierStateChanged()
        }
    }
}
