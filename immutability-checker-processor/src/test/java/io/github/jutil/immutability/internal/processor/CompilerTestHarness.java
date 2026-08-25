package io.github.jutil.immutability.internal.processor;

import javax.lang.model.SourceVersion;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class CompilerTestHarness {

    CompilationResult compile(String className, String source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("Tests require a JDK, not a JRE");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(
                diagnostics, Locale.ROOT, null);
        Path output = null;
        try {
            JavaFileObject sourceFile = new SourceFile(className, source);
            List<String> options = new ArrayList<String>();
            if (SourceVersion.latestSupported() == SourceVersion.RELEASE_8) {
                output = Files.createTempDirectory("immutability-checker-unit-");
                // javac 8 otherwise generates and clears one top-level tree before
                // ANALYZE completes for later declarations in the same source.
                options.addAll(Arrays.asList(
                        "-XDcompilePolicy=simple", "-d", output.toString()));
            } else {
                options.add("-proc:only");
            }
            options.addAll(Arrays.asList(
                    "-classpath", System.getProperty("java.class.path")));
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    fileManager,
                    diagnostics,
                    options,
                    null,
                    Collections.singletonList(sourceFile));
            task.setProcessors(Collections.singletonList(new ImmutableProcessor()));
            boolean successful = Boolean.TRUE.equals(task.call());
            return new CompilationResult(successful, normalizeErrors(diagnostics));
        } catch (IOException failure) {
            throw new IllegalStateException("Could not create compiler test output", failure);
        } finally {
            try {
                fileManager.close();
            } catch (IOException closeFailure) {
                throw new IllegalStateException("Could not close compiler file manager", closeFailure);
            }
            if (output != null) {
                deleteRecursively(output);
            }
        }
    }

    private static void deleteRecursively(Path directory) {
        try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Collections.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException failure) {
                    throw new DeleteFailure(failure);
                }
            });
        } catch (IOException failure) {
            throw new IllegalStateException("Could not delete compiler test output", failure);
        } catch (DeleteFailure failure) {
            throw new IllegalStateException(
                    "Could not delete compiler test output", failure.getCause());
        }
    }

    private static List<String> normalizeErrors(
            DiagnosticCollector<JavaFileObject> diagnostics) {
        List<String> errors = new ArrayList<String>();
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                errors.add(diagnostic.getMessage(Locale.ROOT)
                        .replace('\r', ' ')
                        .replace('\n', ' ')
                        .replaceAll("\\s+", " ")
                        .trim());
            }
        }
        return errors;
    }

    static final class CompilationResult {
        private final boolean successful;
        private final List<String> errors;

        private CompilationResult(boolean successful, List<String> errors) {
            this.successful = successful;
            this.errors = Collections.unmodifiableList(new ArrayList<String>(errors));
        }

        boolean isSuccessful() {
            return successful;
        }

        List<String> getErrors() {
            return errors;
        }

        String joinedErrors() {
            return String.join("\n", errors);
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
    }
}
