package et.alpha.mezgeb.data.demo

import et.alpha.mezgeb.data.local.entity.CreditEntryEntity
import et.alpha.mezgeb.data.local.entity.CustomerEntity
import et.alpha.mezgeb.data.local.entity.RawMessageEntity
import et.alpha.mezgeb.data.local.entity.ReconciliationSessionEntity
import et.alpha.mezgeb.data.local.entity.TransactionEntity
import et.alpha.mezgeb.domain.model.CaptureSource
import et.alpha.mezgeb.domain.model.Channel
import et.alpha.mezgeb.domain.model.CreditKind
import et.alpha.mezgeb.domain.model.Direction
import et.alpha.mezgeb.domain.model.ParseState
import et.alpha.mezgeb.domain.model.SessionStatus
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * The shape of ninety days in a small Addis shop, generated rather than transcribed.
 *
 * Pure functions over a seeded [Random], with no Android and no database, so the
 * distributions can be asserted in a plain unit test — and so the same seed produces
 * the same shop every time, which matters when the demo is what a stranger judges the
 * app by. A demo that looks different on every install looks like a bug.
 *
 * What makes this read like a real shop rather than like test data:
 *  - takings follow the week. Saturday is the big day, Sunday is thin, and the
 *    Ethiopian working week does not have a Western weekend shape.
 *  - amounts are lognormal-ish: many small sales, a few large ones. Uniform random
 *    amounts are the giveaway that data was generated.
 *  - round numbers are over-represented, because people pay 100 and 500 birr far more
 *    often than they pay 137.42.
 *  - outgoings are few and lumpy: rent once a month, a supplier every week or two.
 *  - three days are quiet by accident, because ninety consecutive busy days is not a
 *    shop, it is a spreadsheet.
 */
object DemoData {

    /** Fixed so that every install shows the identical shop. */
    private const val SEED = 20_260_802L

    /** Channel mix, roughly what the corpus showed: telebirr dominant, cash real. */
    private val CHANNEL_WEIGHTS = listOf(
        Channel.TELEBIRR to 52,
        Channel.CBE_BIRR to 20,
        Channel.CASH to 18,
        Channel.BANK to 10,
    )

    private val CUSTOMER_NAMES = listOf(
        "Abebe Bekele", "Tigist Haile", "Dawit Mekonnen", "Hanna Girma",
        "Solomon Tadesse", "Meseret Alemu", "Yohannes Assefa", "Bethlehem Tesfaye",
        "Kalkidan Worku", "Getachew Desta", "Selam Negash", "Mulugeta Abera",
    )

    /** Names that appear as counterparties on captured payments. */
    private val PAYER_NAMES = CUSTOMER_NAMES + listOf(
        "Rahel Bekele", "Ephrem Tilahun", "Genet Wolde", "Samuel Fikru",
        "Aster Kebede", "Biruk Hailu", "Lidya Tsegaye", "Tesfaye Gebre",
    )

    private val SUPPLIERS = listOf(
        "ABYSSINIA WHOLESALE" to "cat_supplier",
        "MERKATO TRADING" to "cat_supplier",
        "ETHIO TELECOM" to "cat_utilities",
        "EEU PREPAID" to "cat_utilities",
    )

    /**
     * Everything the demo needs, in one value, so the caller inserts in FK order and
     * nothing else has to know what that order is.
     */
    data class Shop(
        val rawMessages: List<RawMessageEntity>,
        val transactions: List<TransactionEntity>,
        val customers: List<CustomerEntity>,
        val creditEntries: List<CreditEntryEntity>,
        val sessions: List<ReconciliationSessionEntity>,
    )

    /**
     * @param nowMillis the moment the demo is anchored to. The newest transaction is a
     *   few hours old, not a few weeks, so Today is never empty when someone opens it.
     */
    fun build(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Shop {
        val random = Random(SEED)
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()

        val rawMessages = mutableListOf<RawMessageEntity>()
        val transactions = mutableListOf<TransactionEntity>()

        // Days that happen to be quiet. Chosen by index rather than at random so the
        // sequence below stays reproducible if the loop body ever changes.
        val quietDays = setOf(11, 43, 67)

        for (dayOffset in DAYS_OF_HISTORY downTo 0) {
            val date = today.minusDays(dayOffset.toLong())
            if (dayOffset in quietDays) continue

            val saleCount = salesForDay(date.dayOfWeek, random)
            repeat(saleCount) { indexInDay ->
                val at = timestampWithinTradingHours(date, zone, random, indexInDay)
                if (at > nowMillis) return@repeat

                val channel = weightedChannel(random)
                val amount = saleAmountMinor(random)
                val payer = PAYER_NAMES[random.nextInt(PAYER_NAMES.size)]

                val captured = incomingMessage(
                    channel = channel,
                    amountMinor = amount,
                    payer = payer,
                    at = at,
                    random = random,
                )
                captured.message?.let { rawMessages += it }

                transactions += TransactionEntity(
                    id = "demo_txn_${transactions.size}",
                    rawMessageId = captured.message?.id,
                    direction = Direction.IN,
                    amountMinor = amount,
                    channel = channel,
                    counterpartyName = if (channel == Channel.CASH) null else payer,
                    counterpartyPhone = captured.phone,
                    providerTxnId = captured.reference,
                    balanceAfterMinor = null,
                    occurredAt = at,
                    categoryId = "cat_sales",
                    note = null,
                    reconciliationSessionId = null,
                    editedAt = null,
                )
            }

            outgoingForDay(date, dayOffset, random)?.let { (payee, categoryId, amountMinor) ->
                val at = timestampWithinTradingHours(date, zone, random, indexInDay = 0)
                if (at <= nowMillis) {
                    transactions += TransactionEntity(
                        id = "demo_txn_${transactions.size}",
                        rawMessageId = null,
                        direction = Direction.OUT,
                        amountMinor = amountMinor,
                        channel = Channel.TELEBIRR,
                        counterpartyName = payee,
                        counterpartyPhone = null,
                        providerTxnId = null,
                        balanceAfterMinor = null,
                        occurredAt = at,
                        categoryId = categoryId,
                        note = null,
                        reconciliationSessionId = null,
                        editedAt = null,
                    )
                }
            }
        }

        val customers = buildCustomers(today, zone)
        val credit = buildCreditEntries(customers, today, zone, random)

        return Shop(
            rawMessages = rawMessages + unreadableMessages(nowMillis),
            transactions = transactions,
            customers = customers,
            creditEntries = credit,
            sessions = buildSessions(transactions, credit, today, zone, nowMillis),
        )
    }

    // --- Distributions ------------------------------------------------------------

    /**
     * Saturday is market day and roughly double a weekday; Sunday is half of one.
     * Ranges rather than fixed counts, so no two Tuesdays are identical.
     */
    private fun salesForDay(day: DayOfWeek, random: Random): Int = when (day) {
        DayOfWeek.SATURDAY -> random.nextInt(3, 8)
        DayOfWeek.SUNDAY -> random.nextInt(0, 3)
        DayOfWeek.FRIDAY -> random.nextInt(1, 5)
        else -> random.nextInt(1, 4)
    }

    /**
     * A long tail. Most sales are 20–200 birr; a few are a thousand or more.
     *
     * Built by sampling an exponent rather than a value, then snapping to a round
     * number most of the time — the snapping is what stops the list looking generated.
     */
    private fun saleAmountMinor(random: Random): Long {
        val magnitude = Math.pow(10.0, 1.3 + random.nextDouble() * 1.7)
        val birr = magnitude * (0.6 + random.nextDouble() * 0.8)
        val rounded = when {
            random.nextInt(100) < 55 -> (birr / 50).roundToLong() * 50
            random.nextInt(100) < 70 -> (birr / 10).roundToLong() * 10
            else -> birr.roundToLong()
        }
        return rounded.coerceAtLeast(5L) * 100
    }

    private fun weightedChannel(random: Random): Channel {
        val total = CHANNEL_WEIGHTS.sumOf { it.second }
        var pick = random.nextInt(total)
        for ((channel, weight) in CHANNEL_WEIGHTS) {
            pick -= weight
            if (pick < 0) return channel
        }
        return Channel.TELEBIRR
    }

    /**
     * Trade happens between 08:00 and 20:00, clustered late morning and early evening
     * — the two times people actually shop.
     */
    private fun timestampWithinTradingHours(
        date: LocalDate,
        zone: ZoneId,
        random: Random,
        indexInDay: Int,
    ): Long {
        val peak = if (indexInDay % 2 == 0) 10 else 17
        val hour = (peak + random.nextInt(-2, 3)).coerceIn(8, 20)
        val time = LocalTime.of(hour, random.nextInt(0, 60), random.nextInt(0, 60))
        return date.atTime(time).atZone(zone).toInstant().toEpochMilli()
    }

    /** Rent on the first of the month; a supplier or a bill every week or so. */
    private fun outgoingForDay(
        date: LocalDate,
        dayOffset: Int,
        random: Random,
    ): Triple<String, String, Long>? = when {
        date.dayOfMonth == 1 -> Triple("SHOP RENT", "cat_rent", 850_000L)
        dayOffset % 9 == 4 -> {
            val (name, category) = SUPPLIERS[random.nextInt(SUPPLIERS.size)]
            val amount = (random.nextInt(8, 60) * 5_000L)
            Triple(name, category, amount)
        }
        else -> null
    }

    // --- Messages -------------------------------------------------------------------

    /**
     * The message a captured payment came from, in the shape the real provider sends.
     *
     * Cash has no message — that is the whole point of the cash entry screen — and one
     * payment in eight arrives without one either, standing for the ones a shop typed
     * in by hand after the fact.
     *
     * The provider reference is generated once and used in both the message body and
     * the transaction, because a demo where the two disagree is a demo of a bug.
     */
    private fun incomingMessage(
        channel: Channel,
        amountMinor: Long,
        payer: String,
        at: Long,
        random: Random,
    ): Captured {
        if (channel == Channel.CASH) return Captured(null, null, null)

        val phone = "+2519${random.nextInt(10_000_000, 99_999_999)}"
        if (random.nextInt(8) == 0) return Captured(phone, null, null)

        val birr = "%,.2f".format(amountMinor / 100.0)
        val reference = providerRef(random)
        val (sender, body) = when (channel) {
            Channel.CBE_BIRR ->
                "CBE" to "Dear Customer, your account has been credited with ETB $birr " +
                    "from $payer. Ref $reference. Thank you for banking with CBE."
            Channel.BANK ->
                "AwashBank" to "Credit Alert: ETB $birr received from $payer. " +
                    "Reference: $reference."
            else ->
                "127" to "You have received ETB $birr from $payer ($phone). " +
                    "Transaction number is $reference. Your telebirr balance is ETB 0.00."
        }

        val id = "demo_raw_$reference"
        return Captured(
            phone = phone,
            reference = reference,
            message = RawMessageEntity(
                id = id,
                sender = sender,
                body = body,
                contentHash = sha256(id),
                receivedAt = at,
                source = CaptureSource.NOTIFICATION,
                parseState = ParseState.PARSED,
                matchedRuleId = null,
            ),
        )
    }

    private data class Captured(
        val phone: String?,
        val reference: String?,
        val message: RawMessageEntity?,
    )

    /**
     * Six messages waiting to be taught, in two formats rather than six — because the
     * review queue's job is to show that one lesson clears a batch, and a queue of six
     * unrelated one-offs would not demonstrate that.
     */
    private fun unreadableMessages(nowMillis: Long): List<RawMessageEntity> {
        val hour = 3_600_000L
        val amounts = listOf("240.00", "1,150.00", "75.00")
        val cooperative = amounts.mapIndexed { index, amount ->
            RawMessageEntity(
                id = "demo_queue_coop_$index",
                sender = "COOPBANK",
                body = "Transaction Alert. Acct **4471 Credited ETB $amount. " +
                    "Avail Bal ETB 12,430.55. Info: MOBILE TRANSFER.",
                contentHash = sha256("demo_queue_coop_$index"),
                receivedAt = nowMillis - (index + 1) * 5 * hour,
                source = CaptureSource.NOTIFICATION,
                parseState = ParseState.UNMATCHED,
                matchedRuleId = null,
            )
        }
        val amharic = listOf("500.00", "180.00", "2,000.00").mapIndexed { index, amount ->
            RawMessageEntity(
                id = "demo_queue_am_$index",
                sender = "DASHEN",
                body = "ውድ ደንበኛችን፣ ብር $amount ወደ ሂሳብዎ ገቢ ሆኗል። " +
                    "ቀሪ ሂሳብ ብር 8,940.00 ነው። ዳሽን ባንክ።",
                contentHash = sha256("demo_queue_am_$index"),
                receivedAt = nowMillis - (index + 1) * 7 * hour,
                source = CaptureSource.NOTIFICATION,
                parseState = ParseState.UNMATCHED,
                matchedRuleId = null,
            )
        }
        return cooperative + amharic
    }

    private fun providerRef(random: Random): String {
        val letters = ('A'..'Z')
        return buildString {
            repeat(3) { append(letters.elementAt(random.nextInt(26))) }
            repeat(7) { append(random.nextInt(10)) }
        }
    }

    // --- Credit book ----------------------------------------------------------------

    private fun buildCustomers(today: LocalDate, zone: ZoneId): List<CustomerEntity> =
        CUSTOMER_NAMES.mapIndexed { index, name ->
            CustomerEntity(
                id = "demo_cust_$index",
                name = name,
                phone = "+25191%07d".format(1_000_00 + index * 7_919),
                note = null,
                createdAt = today.minusDays(80L - index * 3)
                    .atStartOfDay(zone).toInstant().toEpochMilli(),
                isArchived = false,
            )
        }

    /**
     * Twelve customers, deliberately uneven: four settled up, six carrying a balance,
     * and two whose oldest unpaid credit is past the thirty-day threshold so the
     * overdue treatment on the customers screen has something to show.
     */
    private fun buildCreditEntries(
        customers: List<CustomerEntity>,
        today: LocalDate,
        zone: ZoneId,
        random: Random,
    ): List<CreditEntryEntity> {
        val entries = mutableListOf<CreditEntryEntity>()

        fun at(daysAgo: Long, hour: Int): Long =
            today.minusDays(daysAgo).atTime(hour, random.nextInt(0, 60))
                .atZone(zone).toInstant().toEpochMilli()

        customers.forEachIndexed { index, customer ->
            // 0,1 overdue; 2..7 current with a balance; 8..11 settled.
            val profile = when (index) {
                0, 1 -> Profile.OVERDUE
                in 2..7 -> Profile.CARRYING
                else -> Profile.SETTLED
            }

            val given = when (profile) {
                Profile.OVERDUE -> random.nextInt(3, 6)
                Profile.CARRYING -> random.nextInt(2, 5)
                Profile.SETTLED -> random.nextInt(1, 4)
            }
            val oldest = when (profile) {
                Profile.OVERDUE -> random.nextInt(38, 70).toLong()
                else -> random.nextInt(4, 26).toLong()
            }

            var givenTotal = 0L
            repeat(given) { n ->
                val amount = (random.nextInt(4, 40) * 2_500L)
                givenTotal += amount
                entries += CreditEntryEntity(
                    id = "demo_credit_${customer.id}_g$n",
                    customerId = customer.id,
                    kind = CreditKind.CREDIT_GIVEN,
                    amountMinor = amount,
                    occurredAt = at(oldest - n * 3, 11),
                    note = null,
                    linkedTransactionId = null,
                )
            }

            val repaid = when (profile) {
                // An overdue customer who has paid nothing is easy; one who paid a
                // little and then stopped is the case a shop actually has to chase.
                Profile.OVERDUE -> givenTotal / random.nextInt(3, 6)
                Profile.CARRYING -> givenTotal / random.nextInt(2, 4)
                Profile.SETTLED -> givenTotal
            }
            if (repaid > 0) {
                entries += CreditEntryEntity(
                    id = "demo_credit_${customer.id}_r",
                    customerId = customer.id,
                    kind = CreditKind.REPAYMENT,
                    // Snapped to 5 birr: nobody repays 233.71.
                    amountMinor = (repaid / 500) * 500,
                    occurredAt = at(
                        if (profile == Profile.SETTLED) 2 else oldest - given * 3 - 1,
                        16,
                    ),
                    note = null,
                    linkedTransactionId = null,
                )
            }
        }
        return entries
    }

    private enum class Profile { OVERDUE, CARRYING, SETTLED }

    // --- Reconciliation ---------------------------------------------------------------

    /**
     * One closed period and one open one.
     *
     * The closed period's figures are computed from the transactions actually generated
     * rather than invented, so opening it shows arithmetic that adds up — a demo whose
     * closed period disagrees with its own ledger is worse than no demo.
     */
    private fun buildSessions(
        transactions: List<TransactionEntity>,
        credit: List<CreditEntryEntity>,
        today: LocalDate,
        zone: ZoneId,
        nowMillis: Long,
    ): List<ReconciliationSessionEntity> {
        fun startOf(daysAgo: Long) =
            today.minusDays(daysAgo).atStartOfDay(zone).toInstant().toEpochMilli()

        fun endOf(daysAgo: Long) =
            today.minusDays(daysAgo).atTime(LocalTime.MAX).atZone(zone).toInstant().toEpochMilli()

        val closedStart = startOf(8)
        val closedEnd = endOf(2)
        val inWindow = transactions.filter { it.occurredAt in closedStart..closedEnd }
        val capturedIn = inWindow.filter { it.direction == Direction.IN && it.channel != Channel.CASH }
            .sumOf { it.amountMinor }
        val capturedOut = inWindow.filter { it.direction == Direction.OUT }.sumOf { it.amountMinor }
        val cash = inWindow.filter { it.direction == Direction.IN && it.channel == Channel.CASH }
            .sumOf { it.amountMinor }
        val creditGiven = credit.filter {
            it.kind == CreditKind.CREDIT_GIVEN && it.occurredAt in closedStart..closedEnd
        }.sumOf { it.amountMinor }
        val creditRepaid = credit.filter {
            it.kind == CreditKind.REPAYMENT && it.occurredAt in closedStart..closedEnd
        }.sumOf { it.amountMinor }

        val closed = ReconciliationSessionEntity(
            id = "demo_session_closed",
            periodStart = closedStart,
            periodEnd = closedEnd,
            openedAt = closedEnd,
            // Counted short by 40 birr, because a period that balances to the santim
            // does not show what the gap explanations are for.
            expectedTotalMinor = capturedIn + cash - 4_000L,
            capturedInMinor = capturedIn,
            capturedOutMinor = capturedOut,
            cashRecordedMinor = cash,
            creditGivenMinor = creditGiven,
            creditRepaidMinor = creditRepaid,
            status = SessionStatus.CLOSED,
            closedAt = closedEnd + 3_600_000L,
            note = null,
        )

        val open = ReconciliationSessionEntity(
            id = "demo_session_open",
            periodStart = startOf(1),
            periodEnd = endOf(0),
            openedAt = nowMillis,
            expectedTotalMinor = null,
            capturedInMinor = 0,
            capturedOutMinor = 0,
            cashRecordedMinor = 0,
            creditGivenMinor = 0,
            creditRepaidMinor = 0,
            status = SessionStatus.OPEN,
            closedAt = null,
            note = null,
        )

        return listOf(closed, open)
    }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }

    const val DAYS_OF_HISTORY: Int = 90
}
