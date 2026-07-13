package com.skillforge.server.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.io.ByteArrayInputStream;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttachmentTypeDetectorTest {

    @TempDir
    Path tempDir;

    @Test
    void detectsGeneratedPdfFromContentAndFilename() throws Exception {
        Path file = tempDir.resolve("report.pdf");
        Files.writeString(file, "%PDF-1.4\n");

        AttachmentTypeDetector.DetectedType type = AttachmentTypeDetector.detect(file);

        assertThat(type.kind()).isEqualTo("pdf");
        assertThat(type.mimeType()).isEqualTo("application/pdf");
        assertThat(type.extension()).isEqualTo(".pdf");
    }

    @Test
    void rejectsGeneratedFileWhoseExtensionSpoofsDetectedContent() throws Exception {
        Path file = tempDir.resolve("report.pdf");
        Files.write(file, new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});

        assertThatThrownBy(() -> AttachmentTypeDetector.detect(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void rejectsUnsupportedBinary() throws Exception {
        Path file = tempDir.resolve("payload.bin");
        Files.write(file, new byte[]{0, 1, 2, 3});

        assertThatThrownBy(() -> AttachmentTypeDetector.detect(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported");
    }

    @Test
    void inspectsOoxmlPackageInsteadOfTrustingDocxExtension() throws Exception {
        Path file = tempDir.resolve("spoofed.docx");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("xl/workbook.xml"));
            zip.write("<workbook/>".getBytes());
            zip.closeEntry();
        }

        assertThatThrownBy(() -> AttachmentTypeDetector.detect(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void validatesEntireCsvAsUtf8WithoutDisallowedControls() throws Exception {
        Path invalidUtf8 = tempDir.resolve("invalid.csv");
        byte[] bytes = "column,value\nfirst,ok\n".getBytes();
        byte[] payload = java.util.Arrays.copyOf(bytes, bytes.length + 2);
        payload[payload.length - 2] = (byte) 0xC3;
        payload[payload.length - 1] = (byte) 0x28;
        Files.write(invalidUtf8, payload);

        assertThatThrownBy(() -> AttachmentTypeDetector.detect(invalidUtf8))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported");

        Path controls = tempDir.resolve("controls.csv");
        Files.write(controls, "column,value\nfirst,ok\nsecond,".getBytes());
        Files.write(controls, new byte[]{0x01}, java.nio.file.StandardOpenOption.APPEND);
        assertThatThrownBy(() -> AttachmentTypeDetector.detect(controls))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported");

        Path unicode = tempDir.resolve("unicode.csv");
        Files.writeString(unicode, "column,value\nname,report \uD83D\uDCCA\n");
        assertThat(AttachmentTypeDetector.detect(unicode).kind()).isEqualTo("csv");
    }

    @Test
    void inspectsOleDirectoryEntriesInsteadOfTrustingDocExtension() throws Exception {
        Path file = tempDir.resolve("spoofed.doc");
        try (POIFSFileSystem filesystem = new POIFSFileSystem();
             var output = Files.newOutputStream(file)) {
            filesystem.createDocument(new ByteArrayInputStream(new byte[]{1, 2, 3}), "Workbook");
            filesystem.writeFilesystem(output);
        }

        assertThatThrownBy(() -> AttachmentTypeDetector.detect(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void acceptsCsvMimeCharsetParameterAfterFullValidation() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "report.csv", "text/csv; charset=UTF-8",
                "column,value\nname,report\n".getBytes());

        assertThat(AttachmentTypeDetector.detect(file).kind()).isEqualTo("csv");
    }
}
