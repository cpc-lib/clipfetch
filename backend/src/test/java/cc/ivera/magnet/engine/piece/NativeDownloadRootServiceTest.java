package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.domain.NativeDownloadRootMapper;
import cc.ivera.magnet.engine.config.NativeTorrentProperties;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Owner-scoped download root selection: only server-approved directories may be
 * saved, browsing never exposes files, and a stale selection falls back safely.
 */
class NativeDownloadRootServiceTest {
    @TempDir Path work;

    private record Fixture(NativeDownloadRootService service,NativeTorrentProperties settings,
                           Path defaultRoot,Path media) {}

    private Fixture fixture() throws Exception {
        String jdbc="jdbc:sqlite:"+work.resolve("roots.sqlite").toAbsolutePath();
        String ddl=Files.readString(Path.of("src/main/resources/db/migration/sqlite/V22__native_download_root.sql"))
                .lines().filter(line -> !line.stripLeading().startsWith("--"))
                .collect(java.util.stream.Collectors.joining("\n"));
        try(var connection=DriverManager.getConnection(jdbc)) {
            for(String statement:ddl.split(";")) if(!statement.isBlank())
                try(var cmd=connection.createStatement()) {cmd.execute(statement);}
        }
        var dataSource=new UnpooledDataSource("org.sqlite.JDBC",jdbc,null,null);
        var configuration=new org.apache.ibatis.session.Configuration();
        configuration.setEnvironment(new Environment("download-roots",
                new SpringManagedTransactionFactory(),dataSource));
        try(var stream=getClass().getClassLoader()
                .getResourceAsStream("mapper/NativeDownloadRootMapper.xml")) {
            assertNotNull(stream);
            new XMLMapperBuilder(stream,configuration,"mapper/NativeDownloadRootMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        var mapper=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration))
                .getMapper(NativeDownloadRootMapper.class);
        var settings=new NativeTorrentProperties();
        Path defaultRoot=work.resolve("storage");
        Path media=work.resolve("media");
        Files.createDirectories(media.resolve("alpha"));
        Files.createDirectories(media.resolve("beta"));
        Files.writeString(media.resolve("ignore.txt"),"not a directory");
        settings.setStorageRoot(defaultRoot.toString());
        settings.setDownloadRootBases(List.of(media.toString()));
        return new Fixture(new NativeDownloadRootService(mapper,settings),settings,defaultRoot,media);
    }

    @Test void browsesApprovedDirectoriesAndKeepsSelectionsOwnerScoped() throws Exception {
        var fx=fixture();
        var rootLevel=fx.service().browse(1,null);
        assertEquals(List.of("media"),rootLevel.entries().stream()
                .map(NativeDownloadRootService.Entry::name).toList());
        assertNull(rootLevel.path());

        var listing=fx.service().browse(1,fx.media().toString());
        assertEquals(List.of("alpha","beta"),listing.entries().stream()
                .map(NativeDownloadRootService.Entry::name).toList());
        assertNull(listing.parent());

        var inside=fx.service().browse(1,fx.media().resolve("alpha").toString());
        assertEquals(0,inside.entries().size());
        assertEquals(fx.media().toString(),inside.parent());

        assertEquals(fx.media().resolve("alpha").toString(),
                fx.service().save(1,fx.media().resolve("alpha").toString()).rootPath());
        assertEquals(fx.media().resolve("beta").toString(),
                fx.service().save(2,fx.media().resolve("beta").toString()).rootPath());
        assertEquals(fx.media().resolve("alpha").toString(),
                fx.service().selectedRoot(1).orElseThrow().toString());
        assertEquals(fx.media().resolve("beta").toString(),
                fx.service().selectedRoot(2).orElseThrow().toString());

        var current=fx.service().current(1);
        assertEquals(fx.media().resolve("alpha").toString(),current.rootPath());
        assertEquals(fx.defaultRoot().toString(),current.defaultRootPath());
        assertEquals(List.of(fx.defaultRoot().toString(),fx.media().toString()),current.bases());

        fx.service().clear(1);
        assertTrue(fx.service().selectedRoot(1).isEmpty());
        assertEquals(fx.media().resolve("beta").toString(),
                fx.service().selectedRoot(2).orElseThrow().toString());
        // The logical delete writes deleted = id, so re-selecting the same folder is allowed.
        assertEquals(fx.media().resolve("alpha").toString(),
                fx.service().save(1,fx.media().resolve("alpha").toString()).rootPath());
    }

    @Test void rejectsOutsideBasesFilesMissingFoldersAndSymlinks() throws Exception {
        var fx=fixture();
        Path outside=Files.createDirectories(work.resolve("outside"));
        assertThrows(SecurityException.class,()->fx.service().save(1,outside.toString()));
        assertThrows(SecurityException.class,
                ()->fx.service().browse(1,outside.toString()));
        assertThrows(IllegalArgumentException.class,
                ()->fx.service().save(1,fx.media().resolve("ignore.txt").toString()));
        assertThrows(IllegalArgumentException.class,
                ()->fx.service().save(1,fx.media().resolve("missing").toString()));
        assertThrows(IllegalArgumentException.class,()->fx.service().save(1,"  "));
        assertTrue(fx.service().selectedRoot(1).isEmpty());

        Path link=fx.media().resolve("link");
        try {
            Files.createSymbolicLink(link,outside);
        }catch(IOException | UnsupportedOperationException | SecurityException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,"Symbolic links are unavailable here");
        }
        assertThrows(SecurityException.class,()->fx.service().save(1,link.toString()));
        assertFalse(fx.service().browse(1,fx.media().toString()).entries().stream()
                .anyMatch(entry->entry.name().equals("link")));
    }

    @Test void internalDefaultStorageIsNeverBrowsableOrSelectableByOtherUsers() throws Exception {
        var fx=fixture();
        Path privateTask=Files.createDirectories(fx.defaultRoot().resolve("91")
                .resolve(java.util.UUID.randomUUID().toString()));
        assertThrows(SecurityException.class,
                ()->fx.service().browse(92,fx.defaultRoot().toString()));
        assertThrows(SecurityException.class,
                ()->fx.service().browse(92,privateTask.toString()));
        assertThrows(SecurityException.class,
                ()->fx.service().save(92,fx.defaultRoot().toString()));
        assertThrows(SecurityException.class,
                ()->fx.service().save(92,privateTask.toString()));
        assertFalse(fx.service().browse(92,null).entries().stream()
                .anyMatch(e->e.path().startsWith(fx.defaultRoot().toString())));
    }

    @Test void overlappingApprovedBaseMustHideTheInternalDefaultStorageTree() throws Exception {
        var fx=fixture();
        Path parent=fx.defaultRoot().getParent();
        Files.createDirectories(fx.defaultRoot().resolve("24"));
        fx.settings().setDownloadRootBases(List.of(parent.toString(),fx.defaultRoot().toString()));
        assertThrows(SecurityException.class,
                ()->fx.service().browse(25,fx.defaultRoot().toString()));
        assertThrows(SecurityException.class,
                ()->fx.service().save(25,fx.defaultRoot().resolve("24").toString()));
        assertFalse(fx.service().browse(25,parent.toString()).entries().stream()
                .anyMatch(e->e.path().equals(fx.defaultRoot().toString())));
        assertFalse(fx.service().browse(25,null).entries().stream()
                .anyMatch(e->e.path().equals(fx.defaultRoot().toString())));
        assertEquals(parent.toString(),fx.service().save(25,parent.toString()).rootPath());
    }

    @Test void neverOffersOsManagedDirectoriesEvenWhenWidened() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(File.separatorChar=='\\',
                "Windows-only sensitive path check");
        Path windows=Path.of("C:\\Windows");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(windows),
                "Windows directory is unavailable");
        var fx=fixture();
        fx.settings().setDownloadRootBases(List.of(windows.toString()));
        assertThrows(SecurityException.class,()->fx.service().save(1,windows.toString()));
        assertTrue(fx.service().selectedRoot(1).isEmpty());
    }

    @Test void invalidatesStoredSelectionWhenTheFolderDisappears() throws Exception {
        var fx=fixture();
        Path alpha=fx.media().resolve("alpha");
        fx.service().save(1,alpha.toString());
        assertEquals(alpha.toString(),fx.service().selectedRoot(1).orElseThrow().toString());
        Files.delete(alpha);
        assertTrue(fx.service().selectedRoot(1).isEmpty(),
                "A vanished folder must fall back to the default root");
        assertNull(fx.service().current(1).rootPath());
    }

    @Test void taskCreationHonoursTheSelectedRootAndStillValidatesPersistedPaths() throws Exception {
        var fx=fixture();
        Path selected=Files.createDirectories(fx.media().resolve("alpha"));
        fx.service().save(1,selected.toString());
        Path base=fx.service().selectedRoot(1).orElseThrow();
        var taskId=java.util.UUID.randomUUID();
        Path resolved=NativeTaskStoragePaths.resolve(fx.settings(),1,taskId,base);
        assertTrue(resolved.startsWith(selected));
        assertEquals("1",resolved.getParent().getFileName().toString());
        assertEquals(taskId.toString(),resolved.getFileName().toString());
        assertEquals(resolved,NativeTaskStoragePaths.assertPersisted(
                fx.settings(),1,taskId,resolved.toString()));

        // A path outside every approved base can never be accepted as a task root.
        Path outside=Files.createDirectories(work.resolve("outside-root"));
        assertThrows(SecurityException.class,
                ()->NativeTaskStoragePaths.resolve(fx.settings(),1,taskId,outside));
        assertThrows(SecurityException.class,()->NativeTaskStoragePaths.assertPersisted(
                fx.settings(),1,taskId,outside.resolve(taskId.toString()).toString()));
        assertThrows(SecurityException.class,()->NativeTaskStoragePaths.assertPersisted(
                fx.settings(),2,taskId,resolved.toString()));
    }
}
