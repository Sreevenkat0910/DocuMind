package com.fruity.documind.web;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 0 smoke-test endpoint (README §7). Confirms three things in one call:
 *  - the web layer is up,
 *  - the app can actually reach PostgreSQL (a real round-trip, not just "process alive"),
 *  - the pgvector extension is installed in this database.
 */
@RestController
public class HealthController {

    private final JdbcTemplate jdbcTemplate;

    public HealthController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");

        // DB round-trip: proves connectivity, not just that Tomcat answered.
        Integer one = jdbcTemplate.queryForObject("select 1", Integer.class);
        body.put("database", (one != null && one == 1) ? "UP" : "DOWN");

        // pgvector presence: the whole point of Phase 0 was getting this extension live.
        String vectorVersion = jdbcTemplate.query(
                "select extversion from pg_extension where extname = 'vector'",
                rs -> rs.next() ? rs.getString(1) : null);
        body.put("pgvector", vectorVersion != null ? vectorVersion : "NOT INSTALLED");

        return body;
    }
}
