package com.limingyuan.bookkeeping.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AnalysisModuleBoundaryTest {
    @Test void analysisDependsOnTheReadPortAndCoreDoesNotDependOnAnalysis() throws IOException {
        var root = Path.of("src/main/java/com/limingyuan/bookkeeping");
        var analysis = root.resolve("analysis");
        var analysisImports = importsUnder(analysis);
        assertThat(analysisImports).anyMatch(line -> line.contains("com.limingyuan.bookkeeping.statistics.application.MonthlyBookkeepingFactsReader"));
        assertThat(analysisImports).noneMatch(line -> line.contains(".persistence.")
                && !line.contains("com.limingyuan.bookkeeping.analysis.persistence"));
        assertThat(analysisImports).noneMatch(line -> line.contains("Repository"));
        assertThat(importsUnder(analysis, analysis.resolve("persistence")))
                .noneMatch(line -> line.contains("JdbcClient") || line.contains("springframework.jdbc"));
        assertThat(importsUnder(analysis.resolve("application")))
                .noneMatch(line -> line.contains("com.limingyuan.bookkeeping.analysis.infrastructure")
                        || line.contains("com.limingyuan.bookkeeping.analysis.persistence"));

        var coreImports = new ArrayList<String>();
        try (var walk = Files.walk(root)) {
            walk.filter(path -> path.toString().endsWith(".java")).filter(path -> !path.startsWith(analysis))
                    .forEach(path -> coreImports.addAll(importLines(path)));
        }
        assertThat(coreImports).noneMatch(line -> line.contains("com.limingyuan.bookkeeping.analysis"));
        assertThat(Files.readString(analysis.resolve("persistence/JdbcAnalysisGenerationStore.java")))
                .doesNotContain("UPDATE analysis_snapshots", "DELETE FROM analysis_snapshots");
    }

    private static List<String> importsUnder(Path root) throws IOException {
        return importsUnder(root, null);
    }

    private static List<String> importsUnder(Path root, Path excluded) throws IOException {
        var lines = new ArrayList<String>();
        try (var walk = Files.walk(root)) {
            walk.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> excluded == null || !path.startsWith(excluded))
                    .forEach(path -> lines.addAll(importLines(path)));
        }
        return lines;
    }

    private static List<String> importLines(Path path) {
        try {
            return Files.readAllLines(path).stream().filter(line -> line.startsWith("import ")).toList();
        } catch (IOException ex) {
            throw new IllegalStateException(path.toString(), ex);
        }
    }
}
