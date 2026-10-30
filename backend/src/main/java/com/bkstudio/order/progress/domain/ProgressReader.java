package com.bkstudio.order.progress.domain;

import com.bkstudio.catalog.domain.CatalogProperties;
import com.bkstudio.order.progress.api.ProgressDtos.Photo;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Reads approved stages and their photos of one order. */
@Component
class ProgressReader {

    /** Photo URLs use the 1000 px rendition. */
    static final int PHOTO_WIDTH = 1000;

    record Row(String note, Instant approvedAt, List<Photo> images) {}

    private final JdbcTemplate jdbc;
    private final CatalogProperties props;

    ProgressReader(JdbcTemplate jdbc, CatalogProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    /** Keyed by stage name. */
    Map<String, Row> load(long orderId) {
        Map<String, List<Photo>> photos = new HashMap<>();
        jdbc.query(
                "SELECT stage, storage_key, width, height FROM order_progress_image WHERE order_id = ?"
                        + " ORDER BY stage, sort_order",
                rs -> {
                    photos.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                            .add(new Photo(props.imageUrl(rs.getString(2), PHOTO_WIDTH), rs.getInt(3), rs.getInt(4)));
                },
                orderId);
        Map<String, Row> out = new HashMap<>();
        jdbc.query(
                "SELECT stage, note, approved_at FROM order_progress WHERE order_id = ?",
                rs -> {
                    String stage = rs.getString(1);
                    out.put(
                            stage,
                            new Row(
                                    rs.getString(2),
                                    rs.getTimestamp(3).toInstant(),
                                    photos.getOrDefault(stage, List.of())));
                },
                orderId);
        return out;
    }
}
