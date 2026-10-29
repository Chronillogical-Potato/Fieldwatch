package app.fieldwatch.ui.screen

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.fieldwatch.BuildConfig
import app.fieldwatch.data.LanguageSnapshot
import app.fieldwatch.domain.BundledLanguages
import app.fieldwatch.domain.LanguageAiCopy
import app.fieldwatch.domain.LanguageDoorCopy
import app.fieldwatch.domain.LanguageExportCopy
import app.fieldwatch.domain.LanguagePreview
import app.fieldwatch.domain.LanguageText
import app.fieldwatch.domain.OfficialSentences
import app.fieldwatch.ui.LanguageUiState
import app.fieldwatch.ui.component.FieldwatchActionButton
import app.fieldwatch.ui.component.FieldwatchDropdownField
import app.fieldwatch.ui.component.SectionCard

@Composable
fun LanguageDoor(onImport: () -> Unit, onEnglish: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            LanguageDoorCopy.TITLE,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = ink,
        )
        Text(LanguageDoorCopy.BODY, color = ink)
        Text(OfficialSentences.NOT_REVIEWED, color = ink)
        FieldwatchActionButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
            Text(LanguageDoorCopy.IMPORT)
        }
        TextButton(onClick = onEnglish) { Text(LanguageDoorCopy.CONTINUE) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguagePage(
    state: LanguageUiState,
    onChoose: (String) -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onRemove: () -> Unit,
) {
    val snap = state.snap
    val context = LocalContext.current
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var pendingChoice by rememberSaveable { mutableStateOf<String?>(null) }
    val options = languageChoices()
    val currentName = if (snap.imported) {
        snap.activeName
    } else {
        options.firstOrNull { it.first == snap.choice }?.second ?: snap.activeName
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (snap.bundledActive) {
            Text(
                OfficialSentences.AI,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (snap.imported) {
            Text(
                LanguageText.text("language.import.blocks"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SectionCard(LanguageText.text("language.section.set")) {
            SectionAbout("language.section.set.about")
            val open = menuOpen && !snap.imported
            ExposedDropdownMenuBox(
                expanded = open,
                onExpandedChange = { if (!snap.imported) menuOpen = it },
                modifier = Modifier.fillMaxWidth(),
            ) {
                FieldwatchDropdownField(
                    label = LanguageText.text("settings.topic.language"),
                    value = currentName,
                    expanded = open,
                    enabled = !snap.imported,
                )
                ExposedDropdownMenu(open, { menuOpen = false }) {
                    options.forEach { (choice, label) ->
                        val selected = !snap.imported && choice == snap.choice
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 32.dp)
                                .clickable {
                                    menuOpen = false
                                    if (!snap.imported && choice != snap.choice) pendingChoice = choice
                                }
                                .padding(horizontal = 12.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.weight(1f),
                            )
                            if (selected) {
                                Icon(Icons.Outlined.Check, contentDescription = null)
                            }
                        }
                    }
                }
            }
        }
        SectionCard(LanguageText.text("language.section.add")) {
            SectionAbout("language.section.add.about")
            FieldwatchActionButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                Text(LanguageText.text("language.import"))
            }
        }
        if (snap.imported) {
            SectionCard(LanguageText.text("language.section.remove")) {
                SectionAbout("language.section.remove.about")
                FieldwatchActionButton(
                    onClick = { confirmRemove = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(LanguageText.text("language.remove")) }
            }
        }
        FieldwatchActionButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
            Text(LanguageExportCopy.BUTTON)
        }
        Text(
            OfficialSentences.AUTHOR_GUIDE,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.clickable {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(OfficialSentences.AUTHOR_GUIDE_URL))
                runCatching { context.startActivity(intent) }
            },
        )
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(LanguageText.text("language.remove.title")) },
            text = { Text(LanguageText.text("language.remove.body")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    onRemove()
                }) { Text(LanguageText.text("language.remove")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) {
                    Text(LanguageText.text("language.cancel"))
                }
            },
        )
    }
    val pending = pendingChoice
    val pendingName = pending?.let { choice -> options.firstOrNull { it.first == choice }?.second }
    if (pending != null && pendingName != null) {
        AlertDialog(
            onDismissRequest = { pendingChoice = null },
            title = {
                Text(LanguageText.text("language.confirm.use", mapOf("language" to pendingName)))
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingChoice = null
                    onChoose(pending)
                }) { Text(LanguageText.text("language.use")) }
            },
            dismissButton = {
                TextButton(onClick = { pendingChoice = null }) {
                    Text(LanguageText.text("language.cancel"))
                }
            },
        )
    }
}

@Composable
private fun SectionAbout(key: String) {
    Text(
        LanguageText.text(key),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun languageChoices(): List<Pair<String, String>> {
    val languages = buildList {
        add(LanguageSnapshot.CHOICE_ENGLISH to "English")
        BundledLanguages.all.forEach { add(it.tag to it.english) }
    }.sortedBy { it.second }
    return buildList {
        add(LanguageSnapshot.CHOICE_FOLLOW to LanguageText.text("language.follow"))
        addAll(languages)
    }
}

@Composable
fun LanguageOverlays(
    state: LanguageUiState,
    onConfirmImport: () -> Unit,
    onCancelImport: () -> Unit,
    onSaveReport: () -> Unit,
    onDismissError: () -> Unit,
    onDismissAi: () -> Unit,
) {
    state.pending?.let { pending ->
        AlertDialog(
            onDismissRequest = onCancelImport,
            title = {
                Text(LanguageText.text("language.confirm.title", mapOf("language" to pending.language)))
            },
            text = {
                Column {
                    Text(confirmBody(pending))
                    TextButton(onClick = onSaveReport) {
                        Text(LanguageText.text("language.report.save"))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirmImport) {
                    Text(LanguageText.text("language.import.action"))
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelImport) { Text(LanguageText.text("language.cancel")) }
            },
        )
    }
    state.errorKey?.let { key ->
        AlertDialog(
            onDismissRequest = onDismissError,
            text = { Text(LanguageText.text(key)) },
            confirmButton = {
                TextButton(onClick = onDismissError) { Text(LanguageAiCopy.OK) }
            },
        )
    }
    if (state.snap.showAi && state.pending == null && state.errorKey == null) {
        AlertDialog(
            onDismissRequest = onDismissAi,
            text = { Text(OfficialSentences.AI) },
            confirmButton = {
                TextButton(onClick = onDismissAi) { Text(LanguageAiCopy.OK) }
            },
        )
    }
}

private fun confirmBody(pending: LanguagePreview): String = buildString {
    pending.writtenFor?.let { written ->
        append(
            LanguageText.text(
                "language.confirm.written",
                mapOf("version" to written, "phone" to BuildConfig.VERSION_NAME),
            ),
        )
        append("\n")
    }
    append(LanguageText.text("language.confirm.change", mapOf("n" to pending.changed.toString())))
    append("\n")
    append(LanguageText.text("language.confirm.stay", mapOf("n" to pending.stayEnglish.toString())))
    append("\n")
    append(LanguageText.text("language.confirm.ignored", mapOf("n" to pending.ignored.toString())))
    pending.newSince?.let { count ->
        append("\n")
        append(LanguageText.text("language.confirm.new", mapOf("n" to count.toString())))
    }
    if (pending.formatNewer) {
        append("\n")
        append(LanguageText.text("language.confirm.newer"))
    }
    append("\n\n")
    append(OfficialSentences.NOT_REVIEWED)
}
