package io.github.whereareyou.ui

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.github.whereareyou.R
import io.github.whereareyou.data.Circle
import io.github.whereareyou.data.Invite
import io.github.whereareyou.data.LocationRepository
import androidx.activity.compose.rememberLauncherForActivityResult

@Composable
fun CirclesScreen(repo: LocationRepository, onJoin: () -> Unit) {
    val circles by repo.circles.collectAsStateWithLifecycle()
    val members by repo.members.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var inviting by remember { mutableStateOf<Circle?>(null) }
    var deleting by remember { mutableStateOf<Circle?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { creating = true }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Add, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.new_circle))
            }
            FilledTonalButton(onClick = onJoin, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.join_circle))
            }
        }
        if (circles.isEmpty()) {
            Text(
                stringResource(R.string.no_circles),
                modifier = Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            items(circles, key = { it.tag }) { circle ->
                val names = members.filter { circle.name in it.circles }.joinToString(", ") { it.name }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(circle.name, style = MaterialTheme.typography.titleLarge)
                                if (names.isNotEmpty()) Text(names, style = MaterialTheme.typography.bodyMedium)
                            }
                            IconButton(onClick = { inviting = circle }) {
                                Icon(Icons.Filled.Share, stringResource(R.string.invite))
                            }
                            IconButton(onClick = { deleting = circle }) {
                                Icon(Icons.Filled.Delete, stringResource(R.string.delete))
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.share_in_circle), Modifier.weight(1f))
                            Switch(checked = circle.share, onCheckedChange = { repo.setShare(circle, it) })
                        }
                    }
                }
            }
        }
    }

    if (creating) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text(stringResource(R.string.new_circle)) },
            text = {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.circle_name)) }, singleLine = true)
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    repo.createCircle(name)
                    creating = false
                }) { Text(stringResource(R.string.create)) }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    inviting?.let { InviteDialog(it, onDismiss = { inviting = null }) }

    deleting?.let { circle ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_circle_title, circle.name)) },
            text = { Text(stringResource(R.string.delete_circle_text)) },
            confirmButton = {
                TextButton(onClick = {
                    repo.deleteCircle(circle)
                    deleting = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun InviteDialog(circle: Circle, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val link = remember(circle.tag) { Invite.encode(circle) }
    val qr = remember(link) { qrBitmap(link).asImageBitmap() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.invite_title, circle.name)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(qr, null, Modifier.size(260.dp))
                Spacer(Modifier.size(12.dp))
                Text(stringResource(R.string.invite_text), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
                context.startActivity(Intent.createChooser(send, null))
            }) { Text(stringResource(R.string.share_link)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

/** Join a circle from a scanned QR code, a pasted link, or an opened whereareyou:// link. */
@Composable
fun JoinDialog(initial: String?, onDismiss: () -> Unit, onJoin: (String, ByteArray, Boolean) -> Unit) {
    var text by remember { mutableStateOf(initial ?: "") }
    var share by remember { mutableStateOf(true) }
    val decoded = remember(text) { Invite.decode(text) }
    val scanPrompt = stringResource(R.string.scan_prompt)
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { text = it }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(decoded?.let { stringResource(R.string.join_title, it.first) } ?: stringResource(R.string.join_circle))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (decoded == null) {
                    OutlinedButton(onClick = {
                        scanner.launch(
                            ScanOptions()
                                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setPrompt(scanPrompt)
                                .setBeepEnabled(false)
                                .setOrientationLocked(false)
                        )
                    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.scan_qr)) }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text(stringResource(R.string.paste_invite)) },
                        isError = text.isNotBlank(),
                        supportingText = { if (text.isNotBlank()) Text(stringResource(R.string.invalid_invite)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.share_in_circle), Modifier.weight(1f))
                        Switch(checked = share, onCheckedChange = { share = it })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = decoded != null, onClick = {
                decoded?.let { onJoin(it.first, it.second, share) }
            }) { Text(stringResource(R.string.join_circle)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
