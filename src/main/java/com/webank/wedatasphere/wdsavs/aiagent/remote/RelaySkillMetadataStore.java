package com.webank.wedatasphere.wdsavs.aiagent.remote;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

final class RelaySkillMetadataStore {

    private final Path databasePath;

    RelaySkillMetadataStore(RemoteCcRelayProperties properties) {
        this.databasePath = resolveDatabasePath(properties);
        initialize();
    }

    synchronized List<RelaySkillMetadata> list() {
        List<RelaySkillMetadata> result = new ArrayList<>();
        String sql = "SELECT skill_id, center_sha256, installed_sha256, status, install_path, last_error, updated_at "
                + "FROM relay_skill ORDER BY skill_id";
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                result.add(read(rows));
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to list Relay Skill metadata", e);
        }
    }

    synchronized RelaySkillMetadata find(String skillId) {
        String sql = "SELECT skill_id, center_sha256, installed_sha256, status, install_path, last_error, updated_at "
                + "FROM relay_skill WHERE skill_id = ?";
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, skillId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? read(rows) : null;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to read Relay Skill metadata: " + skillId, e);
        }
    }

    synchronized void save(RelaySkillMetadata metadata) {
        String sql = "INSERT INTO relay_skill(skill_id, center_sha256, installed_sha256, status, install_path, last_error, updated_at) "
                + "VALUES(?, ?, ?, ?, ?, ?, ?) ON CONFLICT(skill_id) DO UPDATE SET "
                + "center_sha256=excluded.center_sha256, installed_sha256=excluded.installed_sha256, "
                + "status=excluded.status, install_path=excluded.install_path, last_error=excluded.last_error, "
                + "updated_at=excluded.updated_at";
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, metadata.getSkillId());
            statement.setString(2, metadata.getCenterSha256());
            statement.setString(3, metadata.getInstalledSha256());
            statement.setString(4, metadata.getStatus());
            statement.setString(5, metadata.getInstallPath());
            statement.setString(6, metadata.getLastError());
            statement.setLong(7, metadata.getUpdatedAt() == null ? System.currentTimeMillis() : metadata.getUpdatedAt());
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to save Relay Skill metadata: " + metadata.getSkillId(), e);
        }
    }

    Path getDatabasePath() {
        return databasePath;
    }

    private void initialize() {
        try {
            Class.forName("org.sqlite.JDBC");
            Path parent = databasePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Connection connection = connection(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS relay_skill ("
                        + "skill_id TEXT PRIMARY KEY, "
                        + "center_sha256 TEXT, "
                        + "installed_sha256 TEXT, "
                        + "status TEXT NOT NULL, "
                        + "install_path TEXT, "
                        + "last_error TEXT, "
                        + "updated_at INTEGER NOT NULL)");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to initialize Relay Skill metadata at " + databasePath, e);
        }
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + databasePath);
    }

    private RelaySkillMetadata read(ResultSet rows) throws Exception {
        RelaySkillMetadata metadata = new RelaySkillMetadata();
        metadata.setSkillId(rows.getString("skill_id"));
        metadata.setCenterSha256(rows.getString("center_sha256"));
        metadata.setInstalledSha256(rows.getString("installed_sha256"));
        metadata.setStatus(rows.getString("status"));
        metadata.setInstallPath(rows.getString("install_path"));
        metadata.setLastError(rows.getString("last_error"));
        metadata.setUpdatedAt(rows.getLong("updated_at"));
        return metadata;
    }

    private Path resolveDatabasePath(RemoteCcRelayProperties properties) {
        if (properties != null && !isBlank(properties.getSkillMetadataDbPath())) {
            return Path.of(properties.getSkillMetadataDbPath()).toAbsolutePath().normalize();
        }
        String suffix = String.valueOf(properties == null ? 18091 : properties.getPort());
        if (properties != null && !isBlank(properties.getNodeIdFilePath())) {
            Path nodeIdPath = Path.of(properties.getNodeIdFilePath()).toAbsolutePath().normalize();
            Path parent = nodeIdPath.getParent();
            if (parent != null) {
                return parent.resolve("relay-skills-" + suffix + ".db");
            }
        }
        Path root = properties != null && !isBlank(properties.getWorkingDirectory())
                ? Path.of(properties.getWorkingDirectory()) : Path.of(System.getProperty("user.dir"));
        return root.toAbsolutePath().normalize().resolve("runtime/relay-skills-" + suffix + ".db");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
