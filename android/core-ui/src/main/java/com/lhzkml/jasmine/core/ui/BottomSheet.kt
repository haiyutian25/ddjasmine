package com.lhzkml.jasmine.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * A hand-rolled bottom sheet usable anywhere: a dialog-level scrim with a
 * rounded panel docked to the bottom of the screen. The panel sizes to its
 * content up to [maxHeightFraction] of the screen and scrolls inside when
 * taller.
 *
 * Deliberately minimal: no drag handle and no swipe-to-dismiss — the close
 * button at the panel's top-right corner is the only way out (a tap on the
 * scrim or the system back key does nothing).
 *
 * @param onClose invoked by the close button.
 * @param modifier applied to the docked panel.
 * @param maxHeightFraction the panel never grows past this fraction of the
 *   screen height; content scrolls inside beyond it.
 * @param content the sheet body, laid out in the panel's column.
 */
@Composable
fun JasmineBottomSheet(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    maxHeightFraction: Float = 0.72f,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = { /* close button only */ },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f)),
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .heightIn(max = this@BoxWithConstraints.maxHeight * maxHeightFraction),
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        IconButton(onClick = onClose) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "关闭",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                    ) {
                        Column(content = content)
                    }
                }
            }
        }
    }
}
