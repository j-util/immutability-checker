package io.github.jutil.immutability.integration;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Properties;
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
    private static final String ANNOTATION_SOURCE =
            "io/github/jutil/immutability/Immutable.java";
    private static final String PROCESSOR_CLASS =
            "io/github/jutil/immutability/internal/processor/ImmutableProcessor.class";
    private static final String PROCESSOR_SOURCE =
            "io/github/jutil/immutability/internal/processor/ImmutableProcessor.java";
    private static final String SERVICE_FILE =
            "META-INF/services/javax.annotation.processing.Processor";
    private static final String PROCESSOR_PROVIDER =
            "io.github.jutil.immutability.internal.processor.ImmutableProcessor";

    @Test
    void packagedArtifactsHaveExpectedBoundariesAndDiscoverProcessor() throws Exception {
        Path annotationApiJar = artifactPath("annotation.api.jar");
        Path annotationApiSourcesJar = artifactPath("annotation.api.sources.jar");
        Path annotationApiJavadocJar = artifactPath("annotation.api.javadoc.jar");
        Path processorJar = artifactPath("processor.jar");
        Path processorSourcesJar = artifactPath("processor.sources.jar");
        Path processorJavadocJar = artifactPath("processor.javadoc.jar");

        String annotationApiVersion;
        try (JarFile jar = new JarFile(annotationApiJar.toFile())) {
            assertNotNull(jar.getJarEntry(ANNOTATION_CLASS));
            assertEquals(1, packagedClassCount(jar));
            assertFalse(hasEntry(jar, PROCESSOR_CLASS));
            assertFalse(hasEntry(jar, SERVICE_FILE));
            assertNotNull(jar.getJarEntry("META-INF/LICENSE"));
            assertEquals(52, classMajorVersion(jar, ANNOTATION_CLASS));
            assertAllPackagedClassesTargetJava8(jar);
            assertNoPolicyFiles(jar);
            Attributes attributes = jar.getManifest().getMainAttributes();
            assertEquals("io.github.jutil.immutability",
                    attributes.getValue("Automatic-Module-Name"));
            annotationApiVersion = artifactVersion(jar, "immutability-checker");
        }

        try (JarFile jar = new JarFile(processorJar.toFile())) {
            assertFalse(hasEntry(jar, ANNOTATION_CLASS));
            assertNotNull(jar.getJarEntry(PROCESSOR_CLASS));
            assertNotNull(jar.getJarEntry(SERVICE_FILE));
            assertNotNull(jar.getJarEntry("META-INF/LICENSE"));
            assertEquals(52, classMajorVersion(jar, PROCESSOR_CLASS));
            assertAllPackagedClassesTargetJava8(jar);
            assertEquals(PROCESSOR_PROVIDER, readEntry(jar, SERVICE_FILE).trim());
            assertNoPolicyFiles(jar);
            Attributes attributes = jar.getManifest().getMainAttributes();
            assertEquals("io.github.jutil.immutability.processor",
                    attributes.getValue("Automatic-Module-Name"));
            assertEquals(annotationApiVersion,
                    artifactVersion(jar, "immutability-checker-processor"));
        }

        assertSourceJarBoundary(annotationApiSourcesJar, true);
        assertSourceJarBoundary(processorSourcesJar, false);
        assertJavadocJarBoundary(annotationApiJavadocJar, true);
        assertJavadocJarBoundary(processorJavadocJar, false);

        Compilation apiOnly = compileWithProcessorPath(
                annotationApiJar,
                annotationApiJar,
                "fixture.ApiOnly",
                "package fixture;\n"
                        + "import io.github.jutil.immutability.Immutable;\n"
                        + "@Immutable final class ApiOnly {\n"
                        + "  private int value; void mutate() { value++; }\n"
                        + "}\n");
        assertTrue(apiOnly.successful,
                "The annotation API alone must not discover a processor: " + apiOnly.diagnostics);

        Compilation passing = compileWithProcessorPath(
                annotationApiJar,
                processorJar,
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

        Compilation staticPassing = compileWithProcessorPath(
                annotationApiJar,
                processorJar,
                "fixture.StaticPassing",
                "package fixture;\n"
                        + "import io.github.jutil.immutability.Immutable;\n"
                        + "@Immutable final class StaticPassing {\n"
                        + "  private static int version; static { version = 1; }\n"
                        + "}\n");
        assertTrue(staticPassing.successful, staticPassing.diagnostics);

        Compilation collectionPassing = compileWithProcessorPath(
                annotationApiJar,
                processorJar,
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

        Compilation failing = compileWithProcessorPath(
                annotationApiJar,
                processorJar,
                "fixture.Failing",
                "package fixture;\n"
                        + "import io.github.jutil.immutability.Immutable;\n"
                        + "@Immutable final class Failing {\n"
                        + "  private int value; void mutate() { value++; }\n"
                        + "}\n");
        assertFalse(failing.successful, "Expected packaged processor to reject fixture");
        assertTrue(failing.diagnostics.contains("[IC006]"), failing.diagnostics);
        assertTrue(failing.diagnostics.contains("Failing.value"), failing.diagnostics);

        Compilation staticFailing = compileWithProcessorPath(
                annotationApiJar,
                processorJar,
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

        Compilation collectionFailing = compileWithProcessorPath(
                annotationApiJar,
                processorJar,
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

        Compilation local = compileWithProcessorPath(
                annotationApiJar,
                processorJar,
                "fixture.LocalUse",
                "package fixture;\n"
                        + "import io.github.jutil.immutability.Immutable;\n"
                        + "final class LocalUse { void create() { @Immutable class Local {} } }\n");
        assertFalse(local.successful, "Expected packaged processor to reject local annotated type");
        assertTrue(local.diagnostics.contains("[IC002]"), local.diagnostics);
    }

    private static Path artifactPath(String property) {
        Path path = Paths.get(System.getProperty(property));
        assertTrue(Files.isRegularFile(path), "Packaged artifact not found: " + path);
        return path;
    }

    private static void assertSourceJarBoundary(Path path, boolean annotationApi)
            throws IOException {
        try (JarFile jar = new JarFile(path.toFile())) {
            assertEquals(annotationApi, hasEntry(jar, ANNOTATION_SOURCE));
            assertEquals(!annotationApi, hasEntry(jar, PROCESSOR_SOURCE));
            assertEquals(!annotationApi, hasEntry(jar, SERVICE_FILE));
            assertNotNull(jar.getJarEntry("META-INF/LICENSE"));
            assertNoPolicyFiles(jar);
        }
    }

    private static void assertJavadocJarBoundary(Path path, boolean annotationApi)
            throws IOException {
        try (JarFile jar = new JarFile(path.toFile())) {
            assertEquals(annotationApi,
                    hasEntry(jar, "io/github/jutil/immutability/Immutable.html"));
            assertEquals(!annotationApi,
                    hasEntry(jar,
                            "io/github/jutil/immutability/internal/processor/ImmutableProcessor.html"));
            assertNoPolicyFiles(jar);
        }
    }

    private static boolean hasEntry(JarFile jar, String entryName) {
        return jar.getJarEntry(entryName) != null;
    }

    private static int packagedClassCount(JarFile jar) {
        int count = 0;
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
                count++;
            }
        }
        return count;
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

    private static void assertNoPolicyFiles(JarFile jar) {
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            assertFalse(name.equals("AGENTS.md") || name.endsWith("/AGENTS.md"), name);
            assertFalse(name.equals("PROJECT_INVARIANTS.md")
                    || name.endsWith("/PROJECT_INVARIANTS.md"), name);
        }
    }

    private static String artifactVersion(JarFile jar, String artifactId) throws IOException {
        String entryName = "META-INF/maven/io.github.j-util/" + artifactId + "/pom.properties";
        JarEntry entry = jar.getJarEntry(entryName);
        assertNotNull(entry, entryName);
        Properties properties = new Properties();
        try (InputStream input = jar.getInputStream(entry)) {
            properties.load(input);
        }
        return properties.getProperty("version");
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

    private static Compilation compileWithProcessorPath(
            Path annotationApiJar,
            Path processorPathJar,
            String className,
            String source) throws IOException, InterruptedException {
        Path output = Files.createTempDirectory("immutability-checker-artifact-it-");
        try {
            Path sourceFile = output.resolve("src")
                    .resolve(className.replace('.', '/') + ".java");
            Path classes = output.resolve("classes");
            Files.createDirectories(sourceFile.getParent());
            Files.createDirectories(classes);
            Files.write(sourceFile, source.getBytes(StandardCharsets.UTF_8));

            Process process = new ProcessBuilder(
                    javacExecutable().toString(),
                    "-classpath", annotationApiJar.toString(),
                    "-processorpath", processorPathJar.toString(),
                    "-source", "8",
                    "-target", "8",
                    "-Xlint:-options",
                    "-d", classes.toString(),
                    sourceFile.toString())
                    .redirectErrorStream(true)
                    .start();
            String diagnostics;
            try (InputStream input = process.getInputStream()) {
                diagnostics = readProcessOutput(input);
            }
            return new Compilation(process.waitFor() == 0, diagnostics);
        } finally {
            deleteRecursively(output);
        }
    }

    private static Path javacExecutable() {
        String executable = System.getProperty("os.name").startsWith("Windows")
                ? "javac.exe"
                : "javac";
        Path javac = Paths.get(System.getProperty("java.home"), "bin", executable);
        if (!Files.isRegularFile(javac)) {
            javac = Paths.get(System.getProperty("java.home"), "..", "bin", executable)
                    .normalize();
        }
        assertTrue(Files.isRegularFile(javac), "Integration test requires a JDK: " + javac);
        return javac;
    }

    private static String readProcessOutput(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        for (int count = input.read(buffer); count >= 0; count = input.read(buffer)) {
            output.write(buffer, 0, count);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
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
