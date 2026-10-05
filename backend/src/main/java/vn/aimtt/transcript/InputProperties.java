package vn.aimtt.transcript;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.input")
public record InputProperties(long maxUploadBytes, int maxExtractedChars, long maxZipEntryBytes,
                              long maxZipTotalBytes, int maxZipEntries, double minInflateRatio,
                              Duration sourceRetention) {}
