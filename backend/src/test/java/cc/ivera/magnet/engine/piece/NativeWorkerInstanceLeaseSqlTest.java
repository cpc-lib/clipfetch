package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.domain.TorrentNativeWorkerInstanceMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class NativeWorkerInstanceLeaseSqlTest {
    @TempDir Path dir;
    @Test void ownershipTokenExpiryAndStoppedLeaseAreFenced() throws Exception {
        String jdbc="jdbc:sqlite:"+dir.resolve("lease.sqlite").toAbsolutePath();
        String ddl=Files.readString(Path.of("src/main/resources/db/migration/sqlite/V19__native_worker_instance_lease.sql"))
            .lines().filter(s->!s.stripLeading().startsWith("--"))
            .collect(java.util.stream.Collectors.joining("\n"));
        try(var conn=DriverManager.getConnection(jdbc);var stmt=conn.createStatement()){
            for(var sql:ddl.split(";"))if(!sql.isBlank())stmt.execute(sql);
        }
        var config=new org.apache.ibatis.session.Configuration();
        config.setEnvironment(new Environment("sqlite-instance",new JdbcTransactionFactory(),
            new UnpooledDataSource("org.sqlite.JDBC",jdbc,null,null)));
        try(var xml=getClass().getClassLoader().getResourceAsStream("mapper/TorrentNativeWorkerInstanceMapper.xml")){
            assertNotNull(xml);
            new XMLMapperBuilder(xml,config,"mapper/TorrentNativeWorkerInstanceMapper.xml",
                config.getSqlFragments()).parse();
        }
        var factory=new SqlSessionFactoryBuilder().build(config);
        var now=LocalDateTime.of(2026,10,15,12,0);
        String id=UUID.randomUUID().toString(),token=UUID.randomUUID().toString();
        try(var session=factory.openSession(true)){
            var mapper=session.getMapper(TorrentNativeWorkerInstanceMapper.class);
            assertEquals(1,mapper.register(id,token,now,now.plusSeconds(30)));
            assertEquals(1,mapper.isLive(id,now.plusSeconds(10)));
            assertEquals(0,mapper.renew(id,"wrong",now.plusSeconds(10),now.plusMinutes(1)));
            assertEquals(1,mapper.renew(id,token,now.plusSeconds(10),now.plusMinutes(1)));
            assertEquals(0,mapper.renew(id,token,now.plusMinutes(2),now.plusMinutes(3)));
            assertEquals(0,mapper.isLive(id,now.plusMinutes(2)));
            assertEquals(0,mapper.stop(id,"wrong"));
            assertEquals(1,mapper.stop(id,token));
            assertEquals(0,mapper.isLive(id,now.plusSeconds(10)));
        }
    }
}
