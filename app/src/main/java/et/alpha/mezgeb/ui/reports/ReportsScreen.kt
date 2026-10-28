package et.alpha.mezgeb.ui.reports

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import et.alpha.mezgeb.R
import et.alpha.mezgeb.designsystem.MezgebTheme
import et.alpha.mezgeb.designsystem.component.ChartBar
import et.alpha.mezgeb.designsystem.component.DailyNetChart
import et.alpha.mezgeb.designsystem.component.EmptyState
import et.alpha.mezgeb.designsystem.component.MezgebScaffold
import et.alpha.mezgeb.domain.report.BreakdownSlice
import et.alpha.mezgeb.domain.report.DailyNet
import et.alpha.mezgeb.domain.report.Report
import et.alpha.mezgeb.domain.report.ReportPeriod
import et.alpha.mezgeb.ui.common.rememberDayLabel
import et.alpha.mezgeb.ui.common.spokenDailyNet
import et.alpha.mezgeb.ui.common.spokenNet
import et.alpha.mezgeb.util.MoneyFormatter

@Composable
fun ReportsRoute(
    modifier: Modifier = Modifier,
    viewModel: ReportsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingKind by remember { mutableStateOf(ExportKind.LEDGER_CSV) }

    // The system picker chooses where the file goes, so no storage permission is
    // needed and the export can land somewhere this app cannot read back.
    //
    // The mime type travels with each launch rather than living in the contract.
    // `rememberLauncherForActivityResult` captures its contract on first composition
    // and never rebuilds it, so `CreateDocument(mimeTypeOfWhateverIsSelected)` pins
    // whichever type happened to be selected first — which put a PDF on a real phone
    // named `mezgeb-summary-2026-08-02.pdf.csv`. Correct bytes, wrong name, and a
    // spreadsheet app waiting to open it.
    val createDocument = rememberLauncherForActivityResult(CreateExportDocument()) { uri ->
        if (uri != null) {
            viewModel.export(pendingKind, uri.toString()) {
                context.contentResolver.openOutputStream(uri)
            }
        }
    }

    // Offering to share is useful exactly here: the file is written and the user is
    // still thinking about who needs it.
    val exported = state.exported
    LaunchedEffect(exported) {
        if (exported != null) {
            val share = Intent(Intent.ACTION_SEND).apply {
                type = exported.mimeType
                putExtra(Intent.EXTRA_STREAM, exported.uri.toUri())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching {
                context.startActivity(
                    Intent.createChooser(share, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            viewModel.onEvent(ReportsEvent.ExportShown)
        }
    }

    ReportsScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onExport = { kind ->
            pendingKind = kind
            createDocument.launch(
                ExportRequest(
                    fileName = viewModel.suggestedFileName(kind),
                    mimeType = viewModel.mimeType(kind),
                ),
            )
        },
        modifier = modifier,
    )
}

/** What to call the file and what it is, decided per launch rather than per screen. */
data class ExportRequest(val fileName: String, val mimeType: String)

/**
 * `ACTION_CREATE_DOCUMENT`, with the type supplied at launch time.
 *
 * The stock [ActivityResultContracts.CreateDocument] takes its mime type in the
 * constructor, which composes badly with a launcher that is remembered once and a
 * type that depends on a menu choice made later.
 */
private class CreateExportDocument : ActivityResultContract<ExportRequest, Uri?>() {

    override fun createIntent(context: Context, input: ExportRequest): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.mimeType)
            .putExtra(Intent.EXTRA_TITLE, input.fileName)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent.takeIf { resultCode == Activity.RESULT_OK }?.data
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(
    state: ReportsUiState,
    onEvent: (ReportsEvent) -> Unit,
    onExport: (ExportKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MezgebTheme.spacing
    val snackbarHostState = remember { SnackbarHostState() }
    var showRangePicker by remember { mutableStateOf(false) }
    val failedText = stringResource(R.string.report_export_failed)

    LaunchedEffect(state.errorMessage) {
        if (state.errorMessage != null) {
            snackbarHostState.showSnackbar(failedText)
            onEvent(ReportsEvent.ErrorShown)
        }
    }

    MezgebScaffold(
        title = stringResource(R.string.destination_reports),
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        actions = { ExportMenu(onExport = onExport) },
    ) { inner ->
        LazyColumn(
            Modifier
                .padding(inner)
                .fillMaxSize(),
        ) {
            item("period") {
                // Scrolls rather than squeezes, as in the ledger's filter bar: four
                // chips are wider than a 360dp phone, so the last one was cut short.
                Row(
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(spacing.screenGutter),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    ReportPeriod.entries.forEach { period ->
                        FilterChip(
                            selected = state.period == period,
                            onClick = {
                                if (period == ReportPeriod.CUSTOM) {
                                    showRangePicker = true
                                } else {
                                    onEvent(ReportsEvent.PeriodChosen(period))
                                }
                            },
                            label = { Text(stringResource(period.labelRes())) },
                        )
                    }
                }
            }

            val report = state.report
            when {
                report == null -> if (!state.isLoading) item("empty") { NotEnoughData() }

                // Below three transactions a "breakdown by category" is really just a
                // list of the transactions, dressed up as analysis.
                !report.hasEnoughData -> item("insufficient") { NotEnoughData() }

                else -> {
                    item("summary") { Summary(report, Modifier.padding(spacing.screenGutter)) }

                    item("chart") {
                        Column(Modifier.padding(horizontal = spacing.screenGutter)) {
                            Text(
                                text = stringResource(R.string.report_daily_net),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            DailyNetChart(
                                bars = report.dailyNet.map { ChartBar("", it.netMinor) },
                                contentDescription = spokenDailyNet(
                                    report.dailyNet.map { it.netMinor },
                                ),
                            )
                        }
                    }

                    breakdown("channel", R.string.report_by_channel, report.byChannel)
                    breakdown("category", R.string.report_by_category, report.byCategory)

                    if (report.topDebtors.isNotEmpty()) {
                        item("debtors_heading") { SectionHeading(R.string.report_top_debtors) }
                        items(report.topDebtors.size) { index ->
                            val debtor = report.topDebtors[index]
                            LabelledAmountRow(debtor.customer.name, debtor.balanceMinor)
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    if (showRangePicker) {
        val pickerState = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { showRangePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val from = pickerState.selectedStartDateMillis
                        val to = pickerState.selectedEndDateMillis
                        if (from != null && to != null) {
                            onEvent(ReportsEvent.CustomRangeChosen(from, to))
                        }
                        showRangePicker = false
                    },
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRangePicker = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        ) {
            DateRangePicker(state = pickerState)
        }
    }
}

private fun LazyListScope.breakdown(
    key: String,
    headingRes: Int,
    slices: List<BreakdownSlice>,
) {
    if (slices.isEmpty()) return
    item("${key}_heading") { SectionHeading(headingRes) }
    items(slices.size) { index ->
        val slice = slices[index]
        LabelledAmountRow(
            label = slice.label,
            amountMinor = slice.amountMinor,
            trailing = "%.0f%%".format(slice.share * 100),
        )
        HorizontalDivider()
    }
}

@Composable
private fun SectionHeading(labelRes: Int) {
    Text(
        text = stringResource(labelRes),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(
            start = MezgebTheme.spacing.screenGutter,
            end = MezgebTheme.spacing.screenGutter,
            top = MezgebTheme.spacing.lg,
            bottom = MezgebTheme.spacing.sm,
        ),
    )
}

@Composable
private fun Summary(report: Report, modifier: Modifier = Modifier) {
    val spacing = MezgebTheme.spacing
    Card(modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Text(
                text = rememberDayLabel(report.periodStart) + " – " +
                    rememberDayLabel(report.periodEnd),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Not Today's labels: those say "today", and this period is rarely one day.
            AmountLine(R.string.report_total_in, report.totalInMinor)
            AmountLine(R.string.report_total_out, report.totalOutMinor)
            AmountLine(
                R.string.today_net,
                report.netMinor,
                emphasise = true,
                spoken = spokenNet(report.netMinor),
            )
            Text(
                text = pluralStringResource(
                    R.plurals.report_transaction_count,
                    report.transactionCount,
                    report.transactionCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A label and an amount. [spoken] replaces what TalkBack reads for the amount; the
 * net needs it, because it can go below zero and a minus sign is read as a symbol.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AmountLine(
    labelRes: Int,
    amountMinor: Long,
    emphasise: Boolean = false,
    spoken: String? = null,
) {
    // A flow, so the amount moves under its label rather than squeezing it: in a
    // row "Received" broke inside the word at the largest font (ROADMAP R25).
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // On a wrapper, as on Today, so the description replaces the Text's own
        // semantics rather than sitting beside them on the same node.
        Box(
            if (spoken != null) {
                Modifier.clearAndSetSemantics { contentDescription = spoken }
            } else {
                Modifier
            },
        ) {
            Text(
                text = MoneyFormatter.format(amountMinor),
                style = if (emphasise) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.bodyMedium
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LabelledAmountRow(label: String, amountMinor: Long, trailing: String? = null) {
    val spacing = MezgebTheme.spacing
    // The same flow as [AmountLine]: a row left "TELEBIRR" 56dp at the largest font.
    FlowRow(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.screenGutter, vertical = spacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(end = spacing.sm),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(MoneyFormatter.format(amountMinor), style = MaterialTheme.typography.bodyMedium)
            if (trailing != null) {
                Text(
                    text = trailing,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ExportMenu(onExport: (ExportKind) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = true }) {
        Text(stringResource(R.string.report_export))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        listOf(
            ExportKind.LEDGER_CSV to R.string.report_export_ledger,
            ExportKind.CREDIT_CSV to R.string.report_export_credit,
            ExportKind.BOTH_CSV to R.string.report_export_both,
            ExportKind.SUMMARY_PDF to R.string.report_export_pdf,
        ).forEach { (kind, labelRes) ->
            DropdownMenuItem(
                text = { Text(stringResource(labelRes)) },
                onClick = {
                    expanded = false
                    onExport(kind)
                },
            )
        }
    }
}

@Composable
private fun NotEnoughData() {
    EmptyState(
        title = stringResource(R.string.placeholder_reports_title),
        body = stringResource(R.string.report_insufficient_body),
    )
}

private fun ReportPeriod.labelRes(): Int = when (this) {
    ReportPeriod.DAY -> R.string.ledger_filter_date_today
    ReportPeriod.WEEK -> R.string.reconcile_period_week
    ReportPeriod.MONTH -> R.string.ledger_filter_date_30_days
    ReportPeriod.CUSTOM -> R.string.reconcile_period_custom
}

@Preview(name = "Reports", showBackground = true, heightDp = 900)
@Composable
private fun ReportsPreview() {
    MezgebTheme {
        ReportsScreen(
            state = ReportsUiState(
                isLoading = false,
                report = Report(
                    periodStart = 1_782_950_400_000L,
                    periodEnd = 1_783_468_799_999L,
                    totalInMinor = 245_000,
                    totalOutMinor = 82_000,
                    transactionCount = 18,
                    byChannel = listOf(
                        BreakdownSlice("TELEBIRR", 150_000, 0.61f, 9),
                        BreakdownSlice("BANK", 70_000, 0.29f, 6),
                        BreakdownSlice("CASH", 25_000, 0.10f, 3),
                    ),
                    byCategory = listOf(
                        BreakdownSlice("Sales", 200_000, 0.82f, 15),
                        BreakdownSlice("—", 45_000, 0.18f, 3),
                    ),
                    dailyNet = listOf(
                        DailyNet(1_782_950_400_000L, 45_000),
                        DailyNet(1_783_036_800_000L, 0),
                        DailyNet(1_783_123_200_000L, -12_000),
                        DailyNet(1_783_209_600_000L, 88_000),
                    ),
                    topDebtors = emptyList(),
                ),
            ),
            onEvent = {},
            onExport = {},
        )
    }
}
