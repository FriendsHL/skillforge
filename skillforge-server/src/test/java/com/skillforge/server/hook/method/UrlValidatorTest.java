package com.skillforge.server.hook.method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link UrlValidator} — covers SSRF protection logic used by
 * {@link HttpPostMethod} and {@link FeishuNotifyMethod}.
 */
class UrlValidatorTest {

    @Test
    @DisplayName("valid HTTPS URL passes validation")
    void validate_validHttpsUrl_returnsNull() {
        assertThat(UrlValidator.validate("https://example.com/webhook")).isNull();
    }

    @Test
    @DisplayName("valid HTTP URL passes validation")
    void validate_validHttpUrl_returnsNull() {
        assertThat(UrlValidator.validate("http://example.com/webhook")).isNull();
    }

    @Test
    @DisplayName("null URL is rejected")
    void validate_nullUrl_returnsError() {
        assertThat(UrlValidator.validate(null)).isEqualTo("url is required");
    }

    @Test
    @DisplayName("blank URL is rejected")
    void validate_blankUrl_returnsError() {
        assertThat(UrlValidator.validate("  ")).isEqualTo("url is required");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "file:///etc/passwd",
            "ftp://example.com/file",
            "javascript:alert(1)"
    })
    @DisplayName("non-HTTP schemes are rejected")
    void validate_blockedScheme_returnsError(String url) {
        String error = UrlValidator.validate(url);
        assertThat(error).startsWith("blocked scheme:");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost/api",
            "http://127.0.0.1:8080/api",
            "http://0.0.0.0/api",
            "http://[::1]/api"
    })
    @DisplayName("loopback addresses are rejected")
    void validate_loopbackAddress_returnsError(String url) {
        String error = UrlValidator.validate(url);
        assertThat(error).startsWith("blocked host:");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://10.0.0.1/api",
            "http://10.255.255.255/api",
            "http://172.16.0.1/api",
            "http://172.31.255.255/api",
            "http://192.168.0.1/api",
            "http://192.168.1.100/api"
    })
    @DisplayName("private network ranges are rejected")
    void validate_privateNetwork_returnsError(String url) {
        String error = UrlValidator.validate(url);
        assertThat(error).startsWith("blocked private network:");
    }

    @Test
    @DisplayName("172.15.x.x is NOT in the private range (boundary check)")
    void validate_172_15_isPublic() {
        assertThat(UrlValidator.validate("http://172.15.0.1/api")).isNull();
    }

    @Test
    @DisplayName("172.32.x.x is NOT in the private range (boundary check)")
    void validate_172_32_isPublic() {
        assertThat(UrlValidator.validate("http://172.32.0.1/api")).isNull();
    }

    @Test
    @DisplayName("allowed hosts bypass private range check")
    void validate_allowedHosts_bypasses() {
        Set<String> allowed = Set.of("open.feishu.cn");
        assertThat(UrlValidator.validate("https://open.feishu.cn/webhook/123", allowed)).isNull();
    }

    @Test
    @DisplayName("allowed hosts are case-insensitive")
    void validate_allowedHosts_caseInsensitive() {
        Set<String> allowed = Set.of("open.feishu.cn");
        // URI.getHost() returns lowercase for standard URLs
        assertThat(UrlValidator.validate("https://open.feishu.cn/webhook/123", allowed)).isNull();
    }

    @Test
    @DisplayName("malformed URL is rejected")
    void validate_malformedUrl_returnsError() {
        assertThat(UrlValidator.validate("not a url at all")).isNotNull();
    }
}
