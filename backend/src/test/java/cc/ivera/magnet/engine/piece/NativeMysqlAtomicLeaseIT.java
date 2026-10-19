package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.domain.TorrentNativeWorkerInstanceMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.sql.DriverManager;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class NativeMysqlAtomicLeaseIT {
    private static final String URL="jdbc:mysql://127.0.0.1:33316/clipfetch_jvm_it?useSSL=false&allowPublicKeyRetrieval=true";
    @Test
    @EnabledIfSystemProperty(named="clipfetch.mysql.atomicLease",matches="true")
    void mysqlUtcTimestampIsAtomicAndExpiredLeaseCannotRenew() throws Exception {
        String pass=System.getenv("CLIPFETCH_MYSQL_IT_PASSWORD");
        assertNotNull(pass,"CLIPFETCH_MYSQL_IT_PASSWORD required");
        var cfg=new org.apache.ibatis.session.Configuration();
        cfg.setEnvironment(new Environment("mysql-atomic",new JdbcTransactionFactory(),
                new UnpooledDataSource("com.mysql.cj.jdbc.Driver",URL,"root",pass)));
        String resource="mapper/TorrentNativeWorkerInstanceMapper.xml";
        try(var xml=getClass().getClassLoader().getResourceAsStream(resource)){
            assertNotNull(xml);
            new XMLMapperBuilder(xml,cfg,resource,cfg.getSqlFragments()).parse();
        }
        var factory=new SqlSessionFactoryBuilder().build(cfg);
        String id=UUID.randomUUID().toString(),token=UUID.randomUUID().toString();
        try(var session=factory.openSession(true)){
            var lease=session.getMapper(TorrentNativeWorkerInstanceMapper.class);
            assertEquals(1,lease.registerAtomic(id,token,true));
            assertEquals(1,lease.isLiveAtDatabaseTime(id,true));
            assertEquals(1,lease.renewAtomic(id,token,true));
            assertEquals(0,lease.renewAtomic(id,UUID.randomUUID().toString(),true),
                    "Wrong token must never extend another instance");
            try(var db=DriverManager.getConnection(URL,"root",pass);
                var query=db.prepareStatement("SELECT TIMESTAMPDIFF(SECOND,UTC_TIMESTAMP(6),lease_until) FROM torrent_native_worker_instance WHERE instance_id=?")) {
                query.setString(1,id);
                try(var r=query.executeQuery()){assertTrue(r.next());assertTrue(r.getInt(1)>=43);}
            }
            try(var db=DriverManager.getConnection(URL,"root",pass);
                var statement=db.prepareStatement("UPDATE torrent_native_worker_instance SET lease_until=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE instance_id=?")) {
                statement.setString(1,id);assertEquals(1,statement.executeUpdate());
            }
            assertEquals(1,lease.isExpiredAtDatabaseTime(id,true));
            assertEquals(0,lease.renewAtomic(id,token,true),"Expired owner cannot revive");
            assertEquals(0,lease.isLiveAtDatabaseTime(id,true));
        }
    }
}
