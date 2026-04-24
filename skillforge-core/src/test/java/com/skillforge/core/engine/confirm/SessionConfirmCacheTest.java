package com.skillforge.core.engine.confirm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionConfirmCacheTest {

    @Test
    @DisplayName("approve then isApproved: same root+tool+target returns true")
    void approveHappy() {
        SessionConfirmCache c = new SessionConfirmCache();
        c.approve("root1", "clawhub", "obsidian");
        assertThat(c.isApproved("root1", "clawhub", "obsidian")).isTrue();
    }

    @Test
    @DisplayName("different target → cache miss")
    void differentTargetMiss() {
        SessionConfirmCache c = new SessionConfirmCache();
        c.approve("root1", "clawhub", "A");
        assertThat(c.isApproved("root1", "clawhub", "B")).isFalse();
    }

    @Test
    @DisplayName("different root → cache miss")
    void differentRootMiss() {
        SessionConfirmCache c = new SessionConfirmCache();
        c.approve("root1", "clawhub", "A");
        assertThat(c.isApproved("root2", "clawhub", "A")).isFalse();
    }

    @Test
    @DisplayName("target == * is never cached or matched")
    void starSentinel() {
        SessionConfirmCache c = new SessionConfirmCache();
        c.approve("root1", "clawhub", "*");
        assertThat(c.isApproved("root1", "clawhub", "*")).isFalse();
    }

    @Test
    @DisplayName("clear(root) removes all entries for that root")
    void clearRoot() {
        SessionConfirmCache c = new SessionConfirmCache();
        c.approve("root1", "clawhub", "A");
        c.approve("root1", "skillhub", "B");
        c.clear("root1");
        assertThat(c.isApproved("root1", "clawhub", "A")).isFalse();
        assertThat(c.isApproved("root1", "skillhub", "B")).isFalse();
    }

    @Test
    @DisplayName("null inputs are rejected, never throw")
    void nullInputs() {
        SessionConfirmCache c = new SessionConfirmCache();
        c.approve(null, "clawhub", "A");
        c.approve("root1", null, "A");
        c.approve("root1", "clawhub", null);
        assertThat(c.isApproved(null, "clawhub", "A")).isFalse();
        assertThat(c.isApproved("root1", null, "A")).isFalse();
        assertThat(c.isApproved("root1", "clawhub", null)).isFalse();
    }
}
