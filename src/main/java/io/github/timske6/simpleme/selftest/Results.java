package io.github.timske6.simpleme.selftest;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import io.github.timske6.simpleme.SimpleME;

/** Collects PASS/FAIL lines of the self-test (thread safe, written by client and server thread). */
final class Results {

    private final List<String> lines = new ArrayList<>();
    private int passed;
    private int failed;

    synchronized void check(boolean condition, String name) {
        check(condition, name, "");
    }

    synchronized void check(boolean condition, String name, String detail) {
        String line = (condition ? "PASS  " : "FAIL  ") + name + (detail.isEmpty() ? "" : "  (" + detail + ")");
        lines.add(line);
        if (condition) {
            passed++;
        } else {
            failed++;
        }
        SimpleME.LOG.info("[selftest] {}", line);
    }

    synchronized void info(String text) {
        lines.add("INFO  " + text);
        SimpleME.LOG.info("[selftest] {}", text);
    }

    synchronized void error(String name, Throwable t) {
        failed++;
        lines.add("FAIL  " + name + "  (exception: " + t + ")");
        SimpleME.LOG.error("[selftest] {} failed", name, t);
    }

    synchronized int failed() {
        return failed;
    }

    synchronized void write(File file) {
        List<String> out = new ArrayList<>(lines);
        out.add("");
        out.add("RESULT: " + passed + " passed, " + failed + " failed");
        try {
            Files.write(file.toPath(), out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            SimpleME.LOG.error("[selftest] could not write {}", file, e);
        }
        SimpleME.LOG.info("[selftest] RESULT: {} passed, {} failed", passed, failed);
    }
}
