package com.protask.storage;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * 本地磁盘存储实现：object key 映射为 baseDir 下的相对路径。
 * 所有 key 解析后必须位于 baseDir 内，防御 ../ 形式的路径穿越。
 */
@Slf4j
public class LocalFileStorage implements FileStorage {

    private final Path baseDir;

    public LocalFileStorage(String baseDir) {
        this(Paths.get(baseDir));
    }

    public LocalFileStorage(Path baseDir) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
    }

    /** 存储根目录（静态资源映射等本地专属场景使用） */
    public Path baseDir() {
        return baseDir;
    }

    @Override
    public void store(String key, InputStream content) throws IOException {
        Path target = resolve(key);
        if (Files.isDirectory(target)) {
            throw new IOException("object key conflicts with existing directory: " + key);
        }
        Files.createDirectories(target.getParent());
        try {
            Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // 写入失败时仅清理本次产生的半成品文件，不误删已有目录
            if (Files.isRegularFile(target)) {
                Files.deleteIfExists(target);
            }
            throw e;
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.isRegularFile(resolve(key));
    }

    @Override
    public InputStream open(String key) throws IOException {
        return Files.newInputStream(resolve(key));
    }

    @Override
    public long size(String key) {
        try {
            Path file = resolve(key);
            return Files.isRegularFile(file) ? Files.size(file) : -1;
        } catch (IOException e) {
            log.warn("读取对象大小失败 key={}", key, e);
            return -1;
        }
    }

    @Override
    public void delete(String key) throws IOException {
        Files.deleteIfExists(resolve(key));
    }

    @Override
    public StorageStats stats() throws IOException {
        if (!Files.isDirectory(baseDir)) {
            return StorageStats.EMPTY;
        }
        long fileCount = 0;
        long totalBytes = 0;
        try (Stream<Path> paths = Files.walk(baseDir)) {
            for (Path path : (Iterable<Path>) paths.filter(Files::isRegularFile)::iterator) {
                fileCount++;
                try {
                    totalBytes += Files.size(path);
                } catch (IOException e) {
                    log.warn("统计存储用量跳过不可读文件 {}", path, e);
                }
            }
        }
        return new StorageStats(fileCount, totalBytes);
    }

    /** 解析 object key 为存储根目录内的绝对路径 */
    private Path resolve(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("object key must not be blank");
        }
        Path target = baseDir.resolve(key).normalize();
        if (!target.startsWith(baseDir)) {
            throw new IllegalArgumentException("object key escapes storage root: " + key);
        }
        return target;
    }
}
