package gg.embargo.manifest;

import com.google.gson.*;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Singleton
public class ManifestManager {

    @Inject
    private Gson gson;

    @Inject
    private OkHttpClient okHttpClient;

    @Getter
    @Setter
    private Manifest manifest;

    @Getter
    @Setter
    private float lastCheckedManifestVersion = -1;

    // Add timestamp for last check
    private long lastCheckTimestamp = 0;

    // Add a flag to track if a request is in progress
    private final AtomicBoolean requestInProgress = new AtomicBoolean(false);

    // 3 minutes in milliseconds
    private static final long CHECK_INTERVAL = 3 * 60 * 1000;

    //private static final String MOCK_API_URI = "https://a278d141-927f-433b-8e4b-6d994067900d.mock.pstmn.io/api/";
    private static final String API_URI = "https://embargo.gg/api/";
    private static final String MANIFEST_ENDPOINT = API_URI + "runelite/manifest";

    public Manifest getLatestManifest() {
        long currentTime = System.currentTimeMillis();

        // Only proceed if 3 minutes have passed since the last check AND no request is
        // in progress
        if (currentTime - lastCheckTimestamp < CHECK_INTERVAL || !requestInProgress.compareAndSet(false, true)) {
            log.debug(
                    "Skipping manifest check - last check was less than 3 minutes ago or request already in progress");
            return manifest; // Return the current manifest instead of null
        }

        try {
            Request r = new Request.Builder()
                    .url(MANIFEST_ENDPOINT)
                    .header("Cache-Control", "no-cache, no-store")
                    .header("Pragma", "no-cache")
                    .cacheControl(new CacheControl.Builder().noCache().noStore().build())
                    .build();

            okHttpClient.newCall(r).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    log.error("Error retrieving manifest", e);
                    requestInProgress.set(false); // Reset the flag
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) {
                    try (response) {
                        if (response.isSuccessful()) {
                            try {
                                if (response.body() == null) {
                                    log.error("Manifest request succeeded but returned empty body");
                                    return;
                                }

                                String json = response.body().string();
                                setManifest(gson.fromJson(json, Manifest.class));
                                log.debug("Set manifest");

                                // Update the timestamp and version
                                lastCheckTimestamp = currentTime;

                                if (lastCheckedManifestVersion != manifest.getVersion()) {
                                    log.debug("Manifest version updated from {} to {}", lastCheckedManifestVersion, manifest.getVersion());
                                    lastCheckedManifestVersion = manifest.getVersion();
                                }
                            } catch (JsonSyntaxException e) {
                                log.error(e.getLocalizedMessage());
                            } catch (IOException e) {
                                log.error("Error reading response body", e);
                            }
                        } else {
                            log.error("Manifest request returned with status {}", response.code());
                            if (response.body() == null) {
                                log.error("Manifest request returned empty body");
                            } else {
                                log.error(response.body().toString());
                            }
                        }
                    } finally {
                        requestInProgress.set(false); // Reset the flag
                    }
                }
            });
        } catch (IllegalArgumentException e) {
            log.error("Bad URL given: {}", e.getLocalizedMessage());
            requestInProgress.set(false); // Reset the flag
        }

        log.debug("Returning set manifest (not null)");
        return manifest; // Return current manifest instead of null
    }
}