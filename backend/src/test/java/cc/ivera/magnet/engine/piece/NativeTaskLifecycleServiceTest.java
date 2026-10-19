package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.domain.TorrentNativeTaskMapper;
import cc.ivera.magnet.engine.metainfo.PieceLayout;
import cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser.TorrentInfo;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeTaskLifecycleServiceTest {
    @TempDir Path work;
    private static final String HASH="3".repeat(40);
    private static final byte[] PAYLOAD="abcdefgh".getBytes(StandardCharsets.US_ASCII);

    private SqlSessionFactory factory() throws Exception {
        String jdbc="jdbc:sqlite:"+work.resolve("native-task.sqlite").toAbsolutePath();
        String ddl=Files.readString(Path.of("src/main/resources/db/migration/sqlite/V13__torrent_native_engine.sql"))
                .lines().filter(line->!line.stripLeading().startsWith("--"))
                .collect(java.util.stream.Collectors.joining("\n"));
        try(var connection=DriverManager.getConnection(jdbc)) {
            for(String statement:ddl.split(";"))if(!statement.isBlank()) {
                try(var sql=connection.createStatement()){sql.execute(statement);}
            }
        }
        String fenceDdl=Files.readString(Path.of("src/main/resources/db/migration/sqlite/V20__native_task_execution_fence.sql"))
                .lines().filter(line->!line.stripLeading().startsWith("--"))
                .collect(java.util.stream.Collectors.joining("\n"));
        try(var conn=DriverManager.getConnection(jdbc);var statement=conn.createStatement()) {
            for(String sql:fenceDdl.split(";"))if(!sql.isBlank())statement.execute(sql);
        }
        String leaseDdl=Files.readString(Path.of("src/main/resources/db/migration/sqlite/V19__native_worker_instance_lease.sql"))
                .lines().filter(line->!line.stripLeading().startsWith("--"))
                .collect(java.util.stream.Collectors.joining("\n"));
        try(var conn=DriverManager.getConnection(jdbc);var statement=conn.createStatement()) {
            for(String sql:leaseDdl.split(";"))if(!sql.isBlank())statement.execute(sql);
        }
        String trackerDdl=Files.readString(Path.of(
                "src/main/resources/db/migration/sqlite/V23__native_task_trackers.sql"))
                .lines().filter(line->!line.stripLeading().startsWith("--"))
                .collect(java.util.stream.Collectors.joining("\n"));
        try(var connection=DriverManager.getConnection(jdbc);
            var ddlStatement=connection.createStatement()) {
            for(String statement:trackerDdl.split(";"))
                if(!statement.isBlank())ddlStatement.execute(statement);
        }
        String auditDdl=Files.readString(Path.of("src/main/resources/db/migration/sqlite/V21__native_recovery_audit.sql"))
                .lines().filter(line->!line.stripLeading().startsWith("--"))
                .collect(java.util.stream.Collectors.joining("\n"));
        try(var conn=DriverManager.getConnection(jdbc);var statement=conn.createStatement()) {
            for(String sql:auditDdl.split(";"))if(!sql.isBlank())statement.execute(sql);
        }
        var cfg=new org.apache.ibatis.session.Configuration();
        cfg.setEnvironment(new Environment("native-task-sqlite",new JdbcTransactionFactory(),
                new UnpooledDataSource("org.sqlite.JDBC",jdbc,null,null)));
        try(var stream=getClass().getClassLoader().getResourceAsStream("mapper/TorrentNativeTaskMapper.xml")) {
            assertNotNull(stream);
            new XMLMapperBuilder(stream,cfg,"mapper/TorrentNativeTaskMapper.xml",
                    cfg.getSqlFragments()).parse();
        }
        try(var stream=getClass().getClassLoader().getResourceAsStream("mapper/TorrentNativeWorkerInstanceMapper.xml")) {
            assertNotNull(stream);
            new XMLMapperBuilder(stream,cfg,"mapper/TorrentNativeWorkerInstanceMapper.xml",
                    cfg.getSqlFragments()).parse();
        }
        try(var stream=getClass().getClassLoader().getResourceAsStream("mapper/TorrentNativeRecoveryAuditMapper.xml")) {
            assertNotNull(stream);
            new XMLMapperBuilder(stream,cfg,"mapper/TorrentNativeRecoveryAuditMapper.xml",
                    cfg.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(cfg);
    }

    private static TorrentInfo torrent() throws Exception {
        byte[] hashes=new byte[40];
        for(int piece=0;piece<2;piece++) {
            byte[] digest=MessageDigest.getInstance("SHA-1")
                    .digest(Arrays.copyOfRange(PAYLOAD,piece*4,piece*4+4));
            System.arraycopy(digest,0,hashes,piece*20,20);
        }
        return new TorrentInfo(HASH,"fixture",
                List.of(new PieceLayout.FileEntry(0,"fixture.bin",0,PAYLOAD.length)),
                PAYLOAD.length,4,hashes);
    }
    private void insert(long user,UUID task,Path dir) throws Exception {
        // A controlled test fixture simulates a separately verified metadata/task creation step.
        String jdbc="jdbc:sqlite:"+work.resolve("native-task.sqlite").toAbsolutePath();
        try(var conn=DriverManager.getConnection(jdbc);
            var sql=conn.prepareStatement("INSERT INTO torrent_native_task "+
                    "(task_uuid,user_id,parse_id,info_hash,status,selected_files_json,"+
                    "selected_total_bytes,verified_selected_bytes,downloaded_wire_bytes,revision,"+
                    "state_path,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            sql.setString(1,task.toString());sql.setLong(2,user);sql.setLong(3,1);
            sql.setString(4,HASH);sql.setString(5,"CREATED");sql.setString(6,"[0]");
            sql.setLong(7,PAYLOAD.length);sql.setLong(8,0);
            sql.setLong(9,0);sql.setLong(10,0);sql.setString(11,dir.toString());
            sql.setString(12,"2026-10-14T00:00:00");
            sql.setString(13,"2026-10-14T00:00:00");
            assertEquals(1,sql.executeUpdate());
        }
    }

    /** Isolated JVM fixture: persist one Piece and RUNNING SQL progress, then crash. */
    public static class CrashedSqliteWorker {
        public static void main(String[] args) throws Exception {
            Path db=Path.of(args[0]),folder=Path.of(args[1]);
            UUID task=UUID.fromString(args[2]);
            var checkpoint=new VerifiedPieceCheckpoint(torrent(),Set.of(0),folder,614,task);
            checkpoint.commit(0,Arrays.copyOfRange(PAYLOAD,0,4),()->{});
            try(var conn=DriverManager.getConnection("jdbc:sqlite:"+db.toAbsolutePath());
                var sql=conn.prepareStatement("UPDATE torrent_native_task SET status='RUNNING',"+
                    "revision=3,verified_selected_bytes=4,downloaded_wire_bytes=4 "+
                    "WHERE task_uuid=? AND user_id=614 AND status='CREATED' AND revision=0")) {
                sql.setString(1,task.toString());
                if(sql.executeUpdate()!=1)throw new IllegalStateException("Failed to checkpoint task");
            }
            Runtime.getRuntime().halt(37);
        }
    }

    @Test void separateJvmCrashThenSqliteFenceAndDiskReverification() throws Exception {
        var db=factory();
        UUID task=UUID.randomUUID();
        Path folder=work.resolve("child-crash").resolve(task.toString());
        insert(614,task,folder);
        String classpath=System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path"));
        String javaExecutable=Path.of(System.getProperty("java.home"),"bin",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                    ?"java.exe":"java").toString();
        var child=new ProcessBuilder(javaExecutable,
                "-cp",classpath,CrashedSqliteWorker.class.getName(),
                work.resolve("native-task.sqlite").toString(),folder.toString(),
                task.toString()).redirectErrorStream(true).start();
        try {
            assertTrue(child.waitFor(15,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(37,child.exitValue(),
                    new String(child.getInputStream().readAllBytes(),StandardCharsets.UTF_8));
        } finally {if(child.isAlive())child.destroyForcibly();}
        try(var session=db.openSession(true)) {
            var lifecycle=new NativeTaskLifecycleService(session.getMapper(TorrentNativeTaskMapper.class));
            assertEquals("RUNNING",lifecycle.get(614,task).state());
            assertEquals(4,lifecycle.get(614,task).verifiedBytes());
            var fenced=lifecycle.fenceOrphanedWorker(614,task,3);
            assertEquals("PAUSED",fenced.state());
            var reopened=new VerifiedPieceCheckpoint(torrent(),Set.of(0),folder,614,task);
            assertEquals(4,reopened.verifiedSelectedBytes());
            // Disk and DB agree: reconciliation is idempotent.
            assertEquals(4,lifecycle.reconcileStopped(614,task,fenced.revision(),reopened).verifiedBytes());
            Files.write(folder.resolve("fixture.bin.clipfetch.part"),new byte[]{'X'},
                    java.nio.file.StandardOpenOption.WRITE);
            var repaired=new VerifiedPieceCheckpoint(torrent(),Set.of(0),folder,614,task);
            var latest=lifecycle.get(614,task);
            assertEquals(0,lifecycle.reconcileStopped(614,task,latest.revision(),repaired).verifiedBytes());
            assertEquals("PAUSED",lifecycle.get(614,task).state());
        }
    }

    /** Two real JVMs share a database and checkpoint, with process-A halted after durable Piece 0. */
    public static final class FencedJvmProcess {
        public static void main(String[] args) throws Exception {
            String jdbc="jdbc:sqlite:"+args[1];
            UUID task=UUID.fromString(args[2]);
            Path root=Path.of(args[3]);
            Path output=Path.of(args[4]);
            var cfg=new org.apache.ibatis.session.Configuration();
            cfg.setEnvironment(new Environment("child",new JdbcTransactionFactory(),
                    new UnpooledDataSource("org.sqlite.JDBC",jdbc,null,null)));
            for(String resource:List.of("mapper/TorrentNativeTaskMapper.xml",
                    "mapper/TorrentNativeWorkerInstanceMapper.xml",
                    "mapper/TorrentNativeRecoveryAuditMapper.xml")) {
                try(var xml=FencedJvmProcess.class.getClassLoader().getResourceAsStream(resource)) {
                    if(xml==null)throw new IllegalStateException("Missing mapper "+resource);
                    new XMLMapperBuilder(xml,cfg,resource,cfg.getSqlFragments()).parse();
                }
            }
            var sessions=new SqlSessionFactoryBuilder().build(cfg);
            try(var session=sessions.openSession(true)) {
                var tasks=session.getMapper(TorrentNativeTaskMapper.class);
                var instances=session.getMapper(cc.ivera.magnet.domain.TorrentNativeWorkerInstanceMapper.class);
                var lifecycle=new NativeTaskLifecycleService(tasks);
                var checkpoint=new VerifiedPieceCheckpoint(torrent(),Set.of(0),root,755,task);
                if("FIRST".equals(args[0])) {
                    String identity=UUID.randomUUID().toString();
                    String token=UUID.randomUUID().toString();
                    var now=java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
                    if(instances.register(identity,token,now,now.plusMinutes(2))!=1)
                        throw new IllegalStateException("First instance lease not registered");
                    var fence=lifecycle.claimFencedWithDiskLock(755,task,1,identity);
                    checkpoint.commit(0,Arrays.copyOfRange(PAYLOAD,0,4),()->{
                        lifecycle.requireFence(755,task,fence.task().revision(),fence.epoch(),identity);
                    });
                    var progress=lifecycle.syncFencedProgress(755,task,fence.task().revision(),
                            fence.epoch(),identity,checkpoint,4);
                    Files.writeString(output,"FIRST:"+progress.verifiedBytes()+":"+fence.epoch());
                    Runtime.getRuntime().halt(37);
                } else {
                    var audit=session.getMapper(cc.ivera.magnet.domain.TorrentNativeRecoveryAuditMapper.class);
                    var guard=new NativeExpiredOwnerFenceService(tasks,instances,audit);
                    var before=lifecycle.get(755,task);
                    if(guard.fenceOnlyIfExpired(755,task,before.revision()))
                        throw new IllegalStateException("Live original owner was incorrectly fenced");
                    // Simulate lease expiry after the first JVM has crashed.
                    try(var conn=DriverManager.getConnection(jdbc);var statement=conn.createStatement()) {
                        if(statement.executeUpdate("UPDATE torrent_native_worker_instance "+
                                "SET lease_until='2020-01-01T00:00:00'")!=1)
                            throw new IllegalStateException("Expected one crashed owner");
                    }
                    if(!guard.fenceOnlyIfExpired(755,task,before.revision()))
                        throw new IllegalStateException("Expired owner not fenced");
                    var paused=lifecycle.get(755,task);
                    if(paused.verifiedBytes()!=4)throw new IllegalStateException("Durable progress not preserved");
                    var checked=lifecycle.reconcileStopped(755,task,paused.revision(),checkpoint);
                    var ready=lifecycle.prepare(755,task,checked.revision());
                    String identity=UUID.randomUUID().toString();
                    var next=lifecycle.claimFencedWithDiskLock(755,task,ready.revision(),identity);
                    if(next.epoch()!=2)throw new IllegalStateException("Execution epoch was not advanced");
                    checkpoint.commit(1,Arrays.copyOfRange(PAYLOAD,4,8),()->{
                        lifecycle.requireFence(755,task,next.task().revision(),next.epoch(),identity);
                    });
                    var finished=lifecycle.syncFencedProgress(755,task,next.task().revision(),
                            next.epoch(),identity,checkpoint,8);
                    if(!"COMPLETED".equals(finished.state()))
                        throw new IllegalStateException("Second JVM did not complete task");
                    Files.writeString(output,"SECOND:"+finished.verifiedBytes()+":"+next.epoch());
                }
            }
        }
    }

    @Test void recoveryAuditFailureRollsBackTaskFenceInOneDatabaseTransaction() throws Exception {
        var db=factory();
        UUID task=UUID.randomUUID();
        long owner=765;
        Path root=work.resolve("audit-rollback").resolve(task.toString());
        insert(owner,task,root);
        new VerifiedPieceCheckpoint(torrent(),Set.of(0),root,owner,task);
        String instance=UUID.randomUUID().toString();
        long revision;
        try(var session=db.openSession(true)) {
            var tasks=session.getMapper(TorrentNativeTaskMapper.class);
            var instances=session.getMapper(cc.ivera.magnet.domain.TorrentNativeWorkerInstanceMapper.class);
            var lifecycle=new NativeTaskLifecycleService(tasks);
            assertEquals("READY",lifecycle.prepare(owner,task,0).state());
            var fence=lifecycle.claimFencedWithDiskLock(owner,task,1,instance);
            revision=fence.task().revision();
            var now=java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
            instances.register(instance,UUID.randomUUID().toString(),now.minusMinutes(3),now.minusMinutes(2));
        }
        try(var session=db.openSession(false)) {
            var tasks=session.getMapper(TorrentNativeTaskMapper.class);
            var instances=session.getMapper(cc.ivera.magnet.domain.TorrentNativeWorkerInstanceMapper.class);
            var audit=org.mockito.Mockito.mock(cc.ivera.magnet.domain.TorrentNativeRecoveryAuditMapper.class);
            org.mockito.Mockito.when(audit.insert(org.mockito.ArgumentMatchers.anyLong(),
                    org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyLong(),
                    org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyString()))
                    .thenThrow(new IllegalStateException("audit unavailable"));
            var guard=new NativeExpiredOwnerFenceService(tasks,instances,audit);
            assertThrows(IllegalStateException.class,
                    ()->guard.fenceOnlyIfExpired(owner,task,revision));
            session.rollback();
        }
        try(var session=db.openSession(true)) {
            var tasks=session.getMapper(TorrentNativeTaskMapper.class);
            var row=tasks.findOwned(task.toString(),owner);
            assertEquals("RUNNING",row.getStatus());
            assertEquals(revision,row.getRevision());
            assertEquals(0,session.getMapper(
                    cc.ivera.magnet.domain.TorrentNativeRecoveryAuditMapper.class).count(owner,task.toString()));
        }
    }

    private static void checkJvmExit(Process child,String name,Path logs,
                                     Path checkpoint,Path sqlite) throws Exception {
        if(child.waitFor(12,java.util.concurrent.TimeUnit.SECONDS))return;
        Path diagnostic=logs.resolve("timeout-"+name+".txt");
        var status=new StringBuilder("PID="+child.pid()+" alive="+child.isAlive()+"\n");
        String jcmd=Path.of(System.getProperty("java.home"),"bin",
                System.getProperty("os.name").toLowerCase().contains("win")?"jcmd.exe":"jcmd").toString();
        try {
            var dump=new ProcessBuilder(jcmd,Long.toString(child.pid()),"Thread.print")
                    .redirectOutput(logs.resolve("threads-"+name+".txt").toFile())
                    .redirectError(logs.resolve("jcmd-"+name+".stderr.log").toFile()).start();
            if(dump.waitFor(4,java.util.concurrent.TimeUnit.SECONDS))
                status.append("THREAD_DUMP=").append(logs.resolve("threads-"+name+".txt"));
            else dump.destroyForcibly();
        }catch(Exception failure){status.append("jcmd failed: ").append(failure).append('\n');}
        try(var ch=java.nio.channels.FileChannel.open(checkpoint.resolve(".clipfetch-native-state")
                .resolve("piece-commit.lock"),java.nio.file.StandardOpenOption.WRITE)) {
            try(var lock=ch.tryLock()) {
                status.append("\nCHECKPOINT_LOCK_FREE=").append(lock!=null);
            }
        }catch(Exception e){status.append("\nCHECKPOINT_LOCK=").append(e);}
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+sqlite.toAbsolutePath());
            var statement=connection.createStatement()) {
            statement.setQueryTimeout(2);
            try(var rs=statement.executeQuery("PRAGMA journal_mode")) {
                if(rs.next())status.append("\nSQLITE_JOURNAL=").append(rs.getString(1));
            }
            try(var rs=statement.executeQuery("PRAGMA locking_mode")) {
                if(rs.next())status.append("\nSQLITE_LOCK_MODE=").append(rs.getString(1));
            }
            statement.execute("PRAGMA busy_timeout=200");
            try {
                statement.execute("BEGIN IMMEDIATE");
                status.append("\nSQLITE_WRITE_LOCK_FREE=true");
                statement.execute("ROLLBACK");
            }catch(java.sql.SQLException held){
                status.append("\nSQLITE_WRITE_LOCK_FREE=false ").append(held.getMessage());
            }
        }catch(Exception e){status.append("\nSQLITE_DIAGNOSTIC=").append(e);}
        Files.writeString(diagnostic,status);
        child.destroyForcibly();
        child.waitFor(4,java.util.concurrent.TimeUnit.SECONDS);
        throw new AssertionError("Child JVM "+name+" timed out: "+diagnostic);
    }

    @org.junit.jupiter.api.RepeatedTest(5)
    void twoJvmsRacingForSameReadyTaskOnlyOneClaimsExecution() throws Exception {
        var db=factory();
        UUID task=UUID.randomUUID();
        Path root=work.resolve("two-jvm-race").resolve(task.toString());
        insert(755,task,root);
        new VerifiedPieceCheckpoint(torrent(),Set.of(0),root,755,task);
        try(var session=db.openSession(true)) {
            assertEquals("READY",new NativeTaskLifecycleService(
                    session.getMapper(TorrentNativeTaskMapper.class)).prepare(755,task,0).state());
        }
        String executable=Path.of(System.getProperty("java.home"),"bin",
                System.getProperty("os.name").toLowerCase().contains("win")?"java.exe":"java").toString();
        String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        Path database=work.resolve("native-task.sqlite");
        Path a=work.resolve("race-a"),b=work.resolve("race-b");
        Path diagnostics=Path.of("target","native-jvm-diagnostics",task.toString()).toAbsolutePath();
        Files.createDirectories(diagnostics);
        var first=new ProcessBuilder(executable,"-cp",cp,FencedJvmProcess.class.getName(),
                "FIRST",database.toString(),task.toString(),root.toString(),a.toString())
                .redirectOutput(diagnostics.resolve("race-a.stdout.log").toFile())
                .redirectError(diagnostics.resolve("race-a.stderr.log").toFile()).start();
        var second=new ProcessBuilder(executable,"-cp",cp,FencedJvmProcess.class.getName(),
                "FIRST",database.toString(),task.toString(),root.toString(),b.toString())
                .redirectOutput(diagnostics.resolve("race-b.stdout.log").toFile())
                .redirectError(diagnostics.resolve("race-b.stderr.log").toFile()).start();
        try {
            checkJvmExit(first,"A",diagnostics,root,database);
            checkJvmExit(second,"B",diagnostics,root,database);
            int winners=(first.exitValue()==37?1:0)+(second.exitValue()==37?1:0);
            assertEquals(1,winners,"Exactly one JVM must own READY task claim");
            assertEquals(1,(Files.exists(a)?1:0)+(Files.exists(b)?1:0));
            try(var session=db.openSession(true)) {
                var row=session.getMapper(TorrentNativeTaskMapper.class).findOwned(task.toString(),755L);
                assertEquals(1L,row.getExecutionEpoch());
                assertEquals(4L,row.getVerifiedSelectedBytes());
                assertEquals("RUNNING",row.getStatus());
            }
            assertEquals(4,new VerifiedPieceCheckpoint(torrent(),Set.of(0),root,755,task)
                    .verifiedSelectedBytes());
        } finally {
            first.destroyForcibly();second.destroyForcibly();
        }
    }

    @Test void twoJvmCrashExpiredLeaseRecheckAndFencedResume() throws Exception {
        var db=factory();
        UUID task=UUID.randomUUID();
        Path root=work.resolve("two-jvm").resolve(task.toString());
        insert(755,task,root);
        new VerifiedPieceCheckpoint(torrent(),Set.of(0),root,755,task);
        try(var session=db.openSession(true)) {
            var service=new NativeTaskLifecycleService(session.getMapper(TorrentNativeTaskMapper.class));
            assertEquals("READY",service.prepare(755,task,0).state());
        }
        String executable=Path.of(System.getProperty("java.home"),"bin",
                System.getProperty("os.name").toLowerCase().contains("win")?"java.exe":"java").toString();
        Path database=work.resolve("native-task.sqlite");
        Path firstMarker=work.resolve("first-jvm.txt");
        Path secondMarker=work.resolve("second-jvm.txt");
        var first=new ProcessBuilder(executable,"-cp",System.getProperty("java.class.path"),
                FencedJvmProcess.class.getName(),"FIRST",database.toString(),
                task.toString(),root.toString(),firstMarker.toString()).redirectErrorStream(true).start();
        assertTrue(first.waitFor(12,java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(37,first.exitValue(),new String(first.getInputStream().readAllBytes()));
        assertEquals("FIRST:4:1",Files.readString(firstMarker));
        var second=new ProcessBuilder(executable,"-cp",System.getProperty("java.class.path"),
                FencedJvmProcess.class.getName(),"SECOND",database.toString(),
                task.toString(),root.toString(),secondMarker.toString()).redirectErrorStream(true).start();
        assertTrue(second.waitFor(12,java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0,second.exitValue(),new String(second.getInputStream().readAllBytes()));
        assertEquals("SECOND:8:2",Files.readString(secondMarker));
        try(var session=db.openSession(true)) {
            var service=new NativeTaskLifecycleService(session.getMapper(TorrentNativeTaskMapper.class));
            assertEquals("COMPLETED",service.get(755,task).state());
            assertEquals(8,service.get(755,task).verifiedBytes());
        }
        assertEquals(8,new VerifiedPieceCheckpoint(torrent(),Set.of(0),root,755,task).verifiedSelectedBytes());
    }

    @Test void expiredOwnerOnlyMayBeFencedAfterLiveLeaseStops() throws Exception {
        var db=factory();
        UUID task=UUID.randomUUID();
        long owner=740;
        Path root=work.resolve("expired-owner").resolve(task.toString());
        insert(owner,task,root);
        new VerifiedPieceCheckpoint(torrent(),Set.of(0),root,owner,task);
        try(var session=db.openSession(true)) {
            var tasks=session.getMapper(TorrentNativeTaskMapper.class);
            var instances=session.getMapper(cc.ivera.magnet.domain.TorrentNativeWorkerInstanceMapper.class);
            var lifecycle=new NativeTaskLifecycleService(tasks);
            var audit=session.getMapper(cc.ivera.magnet.domain.TorrentNativeRecoveryAuditMapper.class);
                    var guard=new NativeExpiredOwnerFenceService(tasks,instances,audit);
            var instance=UUID.randomUUID().toString();
            var now=java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
            assertEquals(1,instances.register(instance,UUID.randomUUID().toString(),now,now.plusMinutes(2)));
            assertEquals("READY",lifecycle.prepare(owner,task,0).state());
            var claimed=lifecycle.claimFencedWithDiskLock(owner,task,1,instance);
            assertFalse(guard.fenceOnlyIfExpired(owner,task,claimed.task().revision()));
            assertEquals("RUNNING",lifecycle.get(owner,task).state());
            // Force expiry through SQL to simulate an owner whose heartbeat cannot renew.
            try(var connection=DriverManager.getConnection(
                    "jdbc:sqlite:"+work.resolve("native-task.sqlite").toAbsolutePath());
                var sql=connection.prepareStatement(
                    "UPDATE torrent_native_worker_instance SET lease_until=? WHERE instance_id=?")) {
                sql.setString(1,"2020-01-01T00:00:00");sql.setString(2,instance);
                assertEquals(1,sql.executeUpdate());
            }
            assertTrue(guard.fenceOnlyIfExpired(owner,task,claimed.task().revision()));
            assertEquals(2,audit.count(owner,task.toString()));
            assertFalse(guard.fenceOnlyIfExpired(owner,task,claimed.task().revision()));
            assertEquals(2,audit.count(owner,task.toString()));
            assertEquals("PAUSED",lifecycle.get(owner,task).state());
            assertThrows(IllegalStateException.class,()->lifecycle.claimForWorker(
                    owner,task,lifecycle.prepare(owner,task,lifecycle.get(owner,task).revision()).revision()));
        }
    }

    @Test void naturalLeaseExpiryConcurrentRecoveryFencesExactlyOnce() throws Exception {
        var db=factory();
        UUID task=UUID.randomUUID();
        long owner=789;
        Path root=work.resolve("natural-expiry").resolve(task.toString());
        insert(owner,task,root);
        new VerifiedPieceCheckpoint(torrent(),Set.of(0),root,owner,task);
        long claimedRevision;
        long epoch;
        String instance=UUID.randomUUID().toString();
        try(var session=db.openSession(true)) {
            var tasks=session.getMapper(TorrentNativeTaskMapper.class);
            var instances=session.getMapper(cc.ivera.magnet.domain.TorrentNativeWorkerInstanceMapper.class);
            var lifecycle=new NativeTaskLifecycleService(tasks);
            var now=java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
            assertEquals(1,instances.register(instance,UUID.randomUUID().toString(),
                    now,now.plusSeconds(1)));
            assertEquals("READY",lifecycle.prepare(owner,task,0).state());
            var claim=lifecycle.claimFencedWithDiskLock(owner,task,1,instance);
            claimedRevision=claim.task().revision();
            epoch=claim.epoch();
            var guard=new NativeExpiredOwnerFenceService(tasks,instances,
                    session.getMapper(cc.ivera.magnet.domain.TorrentNativeRecoveryAuditMapper.class));
            assertFalse(guard.fenceOnlyIfExpired(owner,task,claimedRevision),
                    "Unexpired lease cannot be fenced");
        }
        Thread.sleep(2500);
        var executor=java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var start=new java.util.concurrent.CountDownLatch(1);
            var calls=new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for(int i=0;i<4;i++)calls.add(executor.submit(()->{
                start.await();
                try(var session=db.openSession(true)){
                    var guard=new NativeExpiredOwnerFenceService(
                            session.getMapper(TorrentNativeTaskMapper.class),
                            session.getMapper(cc.ivera.magnet.domain.TorrentNativeWorkerInstanceMapper.class),
                            session.getMapper(cc.ivera.magnet.domain.TorrentNativeRecoveryAuditMapper.class));
                    return guard.fenceOnlyIfExpired(owner,task,claimedRevision);
                }
            }));
            start.countDown();
            int successes=0;
            for(var call:calls)if(call.get(15,java.util.concurrent.TimeUnit.SECONDS))successes++;
            assertEquals(1,successes);
        }finally{executor.shutdownNow();}
        try(var session=db.openSession(true)) {
            var tasks=session.getMapper(TorrentNativeTaskMapper.class);
            var audit=session.getMapper(cc.ivera.magnet.domain.TorrentNativeRecoveryAuditMapper.class);
            var lifecycle=new NativeTaskLifecycleService(tasks);
            assertEquals("PAUSED",lifecycle.get(owner,task).state());
            assertEquals(1,audit.countOutcome(owner,task.toString(),claimedRevision,"FENCED_EXPIRED"));
            assertThrows(IllegalStateException.class,()->lifecycle.requireFence(
                    owner,task,claimedRevision,epoch,instance));
        }
    }

    @Test void fencedClaimAndReleaseCoordinateWithCheckpointFileLock() throws Exception {
        var db=factory();
        UUID task=UUID.randomUUID();
        Path root=work.resolve("cooperative-lock").resolve(task.toString());
        insert(731,task,root);
        try(var session=db.openSession(true)) {
            var service=new NativeTaskLifecycleService(session.getMapper(TorrentNativeTaskMapper.class));
            new VerifiedPieceCheckpoint(torrent(),Set.of(0),root,731,task);
            assertEquals("READY",service.prepare(731,task,0).state());
            String instance=UUID.randomUUID().toString();
            var fence=service.claimFencedWithDiskLock(731,task,1,instance);
            assertEquals(1,fence.epoch());
            assertEquals("PAUSED",service.pauseFencedWithDiskLock(
                    731,task,fence.task().revision(),fence.epoch(),instance).state());
            assertThrows(IllegalStateException.class,()->service.pauseFencedWithDiskLock(
                    731,task,fence.task().revision(),fence.epoch(),instance));
        }
    }

    @Test void fencedWorkerRoundUsesSyntheticPeerAndPersistsVerifiedPieces() throws Exception {
        var db=factory();
        UUID task=UUID.randomUUID();
        long owner=774;
        Path root=work.resolve("synthetic-fenced-peer").resolve(task.toString());
        insert(owner,task,root);
        try(var session=db.openSession(true)) {
            var lifecycle=new NativeTaskLifecycleService(session.getMapper(TorrentNativeTaskMapper.class));
            var checkpoint=new VerifiedPieceCheckpoint(torrent(),Set.of(0),root,owner,task);
            var peers=List.of(java.net.InetSocketAddress.createUnresolved("192.0.2.55",6881));
            var requested=new java.util.ArrayList<Integer>();
            PieceDownloadCoordinator.Fetcher synthetic=(endpoint,info,peerId,planner,piece,deadline)->{
                requested.add(piece);
                return Arrays.copyOfRange(PAYLOAD,piece*4,piece*4+4);
            };
            var coordinator=new PieceDownloadCoordinator(checkpoint,synthetic,peers,new byte[20],
                    1,Duration.ofSeconds(1));
            assertEquals("READY",lifecycle.prepare(owner,task,0).state());
            String instance=UUID.randomUUID().toString();
            var round=new NativeTaskRoundRunner(lifecycle);
            var first=round.runFenced(owner,task,1,checkpoint,coordinator,1,instance,()->true);
            assertEquals(1,first.round().committedPieces());
            assertEquals("PAUSED",first.task().state());
            assertEquals(4,first.task().verifiedBytes());
            var resumed=lifecycle.prepare(owner,task,first.task().revision());
            var second=round.runFenced(owner,task,resumed.revision(),checkpoint,coordinator,1,
                    UUID.randomUUID().toString(),()->true);
            assertEquals("COMPLETED",second.task().state());
            assertEquals(8,second.task().verifiedBytes());
            assertEquals(List.of(0,1),requested);
            assertEquals(8,new VerifiedPieceCheckpoint(torrent(),Set.of(0),root,owner,task)
                    .verifiedSelectedBytes());
            // Completion finalizes the selected .part file to its real torrent name.
            assertEquals(8,Files.size(root.resolve("fixture.bin")));
            assertFalse(Files.exists(root.resolve("fixture.bin.clipfetch.part")));
        }
    }

    @Test void interruptedFencedRoundPausesDurablyWithoutReplacingCancellationCause()
            throws Exception {
        var db=factory();
        long owner=781;
        UUID task=UUID.randomUUID();
        Path root=work.resolve("interrupted-fenced-cleanup").resolve(task.toString());
        insert(owner,task,root);
        var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var restored=new java.util.concurrent.atomic.AtomicBoolean();
        Thread worker=new Thread(()->{
            try(var session=db.openSession(true)) {
                var lifecycle=new NativeTaskLifecycleService(
                        session.getMapper(TorrentNativeTaskMapper.class));
                var checkpoint=new VerifiedPieceCheckpoint(
                        torrent(),Set.of(0),root,owner,task);
                lifecycle.prepare(owner,task,0);
                PieceDownloadCoordinator.Fetcher interrupted=(peer,info,peerId,planner,piece,deadline)->{
                    Thread.currentThread().interrupt();
                    throw new IOException("Synthetic network cancellation");
                };
                var coordinator=new PieceDownloadCoordinator(checkpoint,interrupted,
                        List.of(java.net.InetSocketAddress.createUnresolved("192.0.2.62",6881)),
                        new byte[20],1,Duration.ofSeconds(1));
                try {
                    new NativeTaskRoundRunner(lifecycle).runFenced(
                            owner,task,1,checkpoint,coordinator,1,
                            UUID.randomUUID().toString(),()->true);
                    failure.set(new AssertionError("Interrupted round should not succeed"));
                }catch(IOException expected) {
                    failure.set(expected);
                }
                restored.set(Thread.currentThread().isInterrupted());
            }catch(Throwable unexpected) {
                failure.set(unexpected);
            }finally {Thread.interrupted();}
        },"native-interrupted-round-regression");
        worker.start();
        worker.join(10000);
        assertFalse(worker.isAlive(),"Worker must exit within cancellation bound");
        assertInstanceOf(IOException.class,failure.get());
        assertFalse(failure.get().getMessage().contains("Ownership lock interrupted"),
                "Cleanup must not mask the original Piece cancellation");
        assertTrue(restored.get(),"Cancellation interrupt must be preserved");
        try(var session=db.openSession(true)) {
            var lifecycle=new NativeTaskLifecycleService(
                    session.getMapper(TorrentNativeTaskMapper.class));
            assertEquals("PAUSED",lifecycle.get(owner,task).state(),
                    "Cancellation must not strand the fenced task RUNNING");
        }
    }

    @Test void parallelFencedWorkerCommitsMultiplePiecesWithoutSpuriousRevisionLoss() throws Exception {
        var db=factory();
        UUID task=UUID.randomUUID();
        long owner=776;
        Path root=work.resolve("parallel-fenced-peer").resolve(task.toString());
        insert(owner,task,root);
        try(var session=db.openSession(true)) {
            var lifecycle=new NativeTaskLifecycleService(session.getMapper(TorrentNativeTaskMapper.class));
            var checkpoint=new VerifiedPieceCheckpoint(torrent(),Set.of(0),root,owner,task);
            var peers=List.of(java.net.InetSocketAddress.createUnresolved("192.0.2.55",6881),
                    java.net.InetSocketAddress.createUnresolved("192.0.2.56",6881));
            var entered=new java.util.concurrent.CountDownLatch(2);
            PieceDownloadCoordinator.Fetcher fetch=(endpoint,info,peerId,planner,piece,timeout)->{
                entered.countDown();
                try {
                    if(!entered.await(3,java.util.concurrent.TimeUnit.SECONDS))
                        throw new IOException("The Piece reads did not run in parallel");
                }catch(InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Cancelled parallel test",interrupted);
                }
                return Arrays.copyOfRange(PAYLOAD,piece*4,piece*4+4);
            };
            var coordinator=new PieceDownloadCoordinator(checkpoint,fetch,peers,new byte[20],
                    2,Duration.ofSeconds(5),2,PieceDownloadCoordinator.ChildThreads.none());
            coordinator.configureGroupCommit(2);
            var prepared=lifecycle.prepare(owner,task,0);
            var finished=new NativeTaskRoundRunner(lifecycle).runFenced(owner,task,
                    prepared.revision(),checkpoint,coordinator,2,UUID.randomUUID().toString(),()->true);
            assertEquals(2,finished.round().committedPieces());
            assertEquals("COMPLETED",finished.task().state());
            assertEquals(8,finished.task().verifiedBytes());
            assertEquals(8,Files.size(root.resolve("fixture.bin")));
            assertEquals(8,new VerifiedPieceCheckpoint(torrent(),Set.of(0),root,owner,task)
                    .verifiedSelectedBytes());
        }
    }

    @Test void fencedWorkerRejectsLostHeartbeatBeforeClaim() throws Exception {
        var db=factory();
        UUID task=UUID.randomUUID();
        long owner=775;
        Path root=work.resolve("heartbeat-fail-closed").resolve(task.toString());
        insert(owner,task,root);
        try(var session=db.openSession(true)) {
            var lifecycle=new NativeTaskLifecycleService(session.getMapper(TorrentNativeTaskMapper.class));
            var checkpoint=new VerifiedPieceCheckpoint(torrent(),Set.of(0),root,owner,task);
            var coordinator=new PieceDownloadCoordinator(checkpoint,
                    (peer,info,peerId,planner,piece,deadline)->Arrays.copyOfRange(PAYLOAD,piece*4,piece*4+4),
                    List.of(java.net.InetSocketAddress.createUnresolved("192.0.2.55",6881)),
                    new byte[20],1,Duration.ofSeconds(1));
            var ready=lifecycle.prepare(owner,task,0);
            assertThrows(IllegalStateException.class,()->new NativeTaskRoundRunner(lifecycle).runFenced(
                    owner,task,ready.revision(),checkpoint,coordinator,1,UUID.randomUUID().toString(),()->false));
            assertEquals("READY",lifecycle.get(owner,task).state());
            assertEquals(0,checkpoint.verifiedSelectedBytes());
        }
    }

    @Test void fencedLifecycleClaimsAndPersistsVerifiedProgress() throws Exception {
        var db=factory();
        UUID task=UUID.randomUUID();
        long owner=730;
        Path folder=work.resolve("fenced-life").resolve(task.toString());
        insert(owner,task,folder);
        try(var session=db.openSession(true)) {
            var mapper=session.getMapper(TorrentNativeTaskMapper.class);
            var lifecycle=new NativeTaskLifecycleService(mapper);
            var checkpoint=new VerifiedPieceCheckpoint(torrent(),Set.of(0),folder,owner,task);
            assertEquals("READY",lifecycle.prepare(owner,task,0).state());
            String instance=UUID.randomUUID().toString();
            var fence=lifecycle.claimFencedWithDiskLock(owner,task,1,instance);
            assertEquals(1,fence.epoch());
            assertEquals("RUNNING",fence.task().state());
            assertThrows(IllegalStateException.class,
                    ()->lifecycle.requireFence(owner,task,2,1,UUID.randomUUID().toString()));
            checkpoint.commit(0,Arrays.copyOfRange(PAYLOAD,0,4),()->{});
            var updated=lifecycle.syncFencedProgress(owner,task,2,fence.epoch(),instance,checkpoint,4);
            assertEquals(4,updated.verifiedBytes());
            assertThrows(IllegalStateException.class,
                    ()->lifecycle.syncFencedProgress(owner,task,2,fence.epoch(),instance,checkpoint,8));
            assertEquals("PAUSED",lifecycle.pause(owner,task,updated.revision()).state());
            assertThrows(IllegalStateException.class,
                    ()->lifecycle.requireFence(owner,task,updated.revision(),fence.epoch(),instance));
        }
    }

    @Test void fencedGenerationRejectsOldInstanceEvenWithCurrentRevision() throws Exception {
        var db=factory();
        UUID task=UUID.randomUUID();
        insert(728,task,work.resolve("epoch").resolve(task.toString()));
        try(var session=db.openSession(true)) {
            var mapper=session.getMapper(TorrentNativeTaskMapper.class);
            var lifecycle=new NativeTaskLifecycleService(mapper);
            assertEquals("READY",lifecycle.prepare(728,task,0).state());
            assertEquals(1,mapper.claimExecutionFence(task.toString(),728,1,"instance-A"));
            assertEquals(0,mapper.updateFencedProgress(task.toString(),728,2,1,"instance-B",4,4,false));
            assertEquals(0,mapper.updateFencedProgress(task.toString(),728,2,0,"instance-A",4,4,false));
            assertEquals(0,mapper.updateProgressCas(task.toString(),728L,2,4,4,false));
            assertEquals(1,mapper.updateFencedProgress(task.toString(),728,2,1,"instance-A",4,4,false));
            assertEquals(0,mapper.updateFencedProgress(task.toString(),728,2,1,"instance-A",8,8,true));
            assertEquals(4,lifecycle.get(728,task).verifiedBytes());
        }
    }

    @Test void taskLifecycleIsOwnerAndRevisionFenced() throws Exception {
        var factory=factory();
        UUID mine=UUID.randomUUID(),others=UUID.randomUUID();
        Path myDir=work.resolve("user-11").resolve(mine.toString());
        Path otherDir=work.resolve("user-12").resolve(others.toString());
        insert(11,mine,myDir);insert(12,others,otherDir);
        try(var session=factory.openSession(true)) {
            var mapper=session.getMapper(TorrentNativeTaskMapper.class);
            var service=new NativeTaskLifecycleService(mapper);
            var checkpoint=new VerifiedPieceCheckpoint(torrent(),Set.of(0),myDir,11,mine);
            assertEquals("CREATED",service.get(11,mine).state());
            assertEquals(List.of(mine),service.list(11,1,20).stream()
                    .map(NativeTaskLifecycleService.TaskState::taskUuid).toList());
            assertEquals(List.of(others),service.list(12,1,20).stream()
                    .map(NativeTaskLifecycleService.TaskState::taskUuid).toList());
            assertTrue(service.list(11,2,20).isEmpty());
            assertThrows(IllegalArgumentException.class,()->service.list(11,1,31));
            assertThrows(IllegalArgumentException.class,()->service.list(11,Integer.MAX_VALUE,30));
            assertThrows(NoSuchElementException.class,()->service.get(12,mine));
            assertThrows(NoSuchElementException.class,()->service.pause(12,mine,0));
            assertEquals("READY",service.prepare(11,mine,0).state());
            assertThrows(IllegalStateException.class,()->service.prepare(11,mine,0));
            assertEquals("RUNNING",service.claimForWorker(11,mine,1).state());
            checkpoint.commit(0,Arrays.copyOfRange(PAYLOAD,0,4),()->{});
            var progress=service.syncRunningProgress(11,mine,2,checkpoint,4);
            assertEquals(4,progress.verifiedBytes());
            assertEquals(3,progress.revision());
            assertEquals("PAUSED",service.pause(11,mine,3).state());
            assertThrows(IllegalStateException.class,()->service.syncRunningProgress(
                    11,mine,3,checkpoint,5)); // stale/paused Worker may not update progress
            assertThrows(IllegalStateException.class,()->service.logicalDelete(11,mine,3));
            assertThrows(IllegalStateException.class,()->service.claimForWorker(11,mine,4));
            assertEquals(4,service.get(11,mine).verifiedBytes());
            assertEquals("READY",service.prepare(11,mine,4).state());
            assertEquals("RUNNING",service.claimForWorker(11,mine,5).state());
            checkpoint.commit(1,Arrays.copyOfRange(PAYLOAD,4,8),()->{});
            var complete=service.syncRunningProgress(11,mine,6,checkpoint,8);
            assertEquals("COMPLETED",complete.state());
            assertEquals(100.0,complete.progressPercent());
            assertThrows(IllegalStateException.class,()->service.prepare(11,mine,7));
            service.logicalDelete(11,mine,7);
            assertThrows(NoSuchElementException.class,()->service.get(11,mine));
            assertEquals("CREATED",service.get(12,others).state());
        }
    }

    @Test void continuingFencedRoundsReuseVerifiedCheckpointButRejectStaleTaskRevisions()
            throws Exception {
        var db=factory();
        UUID id=UUID.randomUUID();
        long owner=852;
        Path directory=work.resolve("continuous-checkpoint").resolve(id.toString());
        insert(owner,id,directory);
        try(var session=db.openSession(true)) {
            var service=new NativeTaskLifecycleService(
                    session.getMapper(TorrentNativeTaskMapper.class));
            var checkpoint=new VerifiedPieceCheckpoint(torrent(),Set.of(0),directory,owner,id);
            var ready=service.prepare(owner,id,0);
            assertEquals(0,service.reconcileFreshlyOpened(owner,id,ready.revision(),checkpoint)
                    .verifiedBytes());
            var endpoint=java.net.InetSocketAddress.createUnresolved("192.0.2.11",6881);
            var coordinator=new PieceDownloadCoordinator(checkpoint,
                    (peer,info,peerId,planner,piece,timeout)->Arrays.copyOfRange(
                            PAYLOAD,piece*4,piece*4+4),List.of(endpoint),
                    new byte[20],1,Duration.ofSeconds(3));
            var first=new NativeTaskRoundRunner(service).runFenced(owner,id,
                    ready.revision(),checkpoint,coordinator,1,UUID.randomUUID().toString(),()->true);
            assertEquals("PAUSED",first.task().state());
            assertEquals(4,first.task().verifiedBytes());
            var secondReady=service.prepare(owner,id,first.task().revision());
            var reused=service.reconcileContinuing(owner,id,secondReady.revision(),
                    checkpoint,first.task());
            assertEquals(4,reused.verifiedBytes());
            assertEquals("READY",reused.state());
            assertThrows(IllegalStateException.class,()->service.reconcileContinuing(
                    owner,id,ready.revision(),checkpoint,first.task()));
            assertThrows(java.util.NoSuchElementException.class,()->service.reconcileContinuing(
                    owner+1,id,secondReady.revision(),checkpoint,first.task()));
            var second=new NativeTaskRoundRunner(service).runFenced(owner,id,
                    secondReady.revision(),checkpoint,coordinator,1,
                    UUID.randomUUID().toString(),()->true);
            assertEquals("COMPLETED",second.task().state());
            assertEquals(8,second.task().verifiedBytes());
            assertEquals(8,new VerifiedPieceCheckpoint(torrent(),Set.of(0),directory,owner,id)
                    .verifiedSelectedBytes());
        }
    }

    @Test void initialWorkerReconciliationUsesSingleFreshCheckpointWithoutDroppingCas()
            throws Exception {
        var db=factory();
        UUID id=UUID.randomUUID();
        Path directory=work.resolve("fresh-reconcile").resolve(id.toString());
        insert(121,id,directory);
        try(var session=db.openSession(true)) {
            var service=new NativeTaskLifecycleService(
                    session.getMapper(TorrentNativeTaskMapper.class));
            var ready=service.prepare(121,id,0);
            var checkpoint=new VerifiedPieceCheckpoint(torrent(),Set.of(0),directory,121,id);
            var reconciled=service.reconcileFreshlyOpened(121,id,ready.revision(),checkpoint);
            assertEquals("READY",reconciled.state());
            assertEquals(0,reconciled.verifiedBytes());
            assertEquals(ready.revision(),reconciled.revision());
            assertThrows(IllegalStateException.class,
                    ()->service.reconcileFreshlyOpened(121,id,ready.revision(),checkpoint));
            assertThrows(NoSuchElementException.class,
                    ()->service.reconcileFreshlyOpened(122,id,ready.revision(),
                            new VerifiedPieceCheckpoint(torrent(),Set.of(0),directory,121,id)));
            // Generic reconciliation always reloads disk instead of consuming an in-memory token.
            assertEquals(0,service.reconcileStopped(121,id,ready.revision(),checkpoint).verifiedBytes());
        }
    }

    @Test void corruptedDiskCanDecreaseVerifiedBytesOnlyWhenPaused() throws Exception {
        var factory=factory();
        UUID id=UUID.randomUUID();Path dir=work.resolve(id.toString());
        insert(21,id,dir);
        try(var session=factory.openSession(true)) {
            var service=new NativeTaskLifecycleService(session.getMapper(TorrentNativeTaskMapper.class));
            var checkpoint=new VerifiedPieceCheckpoint(torrent(),Set.of(0),dir,21,id);
            service.prepare(21,id,0);
            service.claimForWorker(21,id,1);
            checkpoint.commit(0,Arrays.copyOfRange(PAYLOAD,0,4),()->{});
            assertEquals(4,service.syncRunningProgress(21,id,2,checkpoint,4).verifiedBytes());
            assertThrows(IllegalStateException.class,()->service.reconcileStopped(21,id,3,checkpoint));
            service.pause(21,id,3);
            Files.write(dir.resolve("fixture.bin.clipfetch.part"),new byte[]{'X'},
                    java.nio.file.StandardOpenOption.WRITE);
            var repaired=service.reconcileStopped(21,id,4,checkpoint);
            assertEquals(0,repaired.verifiedBytes());
            assertEquals("PAUSED",repaired.state());
            assertEquals(5,repaired.revision());
            assertEquals(0,service.reconcileStopped(21,id,5,checkpoint).verifiedBytes());
            assertEquals(5,service.get(21,id).revision());
            // Database revision and userId are verified even when the fixture shares InfoHash.
            assertThrows(SecurityException.class,()->service.reconcileStopped(21,id,5,
                    new VerifiedPieceCheckpoint(torrent(),Set.of(0),work.resolve("wrong"),
                            21,UUID.randomUUID())));
        }
    }

    @Test void sqliteOrphanFenceAndReopenedDiskCheckpointCorrectsCorruptedProgress() throws Exception {
        var db=factory();
        UUID task=UUID.randomUUID();
        long owner=613L;
        Path folder=work.resolve("sqlite-orphan").resolve(task.toString());
        insert(owner,task,folder);
        try(var session=db.openSession(true)) {
            var lifecycle=new NativeTaskLifecycleService(session.getMapper(TorrentNativeTaskMapper.class));
            var original=new VerifiedPieceCheckpoint(torrent(),Set.of(0),folder,owner,task);
            assertEquals("READY",lifecycle.prepare(owner,task,0).state());
            assertEquals("RUNNING",lifecycle.claimForWorker(owner,task,1).state());
            original.commit(0,Arrays.copyOfRange(PAYLOAD,0,4),()->{});
            assertEquals(4,lifecycle.syncRunningProgress(owner,task,2,original,4).verifiedBytes());
            // Simulate a new process retaining only the SQLite file and disk state.
            var running=lifecycle.get(owner,task);
            assertEquals("RUNNING",running.state());
            assertThrows(IllegalStateException.class,
                    ()->lifecycle.reconcileStopped(owner,task,running.revision(),original));
            assertThrows(NoSuchElementException.class,
                    ()->lifecycle.fenceOrphanedWorker(owner+1,task,running.revision()));
            assertEquals("PAUSED",lifecycle.fenceOrphanedWorker(owner,task,running.revision()).state());
            assertThrows(IllegalStateException.class,
                    ()->lifecycle.fenceOrphanedWorker(owner,task,running.revision()));
            Files.write(folder.resolve("fixture.bin.clipfetch.part"),new byte[]{'X'},
                    java.nio.file.StandardOpenOption.WRITE);
            var reopened=new VerifiedPieceCheckpoint(torrent(),Set.of(0),folder,owner,task);
            assertEquals(0,reopened.verifiedSelectedBytes());
            var paused=lifecycle.get(owner,task);
            assertEquals(4,paused.verifiedBytes(),"Database retains old progress until reconciliation");
            var recovered=lifecycle.reconcileStopped(owner,task,paused.revision(),reopened);
            assertEquals("PAUSED",recovered.state());
            assertEquals(0,recovered.verifiedBytes());
            assertTrue(recovered.revision()>paused.revision());
            assertThrows(IllegalStateException.class,
                    ()->lifecycle.prepare(owner,task,paused.revision()));
            assertEquals("READY",lifecycle.prepare(owner,task,recovered.revision()).state());
        }
    }

    @Test void boundedWorkerRoundSynchronizesProgressAndPausesBeforeNextRound() throws Exception {
        var factory=factory();
        UUID id=UUID.randomUUID();
        Path folder=work.resolve("worker").resolve(id.toString());
        insert(77,id,folder);
        try(var session=factory.openSession(true)) {
            var lifecycle=new NativeTaskLifecycleService(session.getMapper(TorrentNativeTaskMapper.class));
            var state=new VerifiedPieceCheckpoint(torrent(),Set.of(0),folder,77,id);
            var peers=List.of(java.net.InetSocketAddress.createUnresolved("8.8.8.8",6881));
            PieceDownloadCoordinator.Fetcher local=(peer,t,peerId,layout,piece,deadline) ->
                    Arrays.copyOfRange(PAYLOAD,piece*4,piece*4+4);
            var coordinator=new PieceDownloadCoordinator(state,local,peers,
                    new byte[20],1,Duration.ofSeconds(1));
            var runner=new NativeTaskRoundRunner(lifecycle);
            lifecycle.prepare(77,id,0);
            var first=runner.run(77,id,1,state,coordinator,1);
            assertEquals(1,first.round().committedPieces());
            assertEquals("PAUSED",first.task().state());
            assertEquals(4,first.task().verifiedBytes());
            assertEquals(4,first.task().wireBytes());
            assertEquals("READY",lifecycle.prepare(77,id,first.task().revision()).state());
            var ready=lifecycle.get(77,id);
            var second=runner.run(77,id,ready.revision(),state,coordinator,2);
            assertEquals("COMPLETED",second.task().state());
            assertEquals(8,second.task().verifiedBytes());
            assertEquals(8,second.task().wireBytes());
            assertEquals(100.0,second.task().progressPercent());
            assertEquals(8,new VerifiedPieceCheckpoint(torrent(),Set.of(0),folder,77,id)
                    .verifiedSelectedBytes());
        }
    }

    @Test void staleWorkerAfterConcurrentPauseCannotPublishProgress() throws Exception {
        var factory=factory();
        UUID id=UUID.randomUUID();
        Path folder=work.resolve("paused-worker").resolve(id.toString());
        insert(88,id,folder);
        try(var session=factory.openSession(true)) {
            var lifecycle=new NativeTaskLifecycleService(session.getMapper(TorrentNativeTaskMapper.class));
            var state=new VerifiedPieceCheckpoint(torrent(),Set.of(0),folder,88,id);
            lifecycle.prepare(88,id,0);
            PieceDownloadCoordinator.Fetcher racing=(peer,t,pid,layout,piece,deadline)->{
                lifecycle.pause(88,id,2); // Another owner operation fences the active Worker.
                return Arrays.copyOfRange(PAYLOAD,piece*4,piece*4+4);
            };
            var coordinator=new PieceDownloadCoordinator(state,racing,
                    List.of(java.net.InetSocketAddress.createUnresolved("8.8.8.8",6881)),
                    new byte[20],1,Duration.ofSeconds(1));
            var runner=new NativeTaskRoundRunner(lifecycle);
            assertThrows(IOException.class,
                    ()->runner.run(88,id,1,state,coordinator,1));
            assertEquals("PAUSED",lifecycle.get(88,id).state());
            assertEquals(0,lifecycle.get(88,id).verifiedBytes());
            // Early CAS fencing now rejects the returned Piece before disk commit.
            assertEquals(0,state.verifiedSelectedBytes());
            assertEquals(0,lifecycle.reconcileStopped(88,id,3,state).verifiedBytes());
            assertEquals("PAUSED",lifecycle.get(88,id).state());
        }
    }

    @Test void pausedTaskCannotStartAnotherPeerRequestAfterFailedAttempt() throws Exception {
        var factory=factory();
        UUID task=UUID.randomUUID();
        Path folder=work.resolve("guard").resolve(task.toString());
        insert(99,task,folder);
        try(var session=factory.openSession(true)) {
            var lifecycle=new NativeTaskLifecycleService(session.getMapper(TorrentNativeTaskMapper.class));
            var checkpoint=new VerifiedPieceCheckpoint(torrent(),Set.of(0),folder,99,task);
            lifecycle.prepare(99,task,0);
            var attempts=new java.util.concurrent.atomic.AtomicInteger();
            PieceDownloadCoordinator.Fetcher fetcher=(endpoint,torrent,identity,layout,piece,deadline)->{
                attempts.incrementAndGet();
                lifecycle.pause(99,task,2);
                throw new java.io.IOException("Peer closed connection");
            };
            var coordinator=new PieceDownloadCoordinator(checkpoint,fetcher,
                    List.of(java.net.InetSocketAddress.createUnresolved("8.8.8.8",6881),
                            java.net.InetSocketAddress.createUnresolved("9.9.9.9",6881)),
                    new byte[20],2,Duration.ofSeconds(1));
            assertThrows(IOException.class,()->new NativeTaskRoundRunner(lifecycle)
                    .run(99,task,1,checkpoint,coordinator,1));
            assertEquals(1,attempts.get());
            assertEquals("PAUSED",lifecycle.get(99,task).state());
            assertEquals(0,lifecycle.get(99,task).verifiedBytes());
        }
    }

    @Test void asyncCancellationAndExplicitOrphanRecoveryUseActualSqliteCas() throws Exception {
        var factory=factory();
        UUID active=UUID.randomUUID(),orphan=UUID.randomUUID();
        insert(81,active,work.resolve("async-"+active));
        insert(81,orphan,work.resolve("orphan-"+orphan));
        try(var session=factory.openSession(true)) {
            var lifecycle=new NativeTaskLifecycleService(session.getMapper(TorrentNativeTaskMapper.class));
            var flags=new cc.ivera.magnet.engine.config.NativeTorrentProperties();
            flags.setEnabled(true);
            flags.setWorkerRoundEnabled(true);
            flags.setAsyncWorkerEnabled(true);
            var controls=new NativeWorkerJobManager.TaskAccess() {
                @Override public NativeTaskLifecycleService.TaskState get(long user,UUID task) {
                    return lifecycle.get(user,task);
                }
                @Override public NativeTaskLifecycleService.TaskState pause(long user,UUID task,long rev) {
                    return lifecycle.pause(user,task,rev);
                }
                @Override public NativeTaskLifecycleService.TaskState fenceOrphan(long user,UUID task,long rev) {
                    return lifecycle.fenceOrphanedWorker(user,task,rev);
                }
            };
            var started=new java.util.concurrent.CountDownLatch(1);
            var release=new java.util.concurrent.CountDownLatch(1);
            try(var async=new NativeWorkerJobManager((owner,id,revision,maxPieces)->{
                lifecycle.claimForWorker(owner,id,revision);
                started.countDown();
                try {release.await(3,java.util.concurrent.TimeUnit.SECONDS);}
                catch(InterruptedException ignored) {/* a slow transport can resist interruption */}
                return new NativeTaskWorkerRoundService.RoundReport(0,false,null,
                        lifecycle.get(owner,id));
            },controls,flags,1,1,1)) {
                assertEquals("READY",lifecycle.prepare(81,active,0).state());
                var job=async.submit(81,active,1,1);
                assertTrue(started.await(2,java.util.concurrent.TimeUnit.SECONDS));
                assertEquals("RUNNING",lifecycle.get(81,active).state());
                assertThrows(NoSuchElementException.class,()->async.cancel(82,job));
                assertEquals(NativeWorkerJobManager.State.CANCELLED,async.cancel(81,job).state());
                assertEquals("PAUSED",lifecycle.get(81,active).state());
                assertEquals(3,lifecycle.get(81,active).revision());
                release.countDown();
                assertEquals(NativeWorkerJobManager.State.CANCELLED,
                        async.await(81,job,Duration.ofSeconds(1)).state());

                lifecycle.prepare(81,orphan,0);
                lifecycle.claimForWorker(81,orphan,1);
                assertThrows(IllegalStateException.class,
                        ()->async.recoverOwnedOrphan(81,orphan,2));
                flags.setSingleInstanceOrphanRecoveryEnabled(true);
                assertEquals("PAUSED",async.recoverOwnedOrphan(81,orphan,2).state());
                assertEquals(3,lifecycle.get(81,orphan).revision());
                assertThrows(NoSuchElementException.class,
                        ()->async.recoverOwnedOrphan(82,orphan,3));
            }finally {release.countDown();}
        }
    }

    @Test void sameInfoHashAcrossUsersKeepsCheckpointAndRecoveryIsolated() throws Exception {
        var factory=factory();
        UUID first=UUID.randomUUID(),second=UUID.randomUUID();
        Path firstRoot=work.resolve("owner-101").resolve(first.toString());
        Path secondRoot=work.resolve("owner-202").resolve(second.toString());
        insert(101,first,firstRoot);
        insert(202,second,secondRoot);
        try(var session=factory.openSession(true)) {
            var lifecycle=new NativeTaskLifecycleService(session.getMapper(TorrentNativeTaskMapper.class));
            var firstCheckpoint=new VerifiedPieceCheckpoint(torrent(),Set.of(0),firstRoot,101,first);
            var secondCheckpoint=new VerifiedPieceCheckpoint(torrent(),Set.of(0),secondRoot,202,second);
            lifecycle.prepare(101,first,0);
            lifecycle.prepare(202,second,0);
            lifecycle.claimForWorker(101,first,1);
            lifecycle.claimForWorker(202,second,1);
            firstCheckpoint.commit(0,Arrays.copyOfRange(PAYLOAD,0,4),()->{});
            secondCheckpoint.commit(0,Arrays.copyOfRange(PAYLOAD,0,4),()->{});
            assertEquals(4,lifecycle.syncRunningProgress(101,first,2,firstCheckpoint,4).verifiedBytes());
            assertEquals(4,lifecycle.syncRunningProgress(202,second,2,secondCheckpoint,4).verifiedBytes());
            assertThrows(NoSuchElementException.class,()->lifecycle.pause(202,first,3));
            assertThrows(SecurityException.class,()->lifecycle.syncRunningProgress(
                    101,first,3,secondCheckpoint,4));
            lifecycle.pause(101,first,3);
            Files.write(firstRoot.resolve("fixture.bin.clipfetch.part"),new byte[]{'X'},
                    java.nio.file.StandardOpenOption.WRITE);
            assertEquals(0,lifecycle.reconcileStopped(101,first,4,firstCheckpoint).verifiedBytes());
            assertEquals("PAUSED",lifecycle.get(101,first).state());
            assertEquals("RUNNING",lifecycle.get(202,second).state());
            assertEquals(4,lifecycle.get(202,second).verifiedBytes());
            assertEquals(4,new VerifiedPieceCheckpoint(torrent(),Set.of(0),secondRoot,202,second)
                    .verifiedSelectedBytes());
        }
    }

    @Test void restartedProcessFencesOrphanAndReverifiesDurableCheckpoint() throws Exception {
        var db=factory();
        UUID task=UUID.randomUUID();
        Path root=work.resolve("restart").resolve(task.toString());
        insert(305,task,root);
        // First application lifecycle persists a RUNNING task and verified Piece.
        try(var original=db.openSession(true)) {
            var service=new NativeTaskLifecycleService(original.getMapper(TorrentNativeTaskMapper.class));
            var checkpoint=new VerifiedPieceCheckpoint(torrent(),Set.of(0),root,305,task);
            service.prepare(305,task,0);
            service.claimForWorker(305,task,1);
            checkpoint.commit(0,Arrays.copyOfRange(PAYLOAD,0,4),()->{});
            assertEquals(4,service.syncRunningProgress(305,task,2,checkpoint,4).verifiedBytes());
            assertEquals("RUNNING",service.get(305,task).state());
        }
        // Emulate a new JVM by discarding all service/checkpoint instances and reopening SQLite.
        // A corrupt Piece must not remain credited after fencing the orphaned revision.
        Files.write(root.resolve("fixture.bin.clipfetch.part"),new byte[]{'X'},
                java.nio.file.StandardOpenOption.WRITE);
        try(var restarted=db.openSession(true)) {
            var service=new NativeTaskLifecycleService(restarted.getMapper(TorrentNativeTaskMapper.class));
            assertEquals("RUNNING",service.get(305,task).state());
            assertThrows(IllegalStateException.class,()->service.prepare(305,task,3));
            assertThrows(NoSuchElementException.class,()->service.fenceOrphanedWorker(306,task,3));
            assertEquals("PAUSED",service.fenceOrphanedWorker(305,task,3).state());
            assertThrows(IllegalStateException.class,()->service.fenceOrphanedWorker(305,task,3));
            var reopened=new VerifiedPieceCheckpoint(torrent(),Set.of(0),root,305,task);
            assertEquals(0,reopened.verifiedSelectedBytes());
            var repaired=service.reconcileStopped(305,task,4,reopened);
            assertEquals(0,repaired.verifiedBytes());
            assertEquals("PAUSED",repaired.state());
            assertEquals(5,repaired.revision());
            assertEquals("READY",service.prepare(305,task,5).state());
            assertEquals(0,service.get(305,task).verifiedBytes());
        }
    }

    @Test void runningTaskCannotBeDeletedAndCrashFenceAllowsPause() throws Exception {
        var factory=factory();UUID id=UUID.randomUUID();Path path=work.resolve(id.toString());
        insert(41,id,path);
        try(var session=factory.openSession(true)) {
            var service=new NativeTaskLifecycleService(session.getMapper(TorrentNativeTaskMapper.class));
            service.prepare(41,id,0);
            service.claimForWorker(41,id,1);
            assertThrows(IllegalStateException.class,()->service.logicalDelete(41,id,2));
            assertEquals("PAUSED",service.fenceOrphanedWorker(41,id,2).state());
            assertThrows(IllegalStateException.class,()->service.fenceOrphanedWorker(41,id,2));
            service.logicalDelete(41,id,3);
            assertThrows(NoSuchElementException.class,()->service.get(41,id));
        }
    }

    @Test void completedTaskRetainsFenceAndDoesNotThrowOnGuardOrPause() throws Exception {
        var db=factory();
        UUID task=UUID.randomUUID();
        long owner=779;
        Path root=work.resolve("completed-fence").resolve(task.toString());
        insert(owner,task,root);
        try(var session=db.openSession(true)) {
            var mapper=session.getMapper(TorrentNativeTaskMapper.class);
            var lifecycle=new NativeTaskLifecycleService(mapper);
            var checkpoint=new VerifiedPieceCheckpoint(torrent(),Set.of(0),root,owner,task);
            var prepared=lifecycle.prepare(owner,task,0);
            String instance=UUID.randomUUID().toString();
            var fence=lifecycle.claimFencedWithDiskLock(owner,task,prepared.revision(),instance);
            checkpoint.commit(0,Arrays.copyOfRange(PAYLOAD,0,4),()->{});
            var updated=lifecycle.syncFencedProgress(owner,task,fence.task().revision(),fence.epoch(),instance,checkpoint,4);
            checkpoint.commit(1,Arrays.copyOfRange(PAYLOAD,4,8),()->{});
            var completed=lifecycle.syncFencedProgress(owner,task,updated.revision(),fence.epoch(),instance,checkpoint,8);
            assertEquals("COMPLETED",completed.state());
            assertDoesNotThrow(()->lifecycle.requireFence(owner,task,completed.revision(),fence.epoch(),instance));
            assertEquals("COMPLETED",lifecycle.pauseFencedWithDiskLock(owner,task,completed.revision(),fence.epoch(),instance).state());
        }
    }
}
