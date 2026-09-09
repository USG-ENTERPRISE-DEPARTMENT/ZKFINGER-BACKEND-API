package com.unionsg.zkfinger.repository;

import com.unionsg.zkfinger.domain.Finger;
import com.unionsg.zkfinger.domain.FingerprintRecord;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence for enrolled templates.
 *
 * <p>The table and column names are inherited from the BioMini service so both backends read
 * and write the same rows. Each customer row carries two fingers, stored in parallel columns;
 * {@link Finger} selects between them. The column names are chosen from a fixed enum rather
 * than interpolated from request input, so the selection cannot become an injection vector.
 */
@Repository
public class FingerprintRepository {

    private static final Logger log = LoggerFactory.getLogger(FingerprintRepository.class);

    private static final String TABLE = "TB_TBLSIG_DETAILS_TEMP";

    private final JdbcTemplate jdbc;

    public FingerprintRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Column pair holding the template and its preview image for one finger. */
    private record Columns(String template, String image) {

        static Columns of(Finger finger) {
            return finger == Finger.ONE
                    ? new Columns("FINGER_PRINT_ONE", "B64_FINGER_PRINT_ONE")
                    : new Columns("FINGER_PRINT_TWO", "B64_FINGER_PRINT_TWO");
        }
    }

    /**
     * Inserts or updates the template for one finger of one customer.
     *
     * <p>Written as an upsert so a repeat capture for the same customer overwrites the earlier
     * template rather than failing or duplicating the row. The BioMini service issued a
     * separate SELECT COUNT before deciding, which races when two captures overlap.
     *
     * @return true when a row was written
     */
    @Transactional
    public boolean save(String relationNo, Finger finger, byte[] template,
                        byte[] imageBytes, int templateSize, int quality) {

        Columns columns = Columns.of(finger);

        String sql = """
                INSERT INTO %s (RELATION_NO, %s, %s, FINGER_SIZE, FINGER_QUALITY)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (RELATION_NO) DO UPDATE SET
                    %s = EXCLUDED.%s,
                    %s = EXCLUDED.%s,
                    FINGER_SIZE = EXCLUDED.FINGER_SIZE,
                    FINGER_QUALITY = EXCLUDED.FINGER_QUALITY
                """.formatted(TABLE, columns.template(), columns.image(),
                columns.template(), columns.template(),
                columns.image(), columns.image());

        int rows = jdbc.update(sql, relationNo, template, imageBytes, templateSize, quality);
        log.debug("Stored template for relation_no={} finger={} rows={}", relationNo, finger.code(), rows);
        return rows > 0;
    }

    /** Counts rows holding a usable template for the given finger. */
    public int countTemplates(Finger finger, int minQuality) {
        String sql = """
                SELECT COUNT(*) FROM %s
                WHERE %s IS NOT NULL AND COALESCE(FINGER_QUALITY, 0) >= ?
                """.formatted(TABLE, Columns.of(finger).template());

        Integer count = jdbc.queryForObject(sql, Integer.class, minQuality);
        return count == null ? 0 : count;
    }

    /**
     * Reads one page of stored templates, ordered by primary key so paging is stable.
     *
     * <p>Ordering matters: without it Postgres may return rows in a different order between
     * pages, which would let a scan miss records or compare some of them twice.
     */
    public List<FingerprintRecord> findPage(Finger finger, int minQuality, int limit, int offset) {
        Columns columns = Columns.of(finger);

        String sql = """
                SELECT RELATION_NO, %s AS TEMPLATE, FINGER_SIZE, FINGER_QUALITY
                FROM %s
                WHERE %s IS NOT NULL AND COALESCE(FINGER_QUALITY, 0) >= ?
                ORDER BY RELATION_NO
                LIMIT ? OFFSET ?
                """.formatted(columns.template(), TABLE, columns.template());

        return jdbc.query(sql, (rs, rowNum) -> {
            byte[] template = rs.getBytes("TEMPLATE");
            return new FingerprintRecord(
                    rs.getString("RELATION_NO"),
                    template == null ? new byte[0] : template,
                    rs.getInt("FINGER_SIZE"),
                    rs.getInt("FINGER_QUALITY"));
        }, minQuality, limit, offset);
    }

    /** Reads the stored templates for one customer, across both finger columns. */
    public List<FingerprintRecord> findByRelationNo(String relationNo) {
        String sql = """
                SELECT RELATION_NO, FINGER_PRINT_ONE, FINGER_PRINT_TWO, FINGER_SIZE, FINGER_QUALITY
                FROM %s WHERE RELATION_NO = ?
                """.formatted(TABLE);

        return jdbc.query(sql, (rs, rowNum) -> {
            List<FingerprintRecord> found = new ArrayList<>(2);
            for (String column : List.of("FINGER_PRINT_ONE", "FINGER_PRINT_TWO")) {
                byte[] template = rs.getBytes(column);
                if (template != null && template.length > 0) {
                    found.add(new FingerprintRecord(rs.getString("RELATION_NO"), template,
                            rs.getInt("FINGER_SIZE"), rs.getInt("FINGER_QUALITY")));
                }
            }
            return found;
        }, relationNo).stream().flatMap(List::stream).toList();
    }

    /** Whether a row exists for the given customer. */
    public boolean exists(String relationNo) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM %s WHERE RELATION_NO = ?".formatted(TABLE),
                Integer.class, relationNo);
        return count != null && count > 0;
    }

    /** Runs a trivial query to prove the pool can still reach the database. */
    public void healthCheck() {
        jdbc.queryForObject("SELECT 1", Integer.class);
    }
}
