package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.domain.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NativeRetryPlanSqlTest {
    @TempDir Path temp;
    @Test void ownerRevisionDueTimeAndSingleClaimAreFencedBySql() throws Exception {
        var jdbc="jdbc:sqlite:"+temp.resolve("retry.sqlite").toAbsolutePath();
        String ddl=Files.readString(Path.of("src/main/resources/db/migration/sqlite/V16__native_retry_plan.sql"))
                .lines().filter(s->!s.stripLeading().startsWith("--"))
                .collect(java.util.stream.Collectors.joining("\n"));
        try(var connection=DriverManager.getConnection(jdbc);
            var statement=connection.createStatement()) {
            for(String part:ddl.split(";"))if(!part.isBlank())statement.execute(part);
        }
        String v17=Files.readString(Path.of("src/main/resources/db/migration/sqlite/V17__native_retry_worker_binding.sql"))
                .lines().filter(line->!line.stripLeading().startsWith("--"))
                .collect(java.util.stream.Collectors.joining("\n"));
        try(var conn=DriverManager.getConnection(jdbc);var statement=conn.createStatement()) {
            for(String sql:v17.split(";"))if(!sql.isBlank())statement.execute(sql);
        }
        String v18=Files.readString(Path.of("src/main/resources/db/migration/sqlite/V18__native_retry_worker_outcome.sql"))
                .lines().filter(line->!line.stripLeading().startsWith("--"))
                .collect(java.util.stream.Collectors.joining("\n"));
        try(var conn=DriverManager.getConnection(jdbc);var statement=conn.createStatement()) {
            for(String sql:v18.split(";"))if(!sql.isBlank())statement.execute(sql);
        }
        var cfg=new org.apache.ibatis.session.Configuration();
        cfg.setEnvironment(new Environment("sqlite-retry",new JdbcTransactionFactory(),
                new UnpooledDataSource("org.sqlite.JDBC",jdbc,null,null)));
        try(var input=getClass().getClassLoader()
                .getResourceAsStream("mapper/TorrentNativeRetryPlanMapper.xml")) {
            assertNotNull(input);
            new XMLMapperBuilder(input,cfg,"mapper/TorrentNativeRetryPlanMapper.xml",
                    cfg.getSqlFragments()).parse();
        }
        var factory=new SqlSessionFactoryBuilder().build(cfg);
        var task=UUID.randomUUID().toString();
        var now=LocalDateTime.now(java.time.Clock.systemUTC());
        try(var s=factory.openSession(true)) {
            var m=s.getMapper(TorrentNativeRetryPlanMapper.class);
            var plan=TorrentNativeRetryPlan.builder().taskUuid(task).userId(81L)
                    .expectedRevision(4L).maxAttempts(3).nextAttemptAt(now.plusMinutes(1))
                    .updatedAt(now).build();
            assertEquals(1,m.insertPending(plan));
            assertTrue(m.listDue(now,5).isEmpty());
            assertEquals(1,m.listDue(now.plusMinutes(2),5).size());
            assertNull(m.findOwned(82L,task));
            assertEquals(0,m.claimDue(81L,task,4L,"other",now,now.plusMinutes(2)));
            assertEquals(0,m.claimDue(82L,task,4L,"other",now.plusMinutes(2),now.plusMinutes(3)));
            assertEquals(0,m.claimDue(81L,task,3L,"other",now.plusMinutes(2),now.plusMinutes(3)));
            assertEquals(1,m.claimDue(81L,task,4L,"owned",now.plusMinutes(2),now.plusMinutes(3)));
            assertEquals(0,m.claimDue(81L,task,4L,"duplicate",now.plusMinutes(2),now.plusMinutes(3)));
            assertEquals("CLAIMED",m.findOwned(81L,task).getStatus());
            String worker=UUID.randomUUID().toString();
            assertEquals(0,m.bindWorker(81L,task,4L,"bad-token",worker,now.plusMinutes(2)));
            assertEquals(0,m.bindWorker(82L,task,4L,"owned",worker,now.plusMinutes(2)));
            assertEquals(0,m.bindWorker(81L,task,3L,"owned",worker,now.plusMinutes(2)));
            assertEquals(1,m.bindWorker(81L,task,4L,"owned",worker,now.plusMinutes(2)));
            assertEquals(worker,m.findOwned(81L,task).getWorkerJobUuid());
            assertEquals(0,m.releaseClaim(81L,task,"owned",4L,now.plusMinutes(4),now.plusMinutes(3)));
            assertEquals(0,m.bindWorker(81L,task,4L,"owned",UUID.randomUUID().toString(),now.plusMinutes(2)));
            assertEquals(0,m.finishWorker(82L,task,4L,"owned",worker,"SUCCEEDED",now));
            assertEquals(0,m.finishWorker(81L,task,4L,"wrong",worker,"SUCCEEDED",now));
            assertEquals(0,m.finishWorker(81L,task,4L,"owned","other","SUCCEEDED",now));
            assertEquals(1,m.finishWorker(81L,task,4L,"owned",worker,"PARTIAL",now));
            assertEquals("PARTIAL",m.findOwned(81L,task).getWorkerOutcome());
            assertEquals(0,m.finishWorker(81L,task,4L,"owned",worker,"FAILED",now));
            assertEquals(1,m.findOwned(81L,task).getAttemptCount());
            assertEquals(0,m.stopOwned(82L,task,4L));
            assertEquals(0,m.stopOwned(81L,task,3L));
            assertEquals(0,m.stopOwned(81L,task,4L)); // already terminal
            assertEquals(0,m.claimDue(81L,task,4L,"late",now.plusMinutes(3),now.plusMinutes(4)));

            var retry=UUID.randomUUID().toString();
            plan.setTaskUuid(retry);
            plan.setNextAttemptAt(now);
            plan.setMaxAttempts(2);
            assertEquals(1,m.insertPending(plan));
            assertEquals(1,m.claimDue(81L,retry,4L,"lease-a",now,now.plusSeconds(30)));
            assertEquals(0,m.bindWorker(81L,retry,4L,"lease-a",UUID.randomUUID().toString(),now.plusSeconds(31)));
            assertNull(m.findOwned(81L,retry).getWorkerJobUuid());
            assertEquals(0,m.releaseClaim(81L,retry,"wrong",4L,now.plusSeconds(2),now));
            assertEquals(1,m.releaseClaim(81L,retry,"lease-a",4L,now.plusSeconds(2),now));
            assertEquals("PENDING",m.findOwned(81L,retry).getStatus());
            assertEquals(0,m.claimDue(81L,retry,4L,"too-soon",now.plusSeconds(1),now.plusMinutes(1)));
            assertEquals(1,m.claimDue(81L,retry,4L,"lease-b",now.plusSeconds(3),now.plusMinutes(1)));
            assertEquals(1,m.releaseClaim(81L,retry,"lease-b",4L,now.plusMinutes(2),now.plusSeconds(4)));
            assertEquals("STOPPED",m.findOwned(81L,retry).getStatus());

            var expired=UUID.randomUUID().toString();
            plan.setTaskUuid(expired);
            plan.setMaxAttempts(3);
            assertEquals(1,m.insertPending(plan));
            assertEquals(1,m.claimDue(81L,expired,4L,"crashed",now,now.plusSeconds(5)));
            assertTrue(m.listExpiredClaims(now.plusSeconds(4),5).isEmpty());
            assertEquals(1,m.listExpiredClaims(now.plusSeconds(6),5).size());
            assertEquals(expired,m.listExpiredClaims(now.plusSeconds(6),5).get(0).getTaskUuid());
            assertEquals(0,m.expireClaim(81L,expired,4L,now.plusSeconds(4)));
            assertEquals(1,m.expireClaim(81L,expired,4L,now.plusSeconds(6)));
            assertEquals("STOPPED",m.findOwned(81L,expired).getStatus());
            assertEquals(0,m.releaseClaim(81L,expired,"crashed",4L,now.plusMinutes(1),now.plusSeconds(7)));
        }
    }
}
