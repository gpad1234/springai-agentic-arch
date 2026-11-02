package com.example.springaigateway;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoresTest {

    // Built at runtime so no key-shaped literal is committed (secret scanners flag those).
    private static String fakeKey(String fill, String tail) {
        return "sk-ant-" + fill.repeat(44) + tail;
    }

    private static final String FIRST_KEY = fakeKey("a", "1111");
    private static final String SECOND_KEY = fakeKey("b", "2222");

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path dir;

    @Test
    void keysAreMaskedActivatedAndMirroredToEnvFile() throws Exception {
        KeyStore store = new KeyStore(mapper, dir.toString());

        KeyStore.KeyView first = store.add("personal", FIRST_KEY);
        KeyStore.KeyView second = store.add("work", SECOND_KEY);

        assertThat(first.active()).isTrue();
        assertThat(second.active()).isFalse();
        assertThat(first.masked()).isEqualTo("••••1111").doesNotContain("sk-ant");
        assertThat(Files.readString(dir.resolve("active-key.env")))
                .isEqualTo("export ANTHROPIC_API_KEY=" + FIRST_KEY + "\n");

        store.activate(second.id());
        assertThat(Files.readString(dir.resolve("active-key.env"))).contains(SECOND_KEY);

        KeyStore reloaded = new KeyStore(mapper, dir.toString());
        assertThat(reloaded.list()).extracting(KeyStore.KeyView::label).containsExactly("personal", "work");
        assertThat(reloaded.list()).filteredOn(KeyStore.KeyView::active).extracting(KeyStore.KeyView::label)
                .containsExactly("work");

        reloaded.remove(second.id());
        assertThat(dir.resolve("active-key.env")).doesNotExist();
    }

    @Test
    void rejectsKeysThatCouldBreakTheEnvFile() {
        KeyStore store = new KeyStore(mapper, dir.toString());

        assertThatThrownBy(() -> store.add("bad", "abc\nexport X=1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.add("bad", "4krxeQnD-s" + "x".repeat(85)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sk-ant-");
        assertThatThrownBy(() -> store.add("bad", "sk-ant-truncated")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.add("bad", "short")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.add(" ", fakeKey("c", "1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.activate("missing")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void settingsAreValidatedAndPersisted() {
        SettingsStore store = new SettingsStore(mapper, dir.toString(), "http://localhost:8080", 0, false);

        store.update("http://example.com:9000/", 30);

        SettingsStore reloaded = new SettingsStore(mapper, dir.toString(), "http://localhost:8080", 0, false);
        assertThat(reloaded.get()).isEqualTo(new SettingsStore.Settings("http://example.com:9000", 30));
        assertThatThrownBy(() -> store.update("ftp://example.com", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.update("http://example.com/path", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.update("http://user:pw@example.com", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.update("http://example.com", -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rateLimiterCountsPerClientAndResetsEachMinute() {
        ProxyFilters.RateLimitFilter filter = new ProxyFilters.RateLimitFilter(
                new SettingsStore(mapper, dir.toString(), "http://localhost:8080", 0, false));
        long t = 120_000;

        assertThat(filter.allow("a", 2, t)).isTrue();
        assertThat(filter.allow("a", 2, t)).isTrue();
        assertThat(filter.allow("a", 2, t)).isFalse();
        assertThat(filter.allow("b", 2, t)).isTrue();
        assertThat(filter.allow("a", 2, t + 60_000)).isTrue();
    }
}
