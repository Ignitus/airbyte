/*
 * Copyright (c) 2022 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.server.handlers;

import static java.util.stream.Collectors.toMap;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import io.airbyte.api.model.generated.AirbyteCatalog;
import io.airbyte.api.model.generated.AirbyteStream;
import io.airbyte.api.model.generated.AirbyteStreamAndConfiguration;
import io.airbyte.api.model.generated.AirbyteStreamConfiguration;
import io.airbyte.api.model.generated.CatalogDiff;
import io.airbyte.api.model.generated.ConnectionCreate;
import io.airbyte.api.model.generated.ConnectionIdRequestBody;
import io.airbyte.api.model.generated.ConnectionRead;
import io.airbyte.api.model.generated.ConnectionSearch;
import io.airbyte.api.model.generated.ConnectionUpdate;
import io.airbyte.api.model.generated.DestinationIdRequestBody;
import io.airbyte.api.model.generated.DestinationRead;
import io.airbyte.api.model.generated.JobConfigType;
import io.airbyte.api.model.generated.JobListRequestBody;
import io.airbyte.api.model.generated.JobRead;
import io.airbyte.api.model.generated.JobReadList;
import io.airbyte.api.model.generated.JobStatus;
import io.airbyte.api.model.generated.JobWithAttemptsRead;
import io.airbyte.api.model.generated.OperationCreate;
import io.airbyte.api.model.generated.OperationReadList;
import io.airbyte.api.model.generated.OperationUpdate;
import io.airbyte.api.model.generated.SourceDiscoverSchemaRead;
import io.airbyte.api.model.generated.SourceDiscoverSchemaRequestBody;
import io.airbyte.api.model.generated.SourceIdRequestBody;
import io.airbyte.api.model.generated.SourceRead;
import io.airbyte.api.model.generated.WebBackendConnectionCreate;
import io.airbyte.api.model.generated.WebBackendConnectionRead;
import io.airbyte.api.model.generated.WebBackendConnectionReadList;
import io.airbyte.api.model.generated.WebBackendConnectionRequestBody;
import io.airbyte.api.model.generated.WebBackendConnectionSearch;
import io.airbyte.api.model.generated.WebBackendConnectionUpdate;
import io.airbyte.api.model.generated.WebBackendOperationCreateOrUpdate;
import io.airbyte.api.model.generated.WebBackendWorkspaceState;
import io.airbyte.api.model.generated.WebBackendWorkspaceStateResult;
import io.airbyte.api.model.generated.WorkspaceIdRequestBody;
import io.airbyte.commons.json.Jsons;
import io.airbyte.commons.lang.MoreBooleans;
import io.airbyte.config.persistence.ConfigNotFoundException;
import io.airbyte.config.persistence.ConfigRepository;
import io.airbyte.scheduler.client.EventRunner;
import io.airbyte.validation.json.JsonValidationException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@AllArgsConstructor
@Slf4j
public class WebBackendConnectionsHandler {

  private static final Set<JobStatus> TERMINAL_STATUSES = Sets.newHashSet(JobStatus.FAILED, JobStatus.SUCCEEDED, JobStatus.CANCELLED);

  private final ConnectionsHandler connectionsHandler;
  private final SourceHandler sourceHandler;
  private final DestinationHandler destinationHandler;
  private final JobHistoryHandler jobHistoryHandler;
  private final SchedulerHandler schedulerHandler;
  private final OperationsHandler operationsHandler;
  private final EventRunner eventRunner;
  // todo (cgardens) - this handler should NOT have access to the db. only access via handler.
  private final ConfigRepository configRepository;

  public WebBackendWorkspaceStateResult getWorkspaceState(final WebBackendWorkspaceState webBackendWorkspaceState) throws IOException {
    final var workspaceId = webBackendWorkspaceState.getWorkspaceId();
    final var connectionCount = configRepository.countConnectionsForWorkspace(workspaceId);
    final var destinationCount = configRepository.countDestinationsForWorkspace(workspaceId);
    final var sourceCount = configRepository.countSourcesForWorkspace(workspaceId);

    return new WebBackendWorkspaceStateResult()
        .hasConnections(connectionCount > 0)
        .hasDestinations(destinationCount > 0)
        .hasSources(sourceCount > 0);
  }

  public WebBackendConnectionReadList webBackendListConnectionsForWorkspace(final WorkspaceIdRequestBody workspaceIdRequestBody)
      throws ConfigNotFoundException, IOException, JsonValidationException {

    final List<WebBackendConnectionRead> reads = Lists.newArrayList();
    for (final ConnectionRead connection : connectionsHandler.listConnectionsForWorkspace(workspaceIdRequestBody).getConnections()) {
      reads.add(buildWebBackendConnectionRead(connection));
    }
    return new WebBackendConnectionReadList().connections(reads);
  }

  public WebBackendConnectionReadList webBackendListAllConnectionsForWorkspace(final WorkspaceIdRequestBody workspaceIdRequestBody)
      throws ConfigNotFoundException, IOException, JsonValidationException {

    final List<WebBackendConnectionRead> reads = Lists.newArrayList();
    for (final ConnectionRead connection : connectionsHandler.listAllConnectionsForWorkspace(workspaceIdRequestBody).getConnections()) {
      reads.add(buildWebBackendConnectionRead(connection));
    }
    return new WebBackendConnectionReadList().connections(reads);
  }

  private WebBackendConnectionRead buildWebBackendConnectionRead(final ConnectionRead connectionRead)
      throws ConfigNotFoundException, IOException, JsonValidationException {
    final SourceRead source = getSourceRead(connectionRead);
    final DestinationRead destination = getDestinationRead(connectionRead);
    final OperationReadList operations = getOperationReadList(connectionRead);
    final JobReadList syncJobReadList = getSyncJobs(connectionRead);

    final WebBackendConnectionRead webBackendConnectionRead = getWebBackendConnectionRead(connectionRead, source, destination, operations)
        .catalogId(connectionRead.getSourceCatalogId())
        .isSyncing(syncJobReadList.getJobs()
            .stream()
            .map(JobWithAttemptsRead::getJob)
            .anyMatch(WebBackendConnectionsHandler::isRunningJob));
    setLatestSyncJobProperties(webBackendConnectionRead, syncJobReadList);
    return webBackendConnectionRead;
  }

  private static boolean isRunningJob(final JobRead job) {
    return !TERMINAL_STATUSES.contains(job.getStatus());
  }

  private SourceRead getSourceRead(final ConnectionRead connectionRead) throws JsonValidationException, IOException, ConfigNotFoundException {
    final SourceIdRequestBody sourceIdRequestBody = new SourceIdRequestBody().sourceId(connectionRead.getSourceId());
    return sourceHandler.getSource(sourceIdRequestBody);
  }

  private DestinationRead getDestinationRead(final ConnectionRead connectionRead)
      throws JsonValidationException, IOException, ConfigNotFoundException {
    final DestinationIdRequestBody destinationIdRequestBody = new DestinationIdRequestBody().destinationId(connectionRead.getDestinationId());
    return destinationHandler.getDestination(destinationIdRequestBody);
  }

  private OperationReadList getOperationReadList(final ConnectionRead connectionRead)
      throws JsonValidationException, IOException, ConfigNotFoundException {
    final ConnectionIdRequestBody connectionIdRequestBody = new ConnectionIdRequestBody().connectionId(connectionRead.getConnectionId());
    return operationsHandler.listOperationsForConnection(connectionIdRequestBody);
  }

  private static WebBackendConnectionRead getWebBackendConnectionRead(final ConnectionRead connectionRead,
                                                                      final SourceRead source,
                                                                      final DestinationRead destination,
                                                                      final OperationReadList operations) {
    return new WebBackendConnectionRead()
        .connectionId(connectionRead.getConnectionId())
        .sourceId(connectionRead.getSourceId())
        .destinationId(connectionRead.getDestinationId())
        .operationIds(connectionRead.getOperationIds())
        .name(connectionRead.getName())
        .namespaceDefinition(connectionRead.getNamespaceDefinition())
        .namespaceFormat(connectionRead.getNamespaceFormat())
        .prefix(connectionRead.getPrefix())
        .syncCatalog(connectionRead.getSyncCatalog())
        .status(connectionRead.getStatus())
        .schedule(connectionRead.getSchedule())
        .source(source)
        .destination(destination)
        .operations(operations.getOperations())
        .resourceRequirements(connectionRead.getResourceRequirements());
  }

  private JobReadList getSyncJobs(final ConnectionRead connectionRead) throws IOException {
    final JobListRequestBody jobListRequestBody = new JobListRequestBody()
        .configId(connectionRead.getConnectionId().toString())
        .configTypes(Collections.singletonList(JobConfigType.SYNC));
    return jobHistoryHandler.listJobsFor(jobListRequestBody);
  }

  private static void setLatestSyncJobProperties(final WebBackendConnectionRead WebBackendConnectionRead, final JobReadList syncJobReadList) {
    syncJobReadList.getJobs().stream().map(JobWithAttemptsRead::getJob).findFirst()
        .ifPresent(job -> {
          WebBackendConnectionRead.setLatestSyncJobCreatedAt(job.getCreatedAt());
          WebBackendConnectionRead.setLatestSyncJobStatus(job.getStatus());
        });
  }

  public WebBackendConnectionReadList webBackendSearchConnections(final WebBackendConnectionSearch webBackendConnectionSearch)
      throws ConfigNotFoundException, IOException, JsonValidationException {

    final List<WebBackendConnectionRead> reads = Lists.newArrayList();
    for (final ConnectionRead connectionRead : connectionsHandler.listConnections().getConnections()) {
      if (connectionsHandler.matchSearch(toConnectionSearch(webBackendConnectionSearch), connectionRead)) {
        reads.add(buildWebBackendConnectionRead(connectionRead));
      }
    }

    return new WebBackendConnectionReadList().connections(reads);
  }

  public WebBackendConnectionRead webBackendGetConnection(final WebBackendConnectionRequestBody webBackendConnectionRequestBody)
      throws ConfigNotFoundException, IOException, JsonValidationException {
    final ConnectionIdRequestBody connectionIdRequestBody = new ConnectionIdRequestBody()
        .connectionId(webBackendConnectionRequestBody.getConnectionId());

    final ConnectionRead connection = connectionsHandler.getConnection(connectionIdRequestBody.getConnectionId());

    final Optional<AirbyteCatalog> discovered;
    if (MoreBooleans.isTruthy(webBackendConnectionRequestBody.getWithRefreshedCatalog())) {
      final SourceDiscoverSchemaRequestBody discoverSchemaReadReq = new SourceDiscoverSchemaRequestBody()
          .sourceId(connection.getSourceId())
          .disableCache(true);
      final SourceDiscoverSchemaRead discoverSchema = schedulerHandler.discoverSchemaForSourceFromSourceId(discoverSchemaReadReq);

      discovered = Optional.of(discoverSchema.getCatalog());
      connection.setSourceCatalogId(discoverSchema.getCatalogId());
    } else {
      discovered = connectionsHandler.getConnectionAirbyteCatalog(webBackendConnectionRequestBody.getConnectionId());
    }
    final AirbyteCatalog original = connection.getSyncCatalog();
    final CatalogDiff diff;
    if (discovered.isPresent()) {
      final AirbyteCatalog combined = updateSchemaWithDiscovery(original, discovered.get());
      connection.setSyncCatalog(combined);
      diff = ConnectionsHandler.getDiff(original, discovered.get());
    } else {
      connection.setSyncCatalog(original);
      diff = null;
    }

    return buildWebBackendConnectionRead(connection).catalogDiff(diff);
  }

  @VisibleForTesting
  protected static AirbyteCatalog updateSchemaWithDiscovery(final AirbyteCatalog original, final AirbyteCatalog discovered) {
    // We can't directly use s.getStream() as the key, because it contains a bunch of other fields
    // so instead we just define a quick-and-dirty record class.
    final Map<Stream, AirbyteStreamAndConfiguration> originalStreamsByName = original.getStreams()
        .stream()
        .collect(toMap(s -> new Stream(s.getStream().getName(), s.getStream().getNamespace()), s -> s));

    final List<AirbyteStreamAndConfiguration> streams = new ArrayList<>();

    for (final AirbyteStreamAndConfiguration s : discovered.getStreams()) {
      final AirbyteStream stream = s.getStream();
      final AirbyteStreamAndConfiguration originalStream = originalStreamsByName.get(new Stream(stream.getName(), stream.getNamespace()));
      final AirbyteStreamConfiguration outputStreamConfig;

      if (originalStream != null) {
        final AirbyteStreamConfiguration originalStreamConfig = originalStream.getConfig();
        final AirbyteStreamConfiguration discoveredStreamConfig = s.getConfig();
        outputStreamConfig = new AirbyteStreamConfiguration();

        if (stream.getSupportedSyncModes().contains(originalStreamConfig.getSyncMode())) {
          outputStreamConfig.setSyncMode(originalStreamConfig.getSyncMode());
        } else {
          outputStreamConfig.setSyncMode(discoveredStreamConfig.getSyncMode());
        }

        if (originalStreamConfig.getCursorField().size() > 0) {
          outputStreamConfig.setCursorField(originalStreamConfig.getCursorField());
        } else {
          outputStreamConfig.setCursorField(discoveredStreamConfig.getCursorField());
        }

        outputStreamConfig.setDestinationSyncMode(originalStreamConfig.getDestinationSyncMode());
        if (originalStreamConfig.getPrimaryKey().size() > 0) {
          outputStreamConfig.setPrimaryKey(originalStreamConfig.getPrimaryKey());
        } else {
          outputStreamConfig.setPrimaryKey(discoveredStreamConfig.getPrimaryKey());
        }

        outputStreamConfig.setAliasName(originalStreamConfig.getAliasName());
        outputStreamConfig.setSelected(true);
      } else {
        outputStreamConfig = s.getConfig();
        outputStreamConfig.setSelected(false);
      }
      final AirbyteStreamAndConfiguration outputStream = new AirbyteStreamAndConfiguration()
          .stream(Jsons.clone(stream))
          .config(outputStreamConfig);
      streams.add(outputStream);
    }
    return new AirbyteCatalog().streams(streams);
  }

  public WebBackendConnectionRead webBackendCreateConnection(final WebBackendConnectionCreate webBackendConnectionCreate)
      throws ConfigNotFoundException, IOException, JsonValidationException {
    final List<UUID> operationIds = createOperations(webBackendConnectionCreate);

    final ConnectionCreate connectionCreate = toConnectionCreate(webBackendConnectionCreate, operationIds);
    return buildWebBackendConnectionRead(connectionsHandler.createConnection(connectionCreate));
  }

  public WebBackendConnectionRead webBackendUpdateConnection(final WebBackendConnectionUpdate webBackendConnectionUpdate)
      throws ConfigNotFoundException, IOException, JsonValidationException {
    final List<UUID> operationIds = updateOperations(webBackendConnectionUpdate);
    final ConnectionUpdate connectionUpdate = toConnectionUpdate(webBackendConnectionUpdate, operationIds);

    ConnectionRead connectionRead;
    final boolean needReset = MoreBooleans.isTruthy(webBackendConnectionUpdate.getWithRefreshedCatalog());

    connectionRead = connectionsHandler.updateConnection(connectionUpdate);

    if (needReset) {
      eventRunner.synchronousResetConnection(webBackendConnectionUpdate.getConnectionId());
      eventRunner.startNewManualSync(webBackendConnectionUpdate.getConnectionId());
      connectionRead = connectionsHandler.getConnection(connectionUpdate.getConnectionId());
    }

    return buildWebBackendConnectionRead(connectionRead);
  }

  private List<UUID> createOperations(final WebBackendConnectionCreate webBackendConnectionCreate)
      throws JsonValidationException, ConfigNotFoundException, IOException {
    final List<UUID> operationIds = new ArrayList<>();
    for (final var operationCreate : webBackendConnectionCreate.getOperations()) {
      operationIds.add(operationsHandler.createOperation(operationCreate).getOperationId());
    }
    return operationIds;
  }

  private List<UUID> updateOperations(final WebBackendConnectionUpdate webBackendConnectionUpdate)
      throws JsonValidationException, ConfigNotFoundException, IOException {
    final ConnectionRead connectionRead = connectionsHandler
        .getConnection(webBackendConnectionUpdate.getConnectionId());
    final List<UUID> originalOperationIds = new ArrayList<>(connectionRead.getOperationIds());
    final List<UUID> operationIds = new ArrayList<>();

    for (final var operationCreateOrUpdate : webBackendConnectionUpdate.getOperations()) {
      if (operationCreateOrUpdate.getOperationId() == null || !originalOperationIds.contains(operationCreateOrUpdate.getOperationId())) {
        final OperationCreate operationCreate = toOperationCreate(operationCreateOrUpdate);
        operationIds.add(operationsHandler.createOperation(operationCreate).getOperationId());
      } else {
        final OperationUpdate operationUpdate = toOperationUpdate(operationCreateOrUpdate);
        operationIds.add(operationsHandler.updateOperation(operationUpdate).getOperationId());
      }
    }
    originalOperationIds.removeAll(operationIds);
    operationsHandler.deleteOperationsForConnection(connectionRead.getConnectionId(), originalOperationIds);
    return operationIds;
  }

  @VisibleForTesting
  protected static OperationCreate toOperationCreate(final WebBackendOperationCreateOrUpdate operationCreateOrUpdate) {
    final OperationCreate operationCreate = new OperationCreate();

    operationCreate.name(operationCreateOrUpdate.getName());
    operationCreate.workspaceId(operationCreateOrUpdate.getWorkspaceId());
    operationCreate.operatorConfiguration(operationCreateOrUpdate.getOperatorConfiguration());

    return operationCreate;
  }

  @VisibleForTesting
  protected static OperationUpdate toOperationUpdate(final WebBackendOperationCreateOrUpdate operationCreateOrUpdate) {
    final OperationUpdate operationUpdate = new OperationUpdate();

    operationUpdate.operationId(operationCreateOrUpdate.getOperationId());
    operationUpdate.name(operationCreateOrUpdate.getName());
    operationUpdate.operatorConfiguration(operationCreateOrUpdate.getOperatorConfiguration());

    return operationUpdate;
  }

  @VisibleForTesting
  protected static ConnectionCreate toConnectionCreate(final WebBackendConnectionCreate webBackendConnectionCreate, final List<UUID> operationIds) {
    final ConnectionCreate connectionCreate = new ConnectionCreate();

    connectionCreate.name(webBackendConnectionCreate.getName());
    connectionCreate.namespaceDefinition(webBackendConnectionCreate.getNamespaceDefinition());
    connectionCreate.namespaceFormat(webBackendConnectionCreate.getNamespaceFormat());
    connectionCreate.prefix(webBackendConnectionCreate.getPrefix());
    connectionCreate.sourceId(webBackendConnectionCreate.getSourceId());
    connectionCreate.destinationId(webBackendConnectionCreate.getDestinationId());
    connectionCreate.operationIds(operationIds);
    connectionCreate.syncCatalog(webBackendConnectionCreate.getSyncCatalog());
    connectionCreate.schedule(webBackendConnectionCreate.getSchedule());
    connectionCreate.status(webBackendConnectionCreate.getStatus());
    connectionCreate.resourceRequirements(webBackendConnectionCreate.getResourceRequirements());
    connectionCreate.sourceCatalogId(webBackendConnectionCreate.getSourceCatalogId());

    return connectionCreate;
  }

  @VisibleForTesting
  protected static ConnectionUpdate toConnectionUpdate(final WebBackendConnectionUpdate webBackendConnectionUpdate, final List<UUID> operationIds) {
    final ConnectionUpdate connectionUpdate = new ConnectionUpdate();

    connectionUpdate.connectionId(webBackendConnectionUpdate.getConnectionId());
    connectionUpdate.namespaceDefinition(webBackendConnectionUpdate.getNamespaceDefinition());
    connectionUpdate.namespaceFormat(webBackendConnectionUpdate.getNamespaceFormat());
    connectionUpdate.prefix(webBackendConnectionUpdate.getPrefix());
    connectionUpdate.name(webBackendConnectionUpdate.getName());
    connectionUpdate.operationIds(operationIds);
    connectionUpdate.syncCatalog(webBackendConnectionUpdate.getSyncCatalog());
    connectionUpdate.schedule(webBackendConnectionUpdate.getSchedule());
    connectionUpdate.status(webBackendConnectionUpdate.getStatus());
    connectionUpdate.resourceRequirements(webBackendConnectionUpdate.getResourceRequirements());
    connectionUpdate.sourceCatalogId(webBackendConnectionUpdate.getSourceCatalogId());

    return connectionUpdate;
  }

  @VisibleForTesting
  protected static ConnectionSearch toConnectionSearch(final WebBackendConnectionSearch webBackendConnectionSearch) {
    return new ConnectionSearch()
        .name(webBackendConnectionSearch.getName())
        .connectionId(webBackendConnectionSearch.getConnectionId())
        .source(webBackendConnectionSearch.getSource())
        .sourceId(webBackendConnectionSearch.getSourceId())
        .destination(webBackendConnectionSearch.getDestination())
        .destinationId(webBackendConnectionSearch.getDestinationId())
        .namespaceDefinition(webBackendConnectionSearch.getNamespaceDefinition())
        .namespaceFormat(webBackendConnectionSearch.getNamespaceFormat())
        .prefix(webBackendConnectionSearch.getPrefix())
        .schedule(webBackendConnectionSearch.getSchedule())
        .status(webBackendConnectionSearch.getStatus());
  }

  /**
   * Equivalent to {@see io.airbyte.integrations.base.AirbyteStreamNameNamespacePair}. Intentionally
   * not using that class because it doesn't make sense for airbyte-server to depend on
   * base-java-integration.
   */
  private record Stream(String name, String namespace) {

  }

}
