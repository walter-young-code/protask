package com.protask.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LocalFileStorage 单元测试：存取/删除/统计与路径穿越防御。
 */
class LocalFileStorageTest {

    @TempDir
    Path tempDir;

    private LocalFileStorage storage;

    @BeforeEach
    void setUp() {
        storage = new LocalFileStorage(tempDir);
    }

    private InputStream content(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void storeOpenSizeDelete() throws IOException {
        storage.store("100/20/a.txt", content("hello"));

        assertTrue(storage.exists("100/20/a.txt"));
        assertEquals(5, storage.size("100/20/a.txt"));
        assertEquals(-1, storage.size("100/20/missing.txt"));
        try (InputStream in = storage.open("100/20/a.txt")) {
            assertEquals("hello", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }

        storage.delete("100/20/a.txt");
        assertFalse(storage.exists("100/20/a.txt"));
        storage.delete("100/20/a.txt");
    }

    @Test
    void statsCountsFilesAndBytes() throws IOException {
        storage.store("a.txt", content("123"));
        storage.store("p/t/b.txt", content("12345"));

        StorageStats stats = storage.stats();

        assertEquals(2, stats.fileCount());
        assertEquals(8, stats.totalBytes());
        assertEquals(StorageStats.EMPTY,
                new LocalFileStorage(tempDir.resolve("not-created")).stats());
    }

    @Test
    void rejectsPathTraversal() {
        assertThrows(IllegalArgumentException.class, () -> storage.store("../evil.txt", content("x")));
        assertThrows(IllegalArgumentException.class, () -> storage.exists("a/../../evil.txt"));
        assertThrows(IllegalArgumentException.class, () -> storage.delete(""));
    }

    @Test
    void storeRejectsDirectoryConflict() throws IOException {
        // 目标路径是已存在的目录：拒绝写入且不得误删该目录
        Files.createDirectories(tempDir.resolve("dir.txt"));

        assertThrows(IOException.class, () -> storage.store("dir.txt", content("x")));
        assertTrue(Files.isDirectory(tempDir.resolve("dir.txt")));
    }
}
