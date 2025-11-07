package com.aitravel.planner.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class AmapService {
    private static final Logger log = LoggerFactory.getLogger(AmapService.class);

    @Value("${amap.api.key:}")
    private String apiKey;

    private final RestTemplate http = new RestTemplate();

    /**
     * 通过高德地点搜索获取坐标（lng, lat）。
     */
    public Optional<List<Double>> geocodePlace(String keyword, String city) {
        try {
            if (keyword == null || keyword.isBlank()) return Optional.empty();
            if (apiKey == null || apiKey.isBlank()) {
                log.warn("AMap API KEY 未配置，跳过地理编码: {}", keyword);
                return Optional.empty();
            }
            String q = URLEncoder.encode(keyword, StandardCharsets.UTF_8);
            String cityParam = (city == null || city.isBlank()) ? "" : ("&city=" + URLEncoder.encode(city, StandardCharsets.UTF_8));
            String url = "https://restapi.amap.com/v3/place/text?keywords=" + q + cityParam + "&offset=1&page=1&key=" + apiKey;
            ResponseEntity<Map> res = http.getForEntity(url, Map.class);
            Object pois = ((Map<?, ?>) Objects.requireNonNull(res.getBody())).get("pois");
            if (!(pois instanceof List)) return Optional.empty();
            List<?> list = (List<?>) pois;
            if (list.isEmpty()) return Optional.empty();
            Object first = list.get(0);
            if (!(first instanceof Map)) return Optional.empty();
            Object loc = ((Map<?, ?>) first).get("location"); // "lng,lat"
            if (!(loc instanceof String)) return Optional.empty();
            String[] parts = ((String) loc).split(",");
            if (parts.length != 2) return Optional.empty();
            double lng = Double.parseDouble(parts[0]);
            double lat = Double.parseDouble(parts[1]);
            return Optional.of(List.of(lng, lat));
        } catch (Exception e) {
            log.warn("AMap 地理编码失败: {} (city={}) -> {}", keyword, city, e.toString());
            return Optional.empty();
        }
    }

    /**
     * 简化的路线 polyline：将多个坐标按顺序连接为 "lng,lat;lng,lat;..."。
     * 如需真实驾车路线，可改为调用 AMap 驾车路径 API 生成更加精细的 polyline。
     */
    public String buildPolylineBySequence(List<List<Double>> coords) {
        List<String> parts = new ArrayList<>();
        for (List<Double> c : coords) {
            if (c == null || c.size() < 2) continue;
            parts.add(c.get(0) + "," + c.get(1));
        }
        return String.join(";", parts);
    }
}