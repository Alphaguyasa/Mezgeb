package et.alpha.mezgeb.data.mapper

import et.alpha.mezgeb.data.local.dao.CreditEntryWithRunningBalance
import et.alpha.mezgeb.data.local.dao.CustomerWithBalance
import et.alpha.mezgeb.data.local.dao.UnmatchedSenderGroup
import et.alpha.mezgeb.data.local.entity.CategoryEntity
import et.alpha.mezgeb.data.local.entity.CategoryRuleEntity
import et.alpha.mezgeb.data.local.entity.CreditEntryEntity
import et.alpha.mezgeb.data.local.entity.CustomerEntity
import et.alpha.mezgeb.data.local.entity.ParserRuleEntity
import et.alpha.mezgeb.data.local.entity.RawMessageEntity
import et.alpha.mezgeb.data.local.entity.ReconciliationSessionEntity
import et.alpha.mezgeb.data.local.entity.TransactionEntity
import et.alpha.mezgeb.domain.model.Category
import et.alpha.mezgeb.domain.model.CategoryRule
import et.alpha.mezgeb.domain.model.CreditEntry
import et.alpha.mezgeb.domain.model.CreditStatementLine
import et.alpha.mezgeb.domain.model.Customer
import et.alpha.mezgeb.domain.model.CustomerBalance
import et.alpha.mezgeb.domain.model.ParserRule
import et.alpha.mezgeb.domain.model.RawMessage
import et.alpha.mezgeb.domain.model.ReconciliationSession
import et.alpha.mezgeb.domain.model.Transaction
import et.alpha.mezgeb.domain.model.UnmatchedGroup

// --- RawMessage -----------------------------------------------------------------

fun RawMessageEntity.toDomain(): RawMessage = RawMessage(
    id = id,
    sender = sender,
    body = body,
    contentHash = contentHash,
    receivedAt = receivedAt,
    source = source,
    parseState = parseState,
    matchedRuleId = matchedRuleId,
)

fun RawMessage.toEntity(): RawMessageEntity = RawMessageEntity(
    id = id,
    sender = sender,
    body = body,
    contentHash = contentHash,
    receivedAt = receivedAt,
    source = source,
    parseState = parseState,
    matchedRuleId = matchedRuleId,
)

fun UnmatchedSenderGroup.toDomain(): UnmatchedGroup = UnmatchedGroup(
    sender = sender,
    messageCount = messageCount,
    latestReceivedAt = latestReceivedAt,
)

// --- Transaction ----------------------------------------------------------------

fun TransactionEntity.toDomain(): Transaction = Transaction(
    id = id,
    rawMessageId = rawMessageId,
    direction = direction,
    amountMinor = amountMinor,
    currency = currency,
    channel = channel,
    counterpartyName = counterpartyName,
    counterpartyPhone = counterpartyPhone,
    providerTxnId = providerTxnId,
    balanceAfterMinor = balanceAfterMinor,
    occurredAt = occurredAt,
    categoryId = categoryId,
    note = note,
    isReconciled = isReconciled,
    reconciliationSessionId = reconciliationSessionId,
    isDeleted = isDeleted,
    editedAt = editedAt,
    touchedAt = touchedAt,
)

fun Transaction.toEntity(): TransactionEntity = TransactionEntity(
    id = id,
    rawMessageId = rawMessageId,
    direction = direction,
    amountMinor = amountMinor,
    currency = currency,
    channel = channel,
    counterpartyName = counterpartyName,
    counterpartyPhone = counterpartyPhone,
    providerTxnId = providerTxnId,
    balanceAfterMinor = balanceAfterMinor,
    occurredAt = occurredAt,
    categoryId = categoryId,
    note = note,
    isReconciled = isReconciled,
    reconciliationSessionId = reconciliationSessionId,
    isDeleted = isDeleted,
    editedAt = editedAt,
    touchedAt = touchedAt,
)

// --- Customer & credit ----------------------------------------------------------

fun CustomerEntity.toDomain(): Customer = Customer(
    id = id,
    name = name,
    phone = phone,
    note = note,
    createdAt = createdAt,
    isArchived = isArchived,
)

fun Customer.toEntity(): CustomerEntity = CustomerEntity(
    id = id,
    name = name,
    phone = phone,
    note = note,
    createdAt = createdAt,
    isArchived = isArchived,
)

fun CustomerWithBalance.toDomain(): CustomerBalance = CustomerBalance(
    customer = Customer(
        id = id,
        name = name,
        phone = phone,
        note = note,
        createdAt = createdAt,
        isArchived = isArchived,
    ),
    balanceMinor = balanceMinor,
    oldestUnpaidAt = oldestUnpaidAt,
)

fun CreditEntryEntity.toDomain(): CreditEntry = CreditEntry(
    id = id,
    customerId = customerId,
    kind = kind,
    amountMinor = amountMinor,
    occurredAt = occurredAt,
    note = note,
    linkedTransactionId = linkedTransactionId,
)

fun CreditEntry.toEntity(): CreditEntryEntity = CreditEntryEntity(
    id = id,
    customerId = customerId,
    kind = kind,
    amountMinor = amountMinor,
    occurredAt = occurredAt,
    note = note,
    linkedTransactionId = linkedTransactionId,
)

fun CreditEntryWithRunningBalance.toDomain(): CreditStatementLine = CreditStatementLine(
    entry = CreditEntry(
        id = id,
        customerId = customerId,
        kind = kind,
        amountMinor = amountMinor,
        occurredAt = occurredAt,
        note = note,
        linkedTransactionId = linkedTransactionId,
    ),
    runningBalanceMinor = runningBalanceMinor,
)

// --- Categories & rules ---------------------------------------------------------

fun CategoryEntity.toDomain(): Category = Category(
    id = id,
    name = name,
    kind = kind,
    colorHex = colorHex,
    isSystem = isSystem,
)

fun Category.toEntity(): CategoryEntity = CategoryEntity(
    id = id,
    name = name,
    kind = kind,
    colorHex = colorHex,
    isSystem = isSystem,
)

fun CategoryRuleEntity.toDomain(): CategoryRule = CategoryRule(
    id = id,
    matchField = matchField,
    matchType = matchType,
    pattern = pattern,
    categoryId = categoryId,
    priority = priority,
)

fun CategoryRule.toEntity(): CategoryRuleEntity = CategoryRuleEntity(
    id = id,
    matchField = matchField,
    matchType = matchType,
    pattern = pattern,
    categoryId = categoryId,
    priority = priority,
)

fun ParserRuleEntity.toDomain(): ParserRule = ParserRule(
    id = id,
    name = name,
    senderPattern = senderPattern,
    channel = channel,
    bodyPattern = bodyPattern,
    fieldMapJson = fieldMapJson,
    directionStrategy = directionStrategy,
    directionKeywordsJson = directionKeywordsJson,
    version = version,
    isUserCreated = isUserCreated,
    isEnabled = isEnabled,
    priority = priority,
    createdAt = createdAt,
    matchCount = matchCount,
)

fun ParserRule.toEntity(): ParserRuleEntity = ParserRuleEntity(
    id = id,
    name = name,
    senderPattern = senderPattern,
    channel = channel,
    bodyPattern = bodyPattern,
    fieldMapJson = fieldMapJson,
    directionStrategy = directionStrategy,
    directionKeywordsJson = directionKeywordsJson,
    version = version,
    isUserCreated = isUserCreated,
    isEnabled = isEnabled,
    priority = priority,
    createdAt = createdAt,
    matchCount = matchCount,
)

// --- Reconciliation -------------------------------------------------------------

fun ReconciliationSessionEntity.toDomain(): ReconciliationSession = ReconciliationSession(
    id = id,
    periodStart = periodStart,
    periodEnd = periodEnd,
    openedAt = openedAt,
    expectedTotalMinor = expectedTotalMinor,
    capturedInMinor = capturedInMinor,
    capturedOutMinor = capturedOutMinor,
    cashRecordedMinor = cashRecordedMinor,
    creditGivenMinor = creditGivenMinor,
    creditRepaidMinor = creditRepaidMinor,
    status = status,
    closedAt = closedAt,
    note = note,
)

fun ReconciliationSession.toEntity(): ReconciliationSessionEntity = ReconciliationSessionEntity(
    id = id,
    periodStart = periodStart,
    periodEnd = periodEnd,
    openedAt = openedAt,
    expectedTotalMinor = expectedTotalMinor,
    capturedInMinor = capturedInMinor,
    capturedOutMinor = capturedOutMinor,
    cashRecordedMinor = cashRecordedMinor,
    creditGivenMinor = creditGivenMinor,
    creditRepaidMinor = creditRepaidMinor,
    status = status,
    closedAt = closedAt,
    note = note,
)
