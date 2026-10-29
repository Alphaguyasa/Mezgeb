package et.alpha.mezgeb.ui.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import et.alpha.mezgeb.data.repo.CustomerRepository
import et.alpha.mezgeb.data.repo.ReconciliationRepository
import et.alpha.mezgeb.data.repo.RuleRepository
import et.alpha.mezgeb.data.repo.TransactionFilter
import et.alpha.mezgeb.data.repo.TransactionRepository
import et.alpha.mezgeb.data.settings.SettingsRepository
import et.alpha.mezgeb.domain.report.BuildReportUseCase
import et.alpha.mezgeb.domain.report.CsvExporter
import et.alpha.mezgeb.domain.report.PdfExporter
import et.alpha.mezgeb.domain.report.Report
import et.alpha.mezgeb.domain.report.ReportPeriod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.time.Clock
import java.time.ZoneId
import javax.inject.Inject

/** What an export writes. */
enum class ExportKind {
    LEDGER_CSV,
    CREDIT_CSV,
    BOTH_CSV,
    PERIODS_CSV,
    SUMMARY_PDF,
}

data class ReportsUiState(
    val isLoading: Boolean = true,
    val period: ReportPeriod = ReportPeriod.WEEK,
    val customRange: LongRange? = null,
    val report: Report? = null,
    val shopName: String? = null,
    /** Set when an export has been written, so the screen can offer to share it. */
    val exported: ExportResult? = null,
    val errorMessage: ErrorKind? = null,
) {
    val hasEnoughData: Boolean get() = report?.hasEnoughData == true

    enum class ErrorKind { EXPORT_FAILED }
}

/** Where an export landed, and what it is, so a share sheet can be offered. */
data class ExportResult(val uri: String, val mimeType: String)

sealed interface ReportsEvent {
    data class PeriodChosen(val period: ReportPeriod) : ReportsEvent
    data class CustomRangeChosen(val from: Long, val to: Long) : ReportsEvent
    data object ExportShown : ReportsEvent
    data object ErrorShown : ReportsEvent
}

/**
 * Summaries, and getting them out of the app.
 *
 * Export deliberately never picks its own destination. `ACTION_CREATE_DOCUMENT` hands
 * that to the system picker, which means no storage permission is needed and the file
 * lands wherever the shop keeps things — including a location this app cannot read
 * back. For an offline app whose selling point is that data stays put, the export is
 * the one door out, and the user holds the handle.
 */
@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val buildReport: BuildReportUseCase,
    private val transactions: TransactionRepository,
    private val customers: CustomerRepository,
    private val rules: RuleRepository,
    private val reconciliation: ReconciliationRepository,
    private val settings: SettingsRepository,
    private val pdfExporter: PdfExporter,
    private val clock: Clock,
    private val zone: ZoneId,
) : ViewModel() {

    private val _state = MutableStateFlow(ReportsUiState())
    val state: StateFlow<ReportsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.update { it.copy(shopName = settings.shopName()) }
            refresh()
        }
    }

    fun onEvent(event: ReportsEvent) {
        when (event) {
            is ReportsEvent.PeriodChosen -> {
                _state.update { it.copy(period = event.period) }
                viewModelScope.launch { refresh() }
            }

            is ReportsEvent.CustomRangeChosen -> {
                _state.update {
                    it.copy(period = ReportPeriod.CUSTOM, customRange = event.from..event.to)
                }
                viewModelScope.launch { refresh() }
            }

            ReportsEvent.ExportShown -> _state.update { it.copy(exported = null) }
            ReportsEvent.ErrorShown -> _state.update { it.copy(errorMessage = null) }
        }
    }

    /** The name to offer the system picker, so the user is not naming files by hand. */
    fun suggestedFileName(kind: ExportKind): String {
        val now = clock.millis()
        return when (kind) {
            ExportKind.LEDGER_CSV -> CsvExporter.fileName("mezgeb-ledger", now, zone)
            ExportKind.CREDIT_CSV -> CsvExporter.fileName("mezgeb-credit", now, zone)
            ExportKind.BOTH_CSV -> CsvExporter.fileName("mezgeb-export", now, zone)
            ExportKind.PERIODS_CSV -> CsvExporter.fileName("mezgeb-periods", now, zone)
            ExportKind.SUMMARY_PDF -> PdfExporter.fileName(now, zone)
        }
    }

    fun mimeType(kind: ExportKind): String = when (kind) {
        ExportKind.SUMMARY_PDF -> PdfExporter.MIME_TYPE
        else -> CsvExporter.MIME_TYPE
    }

    /**
     * Writes into a stream the system picker opened for us.
     *
     * @param uri only for handing to a share sheet afterwards; nothing is read back.
     */
    fun export(kind: ExportKind, uri: String, openStream: () -> OutputStream?) {
        viewModelScope.launch {
            val result = runCatching {
                val stream = openStream() ?: error("no output stream for $uri")
                stream.use { writeExport(kind, it) }
            }

            _state.update {
                if (result.isSuccess) {
                    it.copy(exported = ExportResult(uri, mimeType(kind)))
                } else {
                    it.copy(errorMessage = ReportsUiState.ErrorKind.EXPORT_FAILED)
                }
            }
        }
    }

    private suspend fun writeExport(kind: ExportKind, stream: OutputStream) {
        val current = _state.value
        val bounds = boundsFor(current)

        when (kind) {
            ExportKind.SUMMARY_PDF -> {
                val report = current.report ?: buildReport(bounds.first, bounds.last)
                pdfExporter.write(
                    report = report,
                    shopName = current.shopName?.takeIf { it.isNotBlank() } ?: DEFAULT_SHOP_NAME,
                    generatedAt = clock.millis(),
                    output = stream,
                )
            }

            ExportKind.PERIODS_CSV -> stream.writer(Charsets.UTF_8).use { writer ->
                writer.write(periodsCsv())
            }

            else -> stream.writer(Charsets.UTF_8).use { writer ->
                if (kind != ExportKind.CREDIT_CSV) writer.write(ledgerCsv(bounds))
                if (kind == ExportKind.BOTH_CSV) writer.write("\n")
                if (kind != ExportKind.LEDGER_CSV) writer.write(creditCsv())
            }
        }
    }

    private suspend fun ledgerCsv(bounds: LongRange): String = CsvExporter.ledger(
        transactions = transactions.listTransactions(
            TransactionFilter(from = bounds.first, to = bounds.last),
        ),
        categoryNames = rules.getCategories().associate { it.id to it.name },
        zone = zone,
    )

    private suspend fun creditCsv(): String {
        val balances = customers.observeCustomersWithBalance().first()
        val entries = balances.flatMap { customers.observeEntries(it.customer.id).first() }
        return CsvExporter.creditBook(
            entries = entries.sortedBy { it.occurredAt },
            customers = balances.associate { it.customer.id to it.customer },
            zone = zone,
        )
    }

    /**
     * Every closed period, oldest first, whatever period the screen is showing. An
     * accountant asks for the history, and a closed period is already a summary, so
     * there is no reason to make the owner pick a range for it.
     */
    private suspend fun periodsCsv(): String = CsvExporter.periods(
        sessions = reconciliation.observeClosedSessions().first().sortedBy { it.periodStart },
        zone = zone,
    )

    private suspend fun refresh() {
        val bounds = boundsFor(_state.value)
        val report = buildReport(bounds.first, bounds.last)
        _state.update { it.copy(isLoading = false, report = report) }
    }

    private fun boundsFor(state: ReportsUiState): LongRange =
        buildReport.boundsFor(state.period, clock.millis(), state.customRange)

    private companion object {
        /** Better than a blank line at the top of an exported page. */
        const val DEFAULT_SHOP_NAME = "Mezgeb"
    }
}
