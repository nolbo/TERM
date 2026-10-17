package dev.terminal.avf.compat;

import static org.junit.Assert.*;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Load the production bytecode against single-ABI frameworks, reproducing real linkage errors. */
public class SharedPathCompatTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private enum Api { NINE, TEN, BOTH, MISSING, PRIVATE_NINE, REJECTED_NINE }

    private static final String PATH = "/storage/emulated/0/Download/한글 폴더";
    private static final String SOCKET_PATH = "/data/user/0/dev.terminal.app/files/.virtiofs/v-test/s0";

    @Test public void android17UsesNineArgumentsWithEveryExportFieldIntact() throws Throwable {
        try (Fixture fixture = framework(Api.NINE)) { assertExport(fixture.create(true), "nine"); }
    }

    @Test public void olderAndroidUsesTenArgumentsAndAppDomainTrue() throws Throwable {
        try (Fixture fixture = framework(Api.TEN)) { assertExport(fixture.create(false), "ten"); }
    }

    @Test public void aNineArgumentBackportWorksOnAnOlderSdk() throws Throwable {
        try (Fixture fixture = framework(Api.NINE)) { assertExport(fixture.create(false), "nine"); }
    }

    @Test public void anOlderAbiWorksOnANewerSdk() throws Throwable {
        try (Fixture fixture = framework(Api.TEN)) { assertExport(fixture.create(true), "ten"); }
    }

    @Test public void bothAvailableUsesTheSdkPreference() throws Throwable {
        try (Fixture fixture = framework(Api.BOTH)) {
            assertExport(fixture.create(true), "nine");
            assertExport(fixture.create(false), "ten");
        }
    }

    @Test public void unsupportedAbiKeepsBothMissingConstructorErrors() throws Throwable {
        try (Fixture fixture = framework(Api.MISSING)) {
            NoSuchMethodError failure = assertThrows(NoSuchMethodError.class, () -> fixture.create(true));
            assertEquals(1, failure.getSuppressed().length);
            assertTrue(failure.getSuppressed()[0] instanceof NoSuchMethodError);
        }
    }

    @Test public void accessDenialDoesNotRetryAnotherConstructor() throws Throwable {
        try (Fixture fixture = framework(Api.PRIVATE_NINE)) {
            assertThrows(IllegalAccessError.class, () -> fixture.create(true));
            assertEquals(0, fixture.sharedPath.getField("legacyCalls").getInt(null));
        }
    }

    @Test public void aConstructorSecurityFailureDoesNotRetryAnotherConstructor() throws Throwable {
        try (Fixture fixture = framework(Api.REJECTED_NINE)) {
            SecurityException failure = assertThrows(SecurityException.class, () -> fixture.create(true));
            assertEquals("sharing denied", failure.getMessage());
            assertEquals(0, fixture.sharedPath.getField("legacyCalls").getInt(null));
        }
    }

    private static void assertExport(Object export, String abi) throws Exception {
        Class<?> type = export.getClass();
        assertEquals(PATH, type.getField("path").get(export));
        assertEquals(10123, type.getField("hostUid").getInt(export));
        assertEquals(10234, type.getField("hostGid").getInt(export));
        assertEquals(1000, type.getField("guestUid").getInt(export));
        assertEquals(1001, type.getField("guestGid").getInt(export));
        assertEquals(7, type.getField("mask").getInt(export));
        assertEquals("term-storage", type.getField("tag").get(export));
        assertEquals("s0", type.getField("socket").get(export));
        assertEquals(SOCKET_PATH, type.getField("socketPath").get(export));
        assertEquals(abi, type.getField("abi").get(export));
        if (abi.equals("ten")) assertTrue(type.getField("appDomain").getBoolean(export));
    }

    private Fixture framework(Api api) throws Exception {
        Path root = temporary.newFolder().toPath();
        Path source = root.resolve("android/system/virtualmachine/VirtualMachineCustomImageConfig.java");
        Files.createDirectories(source.getParent());
        String common = "String path, int hostUid, int hostGid, int guestUid, int guestGid, int mask, String tag, String socket, ";
        String assignments = "this.path=path; this.hostUid=hostUid; this.hostGid=hostGid; this.guestUid=guestUid; "
                + "this.guestGid=guestGid; this.mask=mask; this.tag=tag; this.socket=socket; this.socketPath=socketPath; ";
        String constructors = "";
        if (api != Api.TEN && api != Api.MISSING) {
            String access = api == Api.PRIVATE_NINE ? "private" : "public";
            String body = api == Api.REJECTED_NINE ? "throw new SecurityException(\"sharing denied\");"
                    : assignments + "this.abi=\"nine\";";
            constructors += access + " SharedPath(" + common + "String socketPath) {" + body + "}";
        }
        if (api != Api.NINE && api != Api.MISSING) {
            constructors += "public SharedPath(" + common + "boolean appDomain, String socketPath) {"
                    + assignments + "this.appDomain=appDomain; this.abi=\"ten\"; legacyCalls++;}";
        }
        Files.writeString(source, "package android.system.virtualmachine; public class VirtualMachineCustomImageConfig {"
                + "public static final class SharedPath {"
                + "public String path,tag,socket,socketPath,abi; public int hostUid,hostGid,guestUid,guestGid,mask;"
                + "public boolean appDomain; public static int legacyCalls;" + constructors + "}}", StandardCharsets.UTF_8);
        // CI uses a JDK. Only the synthetic framework is compiled; the adapter is production bytecode.
        Path javac = Path.of(System.getProperty("java.home"), "bin", "javac");
        Process compiler = new ProcessBuilder(javac.toString(), "-d", root.toString(), source.toString())
                .redirectErrorStream(true).start();
        try {
            assertTrue("Fixture javac timed out", compiler.waitFor(20, TimeUnit.SECONDS));
            String log = new String(compiler.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(log, 0, compiler.exitValue());
        } finally { compiler.destroyForcibly(); }
        for (String name : new String[] {"SharedPathCompat", "SharedPathCompat$Android17", "SharedPathCompat$Legacy"}) {
            String resource = "dev/terminal/avf/compat/" + name + ".class";
            Path target = root.resolve(resource);
            Files.createDirectories(target.getParent());
            try (InputStream bytes = getClass().getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(resource, bytes);
                Files.copy(bytes, target);
            }
        }
        return new Fixture(root);
    }

    private static final class Fixture implements AutoCloseable {
        final URLClassLoader loader;
        final Class<?> sharedPath;
        final Method create;

        Fixture(Path root) throws Exception {
            loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null);
            sharedPath = loader.loadClass("android.system.virtualmachine.VirtualMachineCustomImageConfig$SharedPath");
            create = loader.loadClass("dev.terminal.avf.compat.SharedPathCompat").getMethod("create",
                    boolean.class, String.class, int.class, int.class, int.class, int.class, int.class,
                    String.class, String.class, String.class);
        }

        Object create(boolean preferAndroid17) throws Throwable {
            try {
                return create.invoke(null, preferAndroid17, PATH, 10123, 10234, 1000, 1001, 7,
                        "term-storage", "s0", SOCKET_PATH);
            } catch (InvocationTargetException failure) { throw failure.getCause(); }
        }

        @Override public void close() throws Exception { loader.close(); }
    }
}
