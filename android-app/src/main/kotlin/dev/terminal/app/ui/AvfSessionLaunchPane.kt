package dev.terminal.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.terminal.app.R

/** Renders a service-owned creation request, without a terminal renderer or input connection. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AvfSessionLaunchPane(session: SessionUi, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        // Failure details belong to the Activity's central dialog; the tab retains only its state.
        if (session.launchError == null) {
            val loadingDescription = stringResource(R.string.avf_session_loading)
            LoadingIndicator(Modifier.size(64.dp).semantics { contentDescription = loadingDescription })
        }
    }
}
