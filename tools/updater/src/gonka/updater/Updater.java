package gonka.updater;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Keeps a player's Prism instance on the newest GitHub release of the pack.
 *
 * Prism runs this as the instance's pre-launch command, so it happens every time the
 * player presses Launch and they never update by hand.
 *
 * It does NOT download the release zip. The zip is ~765 MB and most releases change a
 * handful of files, so it reads the zip's central directory with an HTTP range request
 * (GitHub's asset host honours ranges), compares each entry's CRC-32 with the file on
 * disk, and fetches only the entries that differ. A fresh install is the same code path
 * with every entry differing.
 *
 * Failure is safe by construction: everything is downloaded into updater/staging/ and
 * CRC-checked before a single file in the instance is touched. A dropped connection
 * leaves the instance exactly as it was, the player plays the version they have, and
 * the next launch resumes from whatever entries already finished staging.
 *
 * Exit codes are what Prism acts on: non-zero aborts the launch. So this exits 0 on any
 * network or GitHub failure (play what you have) and 1 only when the launch MUST be
 * repeated - the release changed mmc-pack.json, and Prism has already resolved the old
 * loader for this launch.
 */
public final class Updater {

    static final String VERSION = "1.0.0";
    static final String REPO = "gonkhub/gonkas-mc-nations";
    static final String USER_AGENT = "GonkasMCNations-Updater/" + VERSION;

    /** Pack content the player has no business editing: always forced to the release. */
    static final List<String> FORCED = List.of(
            "minecraft/mods/", "minecraft/kubejs/", "minecraft/defaultconfigs/");

    /**
     * Written only if missing. Minecraft rewrites both on every launch (keybinds, video
     * settings, the server list and its cached icons), so "changed locally" is always
     * true and the release copy is only ever a starting point.
     */
    static final Set<String> SEED_ONLY = Set.of("minecraft/options.txt", "minecraft/servers.dat");

    /** Never touched. instance.cfg holds the player's memory and Java settings. */
    static final Set<String> NEVER = Set.of("instance.cfg");

    /** Our own folder - the running jar cannot replace itself on Windows anyway. */
    static final String OWN_DIR = "updater/";

    static final int THREADS = 3;
    /** Neighbouring entries closer than this are fetched in one request. */
    static final long MERGE_GAP = 1L << 20;
    /** But no single request is allowed to grow past this (a big jar alone may). */
    static final long CHUNK_CAP = 96L << 20;
    static final long STALL_MS = 60_000;

    final Path inst;
    final Path own;
    final Path staging;
    final HttpClient http;
    final AtomicLong lastProgress = new AtomicLong(System.currentTimeMillis());
    final Set<InputStream> openStreams = ConcurrentHashMap.newKeySet();

    Updater(Path inst) {
        this.inst = inst;
        this.own = inst.resolve("updater");
        this.staging = own.resolve("staging");
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    public static void main(String[] args) {
        Path inst = findInstance(args);
        log("Gonka's MC Nations updater " + VERSION + " - instance: " + inst);
        try {
            int code = new Updater(inst).run();
            System.exit(code);
        } catch (Throwable t) {
            log("");
            log("Could not update: " + t);
            log("Launching the version you already have. If the server refuses you, it");
            log("probably needs the newer version - try launching again in a minute.");
            System.exit(0);
        }
    }

    static Path findInstance(String[] args) {
        if (args.length > 0) return Paths.get(args[0]).toAbsolutePath().normalize();
        String env = System.getenv("INST_DIR");
        if (env != null && !env.isBlank()) return Paths.get(env).toAbsolutePath().normalize();
        // Fallback: the jar lives in <instance>/updater/.
        try {
            Path jar = Paths.get(Updater.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return jar.getParent().getParent();
        } catch (Exception e) {
            return Paths.get("").toAbsolutePath();
        }
    }

    // ───────────────────────────────────────────────────────────── the run

    int run() throws Exception {
        Files.createDirectories(own);
        State state = State.load(own.resolve("state.tsv"));

        Release rel = latestRelease();
        log("Newest release: " + rel.tag + (state.version == null ? "" : "   (installed: " + state.version + ")"));
        if (rel.tag.equals(state.version) && Files.isDirectory(inst.resolve("minecraft/mods"))) {
            log("Up to date.");
            return 0;
        }

        RemoteZip zip = new RemoteZip(rel.zipUrl);
        this.zip = zip;
        List<Entry> entries = zip.centralDirectory();
        log("Release has " + entries.size() + " files; comparing with this instance...");

        Map<String, Entry> byName = new LinkedHashMap<>();
        for (Entry e : entries) if (!e.isDir()) byName.put(e.name, e);

        // Resume: anything already staged and verified is not fetched again.
        String stagedTag = readString(own.resolve("staging.tag"));
        if (!rel.tag.equals(stagedTag)) {
            deleteTree(staging);
            Files.createDirectories(staging);
            Files.writeString(own.resolve("staging.tag"), rel.tag);
        }

        // Decide what to fetch.
        List<Entry> wanted = new ArrayList<>();
        for (Entry e : byName.values()) {
            if (wants(e, state)) wanted.add(e);
        }

        List<Entry> toFetch = new ArrayList<>();
        for (Entry e : wanted) {
            Path s = stagedPath(e);
            if (Files.isRegularFile(s) && Files.size(s) == e.size && crcOf(s) == e.crc) continue;
            toFetch.add(e);
        }

        long bytes = 0;
        for (Entry e : toFetch) bytes += e.csize;
        if (wanted.isEmpty()) {
            log("Every file already matches " + rel.tag + ".");
        } else {
            log(wanted.size() + " file(s) to update, " + mb(bytes) + " to download"
                    + (toFetch.size() < wanted.size() ? " (" + (wanted.size() - toFetch.size()) + " already downloaded)" : "") + ".");
        }
        if (!toFetch.isEmpty()) download(zip, toFetch, bytes);

        // ── Everything is on disk and verified. Only now touch the instance. ──
        boolean loaderChanged = false;
        for (Entry e : wanted) {
            Path target = inst.resolve(e.name);
            if (e.name.equals("mmc-pack.json")) {
                // Prism rewrites this file with cached fields of its own, so a byte
                // difference is normal. Only a different component version matters.
                loaderChanged = !Files.isRegularFile(target)
                        || !components(Files.readString(target)).equals(components(Files.readString(stagedPath(e))));
            }
            Files.createDirectories(target.getParent());
            Files.move(stagedPath(e), target, StandardCopyOption.REPLACE_EXISTING);
        }

        // Files the pack used to ship and no longer does. A stale mod jar beside its
        // replacement is a duplicate-mod-id crash, so this is not housekeeping.
        int removed = 0;
        for (String path : state.paths()) {
            if (byName.containsKey(path) || SEED_ONLY.contains(path) || !managed(path)) continue;
            if (Files.deleteIfExists(inst.resolve(path))) {
                log("  removed " + path);
                removed++;
            }
        }
        // First run on an instance that was updated by hand: an old jar the pack no
        // longer ships is almost certainly a leftover. Set aside, not deleted.
        if (state.version == null) removed += setAsideUnknownMods(byName);

        State next = new State();
        next.version = rel.tag;
        for (Entry e : byName.values()) {
            if (!managed(e.name)) continue;
            Path p = inst.resolve(e.name);
            if (!Files.isRegularFile(p)) continue;
            long size = Files.size(p), mtime = Files.getLastModifiedTime(p).toMillis();
            Long cached = state.localCrc(e.name, size, mtime);
            long crc = cached != null ? cached : crcOf(p);
            next.put(e.name, e.crc, size, mtime, crc);
        }
        next.save(own.resolve("state.tsv"));
        deleteTree(staging);
        Files.deleteIfExists(own.resolve("staging.tag"));

        log("Updated to " + rel.tag + ": " + wanted.size() + " file(s) written, " + removed + " removed.");

        Entry selfJar = byName.get("updater/nations-updater.jar");
        Path running = own.resolve("nations-updater.jar");
        if (selfJar != null && Files.isRegularFile(running) && crcOf(running) != selfJar.crc) {
            log("Note: this release ships a newer updater. It will keep working; re-importing");
            log("the pack once will pick the new one up.");
        }

        if (loaderChanged) {
            log("");
            log("****************************************************************");
            log("  This update changed the Minecraft/NeoForge version.");
            log("  Prism already prepared the OLD version for this launch, so it");
            log("  has been stopped on purpose. Just press Launch again.");
            log("****************************************************************");
            return 1;
        }
        return 0;
    }

    /** Should this release entry be written into the instance? */
    boolean wants(Entry e, State state) throws Exception {
        if (!managed(e.name)) return false;
        Path p = inst.resolve(e.name);
        if (!Files.isRegularFile(p)) return true;
        if (SEED_ONLY.contains(e.name)) return false;

        if (e.name.equals("mmc-pack.json")) {
            // Prism rewrites this with cached fields, so bytes always drift. What must
            // match the server is the Minecraft/NeoForge versions, whatever the state
            // file says - a loader mismatch is never a local preference to keep.
            return !components(Files.readString(p)).equals(components(fetchSmall(e)));
        }

        long size = Files.size(p), mtime = Files.getLastModifiedTime(p).toMillis();
        if (size == e.size) {
            Long cached = state.localCrc(e.name, size, mtime);
            long crc = cached != null ? cached : crcOf(p);
            if (crc == e.crc) return false;
        }
        for (String f : FORCED) if (e.name.startsWith(f)) return true;

        // A config that differs locally. Mods rewrite their own configs on load, so a
        // difference does not mean the player edited it. The rule: if the PACK changed
        // this file since we last installed it, the pack's version lands; if the pack
        // did not, whatever is on disk stays.
        Long installed = state.releaseCrc(e.name);
        return installed == null || installed != e.crc;
    }

    static boolean managed(String path) {
        if (NEVER.contains(path)) return false;
        if (path.startsWith(OWN_DIR)) return false;
        if (path.startsWith("/") || path.contains("..") || path.contains(":")) return false;
        return true;
    }

    int setAsideUnknownMods(Map<String, Entry> release) throws IOException {
        Path mods = inst.resolve("minecraft/mods");
        if (!Files.isDirectory(mods)) return 0;
        Path aside = own.resolve("removed-mods");
        int n = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(mods, "*.jar")) {
            for (Path jar : ds) {
                String rel = "minecraft/mods/" + jar.getFileName();
                if (release.containsKey(rel)) continue;
                Files.createDirectories(aside);
                Files.move(jar, aside.resolve(jar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                log("  set aside " + rel + " -> updater/removed-mods/ (not in this release)");
                n++;
            }
        }
        return n;
    }

    RemoteZip zip;

    /** One entry's content, staged (so it is not fetched twice) and read back. */
    String fetchSmall(Entry e) throws Exception {
        Path s = stagedPath(e);
        if (!(Files.isRegularFile(s) && crcOf(s) == e.crc)) fetchChunk(zip, List.of(e), new AtomicLong());
        return Files.readString(s);
    }

    Path stagedPath(Entry e) {
        return staging.resolve(e.name);
    }

    // ───────────────────────────────────────────────────────────── GitHub

    record Release(String tag, String zipUrl) {}

    Release latestRelease() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + REPO + "/releases/latest"))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(30))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IOException("GitHub answered " + res.statusCode() + " for the latest release"
                    + (res.statusCode() == 403 ? " (rate limited - try again in a while)" : ""));
        }
        String body = res.body();
        Matcher tag = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        if (!tag.find()) throw new IOException("no tag_name in GitHub's answer");
        // The pack zip, not the launcher zip: the launcher may be attached to the same
        // release for players to download, and installing it over itself would wipe the pack.
        Matcher url = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.zip)\"").matcher(body);
        while (url.find()) {
            String file = url.group(1).substring(url.group(1).lastIndexOf('/') + 1);
            if (!file.contains("Launcher")) return new Release(tag.group(1), url.group(1));
        }
        throw new IOException("release " + tag.group(1) + " has no pack .zip asset");
    }

    // ───────────────────────────────────────────────────────────── remote zip

    record Entry(String name, long crc, long csize, long size, int method, long offset) {
        boolean isDir() { return name.endsWith("/"); }
    }

    final class RemoteZip {
        final String url;
        long length = -1;

        RemoteZip(String url) { this.url = url; }

        HttpResponse<InputStream> open(String range) throws Exception {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .header("Range", "bytes=" + range)
                    .build();
            HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (res.statusCode() != 206) {
                res.body().close();
                throw new IOException("download host answered " + res.statusCode() + " to a range request");
            }
            if (length < 0) {
                String cr = res.headers().firstValue("Content-Range").orElse("");
                int slash = cr.lastIndexOf('/');
                if (slash > 0) length = Long.parseLong(cr.substring(slash + 1).trim());
            }
            return res;
        }

        byte[] read(String range) throws Exception {
            try (InputStream in = open(range).body()) {
                return in.readAllBytes();
            }
        }

        byte[] read(long from, long len) throws Exception {
            return read(from + "-" + (from + len - 1));
        }

        List<Entry> centralDirectory() throws Exception {
            // EOCD is 22 bytes plus a comment of up to 65535. GitHub's asset host (Azure
            // blob storage) answers 501 to a suffix range ("bytes=-N"), so learn the
            // length from a one-byte request and ask for the tail explicitly.
            read("0-0");
            if (length < 0) throw new IOException("download host did not report the file size");
            long tailStart = Math.max(0, length - 65557);
            byte[] tail = read(tailStart, length - tailStart);
            ByteBuffer t = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN);
            int eocd = -1;
            for (int i = tail.length - 22; i >= 0; i--) {
                if (t.getInt(i) == 0x06054b50) { eocd = i; break; }
            }
            if (eocd < 0) throw new IOException("release zip has no end-of-central-directory record");
            long count = t.getShort(eocd + 10) & 0xffff;
            long cdSize = t.getInt(eocd + 12) & 0xffffffffL;
            long cdOff = t.getInt(eocd + 16) & 0xffffffffL;

            if (cdOff == 0xffffffffL || count == 0xffff || cdSize == 0xffffffffL) {
                int loc = eocd - 20;
                if (loc < 0 || t.getInt(loc) != 0x07064b50) throw new IOException("zip64 locator missing");
                long z64 = t.getLong(loc + 8);
                ByteBuffer z = ByteBuffer.wrap(read(z64, 56)).order(ByteOrder.LITTLE_ENDIAN);
                if (z.getInt(0) != 0x06064b50) throw new IOException("zip64 end record missing");
                count = z.getLong(32);
                cdSize = z.getLong(40);
                cdOff = z.getLong(48);
            }

            byte[] cd;
            if (cdOff >= tailStart) {
                int from = (int) (cdOff - tailStart);
                cd = java.util.Arrays.copyOfRange(tail, from, from + (int) cdSize);
            } else {
                cd = read(cdOff, cdSize);
            }

            ByteBuffer b = ByteBuffer.wrap(cd).order(ByteOrder.LITTLE_ENDIAN);
            List<Entry> out = new ArrayList<>();
            int p = 0;
            for (long i = 0; i < count; i++) {
                if (b.getInt(p) != 0x02014b50) throw new IOException("bad central directory entry " + i);
                int flags = b.getShort(p + 8) & 0xffff;
                int method = b.getShort(p + 10) & 0xffff;
                long crc = b.getInt(p + 16) & 0xffffffffL;
                long csize = b.getInt(p + 20) & 0xffffffffL;
                long size = b.getInt(p + 24) & 0xffffffffL;
                int nlen = b.getShort(p + 28) & 0xffff;
                int elen = b.getShort(p + 30) & 0xffff;
                int clen = b.getShort(p + 32) & 0xffff;
                long off = b.getInt(p + 42) & 0xffffffffL;
                Charset cs = (flags & 0x800) != 0 ? StandardCharsets.UTF_8 : Charset.forName("IBM437");
                String name = new String(cd, p + 46, nlen, cs).replace('\\', '/');

                // zip64 extra: present fields appear in this fixed order.
                int x = p + 46 + nlen, xEnd = x + elen;
                while (x + 4 <= xEnd) {
                    int id = b.getShort(x) & 0xffff, len = b.getShort(x + 2) & 0xffff;
                    if (id == 0x0001) {
                        int q = x + 4;
                        if (size == 0xffffffffL) { size = b.getLong(q); q += 8; }
                        if (csize == 0xffffffffL) { csize = b.getLong(q); q += 8; }
                        if (off == 0xffffffffL) { off = b.getLong(q); }
                    }
                    x += 4 + len;
                }
                if (method != 0 && method != 8) throw new IOException(name + ": unsupported compression " + method);
                out.add(new Entry(name, crc, csize, size, method, off));
                p += 46 + nlen + elen + clen;
            }
            return out;
        }
    }

    // ───────────────────────────────────────────────────────────── download

    void download(RemoteZip zip, List<Entry> entries, long totalBytes) throws Exception {
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingLong(Entry::offset));

        // Group neighbours into ranged requests. Each entry's span is approximated as
        // header + name + 256 bytes of extra + data; the real local header is parsed.
        List<List<Entry>> chunks = new ArrayList<>();
        List<Entry> cur = null;
        long curStart = 0, curEnd = 0;
        for (Entry e : sorted) {
            long end = spanEnd(e, zip.length);
            if (cur != null && e.offset - curEnd <= MERGE_GAP && end - curStart <= CHUNK_CAP) {
                cur.add(e);
                curEnd = Math.max(curEnd, end);
            } else {
                cur = new ArrayList<>();
                cur.add(e);
                chunks.add(cur);
                curStart = e.offset;
                curEnd = end;
            }
        }

        AtomicLong done = new AtomicLong();
        long started = System.currentTimeMillis();
        Thread reporter = new Thread(() -> {
            long lastPct = -1;
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(2000);
                    long d = done.get();
                    long pct = totalBytes == 0 ? 100 : d * 100 / totalBytes;
                    if (pct != lastPct) {
                        double secs = Math.max(1, (System.currentTimeMillis() - started) / 1000.0);
                        log(String.format("  %3d%%  %s of %s  (%.1f MB/s)", pct, mb(d), mb(totalBytes), d / secs / 1048576));
                        lastPct = pct;
                    }
                    if (System.currentTimeMillis() - lastProgress.get() > STALL_MS) {
                        log("  connection stalled - retrying");
                        for (InputStream in : openStreams) try { in.close(); } catch (IOException ignored) { }
                        lastProgress.set(System.currentTimeMillis());
                    }
                }
            } catch (InterruptedException ignored) { }
        }, "progress");
        reporter.setDaemon(true);
        reporter.start();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (List<Entry> chunk : chunks) {
                futures.add(pool.submit(() -> { fetchChunk(zip, chunk, done); return null; }));
            }
            for (Future<?> f : futures) f.get();
        } finally {
            pool.shutdownNow();
            reporter.interrupt();
        }
        log("  100%  " + mb(totalBytes) + " downloaded.");
    }

    static long spanEnd(Entry e, long length) {
        long end = e.offset + 30 + e.name.getBytes(StandardCharsets.UTF_8).length + 256 + e.csize;
        return length > 0 ? Math.min(end, length) : end;
    }

    void fetchChunk(RemoteZip zip, List<Entry> chunk, AtomicLong done) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= 4; attempt++) {
            // Entries finished on an earlier attempt are skipped.
            List<Entry> todo = new ArrayList<>();
            for (Entry e : chunk) {
                Path s = stagedPath(e);
                if (!(Files.isRegularFile(s) && Files.size(s) == e.size && crcOf(s) == e.crc)) todo.add(e);
            }
            if (todo.isEmpty()) return;
            long start = todo.get(0).offset;
            long end = 0;
            for (Entry e : todo) end = Math.max(end, spanEnd(e, zip.length));
            try (InputStream raw = zip.open(start + "-" + (end - 1)).body()) {
                openStreams.add(raw);
                try {
                    Positioned in = new Positioned(raw, start);
                    for (Entry e : todo) extract(in, e, done);
                } finally {
                    openStreams.remove(raw);
                }
                return;
            } catch (Exception ex) {
                last = ex;
                if (attempt < 4) {
                    log("  retrying a download (" + ex.getMessage() + ")");
                    Thread.sleep(2000L * attempt);
                }
            }
        }
        throw last;
    }

    void extract(Positioned in, Entry e, AtomicLong done) throws IOException {
        in.skipTo(e.offset);
        byte[] h = in.readExactly(30);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt(0) != 0x04034b50) throw new IOException(e.name + ": bad local header");
        int nlen = b.getShort(26) & 0xffff, elen = b.getShort(28) & 0xffff;
        in.skipTo(e.offset + 30 + nlen + elen);

        Path out = stagedPath(e);
        Files.createDirectories(out.getParent());
        Path part = out.resolveSibling(out.getFileName() + ".part");

        Bounded raw = new Bounded(in, e.csize, done, lastProgress);
        Inflater inf = e.method == 8 ? new Inflater(true) : null;
        InputStream data = inf != null ? new InflaterInputStream(raw, inf, 65536) : raw;
        CRC32 crc = new CRC32();
        long n = 0;
        try (OutputStream os = Files.newOutputStream(part)) {
            byte[] buf = new byte[65536];
            int r;
            while ((r = data.read(buf)) > 0) {
                crc.update(buf, 0, r);
                os.write(buf, 0, r);
                n += r;
            }
        }
        if (inf != null) inf.end();
        // A deflate stream can end before its compressed bytes are all consumed; read
        // the rest so the progress count and the stream position stay honest.
        byte[] skip = new byte[8192];
        while (raw.remaining() > 0 && raw.read(skip, 0, skip.length) > 0) { }
        if (n != e.size || crc.getValue() != e.crc) {
            Files.deleteIfExists(part);
            throw new IOException(e.name + ": downloaded copy failed its checksum");
        }
        Files.move(part, out, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Tracks absolute position in the zip so entries can be skipped to by offset. */
    static final class Positioned extends FilterInputStream {
        long pos;

        Positioned(InputStream in, long start) { super(in); this.pos = start; }

        @Override public int read() throws IOException {
            int r = super.read();
            if (r >= 0) pos++;
            return r;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            int r = super.read(b, off, len);
            if (r > 0) pos += r;
            return r;
        }

        void skipTo(long target) throws IOException {
            if (target < pos) throw new IOException("zip entries overlap");
            byte[] buf = new byte[8192];
            while (pos < target) {
                int r = read(buf, 0, (int) Math.min(buf.length, target - pos));
                if (r < 0) throw new EOFException();
            }
        }

        byte[] readExactly(int n) throws IOException {
            byte[] out = new byte[n];
            int got = 0;
            while (got < n) {
                int r = read(out, got, n - got);
                if (r < 0) throw new EOFException();
                got += r;
            }
            return out;
        }
    }

    /** Exactly {@code limit} bytes of the underlying stream, counted for progress. */
    static final class Bounded extends InputStream {
        final InputStream in;
        long left;
        final AtomicLong done;
        final AtomicLong lastProgress;

        Bounded(InputStream in, long limit, AtomicLong done, AtomicLong lastProgress) {
            this.in = in; this.left = limit; this.done = done; this.lastProgress = lastProgress;
        }

        long remaining() { return left; }

        @Override public int read() throws IOException {
            if (left <= 0) return -1;
            int r = in.read();
            if (r < 0) throw new EOFException();
            left--; done.incrementAndGet(); lastProgress.set(System.currentTimeMillis());
            return r;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int r = in.read(b, off, (int) Math.min(len, left));
            if (r < 0) throw new EOFException();
            left -= r; done.addAndGet(r); lastProgress.set(System.currentTimeMillis());
            return r;
        }
    }

    // ───────────────────────────────────────────────────────────── state

    /** What we installed last time, plus a CRC cache keyed on size + mtime. */
    static final class State {
        String version;
        final Map<String, long[]> rows = new HashMap<>(); // releaseCrc, size, mtime, localCrc

        static State load(Path file) {
            State s = new State();
            if (!Files.isRegularFile(file)) return s;
            try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line = r.readLine();
                if (line == null || !line.startsWith("version\t")) return new State();
                s.version = line.substring(8);
                while ((line = r.readLine()) != null) {
                    String[] f = line.split("\t");
                    if (f.length != 5) continue;
                    s.rows.put(f[0], new long[] {
                            Long.parseLong(f[1]), Long.parseLong(f[2]), Long.parseLong(f[3]), Long.parseLong(f[4]) });
                }
            } catch (Exception e) {
                return new State(); // a damaged state file just means a full comparison
            }
            return s;
        }

        void put(String path, long releaseCrc, long size, long mtime, long localCrc) {
            rows.put(path, new long[] { releaseCrc, size, mtime, localCrc });
        }

        Long releaseCrc(String path) {
            long[] r = rows.get(path);
            return r == null ? null : r[0];
        }

        Long localCrc(String path, long size, long mtime) {
            long[] r = rows.get(path);
            return r != null && r[1] == size && r[2] == mtime ? r[3] : null;
        }

        List<String> paths() {
            List<String> p = new ArrayList<>(rows.keySet());
            Collections.sort(p);
            return p;
        }

        void save(Path file) throws IOException {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                w.write("version\t" + version + "\n");
                for (String p : paths()) {
                    long[] r = rows.get(p);
                    w.write(p + "\t" + r[0] + "\t" + r[1] + "\t" + r[2] + "\t" + r[3] + "\n");
                }
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ───────────────────────────────────────────────────────────── helpers

    static long crcOf(Path p) throws IOException {
        CRC32 crc = new CRC32();
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) > 0) crc.update(buf, 0, r);
        }
        return crc.getValue();
    }

    /** The uid/version pairs of an mmc-pack.json, ignoring Prism's cached fields. */
    static List<String> components(String json) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\"(uid|version)\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        while (m.find()) out.add(m.group(1) + "=" + m.group(2));
        Collections.sort(out);
        return out;
    }

    static String readString(Path p) {
        try { return Files.readString(p).trim(); } catch (IOException e) { return null; }
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    static String mb(long bytes) {
        return String.format("%.1f MB", bytes / 1048576.0);
    }

    static void log(String s) {
        System.out.println("[updater] " + s);
        System.out.flush();
    }
}
