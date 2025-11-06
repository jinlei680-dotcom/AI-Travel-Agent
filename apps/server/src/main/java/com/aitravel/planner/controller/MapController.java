package com.aitravel.planner.controller;

import com.aitravel.planner.map.AmapClient;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "*", allowCredentials = "false")
@Validated
public class MapController {
    private final AmapClient amap;

    public MapController(AmapClient amap) {
        this.amap = amap;
    }

    @GetMapping("/poi/search")
    public ResponseEntity<?> searchPoi(
            @RequestParam("q") @NotBlank String q,
            @RequestParam(value = "city", required = false) String city,
            @RequestParam(value = "offset", required = false) Integer offset,
            @RequestParam(value = "page", required = false) Integer page
    ) throws Exception {
        List<AmapClient.Poi> pois = amap.searchText(q, city, offset, page);
        return ResponseEntity.ok(Map.of("pois", pois));
    }

    @GetMapping("/route/driving")
    public ResponseEntity<?> driving(
            @RequestParam("origin") @Pattern(regexp = "^-?\\d+(\\.\\d+)?,-?\\d+(\\.\\d+)?$", message = "坐标格式应为 'lng,lat'") String origin,
            @RequestParam("destination") @Pattern(regexp = "^-?\\d+(\\.\\d+)?,-?\\d+(\\.\\d+)?$", message = "坐标格式应为 'lng,lat'") String destination
    ) throws Exception {
        var r = amap.driving(origin, destination);
        String polyline = r.polyline() == null ? "" : r.polyline();
        return ResponseEntity.ok(Map.of(
                "distance", r.distance(),
                "duration", r.duration(),
                "polyline", polyline
        ));
    }

    @GetMapping("/geocode")
    public ResponseEntity<?> geocode(
            @RequestParam("address") @NotBlank String address,
            @RequestParam(value = "city", required = false) String city) throws Exception {
        var g = amap.geocode(address, city);
        return ResponseEntity.ok(Map.of(
                "lat", g.lat(),
                "lng", g.lng(),
                "formatted", g.formatted()
        ));
    }

    @GetMapping("/reverse-geocode")
    public ResponseEntity<?> reverseGeocode(@RequestParam("location") @Pattern(regexp = "^-?\\d+(\\.\\d+)?,-?\\d+(\\.\\d+)?$", message = "坐标格式应为 'lng,lat'") String location) throws Exception {
        var g = amap.reverseGeocode(location);
        return ResponseEntity.ok(Map.of(
                "lat", g.lat(),
                "lng", g.lng(),
                "formatted", g.formatted()
        ));
    }
}