package et.alpha.mezgeb.data.repo

import et.alpha.mezgeb.data.local.dao.ReconciliationSessionDao
import et.alpha.mezgeb.data.mapper.toDomain
import et.alpha.mezgeb.data.mapper.toEntity
import et.alpha.mezgeb.domain.model.ReconciliationSession
import et.alpha.mezgeb.domain.model.SessionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReconciliationRepository @Inject constructor(
    private val sessionDao: ReconciliationSessionDao,
) {

    fun observeOpenSession(): Flow<ReconciliationSession?> =
        sessionDao.observeOpenSession().map { it?.toDomain() }

    fun observeClosedSessions(): Flow<List<ReconciliationSession>> =
        sessionDao.observeByStatus(SessionStatus.CLOSED).map { list -> list.map { it.toDomain() } }

    suspend fun getOpenSession(): ReconciliationSession? = sessionDao.getOpenSession()?.toDomain()

    suspend fun getSession(id: String): ReconciliationSession? = sessionDao.getById(id)?.toDomain()

    suspend fun getMostRecentlyClosed(): ReconciliationSession? =
        sessionDao.getMostRecentlyClosed()?.toDomain()

    suspend fun openSession(session: ReconciliationSession) {
        require(session.status == SessionStatus.OPEN) { "openSession requires an OPEN session" }
        sessionDao.insert(session.toEntity())
    }

    /**
     * Closing snapshots every computed figure into the row. Later edits to the
     * underlying transactions must not change these numbers — that is the entire
     * point of closing a period.
     */
    suspend fun closeSession(session: ReconciliationSession) {
        require(session.status == SessionStatus.CLOSED) { "closeSession requires a CLOSED session" }
        require(session.closedAt != null) { "a closed session must carry closedAt" }
        sessionDao.update(session.toEntity())
    }

    suspend fun update(session: ReconciliationSession) = sessionDao.update(session.toEntity())
}
