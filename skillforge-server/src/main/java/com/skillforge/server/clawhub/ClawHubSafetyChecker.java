package com.skillforge.server.clawhub;

import com.skillforge.server.clawhub.ClawHubModels.SafetyReport;
import com.skillforge.server.clawhub.ClawHubModels.SafetyReport.Risk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * ClawHub 安装包安全检查器。
 *
 * 攻击面分两类:
 *  1. zip 本身有害 — zip bomb / zip slip / 二进制可执行文件
 *  2. 内容有害 — SKILL.md 里有 prompt injection,诱导 Agent 调危险工具
 *
 * 输入:zip 字节流。输出:SafetyReport(包含风险评级 + 命中原因)。
 * 这个 checker 是纯函数,不写文件、不改 SkillRegistry,只检查后返回判断。
 */
@Component
public class ClawHubSafetyChecker {

    private static final Logger log = LoggerFactory.getLogger(ClawHubSafetyChecker.class);

    private final ClawHubProperties props;

    /** 文件扩展名白名单(全小写)。 */
    private static final Set<String> ALLOWED_EXTS = Set.of(
            "md", "yaml", "yml", "json", "txt", "py", "js", "ts", "sh",
            "css", "html", "svg", "png", "jpg", "jpeg", "gif", "csv", "toml",
            "xml", "pdf"
    );

    /** 黑名单(命中即 reject) */
    private static final Set<String> BANNED_EXTS = Set.of(
            "exe", "dll", "so", "dylib", "jar", "class", "bin", "elf", "msi",
            "bat", "cmd", "ps1", "vbs", "scr"
    );

    /** Prompt injection 高危关键词,命中即记为 high(2 条 high 触发 BLOCKED)。 */
    private static final List<Pattern> HIGH_PATTERNS = List.of(
            // "ignore previous instructions" / "忽略之前的指令"
            Pattern.compile("(?i)(ignore|disregard)[\\s\\S]{0,15}(previous|all|prior)[\\s\\S]{0,15}(instruction|prompt|message)"),
            Pattern.compile("(忽略|无视|不要遵循)[\\s\\S]{0,10}(之前|前面|所有|全部)[\\s\\S]{0,10}(指令|提示|规则)"),
            // 系统提示词泄露 / 替换
            Pattern.compile("(?i)(system\\s*prompt|系统提示词)[\\s\\S]{0,15}(reveal|leak|show|输出|泄露|泄漏|替换|覆盖)"),
            // 模型 special token
            Pattern.compile("<\\|im_start\\|>|<\\|im_end\\|>|<\\|endoftext\\|>"),
            // rm -rf root
            Pattern.compile("rm\\s+-rf\\s+(/|~|\\$HOME|/\\*)"),
            // fork bomb
            Pattern.compile(":\\(\\)\\s*\\{\\s*:\\|:&\\s*\\};\\s*:"),
            // curl|sh / wget|bash 远程执行
            Pattern.compile("(?i)(curl|wget|fetch)[^\\n|]*\\|\\s*(bash|sh|zsh|python|node)"),
            // ssh 私钥 / aws / netrc
            Pattern.compile("(?i)(\\.aws/credentials|\\.ssh/id_(rsa|ed25519|dsa|ecdsa)|\\.netrc|kubeconfig)")
    );

    /** 中危(累计 ≥3 条 → high) */
    private static final List<Pattern> MEDIUM_PATTERNS = List.of(
            // 疑似想读 secret env
            Pattern.compile("(?i)(AWS|GITHUB|OPENAI|ANTHROPIC|DASHSCOPE|GOOGLE|GEMINI|AZURE)_?(API_?)?(SECRET|TOKEN|KEY)"),
            // base64 大段(常见混淆)
            Pattern.compile("[A-Za-z0-9+/]{200,}={0,2}"),
            // eval / exec 调用
            Pattern.compile("(?i)\\b(eval|exec)\\s*\\("),
            // 把消息发到外网
            Pattern.compile("(?i)(POST|fetch)[^\\n]{0,40}(https?://[^\\s\"']+)")
    );

    public ClawHubSafetyChecker(ClawHubProperties props) {
        this.props = props;
    }

    /**
     * 主入口:对 zip 字节流跑全套检查。
     */
    public SafetyReport check(byte[] zipBytes, String slug) {
        SafetyReport r = new SafetyReport();
        if (zipBytes == null || zipBytes.length == 0) {
            r.risk = Risk.BLOCKED;
            r.blockReasons.add("empty package");
            return r;
        }
        if (zipBytes.length > props.getMaxUncompressedBytes()) {
            // 压缩前都已经超限,显然不行
            r.risk = Risk.BLOCKED;
            r.blockReasons.add("zip exceeds size limit even compressed: " + zipBytes.length);
            return r;
        }

        // 第 1 + 2 层:遍历 zip 条目
        long totalUncompressed = 0;
        int count = 0;
        boolean hasSkillMd = false;
        StringBuilder skillMdContent = null;

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            byte[] buf = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                count++;
                if (count > props.getMaxZipEntries()) {
                    r.risk = Risk.BLOCKED;
                    r.blockReasons.add("too many entries (>" + props.getMaxZipEntries() + ")");
                    return r;
                }
                String name = entry.getName();
                r.entries.add(name);

                // zip slip 校验
                if (name.contains("..") || name.startsWith("/") || name.contains("\0")) {
                    r.risk = Risk.BLOCKED;
                    r.blockReasons.add("suspicious entry path: " + name);
                    return r;
                }
                // 控制字符
                for (int i = 0; i < name.length(); i++) {
                    char c = name.charAt(i);
                    if (c < 0x20 && c != '\t') {
                        r.risk = Risk.BLOCKED;
                        r.blockReasons.add("control character in entry name: " + name);
                        return r;
                    }
                }

                if (entry.isDirectory()) {
                    zis.closeEntry();
                    continue;
                }

                // 扩展名白/黑名单
                String ext = extOf(name);
                if (ext != null) {
                    if (BANNED_EXTS.contains(ext)) {
                        r.risk = Risk.BLOCKED;
                        r.blockReasons.add("banned file type: " + name);
                        return r;
                    }
                    if (!ALLOWED_EXTS.contains(ext)) {
                        // 未知类型 → 警告但不 reject
                        r.warnings.add("unknown file type (kept but flagged): " + name);
                    }
                }

                // 解压(实时累计字节,提前发现 zip bomb)
                long entryBytes = 0;
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                int n;
                while ((n = zis.read(buf)) > 0) {
                    entryBytes += n;
                    totalUncompressed += n;
                    if (entryBytes > props.getMaxSingleEntryBytes()) {
                        r.risk = Risk.BLOCKED;
                        r.blockReasons.add("entry too large: " + name + " (>" + props.getMaxSingleEntryBytes() + ")");
                        return r;
                    }
                    if (totalUncompressed > props.getMaxUncompressedBytes()) {
                        r.risk = Risk.BLOCKED;
                        r.blockReasons.add("total uncompressed exceeds limit: " + totalUncompressed);
                        return r;
                    }
                    baos.write(buf, 0, n);
                }

                // 压缩比检查(只对 ≥ 1KB 的文件做,小文件比值无意义)
                long compressed = entry.getCompressedSize();
                if (compressed > 1024 && entryBytes / Math.max(1, compressed) > props.getMaxCompressionRatio()) {
                    r.risk = Risk.BLOCKED;
                    r.blockReasons.add("compression ratio too high (zip bomb?): " + name);
                    return r;
                }

                // 抓出 SKILL.md 内容做关键词扫描
                String lower = stripLeadingSlash(name).toLowerCase(Locale.ROOT);
                if (lower.equals("skill.md") || lower.endsWith("/skill.md")) {
                    hasSkillMd = true;
                    skillMdContent = new StringBuilder(baos.toString(StandardCharsets.UTF_8));
                }

                zis.closeEntry();
            }
        } catch (IOException e) {
            r.risk = Risk.BLOCKED;
            r.blockReasons.add("malformed zip: " + e.getMessage());
            return r;
        }

        r.entryCount = count;
        r.uncompressedBytes = totalUncompressed;

        // 第 3 层:必须有 SKILL.md
        if (!hasSkillMd) {
            r.risk = Risk.BLOCKED;
            r.blockReasons.add("missing SKILL.md");
            return r;
        }

        // 第 4 层:SKILL.md 静态扫描
        String content = skillMdContent.toString();
        // 截一段给 ask_user UI 显示(前 800 字)
        r.skillMdSnippet = content.length() > 800 ? content.substring(0, 800) + "…" : content;

        int highHits = 0;
        int mediumHits = 0;
        for (Pattern p : HIGH_PATTERNS) {
            Matcher m = p.matcher(content);
            if (m.find()) {
                highHits++;
                r.hits.add("[HIGH] " + p.pattern() + " → \"" + truncate(m.group(), 60) + "\"");
            }
        }
        for (Pattern p : MEDIUM_PATTERNS) {
            Matcher m = p.matcher(content);
            if (m.find()) {
                mediumHits++;
                r.hits.add("[MED] " + p.pattern() + " → \"" + truncate(m.group(), 60) + "\"");
            }
        }

        // 第 5 层:综合评级
        if (highHits >= 2) {
            r.risk = Risk.BLOCKED;
            r.blockReasons.add(highHits + " high-severity prompt-injection patterns matched");
        } else if (highHits == 1) {
            r.risk = Risk.HIGH;
        } else if (mediumHits >= 3) {
            r.risk = Risk.HIGH;
        } else if (mediumHits >= 1 || !r.warnings.isEmpty()) {
            r.risk = Risk.MEDIUM;
        } else {
            r.risk = Risk.LOW;
        }

        log.info("ClawHub safety check slug={} risk={} highHits={} mediumHits={} entries={}",
                slug, r.risk, highHits, mediumHits, count);
        return r;
    }

    /**
     * 把官方 ScanReport 合并进本地评级:官方判定恶意 → 直接 BLOCKED。
     */
    public void mergeOfficialScan(SafetyReport local, ClawHubModels.ScanReport official) {
        if (official == null) return;
        if (official.malicious) {
            local.risk = Risk.BLOCKED;
            local.blockReasons.add("ClawHub official scan: malicious");
        } else if (official.suspicious && local.risk == Risk.LOW) {
            local.risk = Risk.MEDIUM;
            local.warnings.add("ClawHub official scan flagged as suspicious");
        }
    }

    private static String extOf(String name) {
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        String base = slash >= 0 ? name.substring(slash + 1) : name;
        int dot = base.lastIndexOf('.');
        if (dot < 0 || dot == base.length() - 1) return null;
        return base.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String stripLeadingSlash(String s) {
        return s.startsWith("/") ? s.substring(1) : s;
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ");
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }
}
