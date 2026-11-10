package clre20.dailyQuest.data;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

/**
 * 每位玩家一個檔案：data/&lt;uuid&gt;.yml。檔名只由 UUID 組成，不會有路徑跳脫。
 */
public final class YamlStorage implements Storage {

    private final Path dir;
    private final ZoneId zone;

    public YamlStorage(Path dataFolder, ZoneId zone) {
        this.dir = dataFolder.resolve("data");
        this.zone = zone;
    }

    @Override
    public void init() throws IOException {
        Files.createDirectories(dir);
    }

    private Path file(UUID uuid) {
        return dir.resolve(uuid.toString() + ".yml");
    }

    @Override
    public String load(UUID uuid) throws IOException {
        Path f = file(uuid);
        if (!Files.isRegularFile(f)) {
            return null;
        }
        return Files.readString(f, StandardCharsets.UTF_8);
    }

    @Override
    public void save(UUID uuid, String data, long lastSeenDay) throws IOException {
        Path f = file(uuid);
        Path tmp = dir.resolve(uuid + ".yml.tmp");
        Files.writeString(tmp, data, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public int purge(long beforeDay) throws IOException {
        int n = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.yml")) {
            for (Path p : ds) {
                Instant mod = Files.getLastModifiedTime(p).toInstant();
                long day = mod.atZone(zone).toLocalDate().toEpochDay();
                if (day < beforeDay) {
                    Files.deleteIfExists(p);
                    n++;
                }
            }
        }
        return n;
    }

    @Override
    public void close() {
        // 沒有需要關閉的資源
    }
}
