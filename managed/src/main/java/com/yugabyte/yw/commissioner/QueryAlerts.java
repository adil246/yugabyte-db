/*
 * Copyright 2020 YugaByte, Inc. and Contributors
 *
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */

package com.yugabyte.yw.commissioner;

import akka.actor.ActorSystem;

import com.google.common.annotations.VisibleForTesting;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.yugabyte.yw.common.AlertManager;
import com.yugabyte.yw.common.alerts.AlertTemplateSubstitutor;
import com.yugabyte.yw.common.config.ConfigSubstitutor;
import com.yugabyte.yw.common.config.RuntimeConfigFactory;
import com.yugabyte.yw.metrics.MetricQueryHelper;
import com.yugabyte.yw.models.*;
import com.yugabyte.yw.models.helpers.DataConverters;
import org.apache.commons.collections.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.concurrent.ExecutionContext;
import scala.concurrent.duration.Duration;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Singleton
public class QueryAlerts {
  public static final Logger LOG = LoggerFactory.getLogger(QueryAlerts.class);

  private AtomicBoolean running = new AtomicBoolean(false);

  private final ActorSystem actorSystem;

  private final ExecutionContext executionContext;

  private final MetricQueryHelper queryHelper;

  private final AlertManager alertManager;

  private final int YB_QUERY_ALERTS_INTERVAL = 1;

  private final RuntimeConfigFactory configFactory;

  @Inject
  public QueryAlerts(
      ExecutionContext executionContext,
      ActorSystem actorSystem,
      AlertManager alertManager,
      MetricQueryHelper queryHelper,
      RuntimeConfigFactory configFactory) {
    this.actorSystem = actorSystem;
    this.executionContext = executionContext;
    this.queryHelper = queryHelper;
    this.alertManager = alertManager;
    this.configFactory = configFactory;
    this.initialize();
  }

  public void setRunningState(AtomicBoolean state) {
    this.running = state;
  }

  private void initialize() {
    this.actorSystem
        .scheduler()
        .schedule(
            Duration.create(0, TimeUnit.MINUTES),
            Duration.create(YB_QUERY_ALERTS_INTERVAL, TimeUnit.MINUTES),
            this::scheduleRunner,
            this.executionContext);
  }

  public Set<Alert> processAlertDefinitions(UUID customerUUID) {
    Set<Alert> alertsStillActive = new HashSet<>();
    AlertDefinition.listActive(customerUUID)
        .forEach(
            definition -> {
              try {
                String query;
                // TODO Need to store threshold and duration in definition itself - this way custom
                // logic will not be needed for each definition target type
                switch (definition.targetType) {
                  case Universe:
                    Universe universe = Universe.getOrBadRequest(definition.getUniverseUUID());
                    query =
                        new ConfigSubstitutor(configFactory.forUniverse(universe))
                            .replace(definition.query);
                    break;
                  default:
                    throw new IllegalStateException(
                        "Unexpected definition type " + definition.targetType.name());
                }
                if (!queryHelper.queryDirect(query).isEmpty()) {
                  List<Alert> existingAlerts =
                      Alert.getActiveCustomerAlerts(customerUUID, definition.uuid);
                  // Create an alert to activate if it doesn't exist already.
                  if (CollectionUtils.isEmpty(existingAlerts)) {
                    // For now - copy labels from definition to alert. Later, alert will also
                    // have own labels from Prometheus.
                    List<AlertLabel> labels =
                        definition
                            .getEffectiveLabels()
                            .stream()
                            .map(l -> new AlertLabel(l.getName(), l.getValue()))
                            .collect(Collectors.toList());

                    UUID targetUUID = definition.getTargetUUID();
                    Alert.TargetType targetType =
                        DataConverters.definitionToAlertTargetType(definition.targetType);
                    AlertTemplateSubstitutor substitutor = new AlertTemplateSubstitutor(definition);
                    String message = substitutor.replace(definition.getMessageTemplate());

                    Alert.create(
                        customerUUID,
                        targetUUID,
                        targetType,
                        "CUSTOMER_ALERT",
                        "Error",
                        message,
                        definition.isActive,
                        definition.uuid,
                        labels);
                  } else {
                    alertsStillActive.addAll(existingAlerts);
                  }
                }
              } catch (Exception e) {
                LOG.error("Error processing alert definition '{}'", definition.name, e);
              }
            });

    return alertsStillActive;
  }

  @VisibleForTesting
  void scheduleRunner() {
    if (HighAvailabilityConfig.isFollower()) {
      LOG.debug("Skipping querying for alerts for follower platform");
      return;
    }

    if (running.compareAndSet(false, true)) {
      try {
        Set<Alert> alertsStillActive = new HashSet<>();

        // Pick up all alerts still active + create new alerts
        Customer.getAll().forEach(c -> alertsStillActive.addAll(processAlertDefinitions(c.uuid)));

        // Pick up all created alerts that are waiting to be activated
        Set<Alert> alertsToTransition = new HashSet<>(Alert.listToActivate());

        // Pick up all alerts that should be resolved internally but are currently active
        Customer.getAll()
            .forEach(
                c ->
                    Alert.listActiveCustomerAlerts(c.uuid)
                        .forEach(
                            alert -> {
                              if (!alertsStillActive.contains(alert)) alertsToTransition.add(alert);
                            }));

        // Trigger alert transitions
        alertsToTransition.forEach(alertManager::transitionAlert);
      } catch (Exception e) {
        LOG.error("Error querying for alerts", e);
      }

      running.set(false);
    }
  }
}
