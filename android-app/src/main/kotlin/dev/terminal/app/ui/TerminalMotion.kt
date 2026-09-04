package dev.terminal.app.ui

import android.app.Activity
import android.app.ActivityOptions
import android.app.Dialog
import android.content.Intent
import android.view.ViewGroup
import android.view.Window
import androidx.transition.TransitionManager
import com.google.android.material.transition.MaterialFadeThrough
import com.google.android.material.transition.platform.MaterialFade
import com.google.android.material.transition.platform.MaterialSharedAxis

/** Material 3 motion applied to the app-owned Terminal Grid components. */
object TerminalMotion {
    fun configureActivity(window: Window) {
        window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
        window.enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        window.returnTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
        window.exitTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        window.reenterTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
    }

    fun startActivity(activity: Activity, intent: Intent) {
        activity.startActivity(intent, ActivityOptions.makeSceneTransitionAnimation(activity).toBundle())
    }

    fun fadeThrough(target: ViewGroup) {
        if (target.isLaidOut) TransitionManager.beginDelayedTransition(target, MaterialFadeThrough())
    }

    fun configureDialog(dialog: Dialog) {
        dialog.window?.apply {
            requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
            enterTransition = MaterialFade()
            exitTransition = MaterialFade()
        }
    }
}
