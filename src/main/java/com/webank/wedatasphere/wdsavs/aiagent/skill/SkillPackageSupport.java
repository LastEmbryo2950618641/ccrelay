package com.webank.wedatasphere.wdsavs.aiagent.skill;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class SkillPackageSupport {

    public static final long MAX_PACKAGE_BYTES = 50L * 1024L * 1024L;
    private static final long MAX_FILE_BYTES = 20L * 1024L * 1024L;
    private static final long MAX_EXTRACTED_BYTES = 100L * 1024L * 1024L;
    private static final int MAX_FILES = 2_000;
    private static final Pattern SKILL_ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");

    private SkillPackageSupport() {
    }

    public static Path extract(Path archive, Path destination) throws IOException {
        if (Files.size(archive) > MAX_PACKAGE_BYTES) {
            throw new IllegalArgumentException("Skill package exceeds " + MAX_PACKAGE_BYTES + " bytes");
        }
        Files.createDirectories(destination);
        Path normalizedDestination = destination.toAbsolutePath().normalize();
        Set<String> entries = new HashSet<>();
        int fileCount = 0;
        long totalBytes = 0L;
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String entryName = entry.getName().replace('\\', '/');
                if (entryName.isBlank() || entryName.startsWith("/") || entryName.contains("\u0000")
                        || containsRelativePathSegment(entryName)) {
                    throw new IllegalArgumentException("Invalid Skill archive entry: " + entryName);
                }
                Path output = normalizedDestination.resolve(entryName).normalize();
                if (!output.startsWith(normalizedDestination)) {
                    throw new IllegalArgumentException("Skill archive entry escapes destination: " + entryName);
                }
                String normalizedEntryName = relativeName(normalizedDestination, output);
                if (!entries.add(normalizedEntryName)) {
                    throw new IllegalArgumentException("Duplicate Skill archive entry: " + entryName);
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                    continue;
                }
                fileCount++;
                if (fileCount > MAX_FILES) {
                    throw new IllegalArgumentException("Skill package contains too many files");
                }
                Path parent = output.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                long fileBytes = copyLimited(input, output, MAX_FILE_BYTES);
                totalBytes += fileBytes;
                if (totalBytes > MAX_EXTRACTED_BYTES) {
                    throw new IllegalArgumentException("Skill package expands beyond the allowed size");
                }
            }
        }
        return resolveSkillRoot(destination);
    }

    public static Path resolveSkillRoot(Path extractedDirectory) throws IOException {
        Path directManifest = extractedDirectory.resolve("SKILL.md");
        if (Files.isRegularFile(directManifest)) {
            return extractedDirectory;
        }
        try (Stream<Path> children = Files.list(extractedDirectory)) {
            List<Path> directories = children.filter(Files::isDirectory).toList();
            if (directories.size() == 1 && Files.isRegularFile(directories.get(0).resolve("SKILL.md"))) {
                return directories.get(0);
            }
        }
        throw new IllegalArgumentException("Standard Skill package must contain SKILL.md at its root");
    }

    public static String readSkillId(Path skillRoot) throws IOException {
        String markdown = Files.readString(skillRoot.resolve("SKILL.md"), StandardCharsets.UTF_8);
        if (!markdown.startsWith("---")) {
            throw new IllegalArgumentException("SKILL.md must start with YAML frontmatter");
        }
        int end = markdown.indexOf("\n---", 3);
        if (end < 0) {
            throw new IllegalArgumentException("SKILL.md frontmatter is not closed");
        }
        String frontmatter = markdown.substring(3, end);
        Object parsed = new Yaml().load(frontmatter);
        if (!(parsed instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("SKILL.md frontmatter must be a YAML object");
        }
        Object name = values.get("name");
        String skillId = name == null ? "" : String.valueOf(name).trim();
        if (!SKILL_ID_PATTERN.matcher(skillId).matches()) {
            throw new IllegalArgumentException("Invalid Skill name: " + skillId);
        }
        return skillId;
    }

    public static String sha256(Path skillRoot) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<Path> files;
            try (Stream<Path> stream = Files.walk(skillRoot)) {
                files = stream.filter(Files::isRegularFile)
                        .sorted(Comparator.comparing(path -> relativeName(skillRoot, path)))
                        .toList();
            }
            for (Path file : files) {
                byte[] pathBytes = relativeName(skillRoot, file).getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(pathBytes.length).array());
                digest.update(pathBytes);
                digest.update(ByteBuffer.allocate(Long.BYTES).putLong(Files.size(file)).array());
                try (InputStream input = Files.newInputStream(file)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        if (read > 0) {
                            digest.update(buffer, 0, read);
                        }
                    }
                }
            }
            return hex(digest.digest());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to calculate Skill SHA-256", e);
        }
    }

    public static void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            for (Path item : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }

    private static long copyLimited(InputStream input, Path output, long limit) throws IOException {
        long written = 0L;
        try (var stream = Files.newOutputStream(output)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                written += read;
                if (written > limit) {
                    throw new IllegalArgumentException("Skill file exceeds " + limit + " bytes: " + output.getFileName());
                }
                stream.write(buffer, 0, read);
            }
        }
        return written;
    }

    private static String relativeName(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static boolean containsRelativePathSegment(String entryName) {
        for (String segment : entryName.split("/", -1)) {
            if (".".equals(segment) || "..".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }
}
