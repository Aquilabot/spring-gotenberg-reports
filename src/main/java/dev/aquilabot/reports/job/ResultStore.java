package dev.aquilabot.reports.job;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Finished PDFs on local disk, kept for {@link JobProperties#retention()}. Single-node by design. */
@Component
public class ResultStore {

    private static final Logger log = LoggerFactory.getLogger(ResultStore.class);

    private final Path directory;
    private final JobProperties properties;
    private final Clock clock;

    @Autowired
    public ResultStore(JobProperties properties) {
        this(createDirectory(), properties, Clock.systemUTC());
    }

    ResultStore(Path directory, JobProperties properties, Clock clock) {
        this.directory = directory;
        this.properties = properties;
        this.clock = clock;
    }

    /** Working directory for one job's intermediate files. */
    public Path workDirectory(String jobId) throws IOException {
        return Files.createDirectories(directory.resolve("work-" + jobId));
    }

    public Path resultFile(String jobId) {
        return directory.resolve(UUID.fromString(jobId) + ".pdf");
    }

    public Optional<Path> find(String jobId) {
        try {
            Path file = resultFile(jobId);
            return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
        } catch (IllegalArgumentException notAUuid) {
            return Optional.empty();
        }
    }

    @Scheduled(fixedDelayString = "${reports.combined.cleanup-interval:1m}")
    public void deleteExpired() {
        Instant cutoff = clock.instant().minus(properties.retention());
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(f -> f.getFileName().toString().endsWith(".pdf"))
                    .filter(f -> lastModified(f).isBefore(cutoff))
                    .forEach(ResultStore::deleteQuietly);
        } catch (IOException e) {
            log.warn("Cleanup of {} failed", directory, e);
        }
    }

    static void deleteRecursively(Path path) {
        try (Stream<Path> tree = Files.walk(path)) {
            tree.sorted(java.util.Comparator.reverseOrder()).forEach(ResultStore::deleteQuietly);
        } catch (IOException e) {
            log.warn("Could not delete {}", path, e);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not delete {}", file, e);
        }
    }

    private static Instant lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant();
        } catch (IOException e) {
            return Instant.MAX;
        }
    }

    private static Path createDirectory() {
        try {
            return Files.createTempDirectory("combined-reports-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
