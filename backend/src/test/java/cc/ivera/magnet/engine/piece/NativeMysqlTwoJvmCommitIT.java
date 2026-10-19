package cc.ivera.magnet.engine.piece;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in real MySQL, two JVMs, one checkpoint lock.
 * Run against isolated disposable MySQL only.
 */
class NativeMysqlTwoJvmCommitIT {
    @TempDir Path temp;
    static final String URL="jdbc:mysql://127.0.0.1:33316/clipfetch_jvm_it?useSSL=false&allowPublicKeyRetrieval=true";
    static final String USER="root";
    static String password() {
        String value=System.getenv("CLIPFETCH_MYSQL_IT_PASSWORD");
        if(value==null || value.isBlank())
            throw new IllegalStateException("CLIPFETCH_MYSQL_IT_PASSWORD is required");
        return value;
    }

    static Connection connection() throws Exception { return DriverManager.getConnection(URL,USER,password()); }

    public static final class Child {
        public static void main(String[] args) throws Exception {
            String mode=args[0], task=args[1];
            Path root=Path.of(args[2]), marker=Path.of(args[3]);
            if("OBSERVE".equals(mode)) {
                NativeCheckpointOwnershipLock.execute(root,()->{
                    try(var c=connection();var ps=c.prepareStatement(
                            "SELECT status,revision,execution_epoch FROM torrent_native_task WHERE task_uuid=?")) {
                        ps.setString(1,task);
                        try(var rs=ps.executeQuery()){
                            rs.next();
                            String state=rs.getString(1)+":"+rs.getLong(2)+":"+rs.getLong(3);
                            try(var as=c.prepareStatement(
                                    "SELECT COUNT(*) FROM torrent_native_recovery_audit WHERE task_uuid=?")){
                                as.setString(1,task);
                                try(var ar=as.executeQuery()){ar.next();state+=":"+ar.getInt(1);}
                            }
                            Files.writeString(marker,state);
                        }
                    }catch(Exception ex){throw new java.io.IOException(ex);}
                    return null;
                });
                return;
            }
            if(mode.startsWith("SPRING_")) {
                NativeCheckpointOwnershipLock.execute(root,()->{
                    var ds=new org.springframework.jdbc.datasource.DriverManagerDataSource(URL,USER,password());
                    var manager=new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds);
                    var tx=new org.springframework.transaction.support.TransactionTemplate(manager);
                    tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                    tx.execute(status->{
                        try {
                            var c=org.springframework.jdbc.datasource.DataSourceUtils.getConnection(ds);
                            try(var ps=c.prepareStatement("UPDATE torrent_native_task SET status='PAUSED',revision=revision+1 WHERE task_uuid=? AND status='RUNNING' AND revision=2")){
                                ps.setString(1,task);
                                if(ps.executeUpdate()!=1)throw new IllegalStateException("CAS lost");
                            }
                            try(var ps=c.prepareStatement("INSERT INTO torrent_native_recovery_audit(user_id,task_uuid,expected_revision,execution_epoch,execution_instance_id,outcome) VALUES(1,?,2,1,'11111111-1111-1111-1111-111111111111','FENCED_EXPIRED')")){
                                ps.setString(1,task);ps.executeUpdate();
                            }
                            Files.writeString(marker,"SPRING_TX_OPEN");
                            if("SPRING_CRASH".equals(mode))Thread.sleep(60000);
                            else Thread.sleep(1200);
                            return null;
                        }catch(Exception e){throw new IllegalStateException(e);}
                    });
                    return null;
                });
                return;
            }
            NativeCheckpointOwnershipLock.execute(root,()->{
                try(var c=connection()){
                    c.setAutoCommit(false);
                    try(var ps=c.prepareStatement("UPDATE torrent_native_task SET status='PAUSED',revision=revision+1 WHERE task_uuid=? AND status='RUNNING' AND revision=2")){
                        ps.setString(1,task);
                        if(ps.executeUpdate()!=1)throw new IllegalStateException("CAS lost");
                    }
                    try(var ps=c.prepareStatement("INSERT INTO torrent_native_recovery_audit(user_id,task_uuid,expected_revision,execution_epoch,execution_instance_id,outcome) VALUES(1,?,2,1,'11111111-1111-1111-1111-111111111111','FENCED_EXPIRED')")){
                        ps.setString(1,task);ps.executeUpdate();
                    }
                    Files.writeString(marker,"IN_TX");
                    if("CRASH".equals(mode))Thread.sleep(60000);
                    else {
                        Thread.sleep(1200);
                        c.commit();
                    }
                }catch(Exception ex){throw new java.io.IOException(ex);}
                return null;
            });
        }
    }

    static Process child(String mode,String task,Path root,Path marker,Path logs) throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin",
                System.getProperty("os.name").toLowerCase().contains("win")?"java.exe":"java").toString();
        return new ProcessBuilder(java,"-cp",
                System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),
                Child.class.getName(),mode,task,root.toString(),marker.toString())
                .redirectOutput(logs.resolve(mode+"-"+marker.getFileName()+".out").toFile())
                .redirectError(logs.resolve(mode+"-"+marker.getFileName()+".err").toFile()).start();
    }

    static void marker(Path path) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(!Files.exists(path) && System.nanoTime()<end)Thread.sleep(20);
        assertTrue(Files.exists(path),"missing child marker: "+path);
    }

    @Test
    @EnabledIfSystemProperty(named="clipfetch.mysql.twoJvm",matches="true")
    void mysqlCommitAndCrashHaveAtomicTaskAuditVisibility() throws Exception {
        Path logs=Path.of("target","native-mysql-jvm-diagnostics").toAbsolutePath();
        Files.createDirectories(logs);
        for(String mode:new String[]{"CRASH","COMMIT","SPRING_CRASH","SPRING_COMMIT"}){
            String task=UUID.randomUUID().toString();
            Path root=temp.resolve(task);
            Files.createDirectories(root.resolve(".clipfetch-native-state"));
            try(var c=connection();var ps=c.prepareStatement(
                    "INSERT INTO torrent_native_task(task_uuid,user_id,parse_id,info_hash,status,selected_files_json,selected_total_bytes,revision,execution_epoch,execution_instance_id,state_path,created_at,updated_at) VALUES(?,1,1,?,'RUNNING','[0]',8,2,1,'11111111-1111-1111-1111-111111111111',?,NOW(6),NOW(6))")){
                ps.setString(1,task);ps.setString(2,"a".repeat(40));ps.setString(3,root.toString());
                assertEquals(1,ps.executeUpdate());
            }
            Path a=temp.resolve("a-"+mode),b=temp.resolve("b-"+mode);
            Process first=child(mode,task,root,a,logs);
            Process second=null;
            try {
                marker(a);
                second=child("OBSERVE",task,root,b,logs);
                Thread.sleep(180);
                assertFalse(Files.exists(b),"observer crossed file lock before commit");
                if(mode.endsWith("CRASH")){
                    first.destroyForcibly();
                    assertTrue(first.waitFor(5,TimeUnit.SECONDS));
                }else {
                    assertTrue(first.waitFor(10,TimeUnit.SECONDS));
                    assertEquals(0,first.exitValue());
                }
                assertTrue(second.waitFor(10,TimeUnit.SECONDS));
                assertEquals(0,second.exitValue(),"See diagnostics at "+logs);
                assertEquals(mode.endsWith("CRASH")?"RUNNING:2:1:0":"PAUSED:3:1:1",Files.readString(b));
            }finally {
                first.destroyForcibly();
                if(second!=null)second.destroyForcibly();
            }
        }
    }
}
