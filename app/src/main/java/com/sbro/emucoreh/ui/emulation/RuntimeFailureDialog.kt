
package com.sbro.emucoreh.ui.emulation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sbro.emucoreh.R
import com.sbro.emucoreh.core.RuntimeFailure

@Composable
internal fun RuntimeFailureDialog(failure: RuntimeFailure, onExit: () -> Unit) {
    AlertDialog(
        // A failed native device cannot resume by dismissing a dialog. Exit
        // uses the normal owned-session shutdown; it does not auto-save a fault.
        onDismissRequest = {},
        title = { Text(stringResource(R.string.emulation_runtime_failure_title)) },
        text = {
            Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.emulation_runtime_failure_description))
                SelectionContainer {
                    Text(failure.detail, Modifier.testTag("runtime-failure-detail"))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onExit, modifier = Modifier.testTag("runtime-failure-exit")) {
                Text(stringResource(R.string.emulation_exit))
            }
        }
    )
}
