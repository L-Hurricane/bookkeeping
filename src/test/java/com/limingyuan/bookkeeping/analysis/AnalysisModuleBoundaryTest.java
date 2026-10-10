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
        assertThat(analysisImports).noneMatch(line -> line.contains(".persistence."));
        assertThat(analysisImports).noneMatch(line -> line.contains("Repository"));
        assertThat(analysisImports).noneMatch(line -> line.contains("JdbcClient"));
        assertThat(analysisImports).noneMatch(line -> line.contains("springframework.jdbc"));
        assertThat(importsUnder(analysis.resolve("application")))
                .noneMatch(line -> line.contains("com.limingyuan.bookkeeping.analysis.infrastructure"));

        var coreImports = new ArrayList<String>();
        try (var walk = Files.walk(root)) {
            walk.filter(path -> path.toString().endsWith(".java")).filter(path -> !path.startsWith(analysis))
                    .forEach(path -> coreImports.addAll(importLines(path)));
        }
        assertThat(coreImports).noneMatch(line -> line.contains("com.limingyuan.bookkeeping.analysis"));
    }

    private static List<String> importsUnder(Path root) throws IOException {
        var lines = new ArrayList<String>();
        try (var walk = Files.walk(root)) {
            walk.filter(path -> path.toString().endsWith(".java")).forEach(path -> lines.addAll(importLines(path)));
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
