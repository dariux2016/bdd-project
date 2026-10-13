package io.github.bdd.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** File operations confined to the {@code features} directory of a workspace. */
final class FeatureStore {

    static final class PathRejectedException extends RuntimeException {
        PathRejectedException(String message) {
            super(message);
        }
    }

    static final class NotFoundException extends RuntimeException {
        NotFoundException(String message) {
            super(message);
        }
    }

    static final class ConflictException extends RuntimeException {
        ConflictException(String message) {
            super(message);
        }
    }

    private final Path realRoot;

    FeatureStore(Path featuresDir) throws IOException {
        Files.createDirectories(featuresDir);
        this.realRoot = featuresDir.toRealPath();
    }

    Map<String, Object> tree() throws IOException {
        return node(realRoot, "");
    }

    private Map<String, Object> node(Path dir, String relative) throws IOException {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("name", relative.isEmpty() ? "features" : dir.getFileName().toString());
        node.put("path", relative);
        node.put("type", "folder");
        List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            stream.forEach(entries::add);
        }
        entries.sort(Comparator.comparing((Path p) -> !Files.isDirectory(p))
                .thenComparing(p -> p.getFileName().toString().toLowerCase()));
        List<Object> children = new ArrayList<>();
        for (Path entry : entries) {
            String childRelative = relative.isEmpty()
                    ? entry.getFileName().toString()
                    : relative + "/" + entry.getFileName();
            // Links that leave the features directory (or dangle) are not shown.
            if (!Files.exists(entry) || !entry.toRealPath().startsWith(realRoot)) {
                continue;
            }
            if (Files.isDirectory(entry)) {
                children.add(node(entry, childRelative));
            } else {
                Map<String, Object> file = new LinkedHashMap<>();
                file.put("name", entry.getFileName().toString());
                file.put("path", childRelative);
                file.put("type", "file");
                children.add(file);
            }
        }
        node.put("children", children);
        return node;
    }

    Map<String, Object> read(String relative) throws IOException {
        Path file = resolve(relative);
        if (!Files.isRegularFile(file)) {
            throw new NotFoundException("File not found: " + relative);
        }
        return fileState(relative, file);
    }

    Map<String, Object> write(String relative, String content, String version, boolean overwrite) throws IOException {
        Path file = resolve(relative);
        if (!Files.isRegularFile(file)) {
            throw new NotFoundException("File not found: " + relative);
        }
        if (!overwrite) {
            if (version == null) {
                throw new PathRejectedException("The version of the opened file is required");
            }
            if (!version.equals(versionOf(Files.readAllBytes(file)))) {
                throw new ConflictException("The file changed on disk after it was opened");
            }
        }
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return fileState(relative, file);
    }

    Map<String, Object> createFile(String relative, String content) throws IOException {
        Path file = resolve(relative);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new ConflictException("Already exists: " + relative);
        }
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return fileState(relative, file);
    }

    void createFolder(String relative) throws IOException {
        Path dir = resolve(relative);
        if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new ConflictException("Already exists: " + relative);
        }
        Files.createDirectories(dir);
    }

    void rename(String from, String to) throws IOException {
        Path source = resolve(from);
        Path target = resolve(to);
        if (source.equals(realRoot) || target.equals(realRoot)) {
            throw new PathRejectedException("The features directory cannot be renamed");
        }
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new NotFoundException("Not found: " + from);
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new ConflictException("Already exists: " + to);
        }
        if (target.startsWith(source)) {
            throw new PathRejectedException("A folder cannot be moved into itself");
        }
        Files.createDirectories(target.getParent());
        Files.move(source, target);
    }

    void delete(String relative) throws IOException {
        Path path = resolve(relative);
        if (path.equals(realRoot)) {
            throw new PathRejectedException("The features directory cannot be deleted");
        }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new NotFoundException("Not found: " + relative);
        }
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            try (Stream<Path> walk = Files.walk(path)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            }
        } else {
            Files.delete(path);
        }
    }

    private Map<String, Object> fileState(String relative, Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("path", relative);
        state.put("content", new String(bytes, StandardCharsets.UTF_8));
        state.put("version", versionOf(bytes));
        return state;
    }

    static String versionOf(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Resolves a path relative to the features directory, refusing anything that escapes it,
     * including through symbolic links.
     */
    private Path resolve(String relative) throws IOException {
        if (relative == null) {
            throw new PathRejectedException("Missing path");
        }
        if (relative.indexOf('\0') >= 0 || relative.contains("\\")) {
            throw new PathRejectedException("Invalid path: " + relative);
        }
        if (relative.startsWith("/") || relative.matches("^[A-Za-z]:.*")) {
            throw new PathRejectedException("Absolute paths are not allowed: " + relative);
        }
        Path result = realRoot;
        for (String segment : relative.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                throw new PathRejectedException("Path escapes the features directory: " + relative);
            }
            result = result.resolve(segment);
        }
        result = result.normalize();
        if (!result.startsWith(realRoot)) {
            throw new PathRejectedException("Path escapes the features directory: " + relative);
        }
        // Follow links on the deepest existing ancestor: its real location must stay inside.
        Path existing = result;
        while (existing != null && !Files.exists(existing)) {
            if (Files.isSymbolicLink(existing)) {
                throw new PathRejectedException("Path escapes the features directory: " + relative);
            }
            existing = existing.getParent();
        }
        if (existing != null && !existing.toRealPath().startsWith(realRoot)) {
            throw new PathRejectedException("Path escapes the features directory: " + relative);
        }
        return result;
    }
}
