package io.github.whereareyou.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.whereareyou.R
import io.github.whereareyou.data.Fingerprint

private val WarningYellow = Color(0xFFFBC02D)

/** Yellow warning triangle next to a name that another identity also uses. */
@Composable
fun NameClashBadge(modifier: Modifier = Modifier) {
    Icon(Icons.Filled.Warning, stringResource(R.string.name_clash_short), modifier.size(18.dp), tint = WarningYellow)
}

/** Circled (i) next to a member: shows the fingerprint of their identity. */
@Composable
fun IdentityInfoButton(name: String, memberId: String, clash: Boolean, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = modifier.size(32.dp)) {
        Icon(Icons.Outlined.Info, stringResource(R.string.identity_info), Modifier.size(20.dp))
    }
    if (open) IdentityDialog(name, memberId, clash) { open = false }
}

@Composable
private fun IdentityDialog(name: String, memberId: String, clash: Boolean, onDismiss: () -> Unit) {
    val fingerprint = remember(memberId) { Fingerprint.of(memberId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.identity_label), style = MaterialTheme.typography.titleSmall)
                // Two rows of four: easier to read aloud and compare.
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    fingerprint.chunked(4).forEach { row ->
                        Text(row.joinToString(" "), fontSize = 34.sp, lineHeight = 48.sp)
                    }
                }
                Text(
                    stringResource(R.string.identity_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (clash) {
                    Row(verticalAlignment = Alignment.Top) {
                        NameClashBadge()
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.name_clash_text), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
