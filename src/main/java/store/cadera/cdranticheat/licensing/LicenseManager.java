package store.cadera.cdranticheat.licensing;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import store.cadera.cdranticheat.CdrAntiCheat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

public final class LicenseManager {

    public enum Status {
        VALID,
        MISSING,
        MODIFIED,
        INTERNAL_ERROR
    }

    private static final String RESOURCE_NAME = "LICENSE.txt";
    private static final String RUNTIME_NAME = "LICENSE.txt";
    private static final String MARKER_NAME = ".license-state";

    private final CdrAntiCheat plugin;
    private final Path runtimeLicense;
    private final Path marker;
    private byte[] bundledLicense;
    private String expectedHash = "unavailable";
    private Status status = Status.INTERNAL_ERROR;
    private String statusDetail = "not initialized";
    private BukkitTask monitorTask;

    public LicenseManager(CdrAntiCheat plugin) {
        this.plugin = plugin;
        this.runtimeLicense = plugin.getDataFolder().toPath().resolve(RUNTIME_NAME);
        this.marker = plugin.getDataFolder().toPath().resolve(MARKER_NAME);
    }

    public boolean initialize() {
        try {
            bundledLicense = readBundledLicense();
            if (bundledLicense == null || bundledLicense.length == 0) {
                fail(Status.INTERNAL_ERROR, "bundled runtime license resource is missing");
                return false;
            }

            expectedHash = sha256(bundledLicense);
            Files.createDirectories(plugin.getDataFolder().toPath());

            boolean installationKnown = Files.exists(marker);
            if (!installationKnown) {
                if (!Files.exists(runtimeLicense)) {
                    Files.write(
                            runtimeLicense,
                            bundledLicense,
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE
                    );
                    plugin.getLogger().info("Runtime license created at plugins/CdrAntiCheat/" + RUNTIME_NAME + ".");
                }

                if (!validateRuntimeFile()) {
                    return false;
                }

                writeMarker();
                status = Status.VALID;
                statusDetail = "runtime license provisioned and verified";
                return true;
            }

            if (!validateRuntimeFile()) {
                return false;
            }

            if (!validateMarker()) {
                fail(Status.MODIFIED, "license state marker does not match the bundled license");
                return false;
            }

            status = Status.VALID;
            statusDetail = "runtime license verified";
            return true;
        } catch (IOException | NoSuchAlgorithmException exception) {
            fail(Status.INTERNAL_ERROR, exception.getClass().getSimpleName() + ": " + exception.getMessage());
            return false;
        }
    }

    public void startMonitor() {
        stopMonitor();
        long periodTicks = 20L * 60L;
        monitorTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!revalidate()) {
                plugin.getLogger().severe("CdrAntiCheat runtime license integrity was lost while the server was running.");
                plugin.getLogger().severe("CdrAntiCheat is disabling itself. Restore the original LICENSE.txt before restarting.");
                Bukkit.getPluginManager().disablePlugin(plugin);
            }
        }, periodTicks, periodTicks);
    }

    public boolean revalidate() {
        try {
            if (bundledLicense == null || bundledLicense.length == 0) {
                bundledLicense = readBundledLicense();
                if (bundledLicense == null || bundledLicense.length == 0) {
                    fail(Status.INTERNAL_ERROR, "bundled runtime license resource is missing");
                    return false;
                }
                expectedHash = sha256(bundledLicense);
            }

            if (!validateRuntimeFile()) {
                return false;
            }
            if (!validateMarker()) {
                fail(Status.MODIFIED, "license state marker does not match the bundled license");
                return false;
            }

            status = Status.VALID;
            statusDetail = "runtime license verified";
            return true;
        } catch (IOException | NoSuchAlgorithmException exception) {
            fail(Status.INTERNAL_ERROR, exception.getClass().getSimpleName() + ": " + exception.getMessage());
            return false;
        }
    }

    private boolean validateRuntimeFile() throws IOException, NoSuchAlgorithmException {
        if (!Files.isRegularFile(runtimeLicense)) {
            fail(Status.MISSING, "required plugins/CdrAntiCheat/" + RUNTIME_NAME + " is missing");
            return false;
        }

        byte[] actual = Files.readAllBytes(runtimeLicense);
        byte[] expectedDigest = digest(bundledLicense);
        byte[] actualDigest = digest(actual);
        if (!MessageDigest.isEqual(expectedDigest, actualDigest)) {
            fail(Status.MODIFIED, "runtime LICENSE.txt does not match the bundled MENKIESTES license");
            return false;
        }
        return true;
    }

    private boolean validateMarker() throws IOException {
        if (!Files.isRegularFile(marker)) {
            fail(Status.MISSING, "runtime license state marker is missing");
            return false;
        }
        String markerText = Files.readString(marker, StandardCharsets.UTF_8);
        return markerText.lines().anyMatch(line -> line.equalsIgnoreCase("sha256=" + expectedHash));
    }

    private void writeMarker() throws IOException {
        String text = "CdrAntiCheat runtime license state\n"
                + "license=MENKIESTES SOFTWARE LICENSE v1.0\n"
                + "sha256=" + expectedHash + "\n";
        Files.writeString(
                marker,
                text,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );
    }

    private byte[] readBundledLicense() throws IOException {
        try (InputStream stream = plugin.getResource(RESOURCE_NAME)) {
            return stream == null ? null : stream.readAllBytes();
        }
    }

    private String sha256(byte[] data) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(digest(data)).toLowerCase(Locale.ROOT);
    }

    private byte[] digest(byte[] data) throws NoSuchAlgorithmException {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private void fail(Status newStatus, String detail) {
        status = newStatus;
        statusDetail = detail == null ? "unknown" : detail;
        plugin.getLogger().severe("LICENSE VALIDATION FAILED: " + statusDetail);
    }

    public Status status() {
        return status;
    }

    public String statusDetail() {
        return statusDetail;
    }

    public boolean isValid() {
        return status == Status.VALID;
    }

    public String expectedHash() {
        return expectedHash;
    }

    public Path runtimeLicense() {
        return runtimeLicense;
    }

    public void stopMonitor() {
        if (monitorTask != null) {
            monitorTask.cancel();
            monitorTask = null;
        }
    }
}
