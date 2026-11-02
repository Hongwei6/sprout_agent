package com.sprout.agent.agents.skills;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

@Component
public class SkillInstaller {
    private static final int MAX_DOWNLOAD = 5 * 1024 * 1024;
    private static final int MAX_UNPACKED = 10 * 1024 * 1024;
    private static final int MAX_ENTRIES = 100;
    private static final Pattern GITHUB = Pattern.compile("https://github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)/(?:tree|blob)/([A-Za-z0-9_.-]+)/(.+)");
    private static final Pattern GITHUB_PAGE = Pattern.compile("https://github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)(?:/(?:tree|blob)/([A-Za-z0-9_.-]+))?");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");
    private final SkillRegistry registry;
    private final Downloader downloader;
    private final Resolver resolver;

    @Autowired
    public SkillInstaller(SkillRegistry registry) {
        this(registry, SkillInstaller::download, InetAddress::getAllByName);
    }

    SkillInstaller(SkillRegistry registry, Downloader downloader, Resolver resolver) {
        this.registry = registry;
        this.downloader = downloader;
        this.resolver = resolver;
    }

    public SkillRegistry.Skill install(String address) {
        return install(address, null);
    }

    public SkillRegistry.Skill install(String address, String skillName) {
        if (address == null || address.length() > 2048 || !address.equals(address.strip())) {
            throw new IllegalArgumentException("请输入技能网页链接、SKILL.md 或技能 ZIP 地址");
        }
        if (skillName != null && !skillName.isBlank() && !NAME.matcher(skillName).matches()) {
            throw new IllegalArgumentException("技能名称无效");
        }
        while (address.endsWith("/") && address.lastIndexOf('/') > 8) {
            address = address.substring(0, address.length() - 1);
        }
        Matcher match = GITHUB.matcher(address);
        if (match.matches()) {
            String subpath = match.group(4);
            if (subpath.endsWith("/SKILL.md")) subpath = subpath.substring(0, subpath.length() - 9);
            return installGitHub(match.group(1), match.group(2), match.group(3), subpath, skillName);
        }
        Matcher page = GITHUB_PAGE.matcher(address);
        if (page.matches()) {
            return installGitHub(page.group(1), page.group(2), page.group(3), null, skillName);
        }
        URI uri;
        try {
            uri = URI.create(address);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("技能地址无效", ex);
        }
        String path = uri.getRawPath();
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getPort() > 65535
                || path == null || path.contains("%") || path.contains("\\") || path.contains("//")
                || !path.matches("/(?:[A-Za-z0-9_.@~-]+/)*[A-Za-z0-9_.@~-]+")) {
            throw new IllegalArgumentException("仅支持公开 HTTPS SKILL.md 或 ZIP 直链");
        }
        for (String segment : path.split("/")) {
            if (segment.equals(".") || segment.equals("..")) throw new IllegalArgumentException("技能路径无效");
        }
        boolean zip = path.toLowerCase(java.util.Locale.ROOT).endsWith(".zip");
        if (!zip && !path.endsWith("/SKILL.md")) throw new IllegalArgumentException("链接必须指向 SKILL.md 或 ZIP");
        try {
            InetAddress[] addresses = resolver.resolve(uri.getHost());
            if (addresses.length == 0) throw new IllegalArgumentException("技能地址无法解析");
            for (InetAddress ip : addresses) {
                if (!publicAddress(ip)) throw new IllegalArgumentException("技能地址不是公网地址");
            }
            byte[] bytes = downloader.get(uri, addresses[0]);
            if (bytes.length > MAX_DOWNLOAD) throw new IllegalArgumentException("技能下载超过 5 MiB");
            if (zip) return installZip(bytes);
            String[] segments = path.split("/");
            if (bytes.length > 128 * 1024) throw new IllegalArgumentException("SKILL.md 超过 128 KiB");
            String content = new String(bytes, StandardCharsets.UTF_8);
            String name = segments.length < 3 ? declaredName(content) : segments[segments.length - 2];
            if (name == null || !NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("SKILL.md 必须位于合法名称的技能目录中，或声明 name");
            }
            return registry.install(name, content);
        } catch (IOException ex) {
            throw new IllegalStateException("下载技能失败", ex);
        }
    }

    private static String declaredName(String content) {
        if (!content.startsWith("---\n")) return null;
        int end = content.indexOf("\n---", 4);
        if (end < 0) return null;
        Object yaml = new Yaml(new SafeConstructor(new LoaderOptions())).load(content.substring(4, end));
        return yaml instanceof Map<?, ?> metadata && metadata.get("name") instanceof String name ? name : null;
    }

    private SkillRegistry.Skill installGitHub(String owner, String repo, String ref,
            String subpath, String skillName) {
        String archiveUrl = "https://codeload.github.com/" + owner + "/" + repo + "/zip/"
                + (ref == null ? "HEAD" : "refs/heads/" + ref);
        byte[] bytes = fetch(archiveUrl);
        Set<String> approved = validateZipDirectory(bytes, 5000);
        String root = approved.iterator().next().split("/", 2)[0] + "/";
        List<String> skills = new ArrayList<>();
        for (String entry : approved) {
            if (!entry.startsWith(root)) throw new IllegalArgumentException("仓库 ZIP 包含多个根目录");
            String path = entry.substring(root.length());
            if (path.equals("SKILL.md") || path.endsWith("/SKILL.md")) {
                skills.add(path.equals("SKILL.md") ? "" : path.substring(0, path.length() - 9));
            }
        }
        if (subpath != null) {
            if (!skills.contains(subpath)) throw new IllegalArgumentException("技能目录不存在: " + subpath);
        } else if (skillName != null && !skillName.isBlank()) {
            List<String> matches = skills.stream()
                    .filter(path -> path.substring(path.lastIndexOf('/') + 1).equals(skillName)).toList();
            if (matches.size() != 1) throw new IllegalArgumentException("找不到唯一的技能: " + skillName);
            subpath = matches.getFirst();
        } else if (skills.size() == 1) {
            subpath = skills.getFirst();
        } else if (skills.isEmpty()) {
            throw new IllegalArgumentException("仓库中没有找到 SKILL.md，请确认是技能仓库");
        } else {
            throw new IllegalArgumentException("仓库包含 " + skills.size() + " 个技能（"
                    + String.join("、", skills.stream().limit(10)
                        .map(s -> s.substring(s.lastIndexOf('/') + 1)).toList())
                    + "），请在 install_skill 的 skillName 参数指定技能名");
        }
        String name = subpath.isEmpty() ? null : subpath.substring(subpath.lastIndexOf('/') + 1);
        String prefix = root + (subpath.isEmpty() ? "" : subpath + "/");
        Map<String, byte[]> files = new HashMap<>();
        int total = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!approved.remove(entry.getName())) throw new IllegalArgumentException("ZIP 索引和文件不一致");
                if (entry.isDirectory() || !entry.getName().startsWith(prefix)) continue;
                String relative = entry.getName().substring(prefix.length());
                if (!relative.matches("(?:[A-Za-z0-9_.@~-]+/)*[A-Za-z0-9_.@~-]+")
                        || List.of(relative.split("/")).contains(".")
                        || List.of(relative.split("/")).contains("..")
                        || ++entries > MAX_ENTRIES) {
                    throw new IllegalArgumentException("技能目录包含不安全文件或超过 100 个文件");
                }
                byte[] content = zip.readNBytes(MAX_UNPACKED - total + 1);
                total += content.length;
                if (total > MAX_UNPACKED) throw new IllegalArgumentException("技能目录超过 10 MiB");
                files.put(relative, content);
            }
            if (!approved.isEmpty()) throw new IllegalArgumentException("ZIP 索引和文件不一致");
        } catch (IOException ex) {
            throw new IllegalStateException("解压技能目录失败", ex);
        }
        if (!files.containsKey("SKILL.md")) throw new IllegalArgumentException("技能目录缺少 SKILL.md");
        if (name == null) name = declaredName(new String(files.get("SKILL.md"), StandardCharsets.UTF_8));
        return registry.installArchive(name, files);
    }

    private byte[] fetch(String url) {
        URI target = URI.create(url);
        try {
            InetAddress[] addresses = resolver.resolve(target.getHost());
            if (addresses.length == 0) throw new IllegalArgumentException("技能地址无法解析");
            for (InetAddress ip : addresses) {
                if (!publicAddress(ip)) throw new IllegalArgumentException("技能地址不是公网地址");
            }
            return downloader.get(target, addresses[0]);
        } catch (IOException ex) {
            throw new IllegalStateException("下载技能失败", ex);
        }
    }

    private SkillRegistry.Skill installZip(byte[] bytes) throws IOException {
        Set<String> approved = validateZipDirectory(bytes);
        Map<String, byte[]> files = new HashMap<>();
        String root = null;
        int count = 0;
        int total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > MAX_ENTRIES) throw new IllegalArgumentException("ZIP 文件数超过限制");
                if (!approved.remove(entry.getName())) throw new IllegalArgumentException("ZIP 索引和文件不一致");
                String name = entry.getName();
                if (name.contains("\\") || name.startsWith("/") || name.contains("//") || name.contains("%")
                        || name.indexOf('\0') >= 0 || name.matches("^[A-Za-z]:.*")) {
                    throw new IllegalArgumentException("ZIP 路径不安全");
                }
                String[] parts = name.split("/", -1);
                if (parts.length < 2 || !NAME.matcher(parts[0]).matches()
                        || (root != null && !root.equals(parts[0]))) {
                    throw new IllegalArgumentException("ZIP 必须只包含一个技能目录");
                }
                root = parts[0];
                for (int i = 1; i < parts.length; i++) {
                    if (parts[i].equals(".") || parts[i].equals("..") || parts[i].isEmpty() && i != parts.length - 1) {
                        throw new IllegalArgumentException("ZIP 路径不安全");
                    }
                }
                if (entry.isDirectory()) continue;
                if (parts.length < 2 || parts[parts.length - 1].isEmpty()) throw new IllegalArgumentException("ZIP 路径不安全");
                int remaining = MAX_UNPACKED - total;
                byte[] content = zip.readNBytes(remaining + 1);
                total += content.length;
                if (total > MAX_UNPACKED) throw new IllegalArgumentException("ZIP 解压后超过 10 MiB");
                if (name.equals(root + "/SKILL.md") && content.length > 128 * 1024) {
                    throw new IllegalArgumentException("SKILL.md 超过 128 KiB");
                }
                if (files.putIfAbsent(name.substring(root.length() + 1), content) != null) {
                    throw new IllegalArgumentException("ZIP 包含重复文件");
                }
            }
            if (!approved.isEmpty()) throw new IllegalArgumentException("ZIP 索引和文件不一致");
        }
        if (root == null || !files.containsKey("SKILL.md")) throw new IllegalArgumentException("ZIP 缺少 SKILL.md");
        return registry.installArchive(root, files);
    }
    private static Set<String> validateZipDirectory(byte[] bytes) {
        return validateZipDirectory(bytes, MAX_ENTRIES);
    }

    private static Set<String> validateZipDirectory(byte[] bytes, int entryLimit) {
        ByteBuffer archive = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int end = -1;
        for (int i = bytes.length - 22; i >= Math.max(0, bytes.length - 65557); i--) {
            if (archive.getInt(i) == 0x06054b50 && i + 22 + Short.toUnsignedInt(archive.getShort(i + 20)) == bytes.length) {
                end = i;
                break;
            }
        }
        if (end < 0 || archive.getShort(end + 4) != 0 || archive.getShort(end + 6) != 0) {
            throw new IllegalArgumentException("ZIP 索引无效");
        }
        int count = Short.toUnsignedInt(archive.getShort(end + 10));
        long size = Integer.toUnsignedLong(archive.getInt(end + 12));
        long start = Integer.toUnsignedLong(archive.getInt(end + 16));
        if (count == 0 || count > entryLimit || size > end || start > end - size || start + size != end
                || count != Short.toUnsignedInt(archive.getShort(end + 8))) {
            throw new IllegalArgumentException("ZIP 索引无效或文件数超限");
        }
        int offset = (int) start;
        Set<String> names = new HashSet<>();
        for (int i = 0; i < count; i++) {
            if (offset + 46 > end || archive.getInt(offset) != 0x02014b50) {
                throw new IllegalArgumentException("ZIP 索引无效");
            }
            int flags = Short.toUnsignedInt(archive.getShort(offset + 8));
            int method = Short.toUnsignedInt(archive.getShort(offset + 10));
            int nameLength = Short.toUnsignedInt(archive.getShort(offset + 28));
            int extraLength = Short.toUnsignedInt(archive.getShort(offset + 30));
            int commentLength = Short.toUnsignedInt(archive.getShort(offset + 32));
            int mode = Short.toUnsignedInt(archive.getShort(offset + 40));
            int type = mode & 0170000;
            if ((flags & 1) != 0 || method != 0 && method != 8
                    || type != 0 && type != 0100000 && type != 0040000
                    || offset + 46L + nameLength + extraLength + commentLength > end) {
                throw new IllegalArgumentException("ZIP 含加密、特殊文件或无效索引");
            }
            String name = new String(bytes, offset + 46, nameLength, StandardCharsets.UTF_8);
            if (type == 0040000 && !name.endsWith("/") || type == 0100000 && name.endsWith("/")
                    || !names.add(name)) throw new IllegalArgumentException("ZIP 含重复或无效文件");
            offset += 46 + nameLength + extraLength + commentLength;
        }
        if (offset != end) throw new IllegalArgumentException("ZIP 索引无效");
        return names;
    }


    static boolean publicAddress(InetAddress ip) {
        if (ip.isAnyLocalAddress() || ip.isLoopbackAddress() || ip.isLinkLocalAddress()
                || ip.isSiteLocalAddress() || ip.isMulticastAddress()) return false;
        byte[] b = ip.getAddress();
        if (ip instanceof Inet4Address) {
            int a = b[0] & 255, c = b[1] & 255, d = b[2] & 255;
            return a != 0 && a != 10 && a != 127 && a < 224 && !(a == 100 && c >= 64 && c <= 127)
                    && !(a == 169 && c == 254) && !(a == 172 && c >= 16 && c <= 31)
                    && !(a == 192 && (c == 0 || c == 168 || c == 88 && d == 99))
                    && !(a == 198 && c == 51 && d == 100);
        }
        return b.length == 16 && (b[0] & 0xe0) == 0x20
                && !((b[0] & 255) == 0x20 && (b[1] & 255) == 0x01
                    && ((b[2] & 255) == 0x0d && (b[3] & 255) == 0xb8
                        || b[2] == 0 && b[3] == 0))
                && !((b[0] & 255) == 0x20 && (b[1] & 255) == 0x02);
    }

    private static byte[] download(URI uri, InetAddress ip) throws IOException {
        int port = uri.getPort() < 0 ? 443 : uri.getPort();
        try (var plain = new java.net.Socket()) {
            plain.connect(new java.net.InetSocketAddress(ip, port), 5000);
            plain.setSoTimeout(10000);
            try (SSLSocket socket = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(plain, uri.getHost(), port, true)) {
                Thread deadline = Thread.ofVirtual().start(() -> {
                    try {
                        Thread.sleep(15_000);
                        socket.close();
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    } catch (IOException ignored) {
                        // The timeout closes a stalled download.
                    }
                });
                try {
                SSLParameters ssl = socket.getSSLParameters();
                ssl.setEndpointIdentificationAlgorithm("HTTPS");
                socket.setSSLParameters(ssl);
                socket.setSoTimeout(10000);
                socket.startHandshake();
                String host = uri.getHost().contains(":") ? "[" + uri.getHost() + "]" : uri.getHost();
                String target = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
                String request = "GET " + target + " HTTP/1.1\r\nHost: " + host
                        + (port == 443 ? "" : ":" + port)
                        + "\r\nUser-Agent: sprout-agent\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n";
                socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
                InputStream input = socket.getInputStream();
                String status = line(input);
                if (!status.matches("HTTP/1\\.[01] 200(?: .*)?")) {
                    throw new IllegalArgumentException("技能下载失败（仅接受 HTTP 200，不跟随跳转）");
                }
                boolean chunked = false;
                long length = -1;
                for (String header; !(header = line(input)).isEmpty();) {
                    String lower = header.toLowerCase(java.util.Locale.ROOT);
                    if (lower.startsWith("transfer-encoding:")) {
                        if (!lower.substring(18).strip().equals("chunked")) throw new IOException("不支持的传输编码");
                        chunked = true;
                    }
                    if (lower.startsWith("content-length:")) {
                        try { length = Long.parseLong(header.substring(15).strip()); }
                        catch (NumberFormatException ex) { throw new IOException("无效的文件长度", ex); }
                    }
                    if (lower.startsWith("content-encoding:") && !lower.substring(17).strip().equals("identity")) {
                        throw new IOException("不支持的内容编码");
                    }
                }
                if (length > MAX_DOWNLOAD) throw new IllegalArgumentException("技能下载超过 5 MiB");
                if (!chunked) {
                    byte[] result = input.readNBytes(length >= 0 ? (int) length : MAX_DOWNLOAD + 1);
                    if (result.length > MAX_DOWNLOAD) throw new IllegalArgumentException("技能下载超过 5 MiB");
                    if (length >= 0 && result.length != length) throw new IOException("下载文件不完整");
                    return result;
                }
                ByteArrayOutputStream result = new ByteArrayOutputStream();
                for (;;) {
                    String sizeLine = line(input).split(";", 2)[0];
                    int size;
                    try { size = Integer.parseUnsignedInt(sizeLine, 16); }
                    catch (NumberFormatException ex) { throw new IOException("无效的分块大小", ex); }
                    if (size == 0) break;
                    if (size > MAX_DOWNLOAD - result.size()) throw new IllegalArgumentException("技能下载超过 5 MiB");
                    byte[] chunk = input.readNBytes(size);
                    if (chunk.length != size || input.read() != '\r' || input.read() != '\n') throw new IOException("下载文件不完整");
                    result.write(chunk);
                }
                return result.toByteArray();
                } finally {
                    deadline.interrupt();
                }
            }
        }
    }

    private static String line(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int c; (c = input.read()) >= 0;) {
            if (c == '\n') {
                byte[] bytes = line.toByteArray();
                if (bytes.length > 0 && bytes[bytes.length - 1] == '\r') return new String(bytes, 0, bytes.length - 1, StandardCharsets.US_ASCII);
                throw new IOException("无效的 HTTP 换行");
            }
            if (line.size() >= 8192) throw new IOException("HTTP 头过长");
            line.write(c);
        }
        throw new IOException("下载文件不完整");
    }

    @FunctionalInterface interface Resolver { InetAddress[] resolve(String host) throws IOException; }
    @FunctionalInterface interface Downloader { byte[] get(URI uri, InetAddress ip) throws IOException; }
}
