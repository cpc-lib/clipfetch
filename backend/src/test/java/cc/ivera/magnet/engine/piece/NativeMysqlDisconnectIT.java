package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.domain.TorrentNativeWorkerInstanceMapper;
import cc.ivera.magnet.engine.config.NativeTorrentProperties;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class NativeMysqlDisconnectIT {
    private static final String URL="jdbc:mysql://127.0.0.1:33316/clipfetch_jvm_it?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=1500&socketTimeout=1500";
    private static void docker(String operation) throws Exception {
        Process p=new ProcessBuilder("docker",operation,"clipfetch-mysql-clock-it")
                .redirectErrorStream(true).start();
        assertTrue(p.waitFor(10,TimeUnit.SECONDS));
        assertEquals(0,p.exitValue(),new String(p.getInputStream().readAllBytes()));
    }

    @Test
    @EnabledIfSystemProperty(named="clipfetch.mysql.disconnect",matches="true")
    void realDatabaseDisconnectFailsClosedAndReconnectNeverResurrectsOldLease() throws Exception {
        String password=System.getenv("CLIPFETCH_MYSQL_IT_PASSWORD");
        assertNotNull(password);
        var config=new org.apache.ibatis.session.Configuration();
        config.setEnvironment(new Environment("mysql-disconnect",new JdbcTransactionFactory(),
                new UnpooledDataSource("com.mysql.cj.jdbc.Driver",URL,"root",password)));
        String resource="mapper/TorrentNativeWorkerInstanceMapper.xml";
        try(var xml=getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(xml);
            new XMLMapperBuilder(xml,config,resource,config.getSqlFragments()).parse();
        }
        var factory=new SqlSessionFactoryBuilder().build(config);
        try(var session=factory.openSession(true)) {
            var mapper=session.getMapper(TorrentNativeWorkerInstanceMapper.class);
            LocalDateTime dbNow=mapper.databaseNow();
            assertNotNull(dbNow);
            var props=new NativeTorrentProperties();
            props.setInstanceHeartbeatEnabled(true);
            var heartbeat=new NativeWorkerInstanceHeartbeat(mapper,props);
            var lost=new java.util.concurrent.atomic.AtomicInteger();
            heartbeat.setOnLeaseLost(lost::incrementAndGet);
            heartbeat.register();
            assertTrue(heartbeat.isHealthy());
            try{
                docker("pause");
                assertFalse(heartbeat.isHealthy(),"Database outage must fence current worker");
                assertEquals(1,lost.get());
            }finally{
                docker("unpause");
            }
            assertFalse(heartbeat.isHealthy(),"Recovered network must never revive old instance");
            heartbeat.heartbeat();
            assertEquals(1,lost.get());
            try(var reopened=factory.openSession(true)) {
                var recovered=reopened.getMapper(TorrentNativeWorkerInstanceMapper.class);
                assertEquals(1,recovered.isLive(heartbeat.instanceId(),recovered.databaseNow()),
                        "Recovered database may see live lease, but fenced JVM must not revive");
            }
            heartbeat.shutdown();
        }
    }
}
