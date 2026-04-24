package com.skillforge.core.engine.confirm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InstallTargetParserTest {

    @Test
    @DisplayName("clawhub install target → (clawhub, target)")
    void clawhubHappy() {
        InstallTargetParser.Parsed p = InstallTargetParser.parse("npx clawhub install obsidian");
        assertThat(p.toolName()).isEqualTo("clawhub");
        assertThat(p.installTarget()).isEqualTo("obsidian");
    }

    @Test
    @DisplayName("skillhub install scoped @org/pkg")
    void skillhubScoped() {
        InstallTargetParser.Parsed p = InstallTargetParser.parse("skillhub install @org/pkg");
        assertThat(p.toolName()).isEqualTo("skillhub");
        assertThat(p.installTarget()).isEqualTo("@org/pkg");
    }

    @Test
    @DisplayName("skill-hub/cli install foo-bar_1.0")
    void skillHubCli() {
        InstallTargetParser.Parsed p = InstallTargetParser.parse("skill-hub/cli install foo-bar_1.0");
        assertThat(p.toolName()).isEqualTo("skill-hub");
        assertThat(p.installTarget()).isEqualTo("foo-bar_1.0");
    }

    @Test
    @DisplayName("clawhub install (no arg) → unparseable fallback *")
    void clawhubNoArg() {
        InstallTargetParser.Parsed p = InstallTargetParser.parse("clawhub install");
        // No target token matches → installCount==0 → "multiple","*"
        assertThat(p.installTarget()).isEqualTo("*");
    }

    @Test
    @DisplayName("multiple install commands in one line → target *")
    void multipleInstalls() {
        InstallTargetParser.Parsed p = InstallTargetParser.parse(
                "clawhub install a && clawhub install b");
        assertThat(p.toolName()).isEqualTo("multiple");
        assertThat(p.installTarget()).isEqualTo("*");
    }

    @Test
    @DisplayName("r4: flag prefix as target is normalized to * (no cache pollution)")
    void flagPrefixRejected() {
        InstallTargetParser.Parsed p = InstallTargetParser.parse("clawhub install --force obsidian");
        assertThat(p.toolName()).isEqualTo("clawhub");
        assertThat(p.installTarget()).isEqualTo("*");
    }

    @Test
    @DisplayName("r4: short flag -y also rejected")
    void shortFlagRejected() {
        InstallTargetParser.Parsed p = InstallTargetParser.parse("clawhub install -y pkg");
        assertThat(p.installTarget()).isEqualTo("*");
    }

    @Test
    @DisplayName("null command → unknown/*")
    void nullCommand() {
        InstallTargetParser.Parsed p = InstallTargetParser.parse(null);
        assertThat(p.toolName()).isEqualTo("unknown");
        assertThat(p.installTarget()).isEqualTo("*");
    }

    @Test
    @DisplayName("shell metachars in target: $ is not in [A-Za-z0-9._/-@] so regex fails match")
    void shellMetachars() {
        InstallTargetParser.Parsed p = InstallTargetParser.parse("clawhub install $(malicious)");
        // $() doesn't match target regex → installCount==0 → multiple/*
        assertThat(p.installTarget()).isEqualTo("*");
    }
}
