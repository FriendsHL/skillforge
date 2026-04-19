package com.skillforge.server.code;

import com.skillforge.core.engine.hook.BuiltInMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Compiles a Java source string to bytecode in-memory using {@link javax.tools.JavaCompiler}.
 *
 * <p>Runs a source-level security pre-scan before compilation to reject code that attempts to
 * escape the sandbox (Runtime/ProcessBuilder/System.exit/net/reflection/Unsafe/ClassLoader).
 * Scanning is textual — defense in depth, not a replacement for runtime isolation.
 */
@Component
public class DynamicMethodCompiler {

    private static final Logger log = LoggerFactory.getLogger(DynamicMethodCompiler.class);

    private static final Pattern PACKAGE_PATTERN = Pattern.compile(
            "^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern CLASS_NAME_PATTERN = Pattern.compile(
            "\\b(?:public\\s+)?(?:final\\s+)?(?:abstract\\s+)?class\\s+(\\w+)");

    /**
     * Textual security patterns — reject source that matches any. Matching is lexical (not
     * semantic), so sophisticated callers could evade these by composing strings at runtime;
     * the platform must still rely on OS-level sandboxing for strong isolation.
     */
    private static final List<Pattern> FORBIDDEN_PATTERNS = List.of(
            Pattern.compile("\\bRuntime\\s*\\.\\s*getRuntime\\s*\\("),
            Pattern.compile("\\bRuntime\\s*\\.\\s*exec\\s*\\("),
            Pattern.compile("\\bProcessBuilder\\b"),
            Pattern.compile("\\bSystem\\s*\\.\\s*exit\\s*\\("),
            Pattern.compile("\\bjava\\s*\\.\\s*net\\s*\\."),
            Pattern.compile("\\bjava\\s*\\.\\s*lang\\s*\\.\\s*reflect\\s*\\."),
            Pattern.compile("\\bClass\\s*\\.\\s*forName\\s*\\("),
            Pattern.compile("\\.\\s*getDeclaredField\\s*\\("),
            Pattern.compile("\\.\\s*getDeclaredMethod\\s*\\("),
            Pattern.compile("\\.\\s*setAccessible\\s*\\("),
            Pattern.compile("\\bsun\\s*\\.\\s*misc\\s*\\.\\s*Unsafe\\b"),
            Pattern.compile("\\bjdk\\s*\\.\\s*internal\\s*\\.\\s*misc\\s*\\.\\s*Unsafe\\b"),
            Pattern.compile("\\bClassLoader\\b"),
            Pattern.compile("\\bjava\\s*\\.\\s*io\\s*\\."),
            Pattern.compile("\\bjava\\s*\\.\\s*nio\\s*\\."),
            Pattern.compile("\\bSystem\\s*\\.\\s*getenv\\s*\\("),
            Pattern.compile("\\bSystem\\s*\\.\\s*getProperties\\s*\\("),
            Pattern.compile("\\bSystem\\s*\\.\\s*getProperty\\s*\\(")
    );

    public CompilationResult compile(String sourceCode) {
        if (sourceCode == null || sourceCode.isBlank()) {
            return new CompilationResult(false, null, null, List.of("source code is empty"));
        }

        String forbidden = firstForbiddenMatch(sourceCode);
        if (forbidden != null) {
            log.warn("Rejecting dynamic Java compile: forbidden pattern matched: {}", forbidden);
            return new CompilationResult(false, null, null,
                    List.of("source contains a disallowed API reference"));
        }

        String simpleName = extractSimpleClassName(sourceCode);
        if (simpleName == null) {
            return new CompilationResult(false, null, null,
                    List.of("could not locate a top-level class declaration"));
        }
        String pkg = extractPackage(sourceCode);
        String fqcn = (pkg == null || pkg.isBlank()) ? simpleName : pkg + "." + simpleName;

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return new CompilationResult(false, null, null,
                    List.of("no system Java compiler available — ensure JDK (not JRE) is used"));
        }

        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("skillforge-compile-");
            Path sourceDir = workDir.resolve("src");
            Path classDir = workDir.resolve("classes");
            Files.createDirectories(sourceDir);
            Files.createDirectories(classDir);

            Path packageDir = (pkg == null || pkg.isBlank())
                    ? sourceDir
                    : sourceDir.resolve(pkg.replace('.', File.separatorChar));
            Files.createDirectories(packageDir);
            Path sourceFile = packageDir.resolve(simpleName + ".java");
            Files.writeString(sourceFile, sourceCode);

            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            try (StandardJavaFileManager fileManager =
                         compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {

                fileManager.setLocation(StandardLocation.CLASS_OUTPUT,
                        Collections.singletonList(classDir.toFile()));
                fileManager.setLocation(StandardLocation.CLASS_PATH, buildClasspath());

                Iterable<? extends JavaFileObject> units =
                        fileManager.getJavaFileObjectsFromFiles(
                                Collections.singletonList(sourceFile.toFile()));

                JavaCompiler.CompilationTask task = compiler.getTask(
                        null, fileManager, diagnostics,
                        Arrays.asList("-proc:none"),
                        null, units);

                boolean ok = Boolean.TRUE.equals(task.call());
                List<String> errors = new ArrayList<>();
                for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                    if (d.getKind() == Diagnostic.Kind.ERROR) {
                        errors.add(formatDiagnostic(d));
                    }
                }
                if (!ok) {
                    return new CompilationResult(false, null, fqcn, errors);
                }

                Path classFile = classDir.resolve(
                        (pkg == null || pkg.isBlank() ? "" : pkg.replace('.', File.separatorChar) + File.separator)
                                + simpleName + ".class");
                if (!Files.exists(classFile)) {
                    return new CompilationResult(false, null, fqcn,
                            List.of("compilation reported success but class file missing: " + classFile));
                }
                byte[] bytes = Files.readAllBytes(classFile);
                return new CompilationResult(true, bytes, fqcn, List.of());
            }
        } catch (IOException e) {
            log.warn("DynamicMethodCompiler IO failure: {}", e.toString());
            return new CompilationResult(false, null, fqcn, List.of("io error: " + e.getMessage()));
        } finally {
            if (workDir != null) {
                deleteRecursively(workDir);
            }
        }
    }

    private static String formatDiagnostic(Diagnostic<? extends JavaFileObject> d) {
        String msg = d.getMessage(Locale.ROOT);
        msg = msg.replaceAll("/tmp/skillforge-compile-[^/]*/src/", "");
        return String.format(Locale.ROOT, "line %d: %s", d.getLineNumber(), msg);
    }

    private static String firstForbiddenMatch(String src) {
        for (Pattern p : FORBIDDEN_PATTERNS) {
            if (p.matcher(src).find()) {
                return p.pattern();
            }
        }
        return null;
    }

    static String extractPackage(String src) {
        var matcher = PACKAGE_PATTERN.matcher(src);
        return matcher.find() ? matcher.group(1) : null;
    }

    static String extractSimpleClassName(String src) {
        var matcher = CLASS_NAME_PATTERN.matcher(src);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * Build a classpath that includes the core module (so generated classes can implement
     * {@link BuiltInMethod}) and whatever the host JVM exposes via {@code java.class.path}.
     */
    private static List<File> buildClasspath() {
        List<File> entries = new ArrayList<>();
        String jvmCp = System.getProperty("java.class.path", "");
        if (!jvmCp.isBlank()) {
            for (String e : jvmCp.split(File.pathSeparator)) {
                if (!e.isBlank()) {
                    entries.add(new File(e));
                }
            }
        }
        // BuiltInMethod lives in skillforge-core; resolve the jar / classes dir it was loaded from.
        File fromCore = codeSourceDir(BuiltInMethod.class);
        if (fromCore != null && !entries.contains(fromCore)) {
            entries.add(fromCore);
        }
        return entries;
    }

    private static File codeSourceDir(Class<?> c) {
        try {
            if (c.getProtectionDomain() == null || c.getProtectionDomain().getCodeSource() == null) {
                return null;
            }
            java.net.URL loc = c.getProtectionDomain().getCodeSource().getLocation();
            if (loc == null) return null;
            return new File(loc.toURI());
        } catch (Exception e) {
            return null;
        }
    }

    private static void deleteRecursively(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }

    /**
     * Outcome of a single compile attempt.
     *
     * @param success    true if bytecode was produced
     * @param classBytes compiled .class bytes when success; null otherwise
     * @param className  fully-qualified name of the top-level class (may be non-null even on failure,
     *                   when the source parsed but compilation failed)
     * @param errors     diagnostic messages (empty on success)
     */
    public record CompilationResult(boolean success, byte[] classBytes, String className, List<String> errors) {
    }
}
