package cc.ivera.magnet.engine.discovery;

import cc.ivera.magnet.domain.TorrentNativeParseResultMapper;
import cc.ivera.magnet.domain.TorrentNativeTaskMapper;
import cc.ivera.magnet.engine.codec.BencodeDecoder;
import cc.ivera.magnet.engine.codec.BencodeEncoder;
import cc.ivera.magnet.engine.codec.ByteString;
import cc.ivera.magnet.engine.metainfo.PieceLayout;
import cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser;
import cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser.TorrentInfo;
import cc.ivera.magnet.engine.config.NativeTorrentProperties;
import cc.ivera.magnet.engine.piece.NativeTaskCreationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SqlNativeMetadataResultStoreTest {
    @TempDir Path temp;

    private SqlSessionFactory factory() throws Exception {
        var path=temp.resolve("metadata.sqlite");
        String jdbc="jdbc:sqlite:"+path.toAbsolutePath();
        String engine=Files.readString(Path.of("src/main/resources/db/migration/sqlite/V13__torrent_native_engine.sql"))
                .lines().filter(line->!line.stripLeading().startsWith("--"))
                .collect(java.util.stream.Collectors.joining("\n"));
        try(var conn=DriverManager.getConnection(jdbc)) {
            for(String part:engine.split(";"))if(!part.isBlank()) {
                try(var statement=conn.createStatement()) {statement.execute(part);}
            }
        }
        String ddl=Files.readString(Path.of("src/main/resources/db/migration/sqlite/V14__torrent_native_parse_result.sql"))
                .lines().filter(line->!line.stripLeading().startsWith("--"))
                .collect(java.util.stream.Collectors.joining("\n"));
        try(var conn=DriverManager.getConnection(jdbc)) {
            for(String part:ddl.split(";"))if(!part.isBlank()) {
                try(var statement=conn.createStatement()) {statement.execute(part);}
            }
        }
        String upgrade=Files.readString(Path.of("src/main/resources/db/migration/sqlite/V15__native_verified_metadata.sql"))
                .lines().filter(line->!line.stripLeading().startsWith("--"))
                .collect(java.util.stream.Collectors.joining("\n"));
        try(var conn=DriverManager.getConnection(jdbc);var statement=conn.createStatement()) {
            for(String sql:upgrade.split(";")) {
                String clean=sql.lines().filter(line->!line.stripLeading().startsWith("--"))
                        .collect(java.util.stream.Collectors.joining("\n")).trim();
                if(!clean.isEmpty())statement.execute(clean);
            }
        }
        String trackers=Files.readString(Path.of("src/main/resources/db/migration/sqlite/V23__native_task_trackers.sql"))
                .lines().filter(line->!line.stripLeading().startsWith("--"))
                .collect(java.util.stream.Collectors.joining("\n"));
        try(var conn=DriverManager.getConnection(jdbc);var statement=conn.createStatement()) {
            for(String sql:trackers.split(";"))if(!sql.isBlank())statement.execute(sql);
        }
        var source=new UnpooledDataSource("org.sqlite.JDBC",jdbc,null,null);
        var cfg=new org.apache.ibatis.session.Configuration();
        cfg.setEnvironment(new Environment("sqlite-test",new SpringManagedTransactionFactory(),source));
        cfg.setDatabaseId(new cc.ivera.shared.config.MybatisPlusConfig().databaseIdProvider().getDatabaseId(source));
        try(var in=SqlNativeMetadataResultStoreTest.class.getClassLoader()
                .getResourceAsStream("mapper/TorrentNativeParseResultMapper.xml")) {
            assertNotNull(in);
            new XMLMapperBuilder(in,cfg,"mapper/TorrentNativeParseResultMapper.xml",cfg.getSqlFragments()).parse();
        }
        try(var in=SqlNativeMetadataResultStoreTest.class.getClassLoader()
                .getResourceAsStream("mapper/TorrentNativeTaskMapper.xml")) {
            assertNotNull(in);
            new XMLMapperBuilder(in,cfg,"mapper/TorrentNativeTaskMapper.xml",cfg.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(cfg);
    }
    private static TorrentInfo info() {
        return new TorrentInfo("abcdef0123456789abcdef0123456789abcdef01","demo",
                List.of(new PieceLayout.FileEntry(0,"documents/a.txt",0,4),
                        new PieceLayout.FileEntry(1,"documents/b.txt",4,6)),
                10,10,new byte[20]);
    }

    private static byte[] publicTorrent(String name) throws Exception {
        var info=new BencodeDecoder.DictionaryValue(Map.of(
                ByteString.ascii("length"),new BencodeDecoder.IntegerValue(4),
                ByteString.ascii("name"),new BencodeDecoder.BytesValue(name.getBytes(StandardCharsets.UTF_8)),
                ByteString.ascii("piece length"),new BencodeDecoder.IntegerValue(4),
                ByteString.ascii("pieces"),new BencodeDecoder.BytesValue(
                        MessageDigest.getInstance("SHA-1").digest("abcd".getBytes(StandardCharsets.US_ASCII)))));
        return new BencodeEncoder().encode(new BencodeDecoder.DictionaryValue(Map.of(
                ByteString.ascii("announce"),new BencodeDecoder.BytesValue(
                        "https://tracker.example/announce?passkey=fixture".getBytes(StandardCharsets.US_ASCII)),
                ByteString.ascii("info"),info)));
    }

    private static NativeMetadataResultStore transactionalStore(SqlSessionFactory factory) {
        var session=new SqlSessionTemplate(factory);
        var target=new SqlNativeMetadataResultStore(
                session.getMapper(TorrentNativeParseResultMapper.class),session.getMapper(TorrentNativeTaskMapper.class),new ObjectMapper());
        var tx=new TransactionInterceptor(new DataSourceTransactionManager(
                factory.getConfiguration().getEnvironment().getDataSource()),
                new AnnotationTransactionAttributeSource());
        var proxy=new ProxyFactory(target);
        proxy.addAdvice(tx);
        return (NativeMetadataResultStore)proxy.getProxy();
    }

    private static long count(SqlSessionFactory factory,String table) throws Exception {
        try(var connection=factory.getConfiguration().getEnvironment().getDataSource().getConnection();
            var statement=connection.createStatement();
            var rows=statement.executeQuery("SELECT count(*) FROM "+table)) {
            return rows.next()?rows.getLong(1):0;
        }
    }

    @Test void successfulDiscoveryImmediatelySavesSanitizedPieceMetadataWithoutCreatingTask() throws Exception {
        var factory=factory();
        var store=transactionalStore(factory);
        byte[] raw=publicTorrent("discovered.bin");
        var info=new TorrentV1MetainfoParser().parse(raw);
        try(var jobs=new NativeMetadataJobManager((magnet,peer,port)->
                new MetadataResolver.Resolution(info,1,1,raw),store,1,2,1,Duration.ofSeconds(2))) {
            var job=jobs.submit(101,"magnet",new byte[20],6881);
            assertEquals(NativeMetadataJobManager.State.SUCCEEDED,
                    jobs.await(101,job,Duration.ofSeconds(1)).state());
            try(var session=factory.openSession(true)) {
                var metadata=session.getMapper(TorrentNativeTaskMapper.class).findMetadataByHash(info.infoHash());
                assertNotNull(metadata,"Successful metadata discovery must populate torrent_native_metadata immediately");
                var decoded=new BencodeDecoder().decodeTorrent(metadata.getTorrentBencode());
                assertEquals(1,decoded.root().values().size());
                assertArrayEquals(new BencodeDecoder().decodeTorrent(raw).infoBytes(),decoded.infoBytes());
                assertEquals(info.infoHash(),new TorrentV1MetainfoParser().parse(metadata.getTorrentBencode()).infoHash());
                assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(metadata.getTorrentBencode())),metadata.getMetainfoSha256());
                assertEquals(info.pieceLength(),metadata.getPieceLength());
                assertEquals((long)info.pieceLayout().pieceCount(),metadata.getPieceCount());
                assertEquals(info.totalBytes(),metadata.getTotalBytes());
                assertEquals(0,session.getMapper(TorrentNativeTaskMapper.class).countOutstandingOwned(101));
                assertTrue(store.find(101,job).isPresent());
                assertTrue(store.find(202,job).isEmpty());
            }
        }
    }

    @Test void repeatedDiscoveryReusesMetadataAndSubsequentTaskCreationRemainsCompatible() throws Exception {
        var factory=factory();
        var store=transactionalStore(factory);
        byte[] raw=publicTorrent("reusable.bin");
        var info=new TorrentV1MetainfoParser().parse(raw);
        store.saveVerified(101,UUID.randomUUID(),info,raw);
        store.saveVerified(202,UUID.randomUUID(),info,raw);
        assertEquals(1,count(factory,"torrent_native_metadata"));
        assertEquals(2,count(factory,"torrent_native_parse_result"));
        var mapper=new SqlSessionTemplate(factory).getMapper(TorrentNativeTaskMapper.class);
        var settings=new NativeTorrentProperties();
        settings.setStorageRoot(temp.resolve("downloads").toString());
        var creator=new NativeTaskCreationService(mapper,settings,new ObjectMapper());
        var created=creator.createFromTorrent(101,raw,List.of(0));
        assertEquals(info.infoHash(),created.infoHash());
        assertEquals(1,count(factory,"torrent_native_metadata"));
        assertEquals(1,count(factory,"torrent_native_task"));
    }

    @Test void simultaneousDiscoveryOfSameHashSharesOneMetadataRow() throws Exception {
        var factory=factory();
        var store=transactionalStore(factory);
        byte[] raw=publicTorrent("concurrent.bin");
        var info=new TorrentV1MetainfoParser().parse(raw);
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->{start.await();store.saveVerified(101,UUID.randomUUID(),info,raw);return null;});
            var second=executor.submit(()->{start.await();store.saveVerified(202,UUID.randomUUID(),info,raw);return null;});
            start.countDown();
            first.get(3,java.util.concurrent.TimeUnit.SECONDS);
            second.get(3,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(1,count(factory,"torrent_native_metadata"));
        assertEquals(2,count(factory,"torrent_native_parse_result"));
    }

    @Test void metadataInsertFailureDoesNotPersistSummaryOrReportSuccess() throws Exception {
        var factory=factory();
        var store=transactionalStore(factory);
        byte[] raw=publicTorrent("unavailable.bin");
        var info=new TorrentV1MetainfoParser().parse(raw);
        try(var connection=factory.getConfiguration().getEnvironment().getDataSource().getConnection();
            var statement=connection.createStatement()) {
            statement.execute("CREATE TRIGGER reject_metadata BEFORE INSERT ON torrent_native_metadata "
                    +"BEGIN SELECT RAISE(ABORT, 'fixture metadata unavailable'); END");
        }
        try(var jobs=new NativeMetadataJobManager((magnet,peer,port)->
                new MetadataResolver.Resolution(info,1,1,raw),store,1,1,1,Duration.ofSeconds(2))) {
            var job=jobs.submit(101,"magnet",new byte[20],6881);
            assertEquals(NativeMetadataJobManager.State.FAILED,
                    jobs.await(101,job,Duration.ofSeconds(1)).state());
        }
        assertEquals(0,count(factory,"torrent_native_metadata"));
        assertEquals(0,count(factory,"torrent_native_parse_result"));
    }

    @Test void mysqlMetadataInsertTargetsOnlyTheDuplicateKeyConflict() throws Exception {
        var cfg=new org.apache.ibatis.session.Configuration();
        cfg.setDatabaseId("mysql");
        try(var in=getClass().getClassLoader().getResourceAsStream("mapper/TorrentNativeTaskMapper.xml")) {
            assertNotNull(in);
            new XMLMapperBuilder(in,cfg,"mapper/TorrentNativeTaskMapper.xml",cfg.getSqlFragments()).parse();
        }
        String sql=cfg.getMappedStatement(TorrentNativeTaskMapper.class.getName()+".insertMetadataIfAbsent")
                .getBoundSql(Map.of("metadata",cc.ivera.magnet.domain.TorrentNativeMetadata.builder().build())).getSql();
        assertTrue(sql.contains("ON DUPLICATE KEY UPDATE info_hash=info_hash"));
        assertFalse(sql.contains("IGNORE"));
    }

    @Test void summaryInsertFailureRollsBackNewSharedMetadata() throws Exception {
        var factory=factory();
        var store=transactionalStore(factory);
        UUID job=UUID.randomUUID();
        byte[] original=publicTorrent("original.bin");
        var parser=new TorrentV1MetainfoParser();
        store.saveVerified(101,job,parser.parse(original),original);
        byte[] duplicate=publicTorrent("duplicate.bin");
        var info=parser.parse(duplicate);
        assertThrows(RuntimeException.class,()->store.saveVerified(101,job,info,duplicate));
        try(var session=factory.openSession(true)) {
            assertNull(session.getMapper(TorrentNativeTaskMapper.class).findMetadataByHash(info.infoHash()));
        }
        assertEquals(1,count(factory,"torrent_native_metadata"));
        assertEquals(1,count(factory,"torrent_native_parse_result"));
    }

    @Test void privateMetadataAndCorruptSharedMetadataNeverBecomeSuccessfulJobs() throws Exception {
        var factory=factory();
        var store=transactionalStore(factory);
        byte[] raw=publicTorrent("public.bin");
        var document=new BencodeDecoder().decodeTorrent(raw);
        var infoValues=new java.util.HashMap<>(((BencodeDecoder.DictionaryValue)document.root().required("info")).values());
        infoValues.put(ByteString.ascii("private"),new BencodeDecoder.IntegerValue(1));
        byte[] privateRaw=new BencodeEncoder().encode(new BencodeDecoder.DictionaryValue(Map.of(
                ByteString.ascii("info"),new BencodeDecoder.DictionaryValue(infoValues))));
        var parser=new TorrentV1MetainfoParser();
        assertThrows(SecurityException.class,
                ()->store.saveVerified(101,UUID.randomUUID(),parser.parse(privateRaw),privateRaw));
        assertEquals(0,count(factory,"torrent_native_metadata"));
        assertEquals(0,count(factory,"torrent_native_parse_result"));
        var info=parser.parse(raw);
        try(var session=factory.openSession(true)) {
            session.getMapper(TorrentNativeTaskMapper.class).insertMetadata(
                    cc.ivera.magnet.domain.TorrentNativeMetadata.builder()
                            .infoHash(info.infoHash()).torrentBencode(new byte[]{'x'})
                            .metainfoSha256("0".repeat(64)).pieceLength(4).pieceCount(1L).totalBytes(4L)
                            .createdAt(java.time.LocalDateTime.now()).build());
        }
        try(var jobs=new NativeMetadataJobManager((magnet,peer,port)->
                new MetadataResolver.Resolution(info,1,1,raw),store,1,1,1,Duration.ofSeconds(2))) {
            var job=jobs.submit(101,"magnet",new byte[20],6881);
            assertEquals(NativeMetadataJobManager.State.FAILED,
                    jobs.await(101,job,Duration.ofSeconds(1)).state());
            assertTrue(store.find(101,job).isEmpty());
        }
        assertEquals(0,count(factory,"torrent_native_parse_result"));
    }

    @Test void verifiedPublicMetainfoSurvivesRestartAndRejectsForeignOwner() throws Exception {
        var factory=factory();
        byte[] piece=java.security.MessageDigest.getInstance("SHA-1")
                .digest("abcd".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        byte[] prefix="d4:infod6:lengthi4e4:name8:test.bin12:piece lengthi4e6:pieces20:"
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] raw=new byte[prefix.length+piece.length+2];
        System.arraycopy(prefix,0,raw,0,prefix.length);
        System.arraycopy(piece,0,raw,prefix.length,piece.length);
        raw[raw.length-2]='e';raw[raw.length-1]='e';
        var info=new cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser().parse(raw);
        UUID job=UUID.randomUUID();
        try(var session=factory.openSession(true)) {
            var store=new SqlNativeMetadataResultStore(
                    session.getMapper(TorrentNativeParseResultMapper.class),session.getMapper(TorrentNativeTaskMapper.class),new ObjectMapper());
            store.saveVerified(101,job,info,raw);
        }
        try(var session=factory.openSession(true)) {
            var store=new SqlNativeMetadataResultStore(
                    session.getMapper(TorrentNativeParseResultMapper.class),session.getMapper(TorrentNativeTaskMapper.class),new ObjectMapper());
            assertTrue(store.findVerified(202,job).isEmpty());
            assertArrayEquals(raw,store.findVerified(101,job).orElseThrow());
            try(var restored=new NativeMetadataJobManager((magnet,peer,port)->{
                throw new AssertionError("No network after restart");
            },store,1,1,1,Duration.ofSeconds(2))) {
                assertArrayEquals(raw,restored.verifiedTorrentBytes(101,job));
                assertThrows(NoSuchElementException.class,
                        ()->restored.status(202,job));
            }
        }
    }

    @Test void exactUserAndUuidIsolationSurvivesIndependentSqlSession() throws Exception {
        var factory=factory();
        UUID job=UUID.randomUUID();
        try(var session=factory.openSession(true)) {
            var store=new SqlNativeMetadataResultStore(
                    session.getMapper(TorrentNativeParseResultMapper.class),session.getMapper(TorrentNativeTaskMapper.class),new ObjectMapper());
            store.save(101,job,info());
            assertTrue(store.find(202,job).isEmpty());
        }
        try(var session=factory.openSession(true)) {
            var store=new SqlNativeMetadataResultStore(
                    session.getMapper(TorrentNativeParseResultMapper.class),session.getMapper(TorrentNativeTaskMapper.class),new ObjectMapper());
            var value=store.find(101,job).orElseThrow();
            assertEquals("demo",value.name());
            assertEquals(10,value.totalBytes());
            assertEquals(2,value.files().size());
            assertEquals("documents/b.txt",value.files().get(1).path());
            assertTrue(store.find(101,UUID.randomUUID()).isEmpty());
            assertThrows(Exception.class,()->store.save(101,job,info()));
            // A second user may have an independent Job UUID with the same InfoHash.
            UUID other=UUID.randomUUID();
            store.save(202,other,info());
            assertEquals(info().infoHash(),store.find(202,other).orElseThrow().infoHash());
        }
    }

    @Test void newManagerInstanceCanReadCompletedMetadataButNotInflightJobs() throws Exception {
        var factory=factory();
        UUID completed;
        UUID cancelled;
        try(var session=factory.openSession(true)) {
            var store=new SqlNativeMetadataResultStore(
                    session.getMapper(TorrentNativeParseResultMapper.class),session.getMapper(TorrentNativeTaskMapper.class),new ObjectMapper());
            try(var mgr=new NativeMetadataJobManager((magnet,peerId,port)->
                    new MetadataResolver.Resolution(info(),1,1),
                    store,1,2,2,Duration.ofSeconds(2))) {
                completed=mgr.submit(501,"magnet",new byte[20],6881);
                assertEquals(NativeMetadataJobManager.State.SUCCEEDED,
                        mgr.await(501,completed,Duration.ofSeconds(1)).state());
                cancelled=mgr.submit(501,"cancelled",new byte[20],6881);
                // This job may finish immediately; only completed results are durably restored.
            }
        }
        try(var session=factory.openSession(true)) {
            var store=new SqlNativeMetadataResultStore(
                    session.getMapper(TorrentNativeParseResultMapper.class),session.getMapper(TorrentNativeTaskMapper.class),new ObjectMapper());
            try(var restored=new NativeMetadataJobManager((magnet,peerId,port)->
                    {throw new AssertionError("No network needed for saved metadata");},
                    store,1,2,2,Duration.ofSeconds(2))) {
                assertEquals(NativeMetadataJobManager.State.SUCCEEDED,
                        restored.status(501,completed).state());
                assertEquals(2,restored.files(501,completed,1,20).totalFiles());
                assertEquals(1,restored.files(501,completed,2,1).files().size());
                assertThrows(NoSuchElementException.class,()->restored.status(502,completed));
                assertThrows(NoSuchElementException.class,()->restored.files(502,completed,1,20));
                assertThrows(NoSuchElementException.class,()->restored.cancel(502,completed));
            }
        }
    }
}
