// Copyright (c) YugaByte, Inc.
package com.yugabyte.yw.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yugabyte.yw.commissioner.tasks.params.NodeTaskParams;
import com.yugabyte.yw.common.*;
import com.yugabyte.yw.forms.NodeInstanceFormData;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams;
import com.yugabyte.yw.models.*;
import com.yugabyte.yw.models.helpers.NodeDetails;
import com.yugabyte.yw.models.helpers.NodeDetails.NodeState;
import com.yugabyte.yw.models.helpers.TaskType;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import play.libs.Json;
import play.mvc.Result;

import java.util.LinkedList;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.yugabyte.yw.common.AssertHelper.*;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.Assert.*;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;
import static play.mvc.Http.Status.FORBIDDEN;
import static play.mvc.Http.Status.OK;
import static play.test.Helpers.contentAsString;

public class NodeInstanceControllerTest extends FakeDBApplication {
  private final String FAKE_IP = "fake_ip";
  private final String FAKE_IP_2 = "fake_ip_2";
  private Customer customer;
  private Users user;
  private Provider provider;
  private Region region;
  private AvailabilityZone zone;
  private NodeInstance node;

  ArgumentCaptor<TaskType> taskType;
  ArgumentCaptor<NodeTaskParams> taskParams;

  @Before
  public void setUp() {
    customer = ModelFactory.testCustomer("tc", "Test Customer 1");
    user = ModelFactory.testUser(customer);
    provider = ModelFactory.awsProvider(customer);
    region = Region.create(provider, "region-1", "Region 1", "yb-image-1");
    zone = AvailabilityZone.create(region, "az-1", "AZ 1", "subnet-1");

    taskType = ArgumentCaptor.forClass(TaskType.class);
    taskParams = ArgumentCaptor.forClass(NodeTaskParams.class);

    NodeInstanceFormData.NodeInstanceData nodeData1 = new NodeInstanceFormData.NodeInstanceData();
    nodeData1.ip = FAKE_IP;
    nodeData1.region = region.code;
    nodeData1.zone = zone.code;
    nodeData1.instanceType = "fake_instance_type";
    nodeData1.sshUser = "ssh-user";
    node = NodeInstance.create(zone.uuid, nodeData1);
    // Give it a name.
    node.setNodeName("fake_name");
    node.save();
  }

  private Result getNode(UUID nodeUuid) {
    String uri = "/api/customers/" + customer.uuid + "/nodes/" + nodeUuid + "/list";
    return FakeApiHelper.doRequest("GET", uri);
  }

  private Result listByZone(UUID zoneUuid) {
    String uri = "/api/customers/" + customer.uuid + "/zones/" + zoneUuid + "/nodes/list";
    return FakeApiHelper.doRequest("GET", uri);
  }

  private Result listByProvider(UUID providerUUID) {
    String uri = "/api/customers/" + customer.uuid + "/providers/" + providerUUID + "/nodes/list";
    return FakeApiHelper.doRequest("GET", uri);
  }

  private Result createNode(UUID zoneUuid, NodeInstanceFormData.NodeInstanceData details) {
    String uri = "/api/customers/" + customer.uuid + "/zones/" + zoneUuid + "/nodes";
    NodeInstanceFormData formData = new NodeInstanceFormData();
    formData.nodes = new LinkedList<>();
    formData.nodes.add(details);
    JsonNode body = Json.toJson(formData);
    return FakeApiHelper.doRequestWithBody("POST", uri, body);
  }

  private Result deleteInstance(UUID customerUUID, UUID providerUUID, String instanceIP) {
    String uri =
        "/api/customers/"
            + customerUUID
            + "/providers/"
            + providerUUID
            + "/instances/"
            + instanceIP;
    return FakeApiHelper.doRequest("DELETE", uri);
  }

  private Result performNodeAction(
      UUID customerUUID,
      UUID universeUUID,
      String nodeName,
      NodeActionType nodeAction,
      boolean mimicError) {
    String uri =
        "/api/customers/" + customerUUID + "/universes/" + universeUUID + "/nodes/" + nodeName;
    ObjectNode params = Json.newObject();
    if (mimicError) {
      params.put("foo", "bar");
    } else {
      params.put("nodeAction", nodeAction.name());
    }

    return FakeApiHelper.doRequestWithBody("PUT", uri, params);
  }

  private void setInTransitNode(UUID universeUUID) {
    Universe.UniverseUpdater updater =
        new Universe.UniverseUpdater() {
          public void run(Universe universe) {
            UniverseDefinitionTaskParams universeDetails = universe.getUniverseDetails();
            NodeDetails node = universeDetails.nodeDetailsSet.iterator().next();
            node.state = NodeState.Removed;
            universe.setUniverseDetails(universeDetails);
          }
        };
    Universe.saveDetails(universeUUID, updater);
  }

  private void checkOk(Result r) {
    assertEquals(OK, r.status());
  }

  private void checkNotOk(Result r, String error) {
    assertNotEquals(OK, r.status());
    if (error != null) {
      JsonNode json = parseResult(r);
      assertEquals(error, json.get("error").asText());
    }
  }

  private void checkNodesMatch(JsonNode queryNode, NodeInstance dbNode) {
    assertEquals(dbNode.nodeUuid.toString(), queryNode.get("nodeUuid").asText());
    assertEquals(dbNode.zoneUuid.toString(), queryNode.get("zoneUuid").asText());
    assertEquals(dbNode.getDetailsJson(), queryNode.get("details").toString());
    assertEquals(dbNode.getDetails().sshUser, queryNode.get("details").get("sshUser").asText());
  }

  private void checkNodeValid(JsonNode nodeAsJson) {
    checkNodesMatch(nodeAsJson, node);
  }

  private JsonNode parseResult(Result r) {
    return Json.parse(contentAsString(r));
  }

  @Test
  public void testGetNodeWithValidUuid() {
    Result r = getNode(node.nodeUuid);
    checkOk(r);
    JsonNode json = parseResult(r);
    assertTrue(json.isObject());
    checkNodeValid(json);
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testGetNodeWithInvalidUuid() {
    UUID uuid = UUID.randomUUID();
    Result r = assertThrows(YWServiceException.class, () -> getNode(uuid)).getResult();
    String expectedError = "Invalid Node UUID: " + uuid;
    assertBadRequest(r, expectedError);
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testListByZoneSuccess() {
    Result r = listByZone(zone.uuid);
    checkOk(r);
    JsonNode json = parseResult(r);
    assertTrue(json.isArray());
    assertEquals(1, json.size());
    checkNodeValid(json.get(0));
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testListByProviderSuccess() {
    Result r = listByProvider(provider.uuid);
    checkOk(r);
    JsonNode json = parseResult(r);
    assertTrue(json.isArray());
    assertEquals(1, json.size());
    checkNodeValid(json.get(0));
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testListByZoneWrongZone() {
    UUID wrongUuid = UUID.randomUUID();
    Result r = assertThrows(YWServiceException.class, () -> listByZone(wrongUuid)).getResult();
    String expectedError = "Invalid AvailabilityZone UUID: " + wrongUuid.toString();
    checkNotOk(r, expectedError);
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testListByZoneNoFreeNodes() {
    node.inUse = true;
    node.save();
    Result r = listByZone(zone.uuid);
    checkOk(r);

    JsonNode json = parseResult(r);
    assertEquals(0, json.size());

    node.inUse = false;
    node.save();
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testCreateSuccess() {
    NodeInstanceFormData.NodeInstanceData testNode = new NodeInstanceFormData.NodeInstanceData();
    testNode.ip = FAKE_IP_2;
    testNode.region = region.code;
    testNode.zone = zone.code;
    testNode.instanceType = "fake_instance_type";
    testNode.sshUser = "ssh-user";
    Result successReq = createNode(zone.uuid, testNode);
    checkOk(successReq);
    JsonNode json = parseResult(successReq);
    assertThat(json, is(notNullValue()));
    assertTrue(json.isObject());
    JsonNode nodeJson = json.get(FAKE_IP_2);
    assertThat(nodeJson, is(notNullValue()));
    assertTrue(nodeJson.isObject());

    UUID uuid = UUID.fromString(nodeJson.get("nodeUuid").asText());
    NodeInstance dbNode = NodeInstance.get(uuid);
    assertTrue(dbNode != null);
    checkNodesMatch(nodeJson, dbNode);
    assertAuditEntry(1, customer.uuid);
  }

  @Test
  public void testCreateFailureDuplicateIp() {
    Result failedReq =
        assertThrows(YWServiceException.class, () -> createNode(zone.uuid, node.getDetails()))
            .getResult();
    checkNotOk(failedReq, "Invalid nodes in request. Duplicate IP Addresses are not allowed.");
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testCreateFailureInvalidZone() {
    UUID wrongUuid = UUID.randomUUID();
    Result r =
        assertThrows(YWServiceException.class, () -> createNode(wrongUuid, node.getDetails()))
            .getResult();
    String error = "Invalid AvailabilityZone UUID: " + wrongUuid.toString();
    checkNotOk(r, error);
    assertAuditEntry(0, customer.uuid);
  }
  // Test for Delete Instance, use case is only for OnPrem, but test can be validated with AWS
  // provider as well
  @Test
  public void testDeleteInstanceWithValidInstanceIP() {
    Result r = deleteInstance(customer.uuid, provider.uuid, FAKE_IP);
    assertOk(r);
    assertAuditEntry(1, customer.uuid);
  }

  @Test
  public void testDeleteInstanceWithInvalidProviderValidInstanceIP() {
    UUID invalidProviderUUID = UUID.randomUUID();
    Result r =
        assertThrows(
                YWServiceException.class,
                () -> deleteInstance(customer.uuid, invalidProviderUUID, FAKE_IP))
            .getResult();
    assertBadRequest(r, "Cannot find universe " + invalidProviderUUID);
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testDeleteInstanceWithValidProviderInvalidInstanceIP() {
    Result r =
        assertThrows(
                YWServiceException.class, () -> deleteInstance(customer.uuid, provider.uuid, "abc"))
            .getResult();
    assertBadRequest(r, "Node Not Found");
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testDeleteInstanceWithInvalidCustomerUUID() {
    UUID invalidCustomerUUID = UUID.randomUUID();
    Result r = deleteInstance(invalidCustomerUUID, provider.uuid, "random_ip");
    assertEquals(FORBIDDEN, r.status());

    String resultString = contentAsString(r);
    assertEquals(resultString, "Unable To Authenticate User");
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testDeleteInstance() {
    Result r = deleteInstance(customer.uuid, provider.uuid, FAKE_IP);
    assertOk(r);
    assertAuditEntry(1, customer.uuid);
  }

  @Test
  public void testMissingNodeActionParam() {
    verify(mockCommissioner, times(0)).submit(any(), any());
    final Universe u = ModelFactory.createUniverse();
    Universe universe = Universe.saveDetails(u.universeUUID, ApiUtils.mockUniverseUpdater());
    customer.addUniverseUUID(universe.universeUUID);
    customer.save();
    Result r =
        assertThrows(
                YWServiceException.class,
                () ->
                    performNodeAction(
                        customer.uuid,
                        universe.universeUUID,
                        "host-n1",
                        NodeActionType.DELETE,
                        true))
            .getResult();
    assertBadRequest(r, "{\"nodeAction\":[\"This field is required\"]}");
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testInvalidNodeAction() {
    for (NodeActionType nodeActionType : NodeActionType.values()) {
      Universe u = ModelFactory.createUniverse(nodeActionType.name(), customer.getCustomerId());
      customer.addUniverseUUID(u.universeUUID);
      customer.save();
      verify(mockCommissioner, times(0)).submit(any(), any());
      Result r =
          assertThrows(
                  YWServiceException.class,
                  () ->
                      performNodeAction(
                          customer.uuid, u.universeUUID, "fake-n1", nodeActionType, true))
              .getResult();
      assertBadRequest(r, "Invalid Node fake-n1 for Universe");
      assertAuditEntry(0, customer.uuid);
    }
  }

  @Test
  public void testValidNodeAction() {
    for (NodeActionType nodeActionType : NodeActionType.values()) {
      // Skip QUERY b/c it is UI-only flag
      if (nodeActionType == NodeActionType.QUERY) {
        continue;
      }
      UUID fakeTaskUUID = UUID.randomUUID();
      when(mockCommissioner.submit(any(TaskType.class), any(UniverseDefinitionTaskParams.class)))
          .thenReturn(fakeTaskUUID);
      Universe u = ModelFactory.createUniverse(nodeActionType.name(), customer.getCustomerId());
      u = Universe.saveDetails(u.universeUUID, ApiUtils.mockUniverseUpdater());
      customer.addUniverseUUID(u.universeUUID);
      customer.save();
      Result r = performNodeAction(customer.uuid, u.universeUUID, "host-n1", nodeActionType, false);
      verify(mockCommissioner, times(1)).submit(taskType.capture(), taskParams.capture());
      assertEquals(nodeActionType.getCommissionerTask(), taskType.getValue());
      assertOk(r);
      JsonNode json = Json.parse(contentAsString(r));
      assertValue(json, "taskUUID", fakeTaskUUID.toString());
      CustomerTask ct = CustomerTask.find.query().where().eq("task_uuid", fakeTaskUUID).findOne();
      assertNotNull(ct);
      assertEquals(CustomerTask.TargetType.Node, ct.getTarget());
      assertEquals(nodeActionType.getCustomerTask(), ct.getType());
      assertEquals("host-n1", ct.getTargetName());
      Mockito.reset(mockCommissioner);
    }
    assertAuditEntry(NodeActionType.values().length - 1, customer.uuid);
  }

  @Test
  public void testDisableStopRemove() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(TaskType.class), any(UniverseDefinitionTaskParams.class)))
        .thenReturn(fakeTaskUUID);

    Universe u =
        Universe.saveDetails(
            ModelFactory.createUniverse("disable-stop-remove-rf-3", customer.getCustomerId())
                .universeUUID,
            ApiUtils.mockUniverseUpdater());
    setInTransitNode(u.universeUUID);

    Set<NodeDetails> nodes =
        u.getMasters()
            .stream()
            .filter((n) -> n.state == NodeState.Live)
            .collect(Collectors.toSet());

    NodeDetails curNode = nodes.iterator().next();
    Result invalidRemove =
        assertThrows(
                YWServiceException.class,
                () ->
                    performNodeAction(
                        customer.uuid,
                        u.universeUUID,
                        curNode.nodeName,
                        NodeActionType.REMOVE,
                        false))
            .getResult();
    assertBadRequest(
        invalidRemove,
        "Cannot REMOVE "
            + curNode.nodeName
            + ": As it will under replicate the masters (count = 2, replicationFactor = 3)");

    Result invalidStop =
        assertThrows(
                YWServiceException.class,
                () ->
                    performNodeAction(
                        customer.uuid,
                        u.universeUUID,
                        curNode.nodeName,
                        NodeActionType.STOP,
                        false))
            .getResult();
    assertBadRequest(
        invalidStop,
        "Cannot STOP "
            + curNode.nodeName
            + ": As it will under replicate the masters (count = 2, replicationFactor = 3)");
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testStartNodeActionPassesClustersAndRootCAInTaskParams() {
    NodeActionType nodeActionType = NodeActionType.START;
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(TaskType.class), any(UniverseDefinitionTaskParams.class)))
        .thenReturn(fakeTaskUUID);
    Universe u = ModelFactory.createUniverse(nodeActionType.name(), customer.getCustomerId());
    assertNotNull(u.getUniverseDetails().clusters);
    u.getUniverseDetails().rootCA = UUID.randomUUID();

    u = Universe.saveDetails(u.universeUUID, ApiUtils.mockUniverseUpdater());
    customer.addUniverseUUID(u.universeUUID);
    customer.save();
    Result r = performNodeAction(customer.uuid, u.universeUUID, "host-n1", nodeActionType, false);
    verify(mockCommissioner, times(1)).submit(taskType.capture(), taskParams.capture());
    assertEquals(nodeActionType.getCommissionerTask(), taskType.getValue());
    assertOk(r);
    assertEquals(u.getUniverseDetails().clusters.size(), taskParams.getValue().clusters.size());
    assertTrue(taskParams.getValue().clusters.size() > 0);
    assertTrue(
        u.getUniverseDetails().clusters.get(0).equals(taskParams.getValue().clusters.get(0)));
    assertEquals(u.getUniverseDetails().rootCA, taskParams.getValue().rootCA);
    Mockito.reset(mockCommissioner);
  }
}
