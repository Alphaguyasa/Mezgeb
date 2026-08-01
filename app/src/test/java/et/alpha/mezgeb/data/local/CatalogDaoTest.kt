package et.alpha.mezgeb.data.local

import et.alpha.mezgeb.data.local.entity.CategoryRuleEntity
import et.alpha.mezgeb.data.local.entity.ReconciliationSessionEntity
import et.alpha.mezgeb.domain.model.CategoryKind
import et.alpha.mezgeb.domain.model.MatchField
import et.alpha.mezgeb.domain.model.MatchType
import et.alpha.mezgeb.domain.model.SessionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CategoryDaoTest : DatabaseTest() {

    private val dao get() = db.categoryDao()

    @Test
    fun `insertIgnoringExisting is idempotent`() = runTest {
        val batch = listOf(category("cat_sales"), category("cat_rent"))

        dao.insertIgnoringExisting(batch)
        dao.insertIgnoringExisting(batch)

        assertEquals(2, dao.count())
    }

    @Test
    fun `re-seeding does not overwrite a user's rename`() = runTest {
        dao.insertIgnoringExisting(listOf(category("cat_sales", name = "Sales")))
        dao.update(category("cat_sales", name = "Shop takings"))

        dao.insertIgnoringExisting(listOf(category("cat_sales", name = "Sales")))

        assertEquals("Shop takings", dao.getById("cat_sales")?.name)
    }

    @Test
    fun `system categories cannot be deleted but user categories can`() = runTest {
        dao.insert(category("sys", isSystem = true))
        dao.insert(category("mine", isSystem = false))

        dao.deleteUserCategory("sys")
        dao.deleteUserCategory("mine")

        assertNotNull(dao.getById("sys"))
        assertNull(dao.getById("mine"))
    }

    @Test
    fun `observeByKind filters`() = runTest {
        dao.insert(category("in", kind = CategoryKind.INCOME))
        dao.insert(category("out", kind = CategoryKind.EXPENSE))

        assertEquals(listOf("in"), dao.observeByKind(CategoryKind.INCOME).first().map { it.id })
    }
}

class CategoryRuleDaoTest : DatabaseTest() {

    private val dao get() = db.categoryRuleDao()

    @Test
    fun `rules come back in priority order, highest first`() = runTest {
        db.categoryDao().insert(category("cat_sales"))
        dao.insert(rule("r1", priority = 1))
        dao.insert(rule("r2", priority = 10))
        dao.insert(rule("r3", priority = 5))

        assertEquals(listOf("r2", "r3", "r1"), dao.getAll().map { it.id })
    }

    @Test
    fun `rules can be fetched for one category`() = runTest {
        db.categoryDao().insert(category("cat_sales"))
        db.categoryDao().insert(category("cat_rent"))
        dao.insert(rule("r1", categoryId = "cat_sales"))
        dao.insert(rule("r2", categoryId = "cat_rent"))

        assertEquals(listOf("r1"), dao.getForCategory("cat_sales").map { it.id })
    }

    private fun rule(id: String, categoryId: String = "cat_sales", priority: Int = 0) =
        CategoryRuleEntity(
            id = id,
            matchField = MatchField.COUNTERPARTY_NAME,
            matchType = MatchType.CONTAINS,
            pattern = "wholesale",
            categoryId = categoryId,
            priority = priority,
        )
}

class ParserRuleDaoTest : DatabaseTest() {

    private val dao get() = db.parserRuleDao()

    @Test
    fun `upsert replaces a bundled rule by id`() = runTest {
        dao.upsertAll(listOf(parserRule("bundled_1", bodyPattern = "old", version = 1)))
        dao.upsertAll(listOf(parserRule("bundled_1", bodyPattern = "new", version = 2)))

        val stored = dao.getById("bundled_1")
        assertEquals("new", stored?.bodyPattern)
        assertEquals(2, stored?.version)
        assertEquals(1, dao.countBundled())
    }

    @Test
    fun `re-seeding bundled rules leaves user-taught rules untouched`() = runTest {
        dao.insert(parserRule("taught_by_shop", isUserCreated = true, bodyPattern = "mine"))
        dao.upsertAll(listOf(parserRule("bundled_1", bodyPattern = "theirs")))

        // A second seed of the bundled set only ever touches bundled ids.
        dao.upsertAll(listOf(parserRule("bundled_1", bodyPattern = "theirs v2")))

        assertEquals("mine", dao.getById("taught_by_shop")?.bodyPattern)
        assertEquals(true, dao.getById("taught_by_shop")?.isUserCreated)
    }

    @Test
    fun `only enabled rules are handed to the parser, in priority order`() = runTest {
        dao.insert(parserRule("a", priority = 1))
        dao.insert(parserRule("b", priority = 9))
        dao.insert(parserRule("c", priority = 5, isEnabled = false))

        assertEquals(listOf("b", "a"), dao.getEnabled().map { it.id })
    }

    @Test
    fun `setEnabled toggles a rule out of the parser's set`() = runTest {
        dao.insert(parserRule("a"))
        dao.setEnabled("a", false)
        assertEquals(0, dao.getEnabled().size)
    }

    @Test
    fun `match counts increment`() = runTest {
        dao.insert(parserRule("a"))
        dao.incrementMatchCount("a")
        dao.incrementMatchCount("a")
        assertEquals(2L, dao.getById("a")?.matchCount)
    }

    @Test
    fun `bundled rules cannot be deleted through the user-rule path`() = runTest {
        dao.insert(parserRule("bundled", isUserCreated = false))
        dao.insert(parserRule("mine", isUserCreated = true))

        dao.deleteUserRule("bundled")
        dao.deleteUserRule("mine")

        assertNotNull(dao.getById("bundled"))
        assertNull(dao.getById("mine"))
    }
}

class ReconciliationSessionDaoTest : DatabaseTest() {

    private val dao get() = db.reconciliationSessionDao()

    @Test
    fun `the open session is found and closed ones are excluded`() = runTest {
        dao.insert(session("s1", status = SessionStatus.CLOSED, start = T0))
        dao.insert(session("s2", status = SessionStatus.OPEN, start = T0 + 7 * DAY))

        assertEquals("s2", dao.getOpenSession()?.id)
        assertEquals(listOf("s1"), dao.observeByStatus(SessionStatus.CLOSED).first().map { it.id })
    }

    @Test
    fun `closing snapshots figures that later edits do not change`() = runTest {
        dao.insert(session("s1", status = SessionStatus.OPEN, start = T0))

        dao.update(
            session("s1", status = SessionStatus.CLOSED, start = T0).copy(
                capturedInMinor = 171_000,
                closedAt = T0 + 8 * DAY,
            ),
        )

        val stored = dao.getById("s1")
        assertEquals(171_000L, stored?.capturedInMinor)
        assertEquals(SessionStatus.CLOSED, stored?.status)
        assertNull(dao.getOpenSession())
    }

    @Test
    fun `most recently closed is by period end`() = runTest {
        dao.insert(session("older", status = SessionStatus.CLOSED, start = T0, end = T0 + DAY))
        dao.insert(session("newer", status = SessionStatus.CLOSED, start = T0 + 2 * DAY, end = T0 + 3 * DAY))

        assertEquals("newer", dao.getMostRecentlyClosed()?.id)
    }

    private fun session(
        id: String,
        status: SessionStatus,
        start: Long,
        end: Long = start + 7 * DAY,
    ) = ReconciliationSessionEntity(
        id = id,
        periodStart = start,
        periodEnd = end,
        expectedTotalMinor = null,
        capturedInMinor = 0,
        capturedOutMinor = 0,
        cashRecordedMinor = 0,
        creditGivenMinor = 0,
        creditRepaidMinor = 0,
        status = status,
        closedAt = if (status == SessionStatus.CLOSED) end else null,
        note = null,
    )
}
