package cc.ivera.magnet.engine.piece;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class NativeBootTakeoverIT {
    @TempDir Path temp;
    private static final String URL="jdbc:mysql://127.0.0.1:33316/clipfetch_jvm_it?useSSL=false&allowPublicKeyRetrieval=true";
    private static final String PASSWORD_ENV="CLIPFETCH_MYSQL_IT_PASSWORD";

    private Process spawn(String phase,UUID task,Path root,Path marker,Path logs) throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin",
                System.getProperty("os.name").toLowerCase().contains("win")?"java.exe":"java").toString();
        var command=new ProcessBuilder(java,"-cp",
                System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),
                NativeBootTakeoverChild.class.getName(),phase,task.toString(),root.toString(),marker.toString());
        command.environment().put("DB_DRIVER","com.mysql.cj.jdbc.Driver");
        command.environment().put("DB_URL",URL);
        command.environment().put("DB_USER","root");
        command.environment().put("DB_PASSWORD",System.getenv(PASSWORD_ENV));
        command.environment().put("FLYWAY_LOCATIONS","classpath:db/migration/mysql");
        return command.redirectOutput(logs.resolve(phase+".stdout.log").toFile())
                .redirectError(logs.resolve(phase+".stderr.log").toFile()).start();
    }
    @Test
    @EnabledIfSystemProperty(named="clipfetch.mysql.bootTakeover",matches="true")
    void twoRealBootContextsRecoverSameMysqlTaskAndCheckpoint() throws Exception {
        assertNotNull(System.getenv(PASSWORD_ENV));
        UUID task=UUID.randomUUID();
        Path root=temp.resolve(task.toString());
        Path logs=Path.of("target","native-boot-takeover-diagnostics",task.toString()).toAbsolutePath();
        Files.createDirectories(logs);
        try(var db=DriverManager.getConnection(URL,"root",System.getenv(PASSWORD_ENV));
            var insert=db.prepareStatement("INSERT INTO torrent_native_task (task_uuid,user_id,parse_id,info_hash,status,selected_files_json,selected_total_bytes,revision,state_path,created_at,updated_at) VALUES (?,782,1,?,'CREATED','[0]',8,0,?,NOW(6),NOW(6))")) {
            insert.setString(1,task.toString());insert.setString(2,"3".repeat(40));
            insert.setString(3,root.toString());
            assertEquals(1,insert.executeUpdate());
        }
        Path first=temp.resolve("first.marker"),second=temp.resolve("second.marker");
        Process a=spawn("A",task,root,first,logs),b=null;
        try {
            assertTrue(a.waitFor(30,TimeUnit.SECONDS),"Worker A did not terminate");
            assertEquals(37,a.exitValue(),"Worker A stderr: "+logs.resolve("A.stderr.log"));
            assertEquals("FIRST_PIECE_DURABLE",Files.readString(first));
            b=spawn("B",task,root,second,logs);
            assertTrue(b.waitFor(30,TimeUnit.SECONDS),"Worker B did not terminate");
            assertEquals(0,b.exitValue(),"Worker B stderr: "+logs.resolve("B.stderr.log"));
            assertEquals("COMPLETED:8:2:1",Files.readString(second));
            try(var db=DriverManager.getConnection(URL,"root",System.getenv(PASSWORD_ENV));
                var query=db.prepareStatement("SELECT status,execution_epoch,verified_selected_bytes FROM torrent_native_task WHERE task_uuid=?")){
                query.setString(1,task.toString());
                try(var rows=query.executeQuery()){
                    assertTrue(rows.next());
                    assertEquals("COMPLETED",rows.getString(1));
                    assertEquals(2,rows.getLong(2));
                    assertEquals(8,rows.getLong(3));
                }
                try(var audit=db.prepareStatement("SELECT COUNT(*) FROM torrent_native_recovery_audit WHERE task_uuid=? AND outcome='FENCED_EXPIRED'")){
                    audit.setString(1,task.toString());
                    try(var count=audit.executeQuery()){
                        assertTrue(count.next());
                        assertEquals(1,count.getInt(1));
                    }
                }
            }
        }finally{
            a.destroyForcibly();
            if(b!=null)b.destroyForcibly();
        }
    }
}
