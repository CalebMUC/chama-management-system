package ke.chama.modules.dataimport.infrastructure.persistence;

import ke.chama.modules.dataimport.application.port.in.HistoryImports.Kind;
import ke.chama.modules.dataimport.application.port.in.HistoryImports.SummaryLine;
import ke.chama.modules.dataimport.application.port.out.HistoryImportRecords;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Plain JDBC; summaries and undo data as JSONB. Row-level security restricts every statement to the current chama. */
@Repository
class JdbcHistoryImportRecords implements HistoryImportRecords {

    private static final String COLUMNS = """
            id, kind, file_name, row_count, summary::text as summary, undo_data::text as undo_data, imported_by,
            imported_at, undone_by, undone_at""";
    private static final TypeReference<List<SummaryLine>> SUMMARY = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Map<String, String>>> UNDO_DATA = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper json;
    private final RowMapper<Batch> mapper;

    JdbcHistoryImportRecords(NamedParameterJdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.mapper = (rs, i) -> new Batch(rs.getObject("id", UUID.class), Kind.valueOf(rs.getString("kind")),
                rs.getString("file_name"), rs.getInt("row_count"), json.readValue(rs.getString("summary"), SUMMARY),
                json.readValue(rs.getString("undo_data"), UNDO_DATA), rs.getObject("imported_by", UUID.class),
                rs.getTimestamp("imported_at").toInstant(), rs.getObject("undone_by", UUID.class),
                rs.getTimestamp("undone_at") == null ? null : rs.getTimestamp("undone_at").toInstant());
    }

    @Override
    public void insert(UUID chamaId, Batch b) {
        jdbc.update("""
                        insert into dataimport.history_imports
                               (id, tenant_id, kind, file_name, row_count, summary, undo_data, imported_by, imported_at)
                        values (:id, :tenant, :kind, :fileName, :rows, cast(:summary as jsonb), cast(:undo as jsonb),
                                :importedBy, :importedAt)""",
                new MapSqlParameterSource()
                        .addValue("id", b.id())
                        .addValue("tenant", chamaId)
                        .addValue("kind", b.kind().name())
                        .addValue("fileName", b.fileName())
                        .addValue("rows", b.rowCount())
                        .addValue("summary", json.writeValueAsString(b.summary()))
                        .addValue("undo", json.writeValueAsString(b.undoData()))
                        .addValue("importedBy", b.importedBy())
                        .addValue("importedAt", Timestamp.from(b.importedAt())));
    }

    @Override
    public List<Batch> all(UUID chamaId) {
        return jdbc.query("select " + COLUMNS + " from dataimport.history_imports where tenant_id = :tenant "
                + "order by imported_at desc", Map.of("tenant", chamaId), mapper);
    }

    @Override
    public Optional<Batch> find(UUID chamaId, UUID batchId) {
        return jdbc.query("select " + COLUMNS + " from dataimport.history_imports where tenant_id = :tenant and id = :id",
                Map.of("tenant", chamaId, "id", batchId), mapper).stream().findFirst();
    }

    @Override
    public boolean markUndone(UUID chamaId, UUID batchId, UUID undoneBy, Instant undoneAt) {
        return jdbc.update("""
                        update dataimport.history_imports set undone_by = :by, undone_at = :at
                         where tenant_id = :tenant and id = :id and undone_at is null""",
                new MapSqlParameterSource()
                        .addValue("by", undoneBy)
                        .addValue("at", Timestamp.from(undoneAt))
                        .addValue("tenant", chamaId)
                        .addValue("id", batchId)) == 1;
    }
}
