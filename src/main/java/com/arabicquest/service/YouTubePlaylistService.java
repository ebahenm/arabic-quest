package com.arabicquest.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class YouTubePlaylistService {

    public static final int PAGE_SIZE = 50;
    private static final int MAX_PAGES = 40;
    private static final String YT_API_BASE = "https://www.googleapis.com/youtube/v3";
    private static final Pattern PLAYLIST_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{6,120}$");
    private static final Pattern ISO_DURATION_PATTERN = Pattern.compile(
            "^P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$",
            Pattern.CASE_INSENSITIVE
    );

    private static final Logger log = LoggerFactory.getLogger(YouTubePlaylistService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RestTemplate restTemplate = new RestTemplate();

    @Value("${YOUTUBE_API_KEY:}")
    private String youtubeApiKey;

    public void setRestTemplate(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public RestTemplate getRestTemplate() {
        return this.restTemplate;
    }

    public void setYoutubeApiKey(String youtubeApiKey) {
        this.youtubeApiKey = youtubeApiKey;
    }

    public record YouTubeEpisodeItem(
            int position,
            String youtubeVideoId,
            String title,
            int durationSeconds
    ) {
    }

    public record YouTubePlaylistPreview(
            String youtubePlaylistId,
            String title,
            String description,
            String thumbnailUrl,
            int episodeCount,
            long totalDurationSeconds,
            int skippedCount,
            List<YouTubeEpisodeItem> episodes
    ) {
    }

    private record PlaylistItemCandidate(String videoId, String title) {
    }

    private record VideoDetails(int durationSeconds, String privacyStatus, String uploadStatus, String title) {
    }

    public static String extractPlaylistId(String rawUrlOrId) {
        if (rawUrlOrId == null || rawUrlOrId.isBlank()) {
            throw new IllegalArgumentException("YouTube playlist URL or ID is required");
        }
        String trimmed = rawUrlOrId.trim();
        String candidate = trimmed;

        int listIdx = trimmed.indexOf("list=");
        if (listIdx >= 0) {
            String afterList = trimmed.substring(listIdx + "list=".length());
            String rawParam = afterList.split("[?&#/\\s]")[0];
            candidate = URLDecoder.decode(rawParam, StandardCharsets.UTF_8).trim();
        }

        if (!PLAYLIST_ID_PATTERN.matcher(candidate).matches()) {
            throw new IllegalArgumentException("Invalid YouTube playlist URL or ID");
        }
        return candidate;
    }

    public static int parseIso8601DurationSeconds(String isoDuration) {
        if (isoDuration == null || isoDuration.isBlank()) {
            return 0;
        }
        String cleaned = isoDuration.trim();
        if ("P0D".equalsIgnoreCase(cleaned) || "PT0S".equalsIgnoreCase(cleaned)
                || "P".equalsIgnoreCase(cleaned) || "PT".equalsIgnoreCase(cleaned)) {
            return 0;
        }

        Matcher matcher = ISO_DURATION_PATTERN.matcher(cleaned);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Unsupported ISO 8601 duration format: " + cleaned);
        }

        long weeks = parseGroup(matcher.group(1));
        long days = parseGroup(matcher.group(2));
        long hours = parseGroup(matcher.group(3));
        long minutes = parseGroup(matcher.group(4));
        long seconds = parseGroup(matcher.group(5));

        long total = weeks * 604_800L
                + days * 86_400L
                + hours * 3_600L
                + minutes * 60L
                + seconds;
        if (total <= 0) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    private static long parseGroup(String group) {
        return (group == null || group.isBlank()) ? 0L : Long.parseLong(group);
    }

    public YouTubePlaylistPreview fetchPlaylist(String rawPlaylistUrlOrId) {
        String playlistId = extractPlaylistId(rawPlaylistUrlOrId);
        HttpEntity<Void> requestEntity = buildAuthEntity();

        // 1. Fetch playlist title, description, status, and best thumbnail
        URI playlistUri = UriComponentsBuilder.fromUriString(YT_API_BASE + "/playlists")
                .queryParam("part", "snippet,status")
                .queryParam("id", playlistId)
                .build()
                .encode()
                .toUri();

        JsonNode playlistRoot = executeGetJson(playlistUri, requestEntity, playlistId);
        JsonNode playlistItemsNode = playlistRoot.path("items");
        if (!playlistItemsNode.isArray() || playlistItemsNode.isEmpty()) {
            throw new IllegalArgumentException("YouTube playlist not found or is private: " + playlistId);
        }

        JsonNode firstPlaylist = playlistItemsNode.get(0);
        String playlistPrivacy = firstPlaylist.path("status").path("privacyStatus").asText("");
        if ("private".equalsIgnoreCase(playlistPrivacy)) {
            throw new IllegalArgumentException("YouTube playlist is private. Set it to Unlisted or Public before importing.");
        }

        JsonNode snippet = firstPlaylist.path("snippet");
        String playlistTitle = snippet.path("title").asText("").trim();
        if (playlistTitle.isEmpty()) {
            playlistTitle = "Imported Course (" + playlistId + ")";
        }
        String playlistDescription = snippet.path("description").asText("").trim();
        String thumbnailUrl = extractBestThumbnail(snippet.path("thumbnails"));

        // 2. Fetch paginated playlistItems.list (50 per page)
        List<PlaylistItemCandidate> candidates = new ArrayList<>();
        Set<String> seenCandidateIds = new HashSet<>();
        int skippedCount = 0;
        String pageToken = null;
        int pageGuard = 0;

        do {
            pageGuard++;
            UriComponentsBuilder itemsBuilder = UriComponentsBuilder.fromUriString(YT_API_BASE + "/playlistItems")
                    .queryParam("part", "snippet,contentDetails,status")
                    .queryParam("maxResults", PAGE_SIZE)
                    .queryParam("playlistId", playlistId);
            if (pageToken != null && !pageToken.isBlank()) {
                itemsBuilder.queryParam("pageToken", pageToken);
            }
            URI itemsUri = itemsBuilder.build().encode().toUri();

            JsonNode pageRoot = executeGetJson(itemsUri, requestEntity, playlistId);
            JsonNode items = pageRoot.path("items");
            if (items.isArray()) {
                for (JsonNode item : items) {
                    String videoId = item.path("contentDetails").path("videoId").asText("").trim();
                    if (videoId.isEmpty()) {
                        videoId = item.path("snippet").path("resourceId").path("videoId").asText("").trim();
                    }
                    String itemTitle = item.path("snippet").path("title").asText("").trim();
                    String privacyStatus = item.path("status").path("privacyStatus").asText("").trim();

                    if (isSkippedPlaylistItem(videoId, itemTitle, privacyStatus)) {
                        skippedCount++;
                        continue;
                    }
                    if (!seenCandidateIds.add(videoId)) {
                        continue;
                    }
                    candidates.add(new PlaylistItemCandidate(videoId, itemTitle));
                }
            }

            String nextToken = pageRoot.path("nextPageToken").asText(null);
            if (nextToken != null && (nextToken.isBlank() || nextToken.equals(pageToken))) {
                nextToken = null;
            }
            pageToken = nextToken;
        } while (pageToken != null && pageGuard < MAX_PAGES);

        // 3. Batch videos.list (50 video IDs per call) for authoritative contentDetails.duration and status
        Map<String, VideoDetails> videoDetailsById = new HashMap<>();
        for (int start = 0; start < candidates.size(); start += PAGE_SIZE) {
            int end = Math.min(candidates.size(), start + PAGE_SIZE);
            List<PlaylistItemCandidate> chunk = candidates.subList(start, end);
            String joinedIds = String.join(",", chunk.stream().map(PlaylistItemCandidate::videoId).toList());

            URI videosUri = UriComponentsBuilder.fromUriString(YT_API_BASE + "/videos")
                    .queryParam("part", "contentDetails,status,snippet")
                    .queryParam("id", joinedIds)
                    .build()
                    .encode()
                    .toUri();

            JsonNode videosRoot = executeGetJson(videosUri, requestEntity, playlistId);
            JsonNode videoItems = videosRoot.path("items");
            if (videoItems.isArray()) {
                for (JsonNode v : videoItems) {
                    String vid = v.path("id").asText("").trim();
                    if (vid.isEmpty()) {
                        continue;
                    }
                    String isoDuration = v.path("contentDetails").path("duration").asText("");
                    int durationSeconds;
                    try {
                        durationSeconds = parseIso8601DurationSeconds(isoDuration);
                    } catch (IllegalArgumentException ex) {
                        durationSeconds = 0;
                    }
                    String privacy = v.path("status").path("privacyStatus").asText("");
                    String uploadStatus = v.path("status").path("uploadStatus").asText("");
                    String vTitle = v.path("snippet").path("title").asText("").trim();
                    videoDetailsById.put(vid, new VideoDetails(durationSeconds, privacy, uploadStatus, vTitle));
                }
            }
        }

        List<YouTubeEpisodeItem> validEpisodes = new ArrayList<>();
        long totalDurationSeconds = 0L;
        int position = 1;

        for (PlaylistItemCandidate candidate : candidates) {
            VideoDetails details = videoDetailsById.get(candidate.videoId());
            if (details == null || isSkippedVideoDetails(details)) {
                skippedCount++;
                continue;
            }
            String finalTitle = !candidate.title().isBlank()
                    ? candidate.title()
                    : (!details.title().isBlank() ? details.title() : "Episode " + position);
            validEpisodes.add(new YouTubeEpisodeItem(
                    position++,
                    candidate.videoId(),
                    finalTitle,
                    details.durationSeconds()
            ));
            totalDurationSeconds += details.durationSeconds();
        }

        return new YouTubePlaylistPreview(
                playlistId,
                playlistTitle,
                playlistDescription,
                thumbnailUrl,
                validEpisodes.size(),
                totalDurationSeconds,
                skippedCount,
                validEpisodes
        );
    }

    private static boolean isSkippedPlaylistItem(String videoId, String title, String privacyStatus) {
        if (videoId == null || videoId.isBlank()) {
            return true;
        }
        if ("private".equalsIgnoreCase(privacyStatus)
                || "privacyStatusUnspecified".equalsIgnoreCase(privacyStatus)) {
            return true;
        }
        if (title == null || title.isBlank()) {
            return true;
        }
        return "Private video".equalsIgnoreCase(title)
                || "Deleted video".equalsIgnoreCase(title);
    }

    private static boolean isSkippedVideoDetails(VideoDetails details) {
        if ("private".equalsIgnoreCase(details.privacyStatus())) {
            return true;
        }
        if ("rejected".equalsIgnoreCase(details.uploadStatus())
                || "deleted".equalsIgnoreCase(details.uploadStatus())) {
            return true;
        }
        return details.durationSeconds() <= 0;
    }

    private static String extractBestThumbnail(JsonNode thumbnailsNode) {
        if (thumbnailsNode == null || thumbnailsNode.isMissingNode()) {
            return null;
        }
        for (String key : List.of("maxres", "standard", "high", "medium", "default")) {
            String url = thumbnailsNode.path(key).path("url").asText("").trim();
            if (!url.isEmpty()) {
                return url;
            }
        }
        return null;
    }

    private HttpEntity<Void> buildAuthEntity() {
        if (youtubeApiKey == null || youtubeApiKey.isBlank()) {
            throw new IllegalStateException("YOUTUBE_API_KEY is not configured on the server.");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Goog-Api-Key", youtubeApiKey.trim());
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return new HttpEntity<>(headers);
    }

    private JsonNode executeGetJson(URI uri, HttpEntity<Void> entity, String playlistId) {
        try {
            ResponseEntity<String> response = restTemplate.exchange(uri, HttpMethod.GET, entity, String.class);
            String body = response.getBody();
            if (body == null || body.isBlank()) {
                throw new IllegalStateException("YouTube Data API returned an empty response.");
            }
            return objectMapper.readTree(body);
        } catch (HttpStatusCodeException ex) {
            int status = ex.getStatusCode().value();
            log.warn("YouTube Data API returned HTTP {} for playlist {}", status, playlistId);
            if (status == 404) {
                throw new IllegalArgumentException("YouTube playlist not found: " + playlistId);
            }
            throw new IllegalStateException(
                    "YouTube Data API request failed (HTTP " + status + "). Verify YOUTUBE_API_KEY and quota.");
        } catch (RestClientException ex) {
            log.warn("YouTube Data API network error for playlist {} ({})", playlistId, ex.getClass().getSimpleName());
            throw new IllegalStateException("Could not reach YouTube Data API. Please try again shortly.");
        } catch (IllegalArgumentException | IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("Failed to parse YouTube Data API response for playlist {} ({})", playlistId, ex.getClass().getSimpleName());
            throw new IllegalStateException("Received malformed response from YouTube Data API.");
        }
    }
}
