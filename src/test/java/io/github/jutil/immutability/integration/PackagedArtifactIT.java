package io.github.jutil.immutability.integration;

import org.junit.jupiter.api.Test;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackagedArtifactIT {

    private static final String ANNOTATION_CLASS =
            "io/github/jutil/immutability/Immutable.class";
    private static final String PROCESSOR_CLASS =
            "io/github/jutil/immutability/internal/processor/ImmutableProcessor.class";
    private static final String SERVICE_FILE =
            "META-INF/services/javax.annotation.processing.Processor";
    private static final String PROCESSOR_PROVIDER =
            "io.github.jutil.immutability.internal.processor.ImmutableProcessor";

    @Test
    void packagedJarHasExpectedBoundaryAndDiscoversProcessor() throws Exception {
        Path jarPath = Paths.get(System.getProperty("packaged.jar"));
        assertTrue(Files.isRegularFile(jarPath), "Packaged JAR not found: " + jarPath);

        try (JarFile jar = new JarFile(jarPath.toFile())) {
            assertNotNull(jar.getJarEntry(ANNOTATION_CLASS));
            assertNotNull(jar.getJarEntry(PROCESSOR_CLASS));
            assertNotNull(jar.getJarEntry(SERVICE_FILE));
            assertNotNull(jar.getJarEntry("META-INF/LICENSE"));
            assertEquals(52, classMajorVersion(jar, ANNOTATION_CLASS));
            assertEquals(52, classMajorVersion(jar, PROCESSOR_CLASS));
            assertAllPackagedClassesTargetJava8(jar);
            assertEquals(PROCESSOR_PROVIDER, readEntry(jar, SERVICE_FILE).trim());
            Attributes attributes = jar.getManifest().getMainAttributes();
            assertEquals("io.github.jutil.immutability",
                    attributes.getValue("Automatic-Module-Name"));
        }

        Compilation passing = compileWithDiscoveredProcessor(
                jarPath,
                "fixture.Passing",
                "package fixture;\n"
                        + "import io.github.jutil.immutability.Immutable;\n"
                        + "@Immutable final class Passing {\n"
                        + "  private final State state; Passing(State state) { this.state = state; }\n"
                        + "}\n"
                        + "final class State {\n"
                        + "  private String value; State(String value) { this.value = value; }\n"
                        + "}\n");
        assertTrue(passing.successful, passing.diagnostics);

        Compilation staticPassing = compileWithDiscoveredProcessor(
                jarPath,
                "fixture.StaticPassing",
                "package fixture;\n"
                        + "import io.github.jutil.immutability.Immutable;\n"
                        + "@Immutable final class StaticPassing {\n"
                        + "  private static int version; static { version = 1; }\n"
                        + "}\n");
        assertTrue(staticPassing.successful, staticPassing.diagnostics);

        Compilation collectionPassing = compileWithDiscoveredProcessor(
                jarPath,
                "fixture.CollectionPassing",
                "package fixture;\n"
                        + "import io.github.jutil.immutability.Immutable;\n"
                        + "import java.util.ArrayList;\n"
                        + "import java.util.List;\n"
                        + "@Immutable final class CollectionPassing {\n"
                        + "  private List<String> values = new ArrayList<>();\n"
                        + "  CollectionPassing(String value) { values.add(value); }\n"
                        + "  String get(int index) { return values.get(index); }\n"
                        + "}\n");
        assertTrue(collectionPassing.successful, collectionPassing.diagnostics);

        Compilation failing = compileWithDiscoveredProcessor(
                jarPath,
                "fixture.Failing",
                "package fixture;\n"
                        + "import io.github.jutil.immutability.Immutable;\n"
                        + "@Immutable final class Failing {\n"
                        + "  private int value; void mutate() { value++; }\n"
                        + "}\n");
        assertFalse(failing.successful, "Expected packaged processor to reject fixture");
        assertTrue(failing.diagnostics.contains("[IC006]"), failing.diagnostics);
        assertTrue(failing.diagnostics.contains("Failing.value"), failing.diagnostics);

        Compilation staticFailing = compileWithDiscoveredProcessor(
                jarPath,
                "fixture.StaticFailing",
                "package fixture;\n"
                        + "import io.github.jutil.immutability.Immutable;\n"
                        + "@Immutable final class StaticFailing {\n"
                        + "  private static int count; static void increment() { count++; }\n"
                        + "}\n");
        assertFalse(staticFailing.successful,
                "Expected packaged processor to reject static-state fixture");
        assertTrue(staticFailing.diagnostics.contains("[IC006]"), staticFailing.diagnostics);
        assertTrue(staticFailing.diagnostics.contains("StaticFailing.<static>.count"),
                staticFailing.diagnostics);
        assertTrue(staticFailing.diagnostics.contains("after class initialization"),
                staticFailing.diagnostics);

        Compilation collectionFailing = compileWithDiscoveredProcessor(
                jarPath,
                "fixture.CollectionFailing",
                "package fixture;\n"
                        + "import io.github.jutil.immutability.Immutable;\n"
                        + "import java.util.ArrayList;\n"
                        + "import java.util.List;\n"
                        + "@Immutable final class CollectionFailing {\n"
                        + "  private List<String> values = new ArrayList<>();\n"
                        + "  void add(String value) { values.add(value); }\n"
                        + "}\n");
        assertFalse(collectionFailing.successful,
                "Expected packaged processor to reject collection mutation fixture");
        assertTrue(collectionFailing.diagnostics.contains("[IC006]"),
                collectionFailing.diagnostics);
        assertTrue(collectionFailing.diagnostics.contains("CollectionFailing.values"),
                collectionFailing.diagnostics);
        assertTrue(collectionFailing.diagnostics.contains("after instance construction"),
                collectionFailing.diagnostics);

        Compilation local = compileWithDiscoveredProcessor(
                jarPath,
                "fixture.LocalUse",
                "package fixture;\n"
                        + "import io.github.jutil.immutability.Immutable;\n"
                        + "final class LocalUse { void create() { @Immutable class Local {} } }\n");
        assertFalse(local.successful, "Expected packaged processor to reject local annotated type");
        assertTrue(local.diagnostics.contains("[IC002]"), local.diagnostics);
    }

    private static int classMajorVersion(JarFile jar, String entryName) throws IOException {
        JarEntry entry = jar.getJarEntry(entryName);
        assertNotNull(entry, entryName);
        try (DataInputStream input = new DataInputStream(jar.getInputStream(entry))) {
            assertEquals(0xCAFEBABE, input.readInt());
            input.readUnsignedShort();
            return input.readUnsignedShort();
        }
    }

    private static void assertAllPackagedClassesTargetJava8(JarFile jar) throws IOException {
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
                assertEquals(52, classMajorVersion(jar, entry.getName()), entry.getName());
            }
        }
    }

    private static String readEntry(JarFile jar, String entryName) throws IOException {
        JarEntry entry = jar.getJarEntry(entryName);
        assertNotNull(entry, entryName);
        try (InputStream input = jar.getInputStream(entry)) {
            byte[] bytes = new byte[(int) entry.getSize()];
            int offset = 0;
            while (offset < bytes.length) {
                int count = input.read(bytes, offset, bytes.length - offset);
                if (count < 0) {
                    break;
                }
                offset += count;
            }
            return new String(bytes, 0, offset, StandardCharsets.UTF_8);
        }
    }

    private static Compilation compileWithDiscoveredProcessor(
            Path jarPath,
            String className,
            String source) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Integration test requires a JDK");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(
                diagnostics, Locale.ROOT, StandardCharsets.UTF_8);
        Path output = Files.createTempDirectory("immutability-checker-artifact-it-");
        try {
            JavaFileObject sourceFile = new SourceFile(className, source);
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    fileManager,
                    diagnostics,
                    Arrays.asList(
                            "-classpath", jarPath.toString(),
                            "-processorpath", jarPath.toString(),
                            "-source", "8",
                            "-target", "8",
                            "-Xlint:-options",
                            "-d", output.toString()),
                    null,
                    Collections.singletonList(sourceFile));
            boolean successful = Boolean.TRUE.equals(task.call());
            StringBuilder text = new StringBuilder();
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(diagnostic.getKind()).append(": ")
                        .append(diagnostic.getMessage(Locale.ROOT));
            }
            return new Compilation(successful, text.toString());
        } finally {
            fileManager.close();
            deleteRecursively(output);
        }
    }

    private static void deleteRecursively(Path directory) throws IOException {
        try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Collections.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException failure) {
                    throw new DeleteFailure(failure);
                }
            });
        } catch (DeleteFailure failure) {
            throw failure.getCause();
        }
    }

    private static final class Compilation {
        private final boolean successful;
        private final String diagnostics;

        private Compilation(boolean successful, String diagnostics) {
            this.successful = successful;
            this.diagnostics = diagnostics;
        }
    }

    private static final class SourceFile extends SimpleJavaFileObject {
        private final String source;

        private SourceFile(String className, String source) {
            super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension),
                    Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }

    private static final class DeleteFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private DeleteFailure(IOException cause) {
            super(cause);
        }

        @Override
        public synchronized IOException getCause() {
            return (IOException) super.getCause();
        }
    }
}
