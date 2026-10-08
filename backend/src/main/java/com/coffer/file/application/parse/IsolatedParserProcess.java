package com.coffer.file.application.parse;

import com.coffer.file.domain.parse.ParseStatus;
import com.coffer.file.domain.parse.ParsedDocument;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.jar.JarFile;

/** Runs untrusted document decoders in a capped, killable child JVM. */
final class IsolatedParserProcess {
    private static final Logger log = LoggerFactory.getLogger(IsolatedParserProcess.class);
    private static final Semaphore SLOTS = new Semaphore(2);
    private static final int MAX_OUTPUT_BYTES = 16 * 1024 * 1024;
    private static final long WALL_SECONDS = 20;
    private static final ObjectMapper JSON = new ObjectMapper();

    private IsolatedParserProcess() { }

    static ParsedDocument parse(Long fileId, long revision, String fileName, InputStream input) {
        return parse(fileId, revision, fileName, input, TimeUnit.SECONDS.toMillis(WALL_SECONDS));
    }

    static ParsedDocument parse(Long fileId, long revision, String fileName, InputStream input, long wallMillis) {
        if (input == null) return failed(fileId, revision, ParseStatus.CORRUPTED);
        if (wallMillis <= 0) return failed(fileId, revision, ParseStatus.LIMIT_EXCEEDED);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(wallMillis);
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || !SLOTS.tryAcquire(remaining, TimeUnit.NANOSECONDS))
                return failed(fileId, revision, ParseStatus.LIMIT_EXCEEDED);
            // The source read can block before the child JVM exists. Keep the slot
            // with its worker until that read actually exits, even if this caller times out.
            CompletableFuture<ParsedDocument> result = new CompletableFuture<>();
            Thread worker = daemon(() -> {
                try {
                    byte[] source = input.readNBytes(BoundedDocumentParser.MAX_BYTES + 1);
                    if (source.length > BoundedDocumentParser.MAX_BYTES || deadline - System.nanoTime() <= 0)
                        result.complete(failed(fileId, revision, ParseStatus.LIMIT_EXCEEDED));
                    else result.complete(run(fileId, revision, fileName, source, deadline));
                } catch (IOException unreadable) {
                    log.warn("解析输入读取失败 exceptionType={}", unreadable.getClass().getSimpleName());
                    result.complete(failed(fileId, revision, ParseStatus.CORRUPTED));
                } catch (RuntimeException failure) {
                    log.warn("解析输入处理失败 exceptionType={}", failure.getClass().getSimpleName());
                    result.complete(failed(fileId, revision, ParseStatus.FAILED));
                } finally {
                    SLOTS.release();
                }
            }, "coffer-parser-source");
            try {
                remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new TimeoutException();
                return result.get(remaining, TimeUnit.NANOSECONDS);
            } catch (TimeoutException timeout) {
                worker.interrupt();
                closeAbandonedInput(input);
                return failed(fileId, revision, ParseStatus.LIMIT_EXCEEDED);
            } catch (InterruptedException interrupted) {
                worker.interrupt();
                closeAbandonedInput(input);
                Thread.currentThread().interrupt();
                return failed(fileId, revision, ParseStatus.FAILED);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failed(fileId, revision, ParseStatus.FAILED);
        } catch (ExecutionException failure) {
            log.warn("解析工作线程失败 exceptionType={}", failure.getClass().getSimpleName());
            return failed(fileId, revision, ParseStatus.FAILED);
        }
    }

    private static void closeAbandonedInput(InputStream input) {
        daemon(() -> {
            try { input.close(); }
            catch (IOException ignored) { }
        }, "coffer-parser-source-close");
    }

    private static ParsedDocument run(Long fileId, long revision, String fileName, byte[] source, long deadline) {
        Process child = null;
        Thread writerThread = null;
        Thread readerThread = null;
        try {
            List<String> command = command(fileId, revision, fileName);
            ProcessBuilder builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
            restrictEnvironment(builder.environment());
            child = builder.start();
            Process process = child;
            FutureTask<Void> writer = new FutureTask<>(() -> {
                try (var stdin = process.getOutputStream()) { stdin.write(source); }
                return null;
            });
            writerThread = daemon(writer, "coffer-parser-input");
            FutureTask<byte[]> reader = new FutureTask<>(() -> {
                try (var stdout = process.getInputStream()) {
                    return stdout.readNBytes(MAX_OUTPUT_BYTES + 1);
                }
            });
            readerThread = daemon(reader, "coffer-parser-output");
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || !child.waitFor(remaining, TimeUnit.NANOSECONDS)) {
                log.warn("解析进程超过 {} 秒上限", WALL_SECONDS);
                return failed(fileId, revision, ParseStatus.LIMIT_EXCEEDED);
            }
            if (child.exitValue() != 0) return failed(fileId, revision, ParseStatus.LIMIT_EXCEEDED);
            remaining = deadline - System.nanoTime();
            if (remaining <= 0) return failed(fileId, revision, ParseStatus.LIMIT_EXCEEDED);
            writer.get(remaining, TimeUnit.NANOSECONDS);
            remaining = deadline - System.nanoTime();
            if (remaining <= 0) return failed(fileId, revision, ParseStatus.LIMIT_EXCEEDED);
            byte[] output = reader.get(remaining, TimeUnit.NANOSECONDS);
            if (output.length == 0 || output.length > MAX_OUTPUT_BYTES)
                return failed(fileId, revision, ParseStatus.LIMIT_EXCEEDED);
            ParsedDocument parsed = JSON.readValue(output, ParsedDocument.class);
            if (!java.util.Objects.equals(parsed.fileId(), fileId) || parsed.revision() != revision
                    || parsed.chunks().size() > 20_000
                    || parsed.content().length() > BoundedDocumentParser.MAX_TEXT_CHARS + 20_000)
                return failed(fileId, revision, ParseStatus.FAILED);
            return parsed;
        } catch (TimeoutException timeout) {
            return failed(fileId, revision, ParseStatus.LIMIT_EXCEEDED);
        } catch (Exception failure) {
            log.warn("隔离解析失败 exceptionType={}", failure.getClass().getSimpleName());
            return failed(fileId, revision, ParseStatus.FAILED);
        } finally {
            if (child != null) child.destroyForcibly();
            if (writerThread != null) writerThread.interrupt();
            if (readerThread != null) readerThread.interrupt();
        }
    }

    private static Thread daemon(Runnable action, String name) {
        Thread thread = new Thread(action, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static List<String> command(Long fileId, long revision, String fileName) {
        String javaBinary = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java").toString();
        List<String> args = new ArrayList<>(List.of(javaBinary, "-Xmx256m",
                "-XX:MaxDirectMemorySize=64m", "-Djava.awt.headless=true"));
        String classPath = System.getProperty("java.class.path", "");
        if (isCofferArchive(classPath)) {
            args.add("-jar");
            args.add(classPath);
            args.add("--coffer-parse-worker");
        } else {
            args.add("-cp");
            args.add(classPath);
            args.add(ParserWorkerMain.class.getName());
        }
        args.add(fileId == null ? "-" : fileId.toString());
        args.add(Long.toString(revision));
        args.add(fileName == null ? "" : fileName);
        return args;
    }

    private static boolean isCofferArchive(String classPath) {
        if (classPath.contains(java.io.File.pathSeparator)
                || !classPath.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) return false;
        Path archive = Path.of(classPath);
        if (!Files.isRegularFile(archive)) return false;
        try (JarFile jar = new JarFile(archive.toFile())) {
            var manifest = jar.getManifest();
            return manifest != null && "com.coffer.CofferApplication".equals(
                    manifest.getMainAttributes().getValue("Start-Class"));
        } catch (IOException invalid) {
            return false;
        }
    }

    private static void restrictEnvironment(Map<String, String> environment) {
        String systemRoot = environment.get("SystemRoot");
        String temporary = System.getProperty("java.io.tmpdir");
        environment.clear();
        if (systemRoot != null) environment.put("SystemRoot", systemRoot);
        if (temporary != null) {
            environment.put("TEMP", temporary);
            environment.put("TMP", temporary);
            environment.put("TMPDIR", temporary);
        }
    }

    private static ParsedDocument failed(Long fileId, long revision, ParseStatus status) {
        return new ParsedDocument(fileId, revision, "", BoundedDocumentParser.VERSION,
                status, status.name(), List.of());
    }
}
