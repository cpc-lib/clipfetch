package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.domain.TorrentNativeTaskMapper;
import cc.ivera.auth.application.JwtService;
import cc.ivera.auth.domain.User;
import cc.ivera.auth.domain.UserMapper;
import cc.ivera.auth.interfaces.AuthInterceptor;
import cc.ivera.magnet.engine.discovery.MetadataResolver;
import cc.ivera.magnet.engine.discovery.NativeMetadataJobManager;
import cc.ivera.magnet.interfaces.*;
import cc.ivera.shared.web.GlobalExceptionHandler;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import cc.ivera.magnet.engine.codec.*;
import cc.ivera.magnet.engine.codec.BencodeDecoder.*;
import cc.ivera.magnet.engine.config.NativeTorrentProperties;
import cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeTaskCreationServiceTest {
    @TempDir Path work;

    private record TestDb(NativeTaskCreationService service,TorrentNativeTaskMapper mapper,
                          TransactionTemplate tx,String jdbc,Path storage,
                          NativeTorrentProperties settings) {
        NativeTaskCreationService.Created create(long user,byte[] torrent,List<Integer> selected) {
            return tx.execute(status -> service.createFromTorrent(user,torrent,selected));
        }
    }

    private TestDb database() throws Exception {
        String jdbc="jdbc:sqlite:"+work.resolve("tasks.sqlite").toAbsolutePath();
        String ddl=Files.readString(Path.of("src/main/resources/db/migration/sqlite/V13__torrent_native_engine.sql"))
                .lines().filter(line -> !line.stripLeading().startsWith("--"))
                .collect(java.util.stream.Collectors.joining("\n"));
        try(var c=DriverManager.getConnection(jdbc)) {
            for(String statement:ddl.split(";"))if(!statement.isBlank())
                try(var cmd=c.createStatement()) {cmd.execute(statement);}
        }
        String fenceDdl=Files.readString(Path.of("src/main/resources/db/migration/sqlite/V20__native_task_execution_fence.sql"))
                .lines().filter(line -> !line.stripLeading().startsWith("--"))
                .collect(java.util.stream.Collectors.joining("\n"));
        try(var c=DriverManager.getConnection(jdbc);var cmd=c.createStatement()) {
            for(String sql:fenceDdl.split(";"))if(!sql.isBlank())cmd.execute(sql);
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
        var ds=new UnpooledDataSource("org.sqlite.JDBC",jdbc,null,null);
        var cfg=new org.apache.ibatis.session.Configuration();
        cfg.setEnvironment(new Environment("task-creation",new SpringManagedTransactionFactory(),ds));
        try(var stream=getClass().getClassLoader()
                .getResourceAsStream("mapper/TorrentNativeTaskMapper.xml")) {
            assertNotNull(stream);
            new XMLMapperBuilder(stream,cfg,"mapper/TorrentNativeTaskMapper.xml",
                    cfg.getSqlFragments()).parse();
        }
        var factory=new SqlSessionFactoryBuilder().build(cfg);
        var mapper=new SqlSessionTemplate(factory).getMapper(TorrentNativeTaskMapper.class);
        var settings=new NativeTorrentProperties();
        Path storage=work.resolve("storage");
        settings.setStorageRoot(storage.toString());
        settings.setMaxActiveTasksPerUser(2);
        var service=new NativeTaskCreationService(mapper,settings,new ObjectMapper());
        return new TestDb(service,mapper,new TransactionTemplate(
                new DataSourceTransactionManager(ds)),jdbc,storage,settings);
    }

    private static Value file(String path,long bytes) {
        return new DictionaryValue(Map.of(
                ByteString.ascii("length"),new IntegerValue(bytes),
                ByteString.ascii("path"),new ListValue(List.of(
                        new BytesValue(path.getBytes(StandardCharsets.UTF_8))))));
    }
    private static byte[] torrent(boolean isPrivate,String tracker) throws Exception {
        byte[] payload="abcdefghi".getBytes(StandardCharsets.UTF_8);
        byte[] pieces=new byte[60];
        for(int i=0;i<3;i++) {
            var sha=MessageDigest.getInstance("SHA-1").digest(
                    Arrays.copyOfRange(payload,i*4,Math.min(payload.length,i*4+4)));
            System.arraycopy(sha,0,pieces,i*20,20);
        }
        Map<ByteString,Value> info=new HashMap<>();
        info.put(ByteString.ascii("files"),new ListValue(List.of(file("first.txt",4),file("second.txt",5))));
        info.put(ByteString.ascii("name"),new BytesValue("fixture".getBytes(StandardCharsets.UTF_8)));
        info.put(ByteString.ascii("piece length"),new IntegerValue(4));
        info.put(ByteString.ascii("pieces"),new BytesValue(pieces));
        if(isPrivate)info.put(ByteString.ascii("private"),new IntegerValue(1));
        return new BencodeEncoder().encode(new DictionaryValue(Map.of(
                ByteString.ascii("announce"),new BytesValue(tracker.getBytes(StandardCharsets.UTF_8)),
                ByteString.ascii("info"),new DictionaryValue(info))));
    }
    private static int count(String url,String table) throws Exception {
        try(var conn=DriverManager.getConnection(url);
            var statement=conn.createStatement();
            var result=statement.executeQuery("SELECT count(*) FROM "+table)) {
            return result.next()?result.getInt(1):0;
        }
    }

    @Test void differentUsersAndSameInfoHashCreateIndependentTasksAndSharedSanitizedMetadata()
            throws Exception {
        var db=database();
        byte[] bytes=torrent(false,"udp://tracker.first.example:6969");
        byte[] alternate=torrent(false,"udp://tracker.other.example:1234");
        var a=db.create(11,bytes,List.of(1));
        var b=db.create(12,alternate,List.of(0,1));
        assertNotEquals(a.taskUuid(),b.taskUuid());
        assertEquals(a.infoHash(),b.infoHash());
        assertEquals(5,a.selectedBytes());
        assertEquals(9,b.selectedBytes());
        assertEquals("CREATED",a.state());
        assertEquals(0,a.revision());
        assertEquals(1,count(db.jdbc(),"torrent_native_metadata"));
        assertEquals(2,count(db.jdbc(),"torrent_native_task"));
        assertEquals(4,count(db.jdbc(),"torrent_native_file"));
        var rowA=db.mapper().findOwned(a.taskUuid().toString(),11L);
        var rowB=db.mapper().findOwned(b.taskUuid().toString(),12L);
        assertNotNull(rowA);
        assertNotNull(rowB);
        assertNull(db.mapper().findOwned(a.taskUuid().toString(),12L));
        assertEquals(rowA.getMetadataId(),rowB.getMetadataId());
        assertTrue(rowA.getTrackerUrlsJson().contains("tracker.first.example"));
        assertTrue(rowB.getTrackerUrlsJson().contains("tracker.other.example"));
        assertFalse(rowA.getTrackerUrlsJson().contains("tracker.other.example"));
        assertFalse(Files.exists(db.storage())); // Task creation itself does not touch disk.
        assertEquals(java.net.URI.create("udp://tracker.first.example:6969"),
                new NativeTaskCheckpointFactory(db.mapper(),db.settings(),new ObjectMapper())
                        .openPublicOwned(11,a.taskUuid()).permit().trackers().getFirst());
        assertEquals(0L,rowA.getDownloadedWireBytes());
        assertEquals(0L,rowA.getVerifiedSelectedBytes());
        assertEquals(0L,rowA.getParseId());
        assertTrue(rowA.getStatePath().contains(a.taskUuid().toString()));
        assertTrue(rowA.getStatePath().contains("11"));
        assertTrue(Files.isDirectory(db.storage())); // Checkpoint explicitly reopened above.
        var meta=db.mapper().findMetadataByHash(a.infoHash());
        assertNotNull(meta);
        var parsed=new TorrentV1MetainfoParser().parse(meta.getTorrentBencode());
        assertEquals(a.infoHash(),parsed.infoHash());
        assertFalse(new String(meta.getTorrentBencode(),StandardCharsets.ISO_8859_1)
                .contains("tracker.first.example"));
        assertFalse(new String(meta.getTorrentBencode(),StandardCharsets.ISO_8859_1)
                .contains("tracker.other.example"));
    }

    @Test void invalidSelectionPrivateTorrentAndQuotaNeverInsertUnsafeTasks() throws Exception {
        var db=database();
        byte[] publicTorrent=torrent(false,"udp://public.example:80");
        assertThrows(SecurityException.class,
                ()->db.create(5,torrent(true,"http://private.example/passkey"),List.of(0)));
        assertThrows(IllegalArgumentException.class,()->db.create(5,publicTorrent,List.of(0,0)));
        assertThrows(IllegalArgumentException.class,()->db.create(5,publicTorrent,List.of(9)));
        assertThrows(IllegalArgumentException.class,()->db.create(5,publicTorrent,List.of()));
        assertEquals(0,count(db.jdbc(),"torrent_native_task"));
        assertEquals(0,count(db.jdbc(),"torrent_native_metadata"));
        db.create(5,publicTorrent,List.of(0));
        db.create(5,publicTorrent,List.of(1));
        assertThrows(IllegalStateException.class,()->db.create(5,publicTorrent,List.of(0)));
        assertEquals(2,count(db.jdbc(),"torrent_native_task"));
    }

    @Test void restoresOwnerCheckpointOnlyFromVerifiedPersistedPieceMetadata() throws Exception {
        var db=database();
        var created=db.create(88,torrent(false,"udp://tracker.public:6969"),List.of(1));
        var cfg=new NativeTorrentProperties();
        cfg.setStorageRoot(db.storage().toString());
        var factory=new NativeTaskCheckpointFactory(db.mapper(),cfg,new ObjectMapper());
        assertThrows(NoSuchElementException.class,()->factory.openOwned(89,created.taskUuid()));
        var checkpoint=factory.openOwned(88,created.taskUuid());
        assertEquals(5,checkpoint.selectedTotalBytes());
        assertEquals(2,checkpoint.wantedPieces().cardinality());
        assertEquals(0,checkpoint.verifiedSelectedBytes());
        assertTrue(Files.isDirectory(db.storage().resolve("88")
                .resolve(created.taskUuid().toString())));
        try(var conn=DriverManager.getConnection(db.jdbc());
            var statement=conn.prepareStatement(
                    "UPDATE torrent_native_task SET state_path = ? WHERE task_uuid = ?")) {
            statement.setString(1,work.resolve("outside-folder").toString());
            statement.setString(2,created.taskUuid().toString());
            statement.executeUpdate();
        }
        assertThrows(SecurityException.class,()->factory.openOwned(88,created.taskUuid()));
        assertFalse(Files.exists(work.resolve("outside-folder")));
    }

    @Test void refusesTamperedSharedPieceMetadata() throws Exception {
        var db=database();
        var created=db.create(7,torrent(false,"udp://public.example:80"),List.of(0));
        var cfg=new NativeTorrentProperties();
        cfg.setStorageRoot(db.storage().toString());
        var factory=new NativeTaskCheckpointFactory(db.mapper(),cfg,new ObjectMapper());
        try(var conn=DriverManager.getConnection(db.jdbc());
            var statement=conn.prepareStatement(
                    "UPDATE torrent_native_metadata SET torrent_bencode = ? WHERE info_hash = ?")) {
            statement.setBytes(1,new byte[]{'x','y','z'});
            statement.setString(2,created.infoHash());
            statement.executeUpdate();
        }
        assertThrows(SecurityException.class,()->factory.openOwned(7,created.taskUuid()));
    }

    @Test void trustedWorkerRunsFiniteRoundsWithDurableProgressAndRestarts() throws Exception {
        var db=database();
        var created=db.create(300,torrent(false,"udp://tracker.public:80"),List.of(0,1));
        var config=new NativeTorrentProperties();
        config.setStorageRoot(db.storage().toString());
        config.setWorkerRoundEnabled(true);
        config.setLegacyWorkerCompatibilityEnabled(true);
        config.setEnabled(true);
        var lifecycle=new NativeTaskLifecycleService(db.mapper());
        var checkpoints=new NativeTaskCheckpointFactory(db.mapper(),config,new ObjectMapper());
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var discoveries=new java.util.concurrent.atomic.AtomicInteger();
        var worker=new NativeTaskWorkerRoundService(lifecycle,checkpoints,config,
                (hash,peerId,port,permit)->{
                    discoveries.incrementAndGet();
                    assertEquals(created.infoHash(),permit.infoHash());
                    permit.requireMatch(java.util.HexFormat.of().formatHex(hash));
                    return List.of(java.net.InetSocketAddress.createUnresolved("8.8.8.8",6881));
                },
                (peer,info,peerId,planner,piece,limit)->{
                    calls.incrementAndGet();
                    byte[] bytes="abcdefghi".getBytes(StandardCharsets.UTF_8);
                    return Arrays.copyOfRange(bytes,piece*4,Math.min((piece+1)*4,bytes.length));
                },new cc.ivera.magnet.engine.tracker.TrackerAddressPolicy(false));

        lifecycle.prepare(300,created.taskUuid(),0);
        var partial=worker.runOnce(300,created.taskUuid(),1,1);
        assertEquals(1,partial.candidatePeers());
        assertEquals(1,partial.download().committedPieces());
        assertEquals("PAUSED",partial.task().state());
        assertEquals(4,partial.task().verifiedBytes());
        assertEquals(4,partial.task().wireBytes());
        long oldVersion=partial.task().revision();
        assertThrows(IllegalStateException.class,
                ()->worker.runOnce(300,created.taskUuid(),1,1));
        var next=lifecycle.prepare(300,created.taskUuid(),oldVersion);
        // The worker is recreated conceptually by reopening from database.
        var second=worker.runOnce(300,created.taskUuid(),next.revision(),4);
        assertEquals(2,second.download().committedPieces());
        assertEquals("COMPLETED",second.task().state());
        assertEquals(9,second.task().verifiedBytes());
        assertEquals(9,second.task().wireBytes());
        assertEquals(3,calls.get());
        assertEquals(1,discoveries.get(),"Progressing rounds should reuse recently discovered Peers");
        assertEquals(9,checkpoints.openOwned(300,created.taskUuid()).verifiedSelectedBytes());
        assertThrows(NoSuchElementException.class,
                ()->worker.runOnce(301,created.taskUuid(),2,1));
    }

    @Test void cachedPeersAreOwnerTaskScopedAndRefreshAfterAStalledRound() throws Exception {
        var db=database();
        var first=db.create(301,torrent(false,"udp://public.example:80"),List.of(0,1));
        var sameOwner=db.create(301,torrent(false,"udp://public.example:80"),List.of(0,1));
        var otherOwner=db.create(302,torrent(false,"udp://public.example:80"),List.of(0,1));
        var config=db.settings();
        config.setEnabled(true);
        config.setWorkerRoundEnabled(true);
        config.setLegacyWorkerCompatibilityEnabled(true);
        var lifecycle=new NativeTaskLifecycleService(db.mapper());
        var checkpoints=new NativeTaskCheckpointFactory(db.mapper(),config,new ObjectMapper());
        var discoveries=new java.util.concurrent.atomic.AtomicInteger();
        var fail=new java.util.concurrent.atomic.AtomicBoolean();
        var stale=java.net.InetSocketAddress.createUnresolved("8.8.8.8",6881);
        var fresh=java.net.InetSocketAddress.createUnresolved("9.9.9.9",6881);
        var worker=new NativeTaskWorkerRoundService(lifecycle,checkpoints,config,
                (hash,id,port,permit)->List.of(discoveries.incrementAndGet()<=3?stale:fresh),
                (peer,info,id,planner,piece,deadline)->{
                    if(fail.get())throw new java.io.IOException("Peer disconnected");
                    byte[] data="abcdefghi".getBytes(StandardCharsets.US_ASCII);
                    return Arrays.copyOfRange(data,piece*4,Math.min(data.length,(piece+1)*4));
                },new cc.ivera.magnet.engine.tracker.TrackerAddressPolicy(false));
        for(var entry:Map.of(first,301L,sameOwner,301L,otherOwner,302L).entrySet()) {
            var task=entry.getKey();long owner=entry.getValue();
            var ready=lifecycle.prepare(owner,task.taskUuid(),0);
            assertEquals(1,worker.runOnce(owner,task.taskUuid(),ready.revision(),1)
                    .download().committedPieces());
        }
        assertEquals(3,discoveries.get(),"Peer candidates must not cross owners or tasks");
        fail.set(true);
        var ready=lifecycle.prepare(301,first.taskUuid(),lifecycle.get(301,first.taskUuid()).revision());
        var stalled=worker.runOnce(301,first.taskUuid(),ready.revision(),1);
        assertEquals(0,stalled.download().committedPieces());
        assertEquals(3,discoveries.get(),"The existing Peer list is tried before refreshing discovery");
        fail.set(false);
        ready=lifecycle.prepare(301,first.taskUuid(),stalled.task().revision());
        assertEquals(1,worker.runOnce(301,first.taskUuid(),ready.revision(),1)
                .download().committedPieces());
        assertEquals(4,discoveries.get(),"Zero progress must discard the stale Peer list");
    }

    @Test void cachedPeersDoNotBypassVerifiedMetadataOrSurviveAnAbortedRound() throws Exception {
        var db=database();
        var created=db.create(303,torrent(false,"udp://public.example:80"),List.of(0,1));
        var config=db.settings();
        config.setEnabled(true);
        config.setWorkerRoundEnabled(true);
        config.setLegacyWorkerCompatibilityEnabled(true);
        config.setRarestFirstEnabled(true);
        var lifecycle=new NativeTaskLifecycleService(db.mapper());
        var checkpoints=spy(new NativeTaskCheckpointFactory(db.mapper(),config,new ObjectMapper()));
        var discoveries=new java.util.concurrent.atomic.AtomicInteger();
        var abort=new java.util.concurrent.atomic.AtomicBoolean();
        var worker=new NativeTaskWorkerRoundService(lifecycle,checkpoints,config,
                (hash,id,port,permit)->{
                    discoveries.incrementAndGet();
                    return List.of(java.net.InetSocketAddress.createUnresolved("8.8.8.8",6881));
                },
                (peer,info,id,planner,piece,deadline)->{
                    byte[] data="abcdefghi".getBytes(StandardCharsets.US_ASCII);
                    return Arrays.copyOfRange(data,piece*4,Math.min(data.length,(piece+1)*4));
                },new cc.ivera.magnet.engine.tracker.TrackerAddressPolicy(false),
                PieceDownloadCoordinator.ChildThreads.none(),(peer,info,id,deadline)->{
                    if(abort.get())throw new IllegalStateException("Task revision changed");
                    return Optional.empty();
                });
        var ready=lifecycle.prepare(303,created.taskUuid(),0);
        var partial=worker.runOnce(303,created.taskUuid(),ready.revision(),1);
        ready=lifecycle.prepare(303,created.taskUuid(),partial.task().revision());
        abort.set(true);
        long revision=ready.revision();
        assertThrows(IllegalStateException.class,
                ()->worker.runOnce(303,created.taskUuid(),revision,1));
        assertEquals(1,discoveries.get());
        abort.set(false);
        assertEquals(1,worker.runOnce(303,created.taskUuid(),revision,1).download().committedPieces());
        assertEquals(2,discoveries.get(),"An aborted round must invalidate its cached Peers");
        verify(checkpoints,times(3)).openPublicOwned(303,created.taskUuid());
    }

    @Test void noPeersRemainReadyAndPauseDuringDiscoveryDoesNotLaunchPiece() throws Exception {
        var db=database();
        var created=db.create(80,torrent(false,"udp://public.example:80"),List.of(0));
        var config=new NativeTorrentProperties();
        config.setStorageRoot(db.storage().toString());
        config.setWorkerRoundEnabled(true);
        config.setLegacyWorkerCompatibilityEnabled(true);
        config.setEnabled(true);
        var lifecycle=new NativeTaskLifecycleService(db.mapper());
        var checkpointFactory=new NativeTaskCheckpointFactory(db.mapper(),config,new ObjectMapper());
        var finderCalls=new java.util.concurrent.atomic.AtomicInteger();
        var withoutPeers=new NativeTaskWorkerRoundService(lifecycle,checkpointFactory,config,
                (hash,id,port,permit)->{finderCalls.incrementAndGet();return List.of();},
                (peer,info,id,plan,piece,timeout)->{fail("No network Piece requests");return null;},
                new cc.ivera.magnet.engine.tracker.TrackerAddressPolicy(false));
        lifecycle.prepare(80,created.taskUuid(),0);
        var unchanged=withoutPeers.runOnce(80,created.taskUuid(),1,1);
        assertFalse(unchanged.attempted());
        assertEquals("READY",unchanged.task().state());
        assertEquals(0,unchanged.task().verifiedBytes());
        assertEquals(1,finderCalls.get());

        var pausing=new NativeTaskWorkerRoundService(lifecycle,checkpointFactory,config,
                (hash,id,port,permit)->{
                    lifecycle.pause(80,created.taskUuid(),1);
                    return List.of(java.net.InetSocketAddress.createUnresolved("8.8.8.8",6881));
                },
                (peer,info,id,plan,piece,timeout)->{
                    fail("Paused task must never request a Piece");
                    return null;
                },new cc.ivera.magnet.engine.tracker.TrackerAddressPolicy(false));
        assertThrows(IllegalStateException.class,
                ()->pausing.runOnce(80,created.taskUuid(),1,1));
        assertEquals("PAUSED",lifecycle.get(80,created.taskUuid()).state());
        assertEquals(0,lifecycle.get(80,created.taskUuid()).verifiedBytes());
    }

    @Test void nativeWorkerRequiresBothFeatureFlagsAndNeverContactsDiscoveryIfDisabled()
            throws Exception {
        var db=database();
        var created=db.create(33,torrent(false,"udp://tracker.public:80"),List.of(0));
        var config=new NativeTorrentProperties();
        config.setStorageRoot(db.storage().toString());
        var lifecycle=new NativeTaskLifecycleService(db.mapper());
        var check=new NativeTaskCheckpointFactory(db.mapper(),config,new ObjectMapper());
        var guarded=new NativeTaskWorkerRoundService(lifecycle,check,config,
                (hash,id,port,permit)->{fail("No discovery before opt-in");return List.of();},
                (peer,info,id,plan,piece,timeout)->{fail("No piece fetch");return null;},
                new cc.ivera.magnet.engine.tracker.TrackerAddressPolicy(false));
        lifecycle.prepare(33,created.taskUuid(),0);
        assertThrows(IllegalStateException.class,()->guarded.runOnce(33,created.taskUuid(),1,1));
        config.setWorkerRoundEnabled(true);
        config.setLegacyWorkerCompatibilityEnabled(true);
        assertThrows(IllegalStateException.class,()->guarded.runOnce(33,created.taskUuid(),1,1));
        assertEquals("READY",lifecycle.get(33,created.taskUuid()).state());
    }

    @Test void crashAfterFinalCheckpointRecoversCompletedWithoutAnyPeerTraffic() throws Exception {
        var db=database();
        var created=db.create(200,torrent(false,"udp://public.example:6969"),List.of(0,1));
        var config=new NativeTorrentProperties();
        config.setStorageRoot(db.storage().toString());
        config.setEnabled(true);
        config.setWorkerRoundEnabled(true);
        config.setLegacyWorkerCompatibilityEnabled(true);
        var lifecycle=new NativeTaskLifecycleService(db.mapper());
        var checkpointFactory=new NativeTaskCheckpointFactory(db.mapper(),config,new ObjectMapper());
        var disk=checkpointFactory.openOwned(200,created.taskUuid());
        byte[] bytes="abcdefghi".getBytes(StandardCharsets.UTF_8);
        for(int i=0;i<3;i++)
            disk.commit(i,Arrays.copyOfRange(bytes,i*4,Math.min(bytes.length,(i+1)*4)),()->{});
        assertEquals(0,lifecycle.get(200,created.taskUuid()).verifiedBytes());
        lifecycle.prepare(200,created.taskUuid(),0);
        var worker=new NativeTaskWorkerRoundService(lifecycle,checkpointFactory,config,
                (hash,id,port,permit)->{fail("Recovered task is complete; never contact Trackers");return List.of();},
                (peer,info,id,layout,piece,deadline)->{fail("Never download completed pieces");return null;},
                new cc.ivera.magnet.engine.tracker.TrackerAddressPolicy(false));
        var result=worker.runOnce(200,created.taskUuid(),1,4);
        assertFalse(result.attempted());
        assertEquals("COMPLETED",result.task().state());
        assertEquals(9,result.task().verifiedBytes());
        assertEquals(100.0,result.task().progressPercent());
        assertEquals(2,result.task().revision());
    }

    @Test void badPeerDataCannotIncreaseVerifiedBytesOrClaimCompleted() throws Exception {
        var db=database();
        var created=db.create(70,torrent(false,"udp://public.example:6969"),List.of(0));
        var config=new NativeTorrentProperties();
        config.setStorageRoot(db.storage().toString());
        config.setEnabled(true);
        config.setWorkerRoundEnabled(true);
        config.setLegacyWorkerCompatibilityEnabled(true);
        var lifecycle=new NativeTaskLifecycleService(db.mapper());
        var checkpointFactory=new NativeTaskCheckpointFactory(db.mapper(),config,new ObjectMapper());
        var service=new NativeTaskWorkerRoundService(lifecycle,checkpointFactory,config,
                (hash,id,port,permit)->List.of(
                        java.net.InetSocketAddress.createUnresolved("8.8.8.8",6881),
                        java.net.InetSocketAddress.createUnresolved("9.9.9.9",6881)),
                (peer,info,id,plan,piece,deadline)->new byte[]{9,9,9,9},
                new cc.ivera.magnet.engine.tracker.TrackerAddressPolicy(false));
        lifecycle.prepare(70,created.taskUuid(),0);
        var result=service.runOnce(70,created.taskUuid(),1,1);
        assertTrue(result.attempted());
        assertEquals(0,result.download().committedPieces());
        assertEquals(List.of(0),result.download().failedPieces());
        assertEquals("PAUSED",result.task().state());
        assertEquals(0,result.task().verifiedBytes());
        assertEquals(0,checkpointFactory.openOwned(70,created.taskUuid()).verifiedSelectedBytes());
    }

    @Test void verifiedPublicTaskUsesDhtFallbackWhenTrackersFindNoPeer() throws Exception {
        var db=database();
        var created=db.create(501,torrent(false,"udp://tracker.example:6969"),List.of(0,1));
        var options=new NativeTorrentProperties();
        options.setStorageRoot(db.storage().toString());
        options.setEnabled(true);
        options.setWorkerRoundEnabled(true);
        options.setLegacyWorkerCompatibilityEnabled(true);
        options.setDhtFallbackEnabled(true);
        var lifecycle=new NativeTaskLifecycleService(db.mapper());
        var checkpointFactory=new NativeTaskCheckpointFactory(db.mapper(),options,new ObjectMapper());
        var trackerAttempts=new java.util.concurrent.atomic.AtomicInteger();
        var dhtAttempts=new java.util.concurrent.atomic.AtomicInteger();
        var policy=new cc.ivera.magnet.engine.tracker.TrackerAddressPolicy(false);
        var accelerator=new cc.ivera.magnet.engine.discovery.PeerDiscoveryAccelerator(policy,
                (endpoint,hash,id)->List.of(),
                (hash,port,nic)->{fail("LSD must remain off");return List.of();},
                (hash,id,port)->{fail("Unexpected catalog repeat");return List.of();});
        var finder=new cc.ivera.magnet.engine.discovery.NativePublicPeerFinder(
                (hash,id,port)->{trackerAttempts.incrementAndGet();return List.of();},
                accelerator,policy,
                (nodeId,hash)->{
                    dhtAttempts.incrementAndGet();
                    return List.of(java.net.InetSocketAddress.createUnresolved("192.168.1.20",6881),
                            java.net.InetSocketAddress.createUnresolved("8.8.8.8",6881));
                },options::isDhtFallbackEnabled);
        var worker=new NativeTaskWorkerRoundService(lifecycle,checkpointFactory,options,
                finder,(peer,info,id,planner,piece,deadline)->{
                    assertEquals("8.8.8.8",peer.getHostString());
                    return Arrays.copyOfRange("abcdefghi".getBytes(StandardCharsets.UTF_8),
                            piece*4,Math.min(9,(piece+1)*4));
                },policy);
        lifecycle.prepare(501,created.taskUuid(),0);
        var result=worker.runOnce(501,created.taskUuid(),1,1);
        assertTrue(result.attempted());
        assertEquals("PAUSED",result.task().state());
        assertEquals(4,result.task().verifiedBytes());
        assertEquals(4,checkpointFactory.openOwned(501,created.taskUuid())
                .verifiedSelectedBytes());
        assertEquals(1,trackerAttempts.get());
        assertEquals(1,dhtAttempts.get());
    }

    @Test void continuousAsyncJobCompletesActualSqliteTaskAcrossCheckpointedPieceRounds()
            throws Exception {
        var db=database();
        var task=db.create(799,torrent(false,"udp://public.example:6969"),List.of(0,1));
        var config=new NativeTorrentProperties();
        config.setStorageRoot(db.storage().toString());
        config.setEnabled(true);
        config.setWorkerRoundEnabled(true);
        config.setLegacyWorkerCompatibilityEnabled(true);
        config.setAsyncWorkerEnabled(true);
        config.setContinuousWorkerEnabled(true);
        var lifecycle=new NativeTaskLifecycleService(db.mapper());
        var checkpoints=new NativeTaskCheckpointFactory(db.mapper(),config,new ObjectMapper());
        var policy=new cc.ivera.magnet.engine.tracker.TrackerAddressPolicy(false);
        var worker=new NativeTaskWorkerRoundService(lifecycle,checkpoints,config,
                (hash,id,port,permit)->List.of(
                        java.net.InetSocketAddress.createUnresolved("8.8.8.8",6881)),
                (peer,info,peerId,planner,piece,deadline)->{
                    byte[] all="abcdefghi".getBytes(StandardCharsets.US_ASCII);
                    return Arrays.copyOfRange(all,piece*4,Math.min(all.length,(piece+1)*4));
                },policy);
        var taskAccess=new NativeWorkerJobManager.TaskAccess() {
            @Override public NativeTaskLifecycleService.TaskState get(long owner,UUID id) {
                return lifecycle.get(owner,id);
            }
            @Override public NativeTaskLifecycleService.TaskState pause(long owner,UUID id,long rev) {
                return lifecycle.pause(owner,id,rev);
            }
            @Override public NativeTaskLifecycleService.TaskState prepare(long owner,UUID id,long rev) {
                return lifecycle.prepare(owner,id,rev);
            }
            @Override public NativeTaskLifecycleService.TaskState fenceOrphan(long owner,UUID id,long rev) {
                return lifecycle.fenceOrphanedWorker(owner,id,rev);
            }
        };
        var ready=lifecycle.prepare(799,task.taskUuid(),0);
        try(var manager=new NativeWorkerJobManager(worker::runOnce,taskAccess,config,1,2,1)) {
            UUID job=manager.submitContinuous(799,task.taskUuid(),ready.revision(),1,6,30);
            var finished=manager.await(799,job,java.time.Duration.ofSeconds(4));
            assertEquals(NativeWorkerJobManager.State.SUCCEEDED,finished.state());
            assertEquals(3,manager.metrics(799,job).rounds());
            assertEquals(3,manager.metrics(799,job).committedPieces());
            assertEquals(9,manager.metrics(799,job).verifiedBytes());
            assertEquals(9,lifecycle.get(799,task.taskUuid()).verifiedBytes());
            assertEquals("COMPLETED",lifecycle.get(799,task.taskUuid()).state());
            assertEquals(9,checkpoints.openOwned(799,task.taskUuid()).verifiedSelectedBytes());
            assertThrows(NoSuchElementException.class,()->manager.metrics(800,job));
        }
    }

    @Test void parallelNativeRoundCommitsToSqliteAndCheckpointOnlyFromOwnerThread()
            throws Exception {
        var db=database();
        var created=db.create(710,torrent(false,"udp://public.example:6969"),List.of(0,1));
        var config=new NativeTorrentProperties();
        config.setStorageRoot(db.storage().toString());
        config.setEnabled(true);
        config.setWorkerRoundEnabled(true);
        config.setLegacyWorkerCompatibilityEnabled(true);
        config.setParallelPieceEnabled(true);
        config.setParallelPieceWorkers(2);
        var lifecycle=new NativeTaskLifecycleService(db.mapper());
        var checkpoints=new NativeTaskCheckpointFactory(db.mapper(),config,new ObjectMapper());
        var started=new java.util.concurrent.CountDownLatch(2);
        var release=new java.util.concurrent.CountDownLatch(1);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var policy=new cc.ivera.magnet.engine.tracker.TrackerAddressPolicy(false);
        var worker=new NativeTaskWorkerRoundService(lifecycle,checkpoints,config,
                (hash,id,port,permit)->List.of(
                        java.net.InetSocketAddress.createUnresolved("8.8.8.8",6881),
                        java.net.InetSocketAddress.createUnresolved("9.9.9.9",6881)),
                (peer,info,peerId,planner,piece,deadline)->{
                    calls.incrementAndGet();
                    started.countDown();
                    try {
                        if(!release.await(3,java.util.concurrent.TimeUnit.SECONDS))
                            throw new java.io.IOException("Concurrent Piece requests did not overlap");
                    }catch(InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new java.io.IOException("Piece request cancelled",e);
                    }
                    byte[] all="abcdefghi".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    return Arrays.copyOfRange(all,piece*4,Math.min(all.length,(piece+1)*4));
                },policy);
        var initial=lifecycle.prepare(710,created.taskUuid(),0);
        var done=new java.util.concurrent.atomic.AtomicReference<NativeTaskWorkerRoundService.RoundReport>();
        var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var owner=new Thread(()->{
            try {done.set(worker.runOnce(710,created.taskUuid(),initial.revision(),3));}
            catch(Throwable e) {failure.set(e);}
        },"sqlite-parallel-owner");
        owner.start();
        try {
            assertTrue(started.await(2,java.util.concurrent.TimeUnit.SECONDS),
                    "Two Piece connections should overlap");
        }finally {release.countDown();}
        owner.join(7000);
        assertFalse(owner.isAlive(),"A bounded parallel round must terminate");
        assertNull(failure.get(),String.valueOf(failure.get()));
        assertEquals(3,done.get().download().committedPieces());
        assertEquals("COMPLETED",done.get().task().state());
        assertEquals(9,lifecycle.get(710,created.taskUuid()).verifiedBytes());
        assertEquals(9,checkpoints.openOwned(710,created.taskUuid()).verifiedSelectedBytes());
        assertTrue(calls.get()>=3);
    }

    @Test void rarestFirstWorkerUsesReportedPieceAvailabilityAndPersistsCheckpoint()
            throws Exception {
        var db=database();
        var created=db.create(411,torrent(false,"udp://public.example:6969"),List.of(0,1));
        var options=new NativeTorrentProperties();
        options.setStorageRoot(db.storage().toString());
        options.setEnabled(true);
        options.setWorkerRoundEnabled(true);
        options.setLegacyWorkerCompatibilityEnabled(true);
        options.setRarestFirstEnabled(true);
        options.setBitfieldProbePeers(3);
        options.setBitfieldProbeTimeoutMillis(200);
        var lifecycle=new NativeTaskLifecycleService(db.mapper());
        var checkpoints=new NativeTaskCheckpointFactory(db.mapper(),options,new ObjectMapper());
        var policy=new cc.ivera.magnet.engine.tracker.TrackerAddressPolicy(false);
        var p1=java.net.InetSocketAddress.createUnresolved("8.8.8.8",6881);
        var p2=java.net.InetSocketAddress.createUnresolved("9.9.9.9",6881);
        var p3=java.net.InetSocketAddress.createUnresolved("1.1.1.1",6881);
        var fetchOrder=new java.util.ArrayList<Integer>();
        var probes=new java.util.concurrent.atomic.AtomicInteger();
        var worker=new NativeTaskWorkerRoundService(lifecycle,checkpoints,options,
                (hash,id,port,permit)->List.of(p1,p2,p3),
                (peer,info,id,planner,piece,deadline)->{
                    fetchOrder.add(piece);
                    if(piece==2)assertEquals(p1,peer); // Only this Peer reports Piece 2.
                    byte[] data="abcdefghi".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                    return Arrays.copyOfRange(data,piece*4,Math.min(data.length,(piece+1)*4));
                },policy,PieceDownloadCoordinator.ChildThreads.none(),
                (peer,info,id,budget)->{
                    probes.incrementAndGet();
                    java.util.BitSet present=new java.util.BitSet();
                    present.set(0);
                    if(!peer.equals(p3))present.set(1);
                    if(peer.equals(p1))present.set(2);
                    return java.util.Optional.of(present);
                });
        lifecycle.prepare(411,created.taskUuid(),0);
        var result=worker.runOnce(411,created.taskUuid(),1,2);
        assertEquals(List.of(2,1),fetchOrder);
        assertEquals(3,probes.get());
        assertEquals(5,result.task().verifiedBytes());
        assertEquals(5,checkpoints.openOwned(411,created.taskUuid()).verifiedSelectedBytes());
        assertEquals("PAUSED",result.task().state());
    }

    @Test void rarityDisabledDoesNotContactBitfieldPeers() throws Exception {
        var db=database();
        var created=db.create(412,torrent(false,"udp://public.example:6969"),List.of(0,1));
        var options=new NativeTorrentProperties();
        options.setStorageRoot(db.storage().toString());
        options.setEnabled(true);
        options.setWorkerRoundEnabled(true);
        options.setLegacyWorkerCompatibilityEnabled(true);
        var lifecycle=new NativeTaskLifecycleService(db.mapper());
        var checkpoints=new NativeTaskCheckpointFactory(db.mapper(),options,new ObjectMapper());
        var policy=new cc.ivera.magnet.engine.tracker.TrackerAddressPolicy(false);
        var p1=java.net.InetSocketAddress.createUnresolved("8.8.8.8",6881);
        var selectedPieces=new java.util.ArrayList<Integer>();
        var worker=new NativeTaskWorkerRoundService(lifecycle,checkpoints,options,
                (hash,id,port,permit)->List.of(p1),
                (peer,info,id,planner,piece,deadline)->{
                    selectedPieces.add(piece);
                    byte[] data="abcdefghi".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                    return Arrays.copyOfRange(data,piece*4,Math.min(data.length,(piece+1)*4));
                },policy,PieceDownloadCoordinator.ChildThreads.none(),
                (peer,info,id,budget)->{throw new AssertionError("Rarest-first switch is off");});
        lifecycle.prepare(412,created.taskUuid(),0);
        assertEquals(4,worker.runOnce(412,created.taskUuid(),1,1).task().verifiedBytes());
        assertEquals(List.of(0),selectedPieces);
    }

    @Test void exhaustedNetworkDeadlineStopsDiscoveryBeforeAnyPieceFetch() throws Exception {
        var db=database();
        var task=db.create(882,torrent(false,"udp://public.example:6969"),List.of(0));
        var config=new NativeTorrentProperties();
        config.setStorageRoot(db.storage().toString());
        config.setEnabled(true);
        config.setWorkerRoundEnabled(true);
        config.setLegacyWorkerCompatibilityEnabled(true);
        var lifecycle=new NativeTaskLifecycleService(db.mapper());
        var checkpoints=new NativeTaskCheckpointFactory(db.mapper(),config,new ObjectMapper());
        var networkRequests=new java.util.concurrent.atomic.AtomicInteger();
        var worker=new NativeTaskWorkerRoundService(lifecycle,checkpoints,config,
                (hash,id,port,permit)->{
                    try {Thread.sleep(100);}catch(InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new java.io.IOException("Interrupted discovery",e);
                    }
                    return List.of(java.net.InetSocketAddress.createUnresolved("8.8.8.8",6881));
                },
                (peer,info,id,planner,piece,deadline)->{
                    networkRequests.incrementAndGet();
                    return "abcd".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                },new cc.ivera.magnet.engine.tracker.TrackerAddressPolicy(false));
        var ready=lifecycle.prepare(882,task.taskUuid(),0);
        try(var deadline=NativeNetworkBudget.open(java.time.Duration.ofMillis(35))) {
            assertThrows(java.net.SocketTimeoutException.class,
                    ()->worker.runOnce(882,task.taskUuid(),ready.revision(),1));
        }
        assertEquals(0,networkRequests.get());
        assertEquals("READY",lifecycle.get(882,task.taskUuid()).state());
        assertEquals(0,lifecycle.get(882,task.taskUuid()).verifiedBytes());
        assertEquals(0,checkpoints.openOwned(882,task.taskUuid()).verifiedSelectedBytes());
    }

    @Test void lateSha1ValidPeerResponseCannotCommitAfterNetworkBudget() throws Exception {
        var db=database();
        var task=db.create(883,torrent(false,"udp://public.example:6969"),List.of(0));
        var config=new NativeTorrentProperties();
        config.setStorageRoot(db.storage().toString());
        config.setEnabled(true);
        config.setWorkerRoundEnabled(true);
        config.setLegacyWorkerCompatibilityEnabled(true);
        var lifecycle=new NativeTaskLifecycleService(db.mapper());
        var checkpoints=new NativeTaskCheckpointFactory(db.mapper(),config,new ObjectMapper());
        var worker=new NativeTaskWorkerRoundService(lifecycle,checkpoints,config,
                (hash,id,port,permit)->List.of(
                        java.net.InetSocketAddress.createUnresolved("8.8.8.8",6881)),
                (peer,info,id,planner,piece,deadline)->{
                    try {Thread.sleep(100);}catch(InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new java.io.IOException("Interrupted Piece",e);
                    }
                    return "abcd".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                },new cc.ivera.magnet.engine.tracker.TrackerAddressPolicy(false));
        var ready=lifecycle.prepare(883,task.taskUuid(),0);
        try(var deadline=NativeNetworkBudget.open(java.time.Duration.ofMillis(35))) {
            assertThrows(java.net.SocketTimeoutException.class,
                    ()->worker.runOnce(883,task.taskUuid(),ready.revision(),1));
        }
        assertEquals(0,checkpoints.openOwned(883,task.taskUuid()).verifiedSelectedBytes());
        assertEquals(0,lifecycle.get(883,task.taskUuid()).verifiedBytes());
        assertEquals("PAUSED",lifecycle.get(883,task.taskUuid()).state());
    }

    @Test void sqliteAsyncPauseResumeSurvivesNewManagerAndCheckpointReopen() throws Exception {
        var db=database();
        var created=db.create(980,torrent(false,"udp://public.example:6969"),List.of(0,1));
        var flags=new NativeTorrentProperties();
        flags.setStorageRoot(db.storage().toString());
        flags.setEnabled(true);
        flags.setWorkerRoundEnabled(true);
        flags.setLegacyWorkerCompatibilityEnabled(true);
        flags.setAsyncWorkerEnabled(true);
        var lifecycle=new NativeTaskLifecycleService(db.mapper());
        var checkpoints=new NativeTaskCheckpointFactory(db.mapper(),flags,new ObjectMapper());
        var policy=new cc.ivera.magnet.engine.tracker.TrackerAddressPolicy(false);
        var peer=java.net.InetSocketAddress.createUnresolved("8.8.8.8",6881);
        var transferred=new java.util.concurrent.atomic.AtomicInteger();
        var worker=new NativeTaskWorkerRoundService(lifecycle,checkpoints,flags,
                (hash,id,port,permit)->List.of(peer),
                (endpoint,info,localPeerId,planner,piece,budget)->{
                    transferred.incrementAndGet();
                    byte[] data="abcdefghi".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                    return Arrays.copyOfRange(data,piece*4,Math.min(data.length,(piece+1)*4));
                },policy);
        NativeWorkerJobManager.TaskAccess controls=new NativeWorkerJobManager.TaskAccess() {
            @Override public NativeTaskLifecycleService.TaskState get(long owner,UUID id) {
                return lifecycle.get(owner,id);
            }
            @Override public NativeTaskLifecycleService.TaskState pause(long owner,UUID id,long rev) {
                return lifecycle.pause(owner,id,rev);
            }
            @Override public NativeTaskLifecycleService.TaskState prepare(long owner,UUID id,long rev) {
                return lifecycle.prepare(owner,id,rev);
            }
            @Override public NativeTaskLifecycleService.TaskState fenceOrphan(long owner,UUID id,long rev) {
                return lifecycle.fenceOrphanedWorker(owner,id,rev);
            }
        };
        var ready=lifecycle.prepare(980,created.taskUuid(),created.revision());
        try(var firstProcess=new NativeWorkerJobManager(worker::runOnce,controls,flags,1,2,1)) {
            var job=firstProcess.submit(980,created.taskUuid(),ready.revision(),1);
            assertEquals(NativeWorkerJobManager.State.SUCCEEDED,
                    firstProcess.await(980,job,java.time.Duration.ofSeconds(5)).state());
            assertEquals(4,lifecycle.get(980,created.taskUuid()).verifiedBytes());
            assertEquals("PAUSED",lifecycle.get(980,created.taskUuid()).state());
        }
        // Reconstruct a checkpoint from persisted task metadata, not from a live instance.
        assertEquals(4,checkpoints.openOwned(980,created.taskUuid()).verifiedSelectedBytes());
        assertThrows(NoSuchElementException.class,()->checkpoints.openOwned(981,created.taskUuid()));
        try(var secondProcess=new NativeWorkerJobManager(worker::runOnce,controls,flags,1,2,1)) {
            var paused=lifecycle.get(980,created.taskUuid());
            ready=lifecycle.prepare(980,created.taskUuid(),paused.revision());
            var job=secondProcess.submit(980,created.taskUuid(),ready.revision(),2);
            assertEquals(NativeWorkerJobManager.State.SUCCEEDED,
                    secondProcess.await(980,job,java.time.Duration.ofSeconds(5)).state());
            assertEquals("COMPLETED",lifecycle.get(980,created.taskUuid()).state());
            assertEquals(9,lifecycle.get(980,created.taskUuid()).verifiedBytes());
            assertEquals(9,checkpoints.openOwned(980,created.taskUuid()).verifiedSelectedBytes());
            assertThrows(NoSuchElementException.class,()->secondProcess.status(981,job));
        }
        assertEquals(3,transferred.get(),"Committed Pieces must never be redownloaded on resume");
    }

    /**
     * P4.7 explicit internet test: official Ubuntu v1 torrent -> isolated SQLite
     * task -> async Worker -> real BEP3 TCP -> SHA-1 -> durable Checkpoint + CAS.
     * Never downloaded in ordinary mvn test runs, even if the torrent file exists.
     *
     * -Dnative.acceptance.network=true
     * -Dnative.acceptance.torrent=<absolute Ubuntu .torrent path>
     */
    @Test void officialUbuntuTorrentFlowsThroughSqliteAsyncWorkerAndCheckpoint()
            throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Boolean.getBoolean("native.acceptance.network"),
                "Explicit native network acceptance flag required");
        String input=System.getProperty("native.acceptance.torrent");
        org.junit.jupiter.api.Assumptions.assumeTrue(input!=null && !input.isBlank(),
                "Official Ubuntu torrent fixture must be explicitly provided");
        Path source=Path.of(input).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(source));
        assertTrue(Files.size(source)<8*1024*1024);
        byte[] metainfo=Files.readAllBytes(source);
        var parsed=new TorrentV1MetainfoParser().parse(metainfo);
        assertEquals("e1fc140a6391357fa1cf08ddb70274f9c05eb88b",
                parsed.infoHash(),"Unexpected official Ubuntu .torrent fixture");
        assertEquals(262144,parsed.pieceLength());
        var db=database();
        var task=db.create(991,metainfo,List.of(0));
        assertEquals(parsed.infoHash(),task.infoHash());
        assertEquals("CREATED",task.state());
        assertEquals(parsed.totalBytes(),task.selectedBytes());
        var flags=new NativeTorrentProperties();
        flags.setStorageRoot(db.storage().toString());
        flags.setEnabled(true);
        flags.setWorkerRoundEnabled(true);
        flags.setLegacyWorkerCompatibilityEnabled(true);
        flags.setAsyncWorkerEnabled(true);
        flags.setContinuousWorkerEnabled(true);
        flags.setNetworkRoundBudgetSeconds(80);
        flags.setMaxPeersPerTask(20);
        var lifecycle=new NativeTaskLifecycleService(db.mapper());
        var checkpoints=new NativeTaskCheckpointFactory(db.mapper(),flags,new ObjectMapper());
        assertThrows(NoSuchElementException.class,()->lifecycle.get(992,task.taskUuid()));
        var policy=new cc.ivera.magnet.engine.tracker.TrackerAddressPolicy(false);
        var session=new PeerPieceDownloadSession(policy);
        var cached=new java.util.concurrent.atomic.AtomicReference<List<java.net.InetSocketAddress>>();
        var invocation=new java.util.concurrent.atomic.AtomicInteger();
        var tracker=cc.ivera.magnet.engine.tracker.PublicTrackerProbe.network(policy);
        var finder=(NativeTaskWorkerRoundService.PublicPeerFinder)(hash,localId,port,permit)->{
            permit.requireMatch(java.util.HexFormat.of().formatHex(hash));
            var available=cached.get();
            if(available==null) {
                var report=tracker.probe(hash,localId,port,true,4);
                var pool=new cc.ivera.magnet.engine.peer.PeerCandidatePool(256,policy);
                for(var endpoint:report.peers())
                    pool.offer(endpoint,cc.ivera.magnet.engine.peer.PeerCandidatePool.Source.PUBLIC_TRACKER);
                PublicPeerReachabilitySampler.Result result;
                try {
                    result=PublicPeerReachabilitySampler.sample(pool.snapshot(),policy,
                            128,8,900,java.time.Duration.ofSeconds(19),
                            java.nio.ByteBuffer.wrap(hash).getLong());
                }catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException("Public Peer sampling interrupted",e);
                }
                available=result.reachable();
                System.out.println("NATIVE_TASK_ACCEPTANCE_TCP "+result.summary());
                if(available.isEmpty())throw new java.io.IOException(
                        "Public tracker produced no reachable TCP candidates");
                cached.set(available);
            }
            int shift=invocation.getAndIncrement()*5;
            var rotated=new java.util.ArrayList<java.net.InetSocketAddress>();
            for(int i=0;i<Math.min(20,available.size());i++)
                rotated.add(available.get((i+shift)%available.size()));
            return rotated;
        };
        var worker=new NativeTaskWorkerRoundService(lifecycle,checkpoints,flags,
                finder,session::downloadPiece,policy,session);
        NativeWorkerJobManager.TaskAccess controls=new NativeWorkerJobManager.TaskAccess() {
            @Override public NativeTaskLifecycleService.TaskState get(long owner,UUID id) {
                return lifecycle.get(owner,id);
            }
            @Override public NativeTaskLifecycleService.TaskState prepare(long owner,UUID id,long rev) {
                return lifecycle.prepare(owner,id,rev);
            }
            @Override public NativeTaskLifecycleService.TaskState pause(long owner,UUID id,long rev) {
                return lifecycle.pause(owner,id,rev);
            }
            @Override public NativeTaskLifecycleService.TaskState fenceOrphan(long owner,UUID id,long rev) {
                return lifecycle.fenceOrphanedWorker(owner,id,rev);
            }
        };
        var ready=lifecycle.prepare(991,task.taskUuid(),task.revision());
        try(var queue=new NativeWorkerJobManager(worker::runOnce,controls,flags,1,2,1,
                thread->session.abortForThread(thread))) {
            UUID job=queue.submitContinuous(991,task.taskUuid(),ready.revision(),1,4,115);
            NativeWorkerJobManager.Snapshot status=null;
            for(int i=0;i<9;i++) {
                status=queue.await(991,job,java.time.Duration.ofSeconds(15));
                if(status.terminal())break;
            }
            assertNotNull(status);
            assertTrue(status.terminal(),"Bounded public network job exceeded test deadline");
            var metrics=queue.metrics(991,job);
            System.out.println("NATIVE_TASK_ACCEPTANCE_JOB state="+status.state()
                    +" rounds="+metrics.rounds()+" pieces="+metrics.committedPieces()
                    +" verified="+metrics.verifiedBytes()+" durationMs="+metrics.elapsedMillis());
            assertTrue(metrics.verifiedBytes()>=262144,
                    "No genuine Ubuntu Piece transferred through the SQLite async Worker");
            assertTrue(metrics.verifiedBytes()<=4L*262144,
                    "Acceptance must not download an unbounded file");
            assertThrows(NoSuchElementException.class,()->queue.metrics(992,job));
        }
        var row=lifecycle.get(991,task.taskUuid());
        assertTrue(row.verifiedBytes()>=262144);
        assertTrue(row.verifiedBytes()<row.selectedBytes());
        assertEquals("PAUSED",row.state());
        assertEquals(row.verifiedBytes(),
                checkpoints.openOwned(991,task.taskUuid()).verifiedSelectedBytes());
        var reconciled=lifecycle.reconcileStopped(991,task.taskUuid(),row.revision(),
                checkpoints.openOwned(991,task.taskUuid()));
        assertEquals(row.verifiedBytes(),reconciled.verifiedBytes());
        System.out.println("NATIVE_TASK_ACCEPTANCE_SQLITE verified="+row.verifiedBytes()
                +" selected="+row.selectedBytes()+" revision="+row.revision());
    }

    @Test void sqliteCrashedRunningTaskRequiresExplicitFencingAndRehashBeforeResume()
            throws Exception {
        var db=database();
        var created=db.create(984,torrent(false,"udp://public.example:6969"),List.of(0,1));
        var flags=new NativeTorrentProperties();
        flags.setStorageRoot(db.storage().toString());
        flags.setEnabled(true);
        flags.setWorkerRoundEnabled(true);
        flags.setLegacyWorkerCompatibilityEnabled(true);
        flags.setAsyncWorkerEnabled(true);
        var lifecycle=new NativeTaskLifecycleService(db.mapper());
        var checkpoints=new NativeTaskCheckpointFactory(db.mapper(),flags,new ObjectMapper());
        var state=lifecycle.prepare(984,created.taskUuid(),0);
        // The legitimate Worker opens its checkpoint BEFORE claiming RUNNING.
        var disk=checkpoints.openOwned(984,created.taskUuid());
        var running=lifecycle.claimForWorker(984,created.taskUuid(),state.revision());
        assertEquals("RUNNING",running.state());

        // Simulate a process crash after the disk has a verified Piece but before
        // the database progress CAS was able to record it.
        assertThrows(IllegalStateException.class,
                ()->checkpoints.openOwned(984,created.taskUuid()),
                "No separate writer may open a checkpoint while the DB says RUNNING");
        disk.commit(0,"abcd".getBytes(StandardCharsets.UTF_8),()->{});
        assertEquals(0,lifecycle.get(984,created.taskUuid()).verifiedBytes());
        NativeWorkerJobManager.TaskAccess access=new NativeWorkerJobManager.TaskAccess() {
            @Override public NativeTaskLifecycleService.TaskState get(long user,UUID task) {
                return lifecycle.get(user,task);
            }
            @Override public NativeTaskLifecycleService.TaskState pause(long user,UUID task,long revision) {
                return lifecycle.pause(user,task,revision);
            }
            @Override public NativeTaskLifecycleService.TaskState prepare(long user,UUID task,long revision) {
                return lifecycle.prepare(user,task,revision);
            }
            @Override public NativeTaskLifecycleService.TaskState fenceOrphan(long user,UUID task,long revision) {
                return lifecycle.fenceOrphanedWorker(user,task,revision);
            }
        };
        try(var restartedManager=new NativeWorkerJobManager(
                (user,task,revision,budget)->{
                    fail("Orphan recovery must not start background downloads");
                    return null;
                },access,flags,1,2,1)) {
            assertThrows(IllegalStateException.class,
                    ()->restartedManager.recoverOwnedOrphan(984,created.taskUuid(),running.revision()));
            flags.setSingleInstanceOrphanRecoveryEnabled(true);
            assertThrows(NoSuchElementException.class,
                    ()->restartedManager.recoverOwnedOrphan(985,created.taskUuid(),running.revision()));
            assertThrows(IllegalStateException.class,
                    ()->restartedManager.recoverOwnedOrphan(984,created.taskUuid(),running.revision()-1));
            var recovered=restartedManager.recoverOwnedOrphan(984,created.taskUuid(),running.revision());
            assertEquals("PAUSED",recovered.state());
            assertEquals(0,recovered.verifiedBytes());
            var reconciled=lifecycle.reconcileStopped(984,created.taskUuid(),recovered.revision(),
                    checkpoints.openOwned(984,created.taskUuid()));
            assertEquals(4,reconciled.verifiedBytes());
            assertEquals(4,checkpoints.openOwned(984,created.taskUuid()).verifiedSelectedBytes());
            var ready=lifecycle.prepare(984,created.taskUuid(),reconciled.revision());
            var requests=new java.util.ArrayList<Integer>();
            var networkWorker=new NativeTaskWorkerRoundService(lifecycle,checkpoints,flags,
                    (hash,peerId,port,permit)->List.of(
                            java.net.InetSocketAddress.createUnresolved("8.8.8.8",6881)),
                    (peer,info,peerId,planner,piece,timeout)->{
                        requests.add(piece);
                        byte[] source="abcdefghi".getBytes(StandardCharsets.UTF_8);
                        return Arrays.copyOfRange(source,piece*4,Math.min(source.length,(piece+1)*4));
                    },new cc.ivera.magnet.engine.tracker.TrackerAddressPolicy(false));
            var finished=networkWorker.runOnce(984,created.taskUuid(),ready.revision(),4);
            assertEquals("COMPLETED",finished.task().state());
            assertEquals(9,finished.task().verifiedBytes());
            assertEquals(List.of(1,2),requests,
                    "The disk-verified Piece must never be redownloaded after recovery");
            assertEquals(9,checkpoints.openOwned(984,created.taskUuid()).verifiedSelectedBytes());
        }
    }

    @Test void failingFileInsertRollsBackMetadataAndTaskInOneTransaction() throws Exception {
        var db=database();
        try(var conn=DriverManager.getConnection(db.jdbc());
            var stmt=conn.createStatement()) {
            stmt.execute("CREATE TRIGGER reject_second_file BEFORE INSERT ON torrent_native_file "+
                    "WHEN NEW.file_index = 1 BEGIN SELECT RAISE(ABORT, 'forced failure'); END");
        }
        byte[] fixture=torrent(false,"udp://public.example:80");
        assertThrows(RuntimeException.class,()->db.create(44,fixture,List.of(0)));
        assertEquals(0,count(db.jdbc(),"torrent_native_metadata"));
        assertEquals(0,count(db.jdbc(),"torrent_native_task"));
        assertEquals(0,count(db.jdbc(),"torrent_native_file"));
    }

    @Test void selectedDownloadRootKeepsOwnerTaskScopeAndSurvivesCheckpointReopen()
            throws Exception {
        var db=database();
        Path userRoot=Files.createDirectories(work.resolve("user-media"));
        db.settings().setDownloadRootBases(List.of(userRoot.toString()));
        byte[] fixture=torrent(false,"udp://public.example:80");
        var created=db.tx().execute(status->db.service()
                .createFromTorrent(15,fixture,List.of(0),userRoot));
        var row=db.mapper().findOwned(created.taskUuid().toString(),15L);
        assertNotNull(row);
        Path statePath=Path.of(row.getStatePath());
        assertTrue(statePath.startsWith(userRoot),
                "A user-selected root must not fall back to the configured default");
        assertTrue(statePath.endsWith(Path.of("15").resolve(created.taskUuid().toString())));
        var factory=new NativeTaskCheckpointFactory(db.mapper(),db.settings(),new ObjectMapper());
        assertNotNull(factory.openOwned(15,created.taskUuid()));
        assertThrows(NoSuchElementException.class,
                ()->factory.openOwned(16,created.taskUuid()));
        // A base outside every approved root is rejected before any task row is written.
        assertThrows(SecurityException.class,()->db.tx().execute(status->db.service()
                .createFromTorrent(15,fixture,List.of(0),work.resolve("outside"))));
    }

    /**
     * Isolated HTTP / authenticated business flow: the JWT interceptor, real SQLite
     * mapper, metadata selection and Piece Worker all execute together. Peer traffic
     * is replaced with deterministic verified bytes; this is NOT a browser/public-peer test.
     */
    @Test void jwtMagnetToMetadataSelectionContinuousWorkerAndDeleteUsesRealSqlite()
            throws Exception {
        var db=database();
        byte[] bytes=torrent(false,"udp://public.example:6969");
        var parsed=new TorrentV1MetainfoParser().parse(bytes);
        var flags=db.settings();
        flags.setEnabled(true);
        flags.setTaskCreationEnabled(true);
        flags.setTaskControlEnabled(true);
        flags.setMetadataLookupEnabled(true);
        flags.setWorkerRoundEnabled(true);
        flags.setAsyncWorkerEnabled(true);
        flags.setContinuousWorkerEnabled(true);
        flags.setLegacyWorkerCompatibilityEnabled(true); // strictly local synthetic worker

        UserMapper users=mock(UserMapper.class);
        User alice=User.builder().id(71L).email("native-alice@example.test").build();
        User bob=User.builder().id(72L).email("native-bob@example.test").build();
        when(users.selectById(71L)).thenReturn(alice);
        when(users.selectById(72L)).thenReturn(bob);
        var jwt=new JwtService("test-only-native-http-jwt-key-abcdefghijklmnopqrstuvwxyz",30);
        String aliceToken="Bearer "+jwt.generateAccessToken(alice);
        String bobToken="Bearer "+jwt.generateAccessToken(bob);
        var json=new ObjectMapper();
        var roots=mock(NativeDownloadRootService.class);
        var lifecycle=new NativeTaskLifecycleService(db.mapper());
        var checkpoints=new NativeTaskCheckpointFactory(db.mapper(),flags,json);
        var peerPolicy=new cc.ivera.magnet.engine.tracker.TrackerAddressPolicy(false);
        var worker=new NativeTaskWorkerRoundService(lifecycle,checkpoints,flags,
                (hash,peerId,port,permit)->List.of(
                        java.net.InetSocketAddress.createUnresolved("8.8.8.8",6881)),
                (peer,info,peerId,planner,piece,deadline)->{
                    byte[] source="abcdefghi".getBytes(StandardCharsets.US_ASCII);
                    return Arrays.copyOfRange(source,piece*4,Math.min(source.length,(piece+1)*4));
                },peerPolicy);
        var taskAccess=new NativeWorkerJobManager.TaskAccess() {
            public NativeTaskLifecycleService.TaskState get(long owner,UUID task) {
                return lifecycle.get(owner,task);
            }
            public NativeTaskLifecycleService.TaskState pause(long owner,UUID task,long revision) {
                return lifecycle.pause(owner,task,revision);
            }
            public NativeTaskLifecycleService.TaskState prepare(long owner,UUID task,long revision) {
                return lifecycle.prepare(owner,task,revision);
            }
            public NativeTaskLifecycleService.TaskState fenceOrphan(long owner,UUID task,long revision) {
                return lifecycle.fenceOrphanedWorker(owner,task,revision);
            }
        };
        try(var metadata=new NativeMetadataJobManager((uri,peerId,port)->
                    new MetadataResolver.Resolution(parsed,1,1,bytes),
                    1,4,2,java.time.Duration.ofSeconds(10));
            var jobs=new NativeWorkerJobManager(worker::runOnce,taskAccess,flags,1,4,2)) {
            var mvc=MockMvcBuilders.standaloneSetup(
                    new NativeMetadataJobsController(flags,metadata),
                    new NativeTaskCreationController(flags,db.service(),metadata,roots),
                    new NativeTaskControlController(flags,lifecycle,checkpoints),
                    new NativeWorkerJobController(flags,jobs))
                    .addInterceptors(new AuthInterceptor(jwt,users))
                    .setControllerAdvice(new GlobalExceptionHandler()).build();
            String base="/api/magnet/native";
            String requestBody=json.writeValueAsString(Map.of(
                    "magnetUri","magnet:?xt=urn:btih:"+parsed.infoHash(),
                    "consentToPublicDiscovery",true));

            // A fully authenticated metadata submit -> status -> paginated file list.
            var response=mvc.perform(post(base+"/metadata/jobs")
                        .header("Authorization",aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody))
                    .andExpect(status().isOk()).andReturn();
            UUID aliceJob=UUID.fromString(json.readTree(response.getResponse()
                    .getContentAsString()).path("data").path("jobId").asText());
            assertEquals(NativeMetadataJobManager.State.SUCCEEDED,
                    metadata.await(71L,aliceJob,java.time.Duration.ofSeconds(3)).state());
            mvc.perform(get(base+"/metadata/jobs/"+aliceJob+"/files")
                        .header("Authorization",aliceToken).param("page","1")
                        .param("pageSize","1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalFiles").value(2))
                    .andExpect(jsonPath("$.data.files.length()").value(1));
            mvc.perform(get(base+"/metadata/jobs/"+aliceJob)
                        .header("Authorization",bobToken))
                    .andExpect(status().isNotFound());
            mvc.perform(get(base+"/metadata/jobs/"+aliceJob+"/files")
                        .header("Authorization",bobToken))
                    .andExpect(status().isNotFound());
            mvc.perform(post(base+"/tasks/from-metadata/"+aliceJob)
                        .header("Authorization",bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileIndexes\":[0]}"))
                    .andExpect(status().isNotFound());

            var created=mvc.perform(post(base+"/tasks/from-metadata/"+aliceJob)
                        .header("Authorization",aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileIndexes\":[0,1]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.selectedBytes").value(9))
                    .andReturn();
            UUID aliceTask=UUID.fromString(json.readTree(created.getResponse()
                    .getContentAsString()).path("data").path("taskUuid").asText());
            assertEquals("CREATED",lifecycle.get(71L,aliceTask).state());
            mvc.perform(get(base+"/tasks/"+aliceTask)
                        .header("Authorization",bobToken))
                    .andExpect(status().isNotFound());
            mvc.perform(post(base+"/tasks/"+aliceTask+"/prepare")
                        .header("Authorization",aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":0}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.state").value("READY"));

            var submission=mvc.perform(post(base+"/tasks/"+aliceTask+"/continuous")
                        .header("Authorization",aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"piecesPerRound\":1,\"maxRounds\":6,\"maxSeconds\":30}"))
                    .andExpect(status().isOk()).andReturn();
            UUID workerJob=UUID.fromString(json.readTree(submission.getResponse()
                    .getContentAsString()).path("data").path("jobId").asText());
            assertEquals(NativeWorkerJobManager.State.SUCCEEDED,
                    jobs.await(71L,workerJob,java.time.Duration.ofSeconds(5)).state());
            mvc.perform(get(base+"/tasks/"+aliceTask)
                        .header("Authorization",aliceToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.state").value("COMPLETED"))
                    .andExpect(jsonPath("$.data.verifiedBytes").value(9))
                    .andExpect(jsonPath("$.data.progressPercent").value(100.0));
            assertEquals(9,checkpoints.openOwned(71,aliceTask).verifiedSelectedBytes());
            mvc.perform(get(base+"/worker-jobs/"+workerJob)
                        .header("Authorization",bobToken))
                    .andExpect(status().isNotFound());

            // A second user may use the same InfoHash without seeing or changing A.
            var bobResponse=mvc.perform(post(base+"/metadata/jobs")
                        .header("Authorization",bobToken)
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody))
                    .andExpect(status().isOk()).andReturn();
            UUID bobJob=UUID.fromString(json.readTree(bobResponse.getResponse()
                    .getContentAsString()).path("data").path("jobId").asText());
            assertEquals(NativeMetadataJobManager.State.SUCCEEDED,
                    metadata.await(72L,bobJob,java.time.Duration.ofSeconds(3)).state());
            var bobCreated=mvc.perform(post(base+"/tasks/from-metadata/"+bobJob)
                        .header("Authorization",bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileIndexes\":[0]}"))
                    .andExpect(status().isOk()).andReturn();
            UUID bobTask=UUID.fromString(json.readTree(bobCreated.getResponse()
                    .getContentAsString()).path("data").path("taskUuid").asText());
            assertNotEquals(aliceTask,bobTask);
            assertEquals("CREATED",lifecycle.get(72L,bobTask).state());
            assertEquals(4,lifecycle.get(72L,bobTask).selectedBytes());
            mvc.perform(delete(base+"/tasks/"+aliceTask)
                        .header("Authorization",bobToken)
                        .param("expectedRevision",Long.toString(lifecycle.get(71L,aliceTask).revision())))
                    .andExpect(status().isNotFound());

            mvc.perform(delete(base+"/tasks/"+aliceTask)
                        .header("Authorization",aliceToken)
                        .param("expectedRevision",Long.toString(lifecycle.get(71L,aliceTask).revision())))
                    .andExpect(status().isOk());
            mvc.perform(get(base+"/tasks/"+aliceTask)
                        .header("Authorization",aliceToken))
                    .andExpect(status().isNotFound());
            assertEquals("CREATED",lifecycle.get(72L,bobTask).state());
            assertTrue(lifecycle.list(71,1,20).isEmpty());
            assertEquals(1,lifecycle.list(72,1,20).size());
        }
    }
}
