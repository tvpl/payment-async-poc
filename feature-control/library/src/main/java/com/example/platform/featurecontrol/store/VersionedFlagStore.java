package com.example.platform.featurecontrol.store;

import com.example.platform.featurecontrol.config.FeatureSettings;
import com.example.platform.featurecontrol.model.FlagDefinition;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.micronaut.context.annotation.Requires;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Singleton;

/**
 * FTR-04: the single Redis choke point for admin mutations. Every create/update/delete is a
 * compare-and-set on {@link FlagDefinition#version()}, and the mutation plus its audit record
 * (before/after/actor/version/timestamp/result) are written by <strong>one</strong> Lua script — so a
 * crash or a Redis failure between "mutate" and "audit" is structurally impossible: a Redis {@code EVAL}
 * is a single indivisible server-side step, either both effects land or neither does.
 *
 * <p>The audit record goes to a Redis Stream (not the best-effort {@code AuditService} list), because
 * a Stream preserves every entry in order and is the durable evidence this requirement asks for.
 */
@Singleton
@Requires(beans = RedisClient.class)
public class VersionedFlagStore {

    // KEYS: [flagKey, auditStreamKey]. ARGV: [expectedVersion, newJson, actor, timestampMillis, flagName].
    // Returns the new version, or -1 on a version mismatch (no mutation, no audit entry — nothing happens).
    private static final String PUT_LUA = """
            local cur = redis.call('GET', KEYS[1])
            local curVer = 0
            if cur then curVer = tonumber(cjson.decode(cur).version) or 0 end
            local expected = tonumber(ARGV[1])
            if expected ~= curVer then return -1 end
            redis.call('SET', KEYS[1], ARGV[2])
            redis.call('XADD', KEYS[2], '*',
                'action', 'put', 'flag', ARGV[5], 'actor', ARGV[3],
                'before', cur or '', 'after', ARGV[2],
                'version', tostring(curVer + 1), 'result', 'ok', 'ts', ARGV[4])
            return curVer + 1
            """;

    // KEYS: [flagKey, auditStreamKey]. ARGV: [expectedVersion, actor, timestampMillis, flagName].
    // Returns the deleted (or matched-absent) version, or -1 on a version mismatch.
    // AUD-21: a delete of a flag that doesn't exist mutates nothing, so it audits as 'noop', not 'ok'
    // -- 'ok' is reserved for a mutation that actually happened.
    private static final String DELETE_LUA = """
            local cur = redis.call('GET', KEYS[1])
            local curVer = 0
            if cur then curVer = tonumber(cjson.decode(cur).version) or 0 end
            local expected = tonumber(ARGV[1])
            if expected ~= curVer then return -1 end
            local result = 'noop'
            if cur then
                redis.call('DEL', KEYS[1])
                result = 'ok'
            end
            redis.call('XADD', KEYS[2], '*',
                'action', 'delete', 'flag', ARGV[4], 'actor', ARGV[2],
                'before', cur or '', 'after', '',
                'version', tostring(curVer), 'result', result, 'ts', ARGV[3])
            return curVer
            """;

    private final FeatureRedisCommandsProvider redis;
    private final FeatureSettings settings;
    private final ObjectMapper objectMapper;

    public VersionedFlagStore(FeatureRedisCommandsProvider redis, FeatureSettings settings,
                              ObjectMapper objectMapper) {
        this.redis = redis;
        this.settings = settings;
        this.objectMapper = objectMapper;
    }

    public String flagKey(String name) {
        return settings.getKeyPrefix() + name;
    }

    public String auditStreamKey() {
        return settings.getKeyPrefix() + "audit-stream";
    }

    /** @return the new version on success, or {@code -1} if {@code expectedVersion} no longer matches. */
    public long put(String name, long expectedVersion, String newJson, String actor) {
        Long result = redis.commands().eval(PUT_LUA, ScriptOutputType.INTEGER,
                new String[]{flagKey(name), auditStreamKey()},
                Long.toString(expectedVersion), newJson, actor,
                Long.toString(System.currentTimeMillis()), name);
        return result == null ? -1L : result;
    }

    /**
     * @return the deleted version on success (or the matched version, {@code 0}, if the flag was
     *         already absent), or {@code -1} if {@code expectedVersion} no longer matches.
     */
    public long delete(String name, long expectedVersion, String actor) {
        Long result = redis.commands().eval(DELETE_LUA, ScriptOutputType.INTEGER,
                new String[]{flagKey(name), auditStreamKey()},
                Long.toString(expectedVersion), actor,
                Long.toString(System.currentTimeMillis()), name);
        return result == null ? -1L : result;
    }

    /**
     * The version currently stored in Redis, read fresh (not cached) — used both for the conflict
     * message and, by {@link com.example.platform.featurecontrol.admin.FlagAdminService#currentVersion},
     * as the authoritative CAS baseline for a version-less delete. {@code 0} if absent or unreadable.
     */
    public long currentVersion(String name) {
        try {
            String json = redis.commands().get(flagKey(name));
            return json == null ? 0 : objectMapper.readValue(json, FlagDefinition.class).version();
        } catch (Exception e) {
            return 0;
        }
    }
}
