package io.github.whereareyou.ui

import android.content.Intent
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import io.github.whereareyou.data.Fix
import kotlinx.coroutines.delay
import java.text.DateFormat
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
import io.github.whereareyou.data.QrBackup
import io.github.whereareyou.data.LocationRepository

@Composable
fun CirclesScreen(repo: LocationRepository, onJoin: () -> Unit, onRestore: () -> Unit, onShowMember: (String) -> Unit) {
    val circles by repo.circles.collectAsStateWithLifecycle()
    val members by repo.members.collectAsStateWithLifecycle()
    val sharing by repo.sharingActive.collectAsStateWithLifecycle()
    val ownFix by repo.ownFix.collectAsStateWithLifecycle()
    // Members shown on the map: those sharing at least one visible circle with us.
    val shownTags = circles.filter { it.visible }.map { it.tag }.toSet()
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
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
            // Right after reinstalling: get the circles and the same identity back.
            OutlinedButton(onClick = onRestore, modifier = Modifier.padding(horizontal = 24.dp)) {
                Text(stringResource(R.string.backup_restore_hint))
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            items(circles, key = { it.tag }) { circle ->
                val inCircle = members.filter { circle.tag in it.circleTags }
                val isOpen = circle.tag in expanded
                Card(
                    onClick = { expanded = if (isOpen) expanded - circle.tag else expanded + circle.tag },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(circle.name, style = MaterialTheme.typography.titleLarge)
                                if (!isOpen && inCircle.isNotEmpty()) {
                                    Text(inCircle.joinToString(", ") { it.name }, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            IconButton(onClick = { inviting = circle }) {
                                Icon(Icons.Filled.Share, stringResource(R.string.invite))
                            }
                            IconButton(onClick = { deleting = circle }) {
                                Icon(Icons.Filled.Delete, stringResource(R.string.delete))
                            }
                            Icon(if (isOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.share_in_circle), Modifier.weight(1f))
                            Switch(checked = circle.share, onCheckedChange = { repo.setShare(circle, it) })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.show_circle_members), Modifier.weight(1f))
                            Switch(checked = circle.visible, onCheckedChange = { repo.setVisible(circle, it) })
                        }
                        AnimatedVisibility(isOpen) {
                            Column {
                                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                                val me = ownFix
                                if (circle.share && sharing && me != null) {
                                    MemberRow(
                                        id = repo.myId,
                                        name = stringResource(R.string.me, repo.displayName),
                                        fix = me,
                                        now = now,
                                        onClick = null,
                                        avatarName = repo.displayName,
                                        emoji = repo.emoji,
                                        color = repo.markerColor,
                                        battery = repo.sharedBattery,
                                    )
                                } else if (!circle.share) {
                                    Hint(stringResource(R.string.not_sharing_here))
                                }
                                inCircle.forEach { m ->
                                    val onMap = m.circleTags.any { it in shownTags }
                                    MemberRow(
                                        m.id, m.name, m.fix, now,
                                        onClick = if (onMap) ({ onShowMember(m.id) }) else null,
                                        emoji = m.emoji,
                                        color = m.color,
                                        battery = m.battery,
                                    )
                                }
                                if (inCircle.isEmpty()) Hint(stringResource(R.string.no_members_yet))
                            }
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
private fun Hint(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(vertical = 8.dp),
)

/** One member inside an expanded circle: name, last update, accuracy. */
@Composable
private fun MemberRow(
    id: String,
    name: String,
    fix: Fix,
    now: Long,
    onClick: (() -> Unit)?,
    avatarName: String = name,
    emoji: String = "",
    color: Int? = null,
    battery: Int? = null,
) {
    val res = LocalContext.current.resources
    val avatar = remember(id, avatarName, emoji, color) { avatarBitmap(id, avatarName, 96, false, emoji, color).asImageBitmap() }
    val time = DateUtils.formatSameDayTime(fix.time, now, DateFormat.SHORT, DateFormat.SHORT).toString()
    ListItem(
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Image(avatar, null, Modifier.size(36.dp)) },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.weight(1f, fill = false))
                battery?.let {
                    Spacer(Modifier.width(8.dp))
                    BatteryBadge(it)
                }
            }
        },
        supportingContent = {
            Column {
                Text(stringResource(R.string.updated, formatAge(res, fix.time, now), time))
                Text(stringResource(R.string.accuracy_long, fix.accuracy.toInt()))
            }
        },
        trailingContent = if (onClick != null) {
            { Icon(Icons.Filled.Place, stringResource(R.string.show_on_map)) }
        } else {
            null
        },
    )
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
fun JoinDialog(
    initial: String?,
    onDismiss: () -> Unit,
    onBackupQr: (String) -> Unit,
    onJoin: (String, ByteArray, Boolean) -> Unit,
) {
    var text by remember { mutableStateOf(initial ?: "") }
    LaunchedEffect(text) { if (QrBackup.isQrBackup(text)) onBackupQr(text) }
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
