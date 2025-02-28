package com.yugabyte.yw.models;

import com.yugabyte.yw.common.YWServiceException;
import io.ebean.Finder;
import io.ebean.Model;
import io.ebean.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.persistence.EmbeddedId;
import javax.persistence.Entity;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.yugabyte.yw.models.ScopedRuntimeConfig.GLOBAL_SCOPE_UUID;
import static play.mvc.Http.Status.NOT_FOUND;

@Entity
public class RuntimeConfigEntry extends Model {
  private static final Logger LOG = LoggerFactory.getLogger(RuntimeConfigEntry.class);

  @EmbeddedId private final RuntimeConfigEntryKey idKey;

  private String value;

  public RuntimeConfigEntry(UUID scopedConfigId, String path, String value) {
    this.idKey = new RuntimeConfigEntryKey(scopedConfigId, path);
    this.value = value;
  }

  public String getPath() {
    return idKey.getPath();
  }

  public String getValue() {
    return value;
  }

  public void setValue(String value) {
    this.value = value;
  }

  private static final Finder<UUID, RuntimeConfigEntry> findInScope =
      new Finder<UUID, RuntimeConfigEntry>(RuntimeConfigEntry.class) {};

  private static final Finder<RuntimeConfigEntryKey, RuntimeConfigEntry> findOne =
      new Finder<RuntimeConfigEntryKey, RuntimeConfigEntry>(RuntimeConfigEntry.class) {};

  public static List<RuntimeConfigEntry> getAll(UUID scope) {
    return findInScope.query().where().eq("scope_uuid", scope).findList();
  }

  @Deprecated
  public static RuntimeConfigEntry get(UUID scope, String path) {
    return findOne.byId(new RuntimeConfigEntryKey(scope, path));
  }

  public static RuntimeConfigEntry getOrBadRequest(UUID scope, String path) {
    RuntimeConfigEntry runtimeConfigEntry = get(scope, path);
    if (runtimeConfigEntry == null)
      throw new YWServiceException(
          NOT_FOUND, String.format("Key %s is not defined in scope %s", path, scope));
    return runtimeConfigEntry;
  }

  public static Map<String, String> getAsMapForScope(UUID scope) {
    List<RuntimeConfigEntry> scopedValues = getAll(scope);
    Map<String, String> map = new HashMap<>();
    for (RuntimeConfigEntry scopedValue : scopedValues) {
      String path = scopedValue.getPath();
      String value = scopedValue.getValue();
      if (path == null || value == null) {
        LOG.warn("Null key or value in runtime config {} = {}", path, value);
        continue;
      }
      if (map.put(path, value) != null) {
        LOG.warn("Duplicate key in runtime config {}", path);
      }
    }
    return map;
  }

  @Transactional
  public static RuntimeConfigEntry upsertGlobal(String path, String value) {
    RuntimeConfigEntry config = get(GLOBAL_SCOPE_UUID, path);
    if (config == null) {
      ScopedRuntimeConfig.ensureGlobal();
      LOG.debug("Setting {} value to: {}", path, value);
      config = new RuntimeConfigEntry(GLOBAL_SCOPE_UUID, path, value);
    } else {
      LOG.debug("Setting {} value to: {}", path, value);
      config.setValue(value);
    }
    config.save();
    return config;
  }

  @Transactional
  public static RuntimeConfigEntry upsert(Customer customer, String path, String value) {
    RuntimeConfigEntry config = get(customer.uuid, path);
    if (config == null) {
      ScopedRuntimeConfig.ensure(customer);
      config = new RuntimeConfigEntry(customer.uuid, path, value);
    } else {
      config.setValue(value);
    }
    config.save();
    return config;
  }

  @Transactional
  public static RuntimeConfigEntry upsert(Universe universe, String path, String value) {
    RuntimeConfigEntry config = get(universe.universeUUID, path);
    if (config == null) {
      ScopedRuntimeConfig.ensure(universe);
      config = new RuntimeConfigEntry(universe.universeUUID, path, value);
    } else {
      config.setValue(value);
    }
    config.save();
    return config;
  }

  @Transactional
  public static RuntimeConfigEntry upsert(Provider provider, String path, String value) {
    RuntimeConfigEntry config = get(provider.uuid, path);
    if (config == null) {
      ScopedRuntimeConfig.ensure(provider);
      config = new RuntimeConfigEntry(provider.uuid, path, value);
    } else {
      config.setValue(value);
    }
    config.save();
    return config;
  }

  @Override
  public String toString() {
    return "RuntimeConfigEntry{" + "idKey=" + idKey + ", value='" + value + '\'' + '}';
  }
}
