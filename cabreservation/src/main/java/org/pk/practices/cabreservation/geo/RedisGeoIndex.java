package org.pk.practices.cabreservation.geo;

import redis.clients.jedis.GeoCoordinate;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.args.GeoUnit;
import redis.clients.jedis.params.GeoSearchParam;
import redis.clients.jedis.resps.GeoRadiusResponse;

import java.util.List;

/**
 * DESIGN.md §4.2 — one Redis key per region cell (an H3 cell computed by
 * {@link H3CellResolver}). Redis wants (longitude, latitude) order for
 * GEOADD/GEOSEARCH — the classic transposition bug — note it here rather
 * than at every call site.
 */
public class RedisGeoIndex implements GeoIndex {

    private static final String KEY_PREFIX = "geo:";

    private final JedisPooled jedis;

    public RedisGeoIndex(JedisPooled jedis) {
        this.jedis = jedis;
    }

    @Override
    public void upsertDriver(String regionCellId, String driverId, double lat, double lng) {
        jedis.geoadd(key(regionCellId), lng, lat, driverId);
    }

    @Override
    public void removeDriver(String regionCellId, String driverId) {
        // Geo indexes are sorted sets under the hood — ZREM is how Redis removes a member; there's no dedicated "georem".
        jedis.zrem(key(regionCellId), driverId);
    }

    @Override
    public List<NearbyDriver> findNearby(String regionCellId, double lat, double lng, double radiusKm, int maxResults) {
        GeoSearchParam params = GeoSearchParam.geoSearchParam()
                .fromLonLat(new GeoCoordinate(lng, lat))
                .byRadius(radiusKm, GeoUnit.KM)
                .withCoord()
                .withDist()
                .asc()
                .count(maxResults);
        List<GeoRadiusResponse> responses = jedis.geosearch(key(regionCellId), params);
        return responses.stream()
                .map(r -> new NearbyDriver(r.getMemberByString(), r.getCoordinate().getLatitude(), r.getCoordinate().getLongitude(), r.getDistance()))
                .toList();
    }

    private static String key(String regionCellId) {
        return KEY_PREFIX + regionCellId;
    }
}
