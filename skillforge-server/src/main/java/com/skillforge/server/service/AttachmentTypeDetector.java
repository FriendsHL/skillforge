package com.skillforge.server.service;

import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Shared allowlisted content detection for uploaded and generated attachments. */
public final class AttachmentTypeDetector {

    public static final String MIME_DOC = "application/msword";
    public static final String MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    public static final String MIME_XLS = "application/vnd.ms-excel";
    public static final String MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    public static final String MIME_CSV = "text/csv";

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-'};
    private static final byte[] ZIP = {'P', 'K', 0x03, 0x04};
    private static final byte[] OLE = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
            (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
    private static final Map<String, String> EXTENSION_MIME = Map.of(
            "doc", MIME_DOC, "docx", MIME_DOCX, "xls", MIME_XLS,
            "xlsx", MIME_XLSX, "csv", MIME_CSV);

    private AttachmentTypeDetector() {
    }

    public record DetectedType(String mimeType, String kind, String extension) {
    }

    public static DetectedType detect(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Attachment must not be empty");
        }
        String declared = file.getContentType() == null ? "" : file.getContentType();
        try (InputStream input = file.getInputStream()) {
            return detect(input, declared, file.getOriginalFilename());
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to inspect attachment content", e);
        }
    }

    public static DetectedType detect(Path file) {
        if (file == null || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Generated artifact must be a regular file");
        }
        try (SeekableByteChannel channel = Files.newByteChannel(
                file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            return detect(channel, filename(file));
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to inspect generated artifact content", e);
        }
    }

    static DetectedType detect(SeekableByteChannel channel, String filename) {
        String extension = extension(filename);
        String declared = EXTENSION_MIME.getOrDefault(extension, "");
        try {
            channel.position(0);
            InputStream shielded = new FilterInputStream(Channels.newInputStream(channel)) {
                @Override public void close() { }
            };
            DetectedType type = detect(shielded, declared, filename);
            if (!extensionMatches(extension, type)) {
                throw new IllegalArgumentException("Generated artifact extension does not match detected content");
            }
            return type;
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to inspect generated artifact content", e);
        } finally {
            try {
                channel.position(0);
            } catch (IOException ignored) {
                // The caller will fail when it next reads the channel.
            }
        }
    }

    private static DetectedType detect(InputStream source, String declaredMime, String filename) {
        String declared = declaredMime == null ? "" : declaredMime.toLowerCase(Locale.ROOT).trim();
        int parameter = declared.indexOf(';');
        if (parameter >= 0) declared = declared.substring(0, parameter).trim();
        try {
            BufferedInputStream input = source instanceof BufferedInputStream buffered
                    ? buffered : new BufferedInputStream(source, 64 * 1024);
            input.mark(16);
            byte[] head = input.readNBytes(12);
            input.reset();

            DetectedType type;
            if (startsWith(head, PNG)) type = new DetectedType("image/png", "image", ".png");
            else if (startsWith(head, JPEG)) type = new DetectedType("image/jpeg", "image", ".jpg");
            else if (isWebp(head)) type = new DetectedType("image/webp", "image", ".webp");
            else if (startsWith(head, PDF)) type = new DetectedType("application/pdf", "pdf", ".pdf");
            else if (startsWith(head, ZIP)) type = detectOoxml(input);
            else if (startsWith(head, OLE)) type = detectOle(input);
            else if (MIME_CSV.equals(declared) && validUtf8Text(input)) {
                type = new DetectedType(MIME_CSV, "csv", ".csv");
            } else {
                throw unsupported();
            }

            if (!declared.isBlank() && !"application/octet-stream".equals(declared)
                    && !mimeMatches(declared, type.mimeType())) {
                throw new IllegalArgumentException("Declared content type does not match detected content");
            }
            return type;
        } catch (CharacterCodingException e) {
            throw unsupported();
        } catch (IOException | RuntimeException e) {
            if (e instanceof IllegalArgumentException illegal) throw illegal;
            throw new IllegalArgumentException("Failed to inspect attachment content", e);
        }
    }

    private static DetectedType detectOoxml(InputStream input) throws IOException {
        boolean contentTypes = false;
        boolean word = false;
        boolean excel = false;
        try (ZipInputStream zip = new ZipInputStream(input, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                contentTypes |= "[Content_Types].xml".equals(name);
                word |= "word/document.xml".equals(name);
                excel |= "xl/workbook.xml".equals(name);
                zip.closeEntry();
            }
        }
        if (!contentTypes || word == excel) throw unsupported();
        return word
                ? new DetectedType(MIME_DOCX, "word", ".docx")
                : new DetectedType(MIME_XLSX, "excel", ".xlsx");
    }

    private static DetectedType detectOle(InputStream input) throws IOException {
        try (POIFSFileSystem filesystem = new POIFSFileSystem(input)) {
            DirectoryEntry root = filesystem.getRoot();
            boolean word = root.hasEntryCaseInsensitive("WordDocument");
            boolean excel = root.hasEntryCaseInsensitive("Workbook") || root.hasEntryCaseInsensitive("Book");
            if (word == excel) throw unsupported();
            return word
                    ? new DetectedType(MIME_DOC, "word", ".doc")
                    : new DetectedType(MIME_XLS, "excel", ".xls");
        }
    }

    private static boolean validUtf8Text(InputStream input) throws IOException {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (InputStreamReader reader = new InputStreamReader(input, decoder)) {
            char[] buffer = new char[8192];
            int read;
            boolean first = true;
            while ((read = reader.read(buffer)) >= 0) {
                for (int i = 0; i < read; i++) {
                    char value = buffer[i];
                    if (first && value == '\uFEFF') {
                        first = false;
                        continue;
                    }
                    first = false;
                    if (Character.isISOControl(value)
                            && value != '\t' && value != '\n' && value != '\r') return false;
                }
            }
            return !first;
        }
    }

    private static IllegalArgumentException unsupported() {
        return new IllegalArgumentException("Unsupported or unrecognized attachment content");
    }

    private static boolean extensionMatches(String extension, DetectedType type) {
        if (extension.isBlank()) return true;
        if ("jpeg".equals(extension)) extension = "jpg";
        return type.extension().equals("." + extension);
    }

    private static boolean mimeMatches(String declared, String detected) {
        return declared.equals(detected) || ("image/jpg".equals(declared) && "image/jpeg".equals(detected));
    }

    private static boolean isWebp(byte[] head) {
        return head.length >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P';
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (bytes[i] != prefix[i]) return false;
        return true;
    }

    private static String filename(Path file) {
        return file.getFileName() == null ? "artifact" : file.getFileName().toString();
    }

    private static String extension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
