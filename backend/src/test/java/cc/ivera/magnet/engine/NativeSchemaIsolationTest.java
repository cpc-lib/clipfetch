package cc.ivera.magnet.engine;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NativeSchemaIsolationTest {
    @Test
    void sameInfoHashCanBelongToDifferentUsersAndTaskUuids() throws Exception {
        try (var in = getClass().getClassLoader().getResourceAsStream(
                "db/migration/sqlite/V13__torrent_native_engine.sql")) {
            if (in == null) throw new AssertionError("V13 SQLite schema is missing");
            String ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            try (var db = DriverManager.getConnection("jdbc:sqlite::memory:");
                 Statement sql = db.createStatement()) {
                for (String statement : ddl.split(";")) {
                    if (!statement.isBlank()) sql.execute(statement);
                }
                String hash = "abcdef0123456789abcdef0123456789abcdef01";
                for (int n = 1; n <= 2; n++) {
                    sql.executeUpdate("INSERT INTO torrent_native_task "
                            + "(task_uuid,user_id,parse_id,info_hash,status,selected_files_json,state_path,created_at,updated_at) VALUES ("
                            + "'00000000-0000-0000-0000-00000000000" + n + "',"
                            + n + ",100,'" + hash + "','CREATED','[0]','data/" + n + "','2026-01-01','2026-01-01')");
                }
                try (var rs = sql.executeQuery("SELECT count(*) FROM torrent_native_task WHERE info_hash='" + hash + "'")) {
                    rs.next();
                    assertEquals(2, rs.getInt(1));
                }
                assertEquals(1, sql.executeUpdate("UPDATE torrent_native_task SET verified_selected_bytes=777,revision=revision+1 WHERE user_id=1 AND deleted=0"));
                try (var rs = sql.executeQuery("SELECT verified_selected_bytes FROM torrent_native_task WHERE user_id=2 AND deleted=0")) {
                    rs.next();
                    assertEquals(0, rs.getLong(1));
                }
                assertEquals(1, sql.executeUpdate("UPDATE torrent_native_task SET deleted=id WHERE user_id=1 AND deleted=0"));
                try (var rs = sql.executeQuery("SELECT count(*) FROM torrent_native_task WHERE user_id=2 AND deleted=0")) {
                    rs.next();
                    assertEquals(1, rs.getInt(1));
                }
            }
        }
    }
}
