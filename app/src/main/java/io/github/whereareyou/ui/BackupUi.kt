package io.github.whereareyou.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.SecureFlagPolicy
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import io.github.whereareyou.R
import io.github.whereareyou.WhereApp
import io.github.whereareyou.data.Backup
import io.github.whereareyou.data.BackupData
import io.github.whereareyou.data.QrBackup
import io.github.whereareyou.service.LocationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Backup files are small: anything bigger is not one of ours. */
private const val MAX_BACKUP_BYTES = 1 shl 20

private fun readText(context: Context, uri: Uri): String? = try {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val bytes = input.readNBytesCompat(MAX_BACKUP_BYTES + 1)
        if (bytes.size > MAX_BACKUP_BYTES) null else String(bytes)
    }
} catch (e: Exception) {
    null
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (out.size() < limit) {
        val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

private fun copySecret(context: Context, text: String) {
    val clip = ClipData.newPlainText("whereAreYou", text)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // Not shown in the clipboard preview.
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}

/**
 * Password (or share code) field, followed by a row of buttons: [actions], then show / hide.
 * The buttons are under the field so that the whole password stays readable.
 */
@Composable
private fun PasswordField(
    value: String,
    onChange: (String) -> Unit,
    visible: Boolean,
    onToggle: () -> Unit,
    error: String?,
    label: Int = R.string.backup_password,
    digitsOnly: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(if (digitsOnly) it.filter(Char::isDigit).take(8) else it) },
        label = { Text(stringResource(label)) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = if (digitsOnly) KeyboardType.NumberPassword else KeyboardType.Password),
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
    )
    Row {
        actions()
        TextButton(onClick = onToggle) {
            Text(stringResource(if (visible) R.string.backup_hide else R.string.backup_show))
        }
    }
}

@Composable
private fun WorkingDialog(text: String) {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Spacer(Modifier.width(16.dp))
                Text(text)
            }
        },
    )
}

/** "4821 7390" */
private fun formatCode(code: String) = code.chunked(4).joinToString(" ")

/**
 * Transfer screen: the QR code, then (button) the share code in large digits.
 * Screenshots are blocked and the screen is at full brightness while it is shown.
 */
@Composable
private fun QrTransferDialog(text: String, code: String, onDismiss: () -> Unit) {
    var showCode by remember { mutableStateOf(false) }
    val qr = remember(text) { qrBitmap(text, 900).asImageBitmap() }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            window?.attributes = window?.attributes?.apply { screenBrightness = 1f }
            onDispose {}
        }
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (!showCode) {
                    Text(stringResource(R.string.backup_qr_title), style = MaterialTheme.typography.titleLarge)
                    Image(
                        qr,
                        null,
                        Modifier.fillMaxWidth().aspectRatio(1f).background(Color.White).padding(8.dp),
                    )
                    Text(stringResource(R.string.backup_qr_text), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { showCode = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.backup_qr_show_code))
                    }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
                } else {
                    Text(stringResource(R.string.backup_qr_code_title), style = MaterialTheme.typography.titleLarge)
                    Text(
                        formatCode(code),
                        style = MaterialTheme.typography.displayMedium.copy(fontFamily = FontFamily.Monospace),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                    Text(stringResource(R.string.backup_qr_code_text), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.backup_done)) }
                    TextButton(onClick = { showCode = false }) { Text(stringResource(R.string.backup_qr_back)) }
                }
            }
        }
    }
}

/** "Backup" section of the settings: export to an encrypted file or a QR code, or restore. */
@Composable
fun BackupSection(app: WhereApp, onRestore: () -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf<Int?>(null) } // message shown while busy
    var transfer by remember { mutableStateOf<Pair<String, String>?>(null) } // QR text, share code

    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pw = password
        password = ""
        if (uri == null) return@rememberLauncherForActivityResult
        working = R.string.backup_encrypting
        scope.launch {
            val ok = withContext(Dispatchers.Default) {
                try {
                    val text = Backup.export(app.repo.backupData(), pw)
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } != null
                } catch (e: Exception) {
                    false
                }
            }
            working = null
            onMessage(context.getString(if (ok) R.string.backup_saved else R.string.backup_save_failed))
        }
    }

    fun startTransfer() {
        working = R.string.backup_qr_preparing
        scope.launch {
            val code = QrBackup.generateCode()
            val text = withContext(Dispatchers.Default) {
                try {
                    QrBackup.export(app.repo.backupData(), code)
                } catch (e: Exception) {
                    null // more than 255 circles
                }
            }
            working = null
            if (text == null || text.length > QrBackup.MAX_TEXT_LENGTH) {
                onMessage(context.getString(R.string.backup_qr_too_big))
            } else {
                transfer = text to code
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.backup_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.backup_hint), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = {
                password = ""
                visible = false
                asking = true
            }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.backup_export)) }
            OutlinedButton(onClick = onRestore, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.backup_import)) }
        }
        OutlinedButton(onClick = ::startTransfer, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.backup_qr_button))
        }
    }

    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.backup_export_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.backup_export_text), style = MaterialTheme.typography.bodyMedium)
                    PasswordField(password, { password = it }, visible, { visible = !visible }, null) {
                        TextButton(onClick = {
                            password = Backup.generatePassword()
                            visible = true
                        }) { Text(stringResource(R.string.backup_generate)) }
                        TextButton(enabled = password.isNotEmpty(), onClick = {
                            copySecret(context, password)
                            onMessage(context.getString(R.string.backup_copied))
                        }) { Text(stringResource(R.string.backup_copy)) }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = password.isNotEmpty(), onClick = {
                    asking = false
                    val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                    createFile.launch("whereAreYou-$date.way")
                }) { Text(stringResource(R.string.backup_export)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    asking = false
                    password = ""
                }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    transfer?.let { (text, code) -> QrTransferDialog(text, code, onDismiss = { transfer = null }) }
    working?.let { WorkingDialog(stringResource(it)) }
}

/** Starts a restore: [start] asks for the source (file or QR code), [fromQr] continues with a scanned QR code. */
class BackupImporter(val start: () -> Unit, val fromQr: (String) -> Unit)

/**
 * Restoring a backup: pick the file or scan the QR code, type its password or share code,
 * optionally tell the old circles we leave, then become the saved member.
 */
@Composable
fun rememberBackupImport(app: WhereApp, onMessage: (String) -> Unit): BackupImporter {
    val context = LocalContext.current
    val repo = app.repo
    val scope = rememberCoroutineScope()
    var choosing by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf<String?>(null) } // file content or QR text
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var wrongPassword by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf<Int?>(null) } // message shown while busy
    var decoded by remember { mutableStateOf<BackupData?>(null) }
    var askLeave by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }

    fun apply(backup: BackupData, leave: Boolean) {
        working = R.string.backup_restoring
        scope.launch {
            withContext(Dispatchers.Default) {
                if (leave) repo.announceLeaving()
                repo.restore(backup)
            }
            LocationService.refresh(context, repo.sharingActive.value) // new intervals
            working = null
            decoded = null
            done = true
        }
    }

    fun ask(text: String) {
        source = text
        password = ""
        visible = false
        wrongPassword = false
    }

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = readText(context, uri)
        if (text == null || !Backup.isBackup(text)) onMessage(context.getString(R.string.backup_not_a_backup)) else ask(text)
    }
    val scanPrompt = stringResource(R.string.backup_scan_prompt)
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@rememberLauncherForActivityResult
        if (QrBackup.isQrBackup(text)) ask(text) else onMessage(context.getString(R.string.backup_qr_not_backup))
    }

    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(stringResource(R.string.backup_import_title)) },
            text = { Text(stringResource(R.string.backup_source_text)) },
            confirmButton = {
                TextButton(onClick = {
                    choosing = false
                    scanner.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt(scanPrompt)
                            .setBeepEnabled(false)
                            .setOrientationLocked(false)
                    )
                }) { Text(stringResource(R.string.backup_scan_qr)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    choosing = false
                    openFile.launch(arrayOf("*/*"))
                }) { Text(stringResource(R.string.backup_from_file)) }
            },
        )
    }

    source?.let { text ->
        val qr = QrBackup.isQrBackup(text)
        AlertDialog(
            onDismissRequest = { source = null },
            title = { Text(stringResource(R.string.backup_import_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(if (qr) R.string.backup_import_code_text else R.string.backup_import_text),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    PasswordField(
                        password,
                        {
                            password = it
                            wrongPassword = false
                        },
                        visible,
                        { visible = !visible },
                        when {
                            !wrongPassword -> null
                            qr -> stringResource(R.string.backup_wrong_code)
                            else -> stringResource(R.string.backup_wrong_password)
                        },
                        label = if (qr) R.string.backup_share_code else R.string.backup_password,
                        digitsOnly = qr,
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = if (qr) password.length == 8 else password.isNotEmpty(), onClick = {
                    val pw = password
                    working = R.string.backup_decrypting
                    scope.launch {
                        val backup = withContext(Dispatchers.Default) {
                            if (qr) QrBackup.import(text, pw) else Backup.import(text, pw)
                        }
                        working = null
                        if (backup == null) {
                            wrongPassword = true
                            return@launch
                        }
                        source = null
                        password = ""
                        val wasMember = repo.circles.value.isNotEmpty() && !repo.hasSeed(backup.seed)
                        if (wasMember) {
                            decoded = backup
                            askLeave = true
                        } else {
                            apply(backup, leave = false)
                        }
                    }
                }) { Text(stringResource(R.string.backup_import)) }
            },
            dismissButton = { TextButton(onClick = { source = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (askLeave) {
        decoded?.let { backup ->
            AlertDialog(
                onDismissRequest = {
                    askLeave = false
                    decoded = null
                },
                title = { Text(stringResource(R.string.backup_leave_title)) },
                text = { Text(stringResource(R.string.backup_leave_text)) },
                confirmButton = {
                    TextButton(onClick = {
                        askLeave = false
                        apply(backup, leave = true)
                    }) { Text(stringResource(R.string.backup_leave_yes)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        askLeave = false
                        apply(backup, leave = false)
                    }) { Text(stringResource(R.string.backup_leave_no)) }
                },
            )
        }
    }

    if (done) {
        AlertDialog(
            onDismissRequest = { done = false },
            title = { Text(stringResource(R.string.backup_restored_title)) },
            text = { Text(stringResource(R.string.backup_restored_text)) },
            confirmButton = { TextButton(onClick = { done = false }) { Text(stringResource(R.string.ok)) } },
        )
    }

    working?.let { WorkingDialog(stringResource(it)) }

    return remember { BackupImporter(start = { choosing = true }, fromQr = { ask(it) }) }
}
