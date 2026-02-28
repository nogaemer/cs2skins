import com.nogaemer.cs2skins.service.TradeupPersistenceService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

class TradeupPersistenceServiceTest {

    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var conn: Connection
    private lateinit var upsertStmt: PreparedStatement
    private lateinit var checkStmt: PreparedStatement
    private lateinit var snapshotStmt: PreparedStatement
    private lateinit var checkRs: ResultSet

    private lateinit var service: TradeupPersistenceService

    @BeforeEach
    fun setUp() {
        jdbcTemplate = mock(JdbcTemplate::class.java)
        conn = mock(Connection::class.java)
        upsertStmt = mock(PreparedStatement::class.java)
        checkStmt = mock(PreparedStatement::class.java)
        snapshotStmt = mock(PreparedStatement::class.java)
        checkRs = mock(ResultSet::class.java)

        // Route the ConnectionCallback through the mock connection
        `when`(jdbcTemplate.execute(any(ConnectionCallback::class.java))).thenAnswer { inv ->
            @Suppress("UNCHECKED_CAST")
            (inv.arguments[0] as ConnectionCallback<Boolean>).doInConnection(conn)
        }

        `when`(conn.autoCommit).thenReturn(true)
        `when`(checkStmt.executeQuery()).thenReturn(checkRs)
        // Stub column reads used by the application-side idempotency comparison
        `when`(checkRs.getDouble("roi")).thenReturn(0.15)
        `when`(checkRs.getDouble("profit")).thenReturn(5.0)
        `when`(checkRs.getDouble("input_cost")).thenReturn(100.0)
        `when`(checkRs.getDouble("output_cost")).thenReturn(115.0)

        service = TradeupPersistenceService(jdbcTemplate)
    }

    @Test
    fun `persistResult upserts current and appends snapshot when no prior identical snapshot exists`() {
        // prepareStatement is called in order: upsert → idempotency check → snapshot insert
        `when`(conn.prepareStatement(any(String::class.java)))
            .thenReturn(upsertStmt, checkStmt, snapshotStmt)
        `when`(checkRs.next()).thenReturn(false)

        val written = service.persistResult(42, 0.15, 5.0, 100.0, 115.0)

        assertTrue(written, "Expected a snapshot to be written")
        verify(upsertStmt).executeUpdate()
        verify(snapshotStmt).executeUpdate()
        verify(conn).commit()
    }

    @Test
    fun `persistResult skips snapshot when metrics are identical to last snapshot`() {
        // Only two prepareStatement calls: upsert + idempotency check (no snapshot insert)
        `when`(conn.prepareStatement(any(String::class.java)))
            .thenReturn(upsertStmt, checkStmt)
        `when`(checkRs.next()).thenReturn(true) // Identical snapshot already recorded

        val written = service.persistResult(42, 0.15, 5.0, 100.0, 115.0)

        assertFalse(written, "Expected snapshot to be skipped")
        verify(upsertStmt).executeUpdate()
        verify(snapshotStmt, never()).executeUpdate()
        verify(conn).commit()
    }

    @Test
    fun `persistResult rolls back transaction on exception`() {
        `when`(conn.prepareStatement(any(String::class.java))).thenReturn(upsertStmt)
        `when`(upsertStmt.executeUpdate()).thenThrow(RuntimeException("DB failure"))

        assertThrows(RuntimeException::class.java) {
            service.persistResult(42, 0.15, 5.0, 100.0, 115.0)
        }

        verify(conn).rollback()
        verify(conn, never()).commit()
        verify(conn).setAutoCommit(true)
    }
}
