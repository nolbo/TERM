package dev.terminal.app

import android.view.accessibility.AccessibilityNodeInfo

internal fun AccessibilityNodeInfo.hasLabel(label: String): Boolean =
    text?.toString() == label || contentDescription?.toString() == label
