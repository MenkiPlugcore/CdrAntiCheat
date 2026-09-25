package store.cadera.cdranticheat.alert;

public record DiscordTestResult(boolean success, String detail) {
    public static DiscordTestResult success(String detail) {
        return new DiscordTestResult(true, detail);
    }

    public static DiscordTestResult failure(String detail) {
        return new DiscordTestResult(false, detail);
    }
}
