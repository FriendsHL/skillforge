package com.skillforge.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for the configurable port / base-dir / seed-from logic of
 * {@link EmbeddedPostgresConfig} — WITHOUT starting a real PostgreSQL (the {@code @Bean}
 * methods are not invoked). Proves the desktop independent-DB change keeps dev defaults
 * byte-for-byte and that the one-time seed behaves correctly.
 */
@DisplayName("EmbeddedPostgresConfig (config + seed)")
class EmbeddedPostgresConfigTest {

    @Nested
    @DisplayName("port / base-dir resolution")
    class Resolution {

        @Test
        @DisplayName("defaults: blank base-dir → ~/.skillforge, port 15432 (dev unchanged)")
        void defaults() {
            EmbeddedPostgresConfig cfg = new EmbeddedPostgresConfig(15432, "", "");

            assertThat(cfg.port()).isEqualTo(15432);
            assertThat(cfg.baseDir())
                    .isEqualTo(Path.of(System.getProperty("user.home"), ".skillforge"));
        }

        @Test
        @DisplayName("blank base-dir via null is tolerated → ~/.skillforge")
        void nullBaseDir() {
            EmbeddedPostgresConfig cfg = new EmbeddedPostgresConfig(15432, null, null);

            assertThat(cfg.baseDir())
                    .isEqualTo(Path.of(System.getProperty("user.home"), ".skillforge"));
        }

        @Test
        @DisplayName("explicit base-dir + port (desktop: 15433 + ~/.skillforge-desktop)")
        void explicit(@TempDir Path base) {
            EmbeddedPostgresConfig cfg = new EmbeddedPostgresConfig(15433, base.toString(), "");

            assertThat(cfg.port()).isEqualTo(15433);
            assertThat(cfg.baseDir()).isEqualTo(base);
        }
    }

    @Nested
    @DisplayName("trySeedFrom")
    class Seed {

        @Test
        @DisplayName("blank seed-from → no-op, returns false")
        void blankSeed(@TempDir Path target) {
            EmbeddedPostgresConfig cfg = new EmbeddedPostgresConfig(15433, target.toString(), "");

            assertThat(cfg.trySeedFrom(target.resolve("pgdata"))).isFalse();
        }

        @Test
        @DisplayName("seed-from without PG_VERSION → returns false (starts fresh)")
        void seedSourceNotInitialized(@TempDir Path src, @TempDir Path targetBase) throws IOException {
            // src exists but has no PG_VERSION
            Files.writeString(src.resolve("random.txt"), "x");
            EmbeddedPostgresConfig cfg = new EmbeddedPostgresConfig(15433, targetBase.toString(), src.toString());

            assertThat(cfg.trySeedFrom(targetBase.resolve("pgdata"))).isFalse();
        }

        @Test
        @DisplayName("seed-from with PG_VERSION → copies data, skips epg-lock/postmaster.pid, returns true")
        void seedCopies(@TempDir Path src, @TempDir Path targetBase) throws IOException {
            Files.writeString(src.resolve("PG_VERSION"), "16");
            Files.createDirectory(src.resolve("base"));
            Files.writeString(src.resolve("base").resolve("1"), "tabledata");
            // runtime/lock files that MUST NOT be seeded
            Files.writeString(src.resolve("postmaster.pid"), "12345");
            Files.writeString(src.resolve("postmaster.opts"), "opts");
            Files.writeString(src.resolve("epg-lock"), "lock");

            Path dataDir = targetBase.resolve("pgdata");
            EmbeddedPostgresConfig cfg = new EmbeddedPostgresConfig(15433, targetBase.toString(), src.toString());

            boolean seeded = cfg.trySeedFrom(dataDir);

            assertThat(seeded).isTrue();
            assertThat(dataDir.resolve("PG_VERSION")).exists();
            assertThat(dataDir.resolve("base").resolve("1")).exists();
            assertThat(Files.readString(dataDir.resolve("base").resolve("1"))).isEqualTo("tabledata");
            // runtime/lock files excluded
            assertThat(dataDir.resolve("postmaster.pid")).doesNotExist();
            assertThat(dataDir.resolve("postmaster.opts")).doesNotExist();
            assertThat(dataDir.resolve("epg-lock")).doesNotExist();
        }
    }
}
