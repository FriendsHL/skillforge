package com.skillforge.server.clawhub;

import java.util.ArrayList;
import java.util.List;

/**
 * ClawHub 相关 DTO 容器(纯字段类,Jackson 友好)。
 * 用静态嵌套类而不是 record,因为我们需要 setter 给 ClawHubClient 自由赋值。
 */
public class ClawHubModels {

    /** /api/v1/search 单条结果 */
    public static class SkillSummary {
        public String slug;
        public String name;
        public String description;
        public String latestVersion;
        public int downloads;
        public int stars;
        public boolean suspicious;
    }

    /** /api/v1/skills/{slug} 详情 */
    public static class SkillDetail {
        public String slug;
        public String name;
        public String description;
        public String author;
        public String latestVersion;
        public int downloads;
        public int stars;
        public boolean suspicious;
        public String homepage;
        public List<String> versions;
    }

    /** /api/v1/skills/{slug}/scan 官方扫描结果 */
    public static class ScanReport {
        public boolean suspicious;
        public boolean malicious;
        public int score;
        public String summary;
        /** 原始 JSON,审计 + 持久化用 */
        public String rawJson;
    }

    /** SafetyChecker 输出 */
    public static class SafetyReport {
        public enum Risk { LOW, MEDIUM, HIGH, BLOCKED }

        public Risk risk = Risk.LOW;
        public List<String> blockReasons = new ArrayList<>();
        public List<String> warnings = new ArrayList<>();
        public List<String> entries = new ArrayList<>();
        public long uncompressedBytes;
        public int entryCount;
        public String skillMdSnippet;
        /** 命中的 prompt-injection / 危险关键词列表 */
        public List<String> hits = new ArrayList<>();

        public boolean isBlocked() { return risk == Risk.BLOCKED; }
    }
}
