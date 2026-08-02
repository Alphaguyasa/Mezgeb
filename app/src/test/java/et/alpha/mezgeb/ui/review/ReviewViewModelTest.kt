package et.alpha.mezgeb.ui.review

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import et.alpha.mezgeb.capture.BacklogReprocessor
import et.alpha.mezgeb.capture.ReprocessStatus
import et.alpha.mezgeb.data.local.DatabaseTest
import et.alpha.mezgeb.data.local.T0
import et.alpha.mezgeb.data.local.rawMessage
import et.alpha.mezgeb.data.repo.TransactionRepository
import et.alpha.mezgeb.domain.model.ParseState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReviewViewModelTest : DatabaseTest() {

    private lateinit var transactions: TransactionRepository
    private val reprocessor = FakeReprocessor()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        transactions = TransactionRepository(db.transactionDao(), db.rawMessageDao())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `an empty queue is a success state, not an empty list`() = runTest {
        viewModel().state.test {
            val state = awaitItemWhere { !it.isLoading }
            assertTrue(state.isEmpty)
            assertEquals(0, state.totalWaiting)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `messages are grouped by sender, newest group first`() = runTest {
        // One sender, one template, one rule to teach — which is why the queue counts
        // senders rather than messages.
        seed("AWASH", count = 3, from = T0)
        seed("DASHEN", count = 1, from = T0 + 10 * 60_000)

        viewModel().state.test {
            val state = awaitItemWhere { it.groups.isNotEmpty() }
            assertEquals(listOf("DASHEN", "AWASH"), state.groups.map { it.sender })
            assertEquals(4, state.totalWaiting)
            assertFalse(state.isEmpty)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `messages that were read are not in the queue`() = runTest {
        seed("AWASH", count = 2, from = T0)
        db.rawMessageDao().insertIgnoringDuplicates(
            rawMessage("parsed", sender = "AWASH", contentHash = "parsed", parseState = ParseState.PARSED),
        )

        viewModel().state.test {
            assertEquals(2, awaitItemWhere { it.groups.isNotEmpty() }.totalWaiting)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `try again asks for the backlog to be re-run`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(ReviewEvent.Reprocess)

        assertTrue(reprocessor.started)
    }

    @Test
    fun `re-run progress reaches the screen`() = runTest {
        seed("AWASH", count = 1, from = T0)
        val viewModel = viewModel()

        viewModel.state.test {
            awaitItemWhere { !it.isLoading }
            reprocessor.status.value = ReprocessStatus.Running(scanned = 4, total = 10)

            assertEquals(
                ReprocessStatus.Running(4, 10),
                awaitItemWhere { it.reprocess is ReprocessStatus.Running }.reprocess,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun viewModel() = ReviewViewModel(transactions, reprocessor)

    private suspend fun seed(sender: String, count: Int, from: Long) {
        repeat(count) { index ->
            db.rawMessageDao().insertIgnoringDuplicates(
                rawMessage(
                    id = "${sender}_$index",
                    sender = sender,
                    contentHash = "${sender}_$index",
                    receivedAt = from + index * 1000L,
                    parseState = ParseState.UNMATCHED,
                ),
            )
        }
    }

    private class FakeReprocessor : BacklogReprocessor {
        val status = MutableStateFlow<ReprocessStatus>(ReprocessStatus.Idle)
        var started = false

        override fun start() {
            started = true
        }

        override fun observe(): Flow<ReprocessStatus> = status
    }

    private suspend fun ReceiveTurbine<ReviewUiState>.awaitItemWhere(
        predicate: (ReviewUiState) -> Boolean,
    ): ReviewUiState {
        var item = awaitItem()
        while (!predicate(item)) item = awaitItem()
        return item
    }
}
