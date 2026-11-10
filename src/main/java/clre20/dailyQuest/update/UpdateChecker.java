package clre20.dailyQuest.update;

import clre20.dailyQuest.config.Messages;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 向 GitHub Releases 檢查更新。
 * <ul>
 *   <li>單一背景執行緒；結果快取 10 分鐘（失敗 1 分鐘），同時多個請求共用同一次查詢。</li>
 *   <li>沒有定時輪詢；只在開服、/dq version、管理員登入（使用快取）時查詢。</li>
 *   <li>比對時去掉標籤的 v 前綴。</li>
 * </ul>
 */
public final class UpdateChecker {

    public enum Status { NEWER, LATEST, DEV, NO_RELEASE, ERROR, DISABLED }

    public record Result(Status status, String latest, String url, String error) { }

    private static final long CACHE_MS = 10 * 60_000L;
    private static final long FAIL_CACHE_MS = 60_000L;
    private static final int MAX_BODY = 512 * 1024;

    private final JavaPlugin plugin;
    private final ExecutorService executor;
    private final String current;
    private volatile String repository;
    private Messages msg;

    private Result cached;
    private long cachedAt;
    private CompletableFuture<Result> inFlight;

    public UpdateChecker(JavaPlugin plugin, String repository, Messages msg) {
        this.plugin = plugin;
        this.repository = repository;
        this.msg = msg;
        this.current = plugin.getPluginMeta().getVersion();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "DailyQuest-Update");
            t.setDaemon(true);
            return t;
        });
    }

    public void reload(String repository, Messages msg) {
        synchronized (this) {
            if (!repository.equals(this.repository)) {
                cached = null;
            }
            this.repository = repository;
        }
        this.msg = msg;
    }

    public void shutdown() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 取得結果（可能來自快取）。完成時在背景執行緒呼叫。 */
    public synchronized CompletableFuture<Result> check() {
        if (repository.isEmpty()) {
            return CompletableFuture.completedFuture(new Result(Status.DISABLED, null, null, null));
        }
        long now = System.currentTimeMillis();
        if (cached != null) {
            long ttl = cached.status() == Status.ERROR ? FAIL_CACHE_MS : CACHE_MS;
            if (now - cachedAt < ttl) {
                return CompletableFuture.completedFuture(cached);
            }
        }
        if (inFlight != null && !inFlight.isDone()) {
            return inFlight;
        }
        String repo = repository;
        Supplier<Result> task = () -> fetch(repo);
        try {
            inFlight = CompletableFuture.supplyAsync(task, executor).thenApply(r -> {
                synchronized (UpdateChecker.this) {
                    cached = r;
                    cachedAt = System.currentTimeMillis();
                }
                return r;
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            return CompletableFuture.completedFuture(new Result(Status.ERROR, null, null, "shutting down"));
        }
        return inFlight;
    }

    /** 快取中的結果（不發請求）。 */
    public synchronized Result cachedResult() {
        return cached;
    }

    private Result fetch(String repo) {
        String override = System.getProperty("dailyquest.update-api");
        String api = override != null && !override.isBlank() ? override
                : "https://api.github.com/repos/" + repo + "/releases/latest";
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) URI.create(api).toURL().openConnection();
            con.setConnectTimeout(5000);
            con.setReadTimeout(5000);
            con.setInstanceFollowRedirects(true);
            con.setRequestProperty("Accept", "application/vnd.github+json");
            con.setRequestProperty("User-Agent", "DailyQuest/" + current);
            int code = con.getResponseCode();
            if (code == 404) {
                return new Result(Status.NO_RELEASE, null, null, null);
            }
            if (code != 200) {
                return new Result(Status.ERROR, null, null, "HTTP " + code);
            }
            String body;
            try (InputStream in = con.getInputStream()) {
                body = new String(in.readNBytes(MAX_BODY), StandardCharsets.UTF_8);
            }
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            JsonElement tagEl = json.get("tag_name");
            if (tagEl == null || tagEl.isJsonNull()) {
                return new Result(Status.NO_RELEASE, null, null, null);
            }
            String tag = strip(tagEl.getAsString());
            JsonElement urlEl = json.get("html_url");
            String url = urlEl == null || urlEl.isJsonNull() ? "https://github.com/" + repo + "/releases" : urlEl.getAsString();
            int cmp = compare(tag, strip(current));
            Status st = cmp > 0 ? Status.NEWER : cmp == 0 ? Status.LATEST : Status.DEV;
            return new Result(st, tag, url, null);
        } catch (IOException | RuntimeException e) {
            return new Result(Status.ERROR, null, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (con != null) con.disconnect();
        }
    }

    static String strip(String v) {
        String s = v.trim();
        return s.startsWith("v") || s.startsWith("V") ? s.substring(1) : s;
    }

    /** 比較 1.2.3 這類版本；非數字部分視為 0。 */
    static int compare(String a, String b) {
        String[] x = a.split("[.-]");
        String[] y = b.split("[.-]");
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int p = i < x.length ? num(x[i]) : 0;
            int q = i < y.length ? num(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D", "").isEmpty() ? "0" : s.replaceAll("\\D", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------ 輸出

    public void logStartup() {
        check().thenAccept(r -> runMain(() -> {
            for (String line : lines(r)) {
                plugin.getComponentLogger().info(Messages.color(line));
            }
        }));
    }

    public void checkCommand(CommandSender sender) {
        msg.send(sender, "update.checking");
        check().thenAccept(r -> runMain(() -> {
            if (sender instanceof Player p && !p.isOnline()) return;
            for (String line : lines(r)) {
                sender.sendMessage(Messages.color(msg.raw("prefix") + line));
            }
        }));
    }

    /** 管理員登入：有新版本才提醒（必要時查詢一次，之後走快取）。 */
    public void notifyJoin(Player p) {
        check().thenAccept(r -> {
            if (r.status() != Status.NEWER) return;
            runMain(() -> {
                if (!p.isOnline()) return;
                for (String line : lines(r)) {
                    p.sendMessage(Messages.color(msg.raw("prefix") + line));
                }
            });
        });
    }

    private List<String> lines(Result r) {
        List<String> out = new ArrayList<>();
        String key = switch (r.status()) {
            case NEWER -> "update.newer";
            case LATEST -> "update.latest";
            case DEV -> "update.dev";
            case NO_RELEASE -> "update.no-release";
            case ERROR -> "update.error";
            case DISABLED -> "update.disabled";
        };
        for (String line : msg.rawList(key)) {
            out.add(Messages.apply(line, "current", current, "latest", r.latest() == null ? "-" : r.latest(),
                    "url", r.url() == null ? "" : r.url(), "error", r.error() == null ? "" : r.error()));
        }
        return out;
    }

    private void runMain(Runnable r) {
        if (!plugin.isEnabled()) return;
        try {
            Bukkit.getScheduler().runTask(plugin, r);
        } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) {
            // 關閉中
        }
    }
}
