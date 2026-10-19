package com.ahdyahmed.pymk.lightranker;

import com.ahdyahmed.pymk.domain.service.ServingReleaseStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import org.springframework.jdbc.core.JdbcTemplate;

/** Small synchronized model cache: sessions cannot be closed while inference uses them. */
public final class ReleaseLightRankerModel implements LightRankerModel {
    private final ServingReleaseStore releases;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final LightRankerProperties properties;
    private final LinkedHashMap<String, LightRankerModel> models = new LinkedHashMap<>(4, 0.75f, true);

    public ReleaseLightRankerModel(ServingReleaseStore releases, JdbcTemplate jdbc,
                                  ObjectMapper mapper, LightRankerProperties properties) {
        this.releases = releases;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.properties = properties;
        // Preserve startup validation; a managed release can boot without legacy files.
        selected();
    }

    private LightRankerModel selected() {
        var active = releases.current();
        String key = active.managed() ? active.id() : "@legacy:" + active.epoch();
        var model = models.get(key);
        if (model == null) {
            if (!active.managed()) {
                var legacy = jdbc.query("SELECT model_bytes, model_metadata FROM pymk_legacy_release_model", (rs, n) -> {
                    try { return OnnxLightRankerModel.load(rs.getBytes(1), mapper.readTree(rs.getBytes(2))); }
                    catch (java.io.IOException ex) { throw new LightRankerException("Invalid legacy release metadata", ex); }
                });
                model = legacy.isEmpty() ? OnnxLightRankerModel.load(properties.getModelPath(), properties.getMetadataPath(), mapper) : legacy.getFirst();
            } else {
                model = jdbc.queryForObject("SELECT model_bytes, model_metadata FROM pymk_serving_releases WHERE release_id = ? AND state = 'READY'",
                        (rs, n) -> {
                            try { return OnnxLightRankerModel.load(rs.getBytes(1), mapper.readTree(rs.getBytes(2))); }
                            catch (java.io.IOException ex) { throw new LightRankerException("Invalid stored release metadata", ex); }
                        }, active.id());
            }
            models.put(key, model);
            if (models.size() > 2) {
                String oldest = models.keySet().iterator().next();
                models.remove(oldest).close();
            }
        }
        return model;
    }

    @Override public synchronized float[] score(float[][] features) { return selected().score(features); }
    @Override public synchronized String modelVersion() { return selected().modelVersion(); }
    @Override public synchronized void close() { models.values().forEach(LightRankerModel::close); models.clear(); }
}
