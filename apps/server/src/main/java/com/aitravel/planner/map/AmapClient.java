package com.aitravel.planner.map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.server.ResponseStatusException;
import java.net.URLEncoder;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;

@Component
public class AmapClient {
    private static final Logger log = LoggerFactory.getLogger(AmapClient.class);
    private final String apiKey;
    private final RestTemplate http = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    private String getJson(String url) {
        try {
            ResponseEntity<String> res = http.getForEntity(url, String.class);
            return res.getBody();
        } catch (Exception e) {
            log.warn("AMap HTTP call failed for {}: {}", url, e.toString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AMap HTTP error: " + e.getMessage());
        }
    }

    private static String encodePreserveComma(String value) {
        if (value == null) return null;
        String enc = URLEncoder.encode(value, StandardCharsets.UTF_8);
        return enc.replace("%2C", ",");
    }

    public AmapClient(@Value("${amap.api.key:}") String apiKeyProp) {
        String envKey = System.getenv("AMAP_API_KEY");
        String resolved = (apiKeyProp != null && !apiKeyProp.isBlank()) ? apiKeyProp : envKey;
        if (resolved == null || resolved.isBlank()) {
            throw new IllegalStateException("AMap API key not configured. Set 'amap.api.key' or AMAP_API_KEY env.");
        }
        this.apiKey = resolved;
    }

    public record Poi(
            String id,
            String name,
            String address,
            double lat,
            double lng,
            String type,
            String city,
            String district
    ) {}

    public record DrivingRoute(
            long distance,
            long duration,
            String polyline
    ) {}

    public List<Poi> searchText(String keywords, String city, Integer offset, Integer page) throws Exception {
        String url = UriComponentsBuilder.fromHttpUrl("https://restapi.amap.com/v3/place/text")
                .queryParam("key", apiKey)
                .queryParam("keywords", keywords)
                .queryParam("city", city)
                .queryParam("offset", offset == null ? 10 : offset)
                .queryParam("page", page == null ? 1 : page)
                .build()
                .toUriString();
        JsonNode root = mapper.readTree(getJson(url));
        String status = root.path("status").asText("0");
        if (!"1".equals(status)) {
            String info = root.path("info").asText("unknown_error");
            log.warn("AMap API non-OK for {}: {}", url, info);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AMap API error: " + info);
        }
        List<Poi> out = new ArrayList<>();
        for (JsonNode p : root.path("pois")) {
            String loc = p.path("location").asText("");
            double lng = 0, lat = 0;
            if (!loc.isEmpty() && loc.contains(",")) {
                String[] parts = loc.split(",");
                try {
                    lng = Double.parseDouble(parts[0]);
                    lat = Double.parseDouble(parts[1]);
                } catch (NumberFormatException ignored) {}
            }
            out.add(new Poi(
                    p.path("id").asText(null),
                    p.path("name").asText(null),
                    p.path("address").asText(null),
                    lat,
                    lng,
                    p.path("type").asText(null),
                    p.path("cityname").asText(null),
                    p.path("adname").asText(null)
            ));
        }
        return out;
    }

    public DrivingRoute driving(String origin, String destination) throws Exception {
        String encOrigin = encodePreserveComma(origin);
        String encDestination = encodePreserveComma(destination);
        String url = UriComponentsBuilder.fromHttpUrl("https://restapi.amap.com/v3/direction/driving")
                .queryParam("key", apiKey)
                .queryParam("origin", encOrigin)
                .queryParam("destination", encDestination)
                .build()
                .toUriString();
        JsonNode root = mapper.readTree(getJson(url));
        String status = root.path("status").asText("0");
        if (!"1".equals(status)) {
            String info = root.path("info").asText("unknown_error");
            log.warn("AMap driving non-OK for {}: {}", url, info);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AMap API error: " + info);
        }
        JsonNode firstPath = root.path("route").path("paths").isArray() && root.path("route").path("paths").size() > 0
                ? root.path("route").path("paths").get(0)
                : null;
        long distance = firstPath == null ? 0 : firstPath.path("distance").asLong(0);
        long duration = firstPath == null ? 0 : firstPath.path("duration").asLong(0);
        String polyline = firstPath == null ? null : firstPath.path("polyline").asText(null);
        return new DrivingRoute(distance, duration, polyline);
    }

    public record Geocode(double lat, double lng, String formatted) {}

    public Geocode geocode(String address, String city) throws Exception {
        String url = UriComponentsBuilder.fromHttpUrl("https://restapi.amap.com/v3/geocode/geo")
                .queryParam("key", apiKey)
                .queryParam("address", address)
                .queryParam("city", city)
                .build()
                .toUriString();
        JsonNode root = mapper.readTree(getJson(url));
        String status = root.path("status").asText("0");
        String info = root.path("info").asText("unknown_error");
        if (!"1".equals(status)) {
            log.warn("AMap geocode non-OK for {}: {}", url, info);
            // 针对 ENGINE_RESPONSE_DATA_ERROR 进行文本搜索回退
            Geocode fallback = geocodeFallbackByPlaceText(address, city);
            if (fallback != null) return fallback;
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AMap geocode error: " + info);
        }
        JsonNode g = root.path("geocodes").isArray() && root.path("geocodes").size() > 0 ? root.path("geocodes").get(0) : null;
        if (g == null) {
            Geocode fallback = geocodeFallbackByPlaceText(address, city);
            if (fallback != null) return fallback;
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Address not found");
        }
        String loc = g.path("location").asText("");
        double lng = 0, lat = 0;
        if (!loc.isEmpty() && loc.contains(",")) {
            String[] parts = loc.split(",");
            try { lng = Double.parseDouble(parts[0]); lat = Double.parseDouble(parts[1]); } catch (NumberFormatException ignored) {}
        }
        return new Geocode(lat, lng, g.path("formatted_address").asText(null));
    }

    public Geocode reverseGeocode(String location) throws Exception {
        String encLocation = encodePreserveComma(location);
        String url = UriComponentsBuilder.fromHttpUrl("https://restapi.amap.com/v3/geocode/regeo")
                .queryParam("key", apiKey)
                .queryParam("location", encLocation)
                .build()
                .toUriString();
        JsonNode root = mapper.readTree(getJson(url));
        if (!"1".equals(root.path("status").asText("0"))) {
            String info = root.path("info").asText("unknown_error");
            log.warn("AMap reverse geocode non-OK for {}: {}", url, info);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AMap reverse geocode error: " + info);
        }
        JsonNode rg = root.path("regeocode");
        String loc = location;
        double lng = 0, lat = 0;
        if (!loc.isEmpty() && loc.contains(",")) {
            String[] parts = loc.split(",");
            try { lng = Double.parseDouble(parts[0]); lat = Double.parseDouble(parts[1]); } catch (NumberFormatException ignored) {}
        }
        return new Geocode(lat, lng, rg.path("formatted_address").asText(null));
    }

    private Geocode geocodeFallbackByPlaceText(String address, String city) throws Exception {
        String url = UriComponentsBuilder.fromHttpUrl("https://restapi.amap.com/v3/place/text")
                .queryParam("key", apiKey)
                .queryParam("keywords", address)
                .queryParam("city", city)
                .queryParam("offset", 1)
                .queryParam("page", 1)
                .build()
                .toUriString();
        JsonNode root = mapper.readTree(getJson(url));
        if (!"1".equals(root.path("status").asText("0"))) {
            return null;
        }
        JsonNode p = root.path("pois").isArray() && root.path("pois").size() > 0 ? root.path("pois").get(0) : null;
        if (p == null) return null;
        String loc = p.path("location").asText("");
        if (loc.isEmpty() || !loc.contains(",")) return null;
        String[] parts = loc.split(",");
        try {
            double lng = Double.parseDouble(parts[0]);
            double lat = Double.parseDouble(parts[1]);
            String formatted = p.path("name").asText(null);
            return new Geocode(lat, lng, formatted);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}