// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.commissioner.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.yugabyte.yw.commissioner.Commissioner;
import com.yugabyte.yw.common.ApiUtils;
import com.yugabyte.yw.common.ShellResponse;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams;
import com.yugabyte.yw.models.*;
import com.yugabyte.yw.models.helpers.TaskType;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.runners.MockitoJUnitRunner;
import play.libs.Json;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.yugabyte.yw.commissioner.tasks.subtasks.KubernetesCommandExecutor.CommandType.*;
import static com.yugabyte.yw.common.ApiUtils.getTestUserIntent;
import static com.yugabyte.yw.common.AssertHelper.assertJsonEqual;
import static com.yugabyte.yw.common.ModelFactory.createUniverse;
import static com.yugabyte.yw.models.TaskInfo.State.Failure;
import static com.yugabyte.yw.models.TaskInfo.State.Success;
import static org.junit.Assert.*;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class DestroyKubernetesUniverseTest extends CommissionerBaseTest {

  @InjectMocks Commissioner commissioner;

  Universe defaultUniverse;
  String nodePrefix = "demo-universe";

  Map<String, String> config = new HashMap<String, String>();
  AvailabilityZone az1, az2, az3;

  private void setupUniverse(boolean updateInProgress) {
    config.put("KUBECONFIG", "test");
    defaultProvider.setConfig(config);
    defaultProvider.save();
    Region r = Region.create(defaultProvider, "region-1", "PlacementRegion 1", "default-image");
    AvailabilityZone.create(r, "az-1", "PlacementAZ 1", "subnet-1");
    InstanceType i =
        InstanceType.upsert(
            defaultProvider.uuid, "c3.xlarge", 10, 5.5, new InstanceType.InstanceTypeDetails());
    UniverseDefinitionTaskParams.UserIntent userIntent =
        getTestUserIntent(r, defaultProvider, i, 3);
    userIntent.replicationFactor = 3;
    userIntent.masterGFlags = new HashMap<>();
    userIntent.tserverGFlags = new HashMap<>();
    userIntent.universeName = "demo-universe";

    defaultUniverse = createUniverse(defaultCustomer.getCustomerId());
    Universe.saveDetails(
        defaultUniverse.universeUUID,
        ApiUtils.mockUniverseUpdater(
            userIntent, nodePrefix, true /* setMasters */, updateInProgress));
  }

  private void setupUniverseMultiAZ(boolean updateInProgress, boolean skipProviderConfig) {
    if (!skipProviderConfig) {
      config.put("KUBECONFIG", "test");
      defaultProvider.setConfig(config);
      defaultProvider.save();
    }

    Region r = Region.create(defaultProvider, "region-1", "PlacementRegion 1", "default-image");
    az1 = AvailabilityZone.create(r, "az-1", "PlacementAZ 1", "subnet-1");
    az2 = AvailabilityZone.create(r, "az-2", "PlacementAZ 2", "subnet-2");
    az3 = AvailabilityZone.create(r, "az-3", "PlacementAZ 3", "subnet-3");
    InstanceType i =
        InstanceType.upsert(
            defaultProvider.uuid, "c3.xlarge", 10, 5.5, new InstanceType.InstanceTypeDetails());
    UniverseDefinitionTaskParams.UserIntent userIntent =
        getTestUserIntent(r, defaultProvider, i, 3);
    userIntent.replicationFactor = 3;
    userIntent.masterGFlags = new HashMap<>();
    userIntent.tserverGFlags = new HashMap<>();
    userIntent.universeName = "demo-universe";

    defaultUniverse = createUniverse(defaultCustomer.getCustomerId());
    Universe.saveDetails(
        defaultUniverse.universeUUID,
        ApiUtils.mockUniverseUpdater(
            userIntent, nodePrefix, true /* setMasters */, updateInProgress));
  }

  List<TaskType> KUBERNETES_DESTROY_UNIVERSE_TASKS =
      ImmutableList.of(
          TaskType.DestroyEncryptionAtRest,
          TaskType.KubernetesCommandExecutor,
          TaskType.KubernetesCommandExecutor,
          TaskType.KubernetesCommandExecutor,
          TaskType.RemoveUniverseEntry,
          TaskType.SwamperTargetsFileUpdate);

  List<JsonNode> KUBERNETES_DESTROY_UNIVERSE_EXPECTED_RESULTS =
      ImmutableList.of(
          Json.toJson(ImmutableMap.of()),
          Json.toJson(ImmutableMap.of("commandType", HELM_DELETE.name())),
          Json.toJson(ImmutableMap.of("commandType", VOLUME_DELETE.name())),
          Json.toJson(ImmutableMap.of("commandType", NAMESPACE_DELETE.name())),
          Json.toJson(ImmutableMap.of()),
          Json.toJson(ImmutableMap.of()));

  private void assertTaskSequence(Map<Integer, List<TaskInfo>> subTasksByPosition, int numTasks) {
    assertTaskSequence(subTasksByPosition, numTasks, numTasks);
  }

  private void assertTaskSequence(
      Map<Integer, List<TaskInfo>> subTasksByPosition, int numTasks, int numNamespaceDelete) {
    int position = 0;
    for (TaskType taskType : KUBERNETES_DESTROY_UNIVERSE_TASKS) {
      JsonNode expectedResults = KUBERNETES_DESTROY_UNIVERSE_EXPECTED_RESULTS.get(position);
      List<TaskInfo> tasks = subTasksByPosition.get(position);

      if (expectedResults.equals(
          Json.toJson(ImmutableMap.of("commandType", NAMESPACE_DELETE.name())))) {
        if (numNamespaceDelete == 0) {
          position++;
          continue;
        }
        assertEquals(numNamespaceDelete, tasks.size());
      } else if (expectedResults.equals(
          Json.toJson(ImmutableMap.of("commandType", VOLUME_DELETE.name())))) {
        assertEquals(numTasks, tasks.size());
      } else if (expectedResults.equals(
          Json.toJson(ImmutableMap.of("commandType", HELM_DELETE.name())))) {
        assertEquals(numTasks, tasks.size());
      } else {
        assertEquals(1, tasks.size());
      }

      assertEquals(taskType, tasks.get(0).getTaskType());
      List<JsonNode> taskDetails =
          tasks.stream().map(t -> t.getTaskDetails()).collect(Collectors.toList());
      assertJsonEqual(expectedResults, taskDetails.get(0));
      position++;
    }
  }

  private TaskInfo submitTask(DestroyUniverse.Params taskParams) {
    taskParams.universeUUID = defaultUniverse.universeUUID;
    taskParams.expectedUniverseVersion = 2;
    try {
      UUID taskUUID = commissioner.submit(TaskType.DestroyKubernetesUniverse, taskParams);
      return waitForTask(taskUUID);
    } catch (InterruptedException e) {
      assertNull(e.getMessage());
    }
    return null;
  }

  @Test
  public void testDestroyKubernetesUniverseSuccess() {
    setupUniverse(false);
    defaultUniverse.updateConfig(
        ImmutableMap.of(Universe.HELM2_LEGACY, Universe.HelmLegacy.V3.toString()));
    ShellResponse response = new ShellResponse();
    when(mockKubernetesManager.helmDelete(any(), any(), any())).thenReturn(response);
    DestroyUniverse.Params taskParams = new DestroyUniverse.Params();
    taskParams.isForceDelete = false;
    taskParams.customerUUID = defaultCustomer.uuid;
    taskParams.universeUUID = defaultUniverse.universeUUID;
    TaskInfo taskInfo = submitTask(taskParams);
    verify(mockKubernetesManager, times(1)).helmDelete(config, nodePrefix, nodePrefix);
    verify(mockKubernetesManager, times(1)).deleteStorage(config, nodePrefix, nodePrefix);
    verify(mockKubernetesManager, times(1)).deleteNamespace(config, nodePrefix);
    List<TaskInfo> subTasks = taskInfo.getSubTasks();
    Map<Integer, List<TaskInfo>> subTasksByPosition =
        subTasks.stream().collect(Collectors.groupingBy(w -> w.getPosition()));
    assertTaskSequence(subTasksByPosition, 1);
    assertEquals(Success, taskInfo.getTaskState());
    assertFalse(defaultCustomer.getUniverseUUIDs().contains(defaultUniverse.universeUUID));
  }

  @Test
  public void testDestoryKubernetesUniverseWithUpdateInProgress() {
    setupUniverse(true);
    defaultUniverse.updateConfig(
        ImmutableMap.of(Universe.HELM2_LEGACY, Universe.HelmLegacy.V3.toString()));
    DestroyUniverse.Params taskParams = new DestroyUniverse.Params();
    taskParams.isForceDelete = false;
    taskParams.customerUUID = defaultCustomer.uuid;
    taskParams.universeUUID = defaultUniverse.universeUUID;
    TaskInfo taskInfo = submitTask(taskParams);
    assertEquals(Failure, taskInfo.getTaskState());
  }

  @Test
  public void testForceDestroyKubernetesUniverseWithUpdateInProgress() {
    setupUniverse(true);
    defaultUniverse.updateConfig(
        ImmutableMap.of(Universe.HELM2_LEGACY, Universe.HelmLegacy.V3.toString()));
    DestroyUniverse.Params taskParams = new DestroyUniverse.Params();
    taskParams.isForceDelete = true;
    taskParams.customerUUID = defaultCustomer.uuid;
    taskParams.universeUUID = defaultUniverse.universeUUID;
    TaskInfo taskInfo = submitTask(taskParams);
    verify(mockKubernetesManager, times(1)).helmDelete(config, nodePrefix, nodePrefix);
    verify(mockKubernetesManager, times(1)).deleteStorage(config, nodePrefix, nodePrefix);
    verify(mockKubernetesManager, times(1)).deleteNamespace(config, nodePrefix);
    List<TaskInfo> subTasks = taskInfo.getSubTasks();
    Map<Integer, List<TaskInfo>> subTasksByPosition =
        subTasks.stream().collect(Collectors.groupingBy(w -> w.getPosition()));
    assertTaskSequence(subTasksByPosition, 1);
    assertEquals(Success, taskInfo.getTaskState());
    assertFalse(defaultCustomer.getUniverseUUIDs().contains(defaultUniverse.universeUUID));
  }

  @Test
  public void testDestroyKubernetesUniverseSuccessMultiAZ() {
    setupUniverseMultiAZ(/* update in progress */ false, /* skip provider config */ false);
    defaultUniverse.updateConfig(
        ImmutableMap.of(Universe.HELM2_LEGACY, Universe.HelmLegacy.V3.toString()));
    ShellResponse response = new ShellResponse();
    when(mockKubernetesManager.helmDelete(any(), any(), any())).thenReturn(response);

    ArgumentCaptor<UUID> expectedUniverseUUID = ArgumentCaptor.forClass(UUID.class);
    ArgumentCaptor<String> expectedNodePrefix = ArgumentCaptor.forClass(String.class);

    DestroyUniverse.Params taskParams = new DestroyUniverse.Params();
    taskParams.isForceDelete = false;
    taskParams.customerUUID = defaultCustomer.uuid;
    taskParams.universeUUID = defaultUniverse.universeUUID;
    TaskInfo taskInfo = submitTask(taskParams);
    String nodePrefix1 = String.format("%s-%s", nodePrefix, "az-1");
    String nodePrefix2 = String.format("%s-%s", nodePrefix, "az-2");
    String nodePrefix3 = String.format("%s-%s", nodePrefix, "az-3");
    verify(mockKubernetesManager, times(1)).helmDelete(config, nodePrefix1, nodePrefix1);
    verify(mockKubernetesManager, times(1)).helmDelete(config, nodePrefix2, nodePrefix2);
    verify(mockKubernetesManager, times(1)).helmDelete(config, nodePrefix3, nodePrefix3);
    verify(mockKubernetesManager, times(1)).deleteStorage(config, nodePrefix1, nodePrefix1);
    verify(mockKubernetesManager, times(1)).deleteStorage(config, nodePrefix2, nodePrefix2);
    verify(mockKubernetesManager, times(1)).deleteStorage(config, nodePrefix3, nodePrefix3);
    verify(mockKubernetesManager, times(1)).deleteNamespace(config, nodePrefix1);
    verify(mockKubernetesManager, times(1)).deleteNamespace(config, nodePrefix2);
    verify(mockKubernetesManager, times(1)).deleteNamespace(config, nodePrefix3);
    List<TaskInfo> subTasks = taskInfo.getSubTasks();
    Map<Integer, List<TaskInfo>> subTasksByPosition =
        subTasks.stream().collect(Collectors.groupingBy(w -> w.getPosition()));
    assertTaskSequence(subTasksByPosition, 3);
    assertEquals(Success, taskInfo.getTaskState());
    assertFalse(defaultCustomer.getUniverseUUIDs().contains(defaultUniverse.universeUUID));
  }

  @Test
  public void testDestroyKubernetesUniverseSuccessMultiAZWithNamespace() {
    setupUniverseMultiAZ(/* update in progress */ false, /* skip provider config */ true);
    defaultUniverse.updateConfig(
        ImmutableMap.of(Universe.HELM2_LEGACY, Universe.HelmLegacy.V3.toString()));

    String nodePrefix1 = String.format("%s-%s", nodePrefix, az1.code);
    String nodePrefix2 = String.format("%s-%s", nodePrefix, az2.code);
    String nodePrefix3 = String.format("%s-%s", nodePrefix, az3.code);

    String ns1 = "demo-ns-1";
    String ns2 = "demons2";
    String ns3 = nodePrefix3;

    Map<String, String> config1 = new HashMap();
    Map<String, String> config2 = new HashMap();
    Map<String, String> config3 = new HashMap();
    config1.put("KUBECONFIG", "test-kc-" + 1);
    config2.put("KUBECONFIG", "test-kc-" + 2);
    config3.put("KUBECONFIG", "test-kc-" + 3);

    config1.put("KUBENAMESPACE", ns1);
    config2.put("KUBENAMESPACE", ns2);

    az1.updateConfig(config1);
    az2.updateConfig(config2);
    az3.updateConfig(config3);

    ShellResponse response = new ShellResponse();
    when(mockKubernetesManager.helmDelete(any(), any(), any())).thenReturn(response);

    DestroyUniverse.Params taskParams = new DestroyUniverse.Params();
    taskParams.isForceDelete = false;
    taskParams.customerUUID = defaultCustomer.uuid;
    taskParams.universeUUID = defaultUniverse.universeUUID;
    TaskInfo taskInfo = submitTask(taskParams);

    verify(mockKubernetesManager, times(1)).helmDelete(config1, nodePrefix1, ns1);
    verify(mockKubernetesManager, times(1)).helmDelete(config2, nodePrefix2, ns2);
    verify(mockKubernetesManager, times(1)).helmDelete(config3, nodePrefix3, ns3);

    verify(mockKubernetesManager, times(1)).deleteStorage(config1, nodePrefix1, ns1);
    verify(mockKubernetesManager, times(1)).deleteStorage(config2, nodePrefix2, ns2);
    verify(mockKubernetesManager, times(1)).deleteStorage(config3, nodePrefix3, ns3);

    verify(mockKubernetesManager, times(0)).deleteNamespace(config1, ns1);
    verify(mockKubernetesManager, times(0)).deleteNamespace(config2, ns2);
    verify(mockKubernetesManager, times(1)).deleteNamespace(config3, ns3);
    List<TaskInfo> subTasks = taskInfo.getSubTasks();
    Map<Integer, List<TaskInfo>> subTasksByPosition =
        subTasks.stream().collect(Collectors.groupingBy(w -> w.getPosition()));
    assertTaskSequence(subTasksByPosition, 3, 1);
    assertEquals(Success, taskInfo.getTaskState());
    assertFalse(defaultCustomer.getUniverseUUIDs().contains(defaultUniverse.universeUUID));
  }

  @Test
  public void testDestroyKubernetesHelm2UniverseSuccess() {
    setupUniverseMultiAZ(/* update in progress */ false, /* skip provider config */ false);
    defaultUniverse.updateConfig(
        ImmutableMap.of(Universe.HELM2_LEGACY, Universe.HelmLegacy.V2TO3.toString()));
    ShellResponse response = new ShellResponse();
    when(mockKubernetesManager.helmDelete(any(), any(), any())).thenReturn(response);

    ArgumentCaptor<UUID> expectedUniverseUUID = ArgumentCaptor.forClass(UUID.class);
    ArgumentCaptor<String> expectedNodePrefix = ArgumentCaptor.forClass(String.class);

    DestroyUniverse.Params taskParams = new DestroyUniverse.Params();
    taskParams.isForceDelete = false;
    taskParams.customerUUID = defaultCustomer.uuid;
    taskParams.universeUUID = defaultUniverse.universeUUID;
    TaskInfo taskInfo = submitTask(taskParams);
    String nodePrefix1 = String.format("%s-%s", nodePrefix, "az-1");
    String nodePrefix2 = String.format("%s-%s", nodePrefix, "az-2");
    String nodePrefix3 = String.format("%s-%s", nodePrefix, "az-3");
    verify(mockKubernetesManager, times(1)).deleteNamespace(config, nodePrefix1);
    verify(mockKubernetesManager, times(1)).deleteNamespace(config, nodePrefix2);
    verify(mockKubernetesManager, times(1)).deleteNamespace(config, nodePrefix3);

    verify(mockKubernetesManager, times(1)).deleteStorage(config, nodePrefix1, nodePrefix1);
    verify(mockKubernetesManager, times(1)).deleteStorage(config, nodePrefix2, nodePrefix2);
    verify(mockKubernetesManager, times(1)).deleteStorage(config, nodePrefix3, nodePrefix3);
    List<TaskInfo> subTasks = taskInfo.getSubTasks();
    Map<Integer, List<TaskInfo>> subTasksByPosition =
        subTasks.stream().collect(Collectors.groupingBy(w -> w.getPosition()));
    assertTaskSequence(subTasksByPosition, 3);
    assertEquals(Success, taskInfo.getTaskState());
    assertFalse(defaultCustomer.getUniverseUUIDs().contains(defaultUniverse.universeUUID));
  }
}
