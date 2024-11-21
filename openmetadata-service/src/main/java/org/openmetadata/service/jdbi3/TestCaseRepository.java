package org.openmetadata.service.jdbi3;

import static org.openmetadata.common.utils.CommonUtil.nullOrEmpty;
import static org.openmetadata.schema.type.EventType.ENTITY_DELETED;
import static org.openmetadata.schema.type.EventType.ENTITY_UPDATED;
import static org.openmetadata.schema.type.EventType.LOGICAL_TEST_CASE_ADDED;
import static org.openmetadata.schema.type.Include.ALL;
import static org.openmetadata.service.Entity.FIELD_OWNERS;
import static org.openmetadata.service.Entity.FIELD_TAGS;
import static org.openmetadata.service.Entity.TEST_CASE;
import static org.openmetadata.service.Entity.TEST_CASE_RESULT;
import static org.openmetadata.service.Entity.TEST_DEFINITION;
import static org.openmetadata.service.Entity.TEST_SUITE;
import static org.openmetadata.service.Entity.getEntityByName;
import static org.openmetadata.service.Entity.getEntityTimeSeriesRepository;
import static org.openmetadata.service.Entity.populateEntityFieldTags;
import static org.openmetadata.service.exception.CatalogExceptionMessage.entityNotFound;
import static org.openmetadata.service.security.mask.PIIMasker.maskSampleData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.json.JsonPatch;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.sqlobject.transaction.Transaction;
import org.openmetadata.schema.EntityInterface;
import org.openmetadata.schema.EntityTimeSeriesInterface;
import org.openmetadata.schema.api.feed.CloseTask;
import org.openmetadata.schema.api.feed.ResolveTask;
import org.openmetadata.schema.api.tests.CreateTestCaseResult;
import org.openmetadata.schema.entity.data.Table;
import org.openmetadata.schema.entity.teams.User;
import org.openmetadata.schema.tests.TestCase;
import org.openmetadata.schema.tests.TestCaseParameter;
import org.openmetadata.schema.tests.TestCaseParameterValidationRule;
import org.openmetadata.schema.tests.TestCaseParameterValue;
import org.openmetadata.schema.tests.TestDefinition;
import org.openmetadata.schema.tests.TestSuite;
import org.openmetadata.schema.tests.type.Resolved;
import org.openmetadata.schema.tests.type.TestCaseFailureReasonType;
import org.openmetadata.schema.tests.type.TestCaseResolutionStatus;
import org.openmetadata.schema.tests.type.TestCaseResolutionStatusTypes;
import org.openmetadata.schema.tests.type.TestCaseResult;
import org.openmetadata.schema.type.ChangeDescription;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.FieldChange;
import org.openmetadata.schema.type.Include;
import org.openmetadata.schema.type.Relationship;
import org.openmetadata.schema.type.TableData;
import org.openmetadata.schema.type.TagLabel;
import org.openmetadata.schema.type.TaskType;
import org.openmetadata.schema.type.TestCaseParameterValidationRuleType;
import org.openmetadata.schema.utils.EntityInterfaceUtil;
import org.openmetadata.service.Entity;
import org.openmetadata.service.exception.EntityNotFoundException;
import org.openmetadata.service.resources.feeds.MessageParser.EntityLink;
import org.openmetadata.service.search.SearchListFilter;
import org.openmetadata.service.util.EntityUtil;
import org.openmetadata.service.util.EntityUtil.Fields;
import org.openmetadata.service.util.FullyQualifiedName;
import org.openmetadata.service.util.JsonUtils;
import org.openmetadata.service.util.RestUtil;
import org.openmetadata.service.util.ResultList;

@Slf4j
public class TestCaseRepository extends EntityRepository<TestCase> {
  private static final String TEST_SUITE_FIELD = "testSuite";
  private static final String INCIDENTS_FIELD = "incidentId";
  public static final String COLLECTION_PATH = "/v1/dataQuality/testCases";
  private static final String UPDATE_FIELDS =
      "owners,entityLink,testSuite,testSuites,testDefinition";
  private static final String PATCH_FIELDS =
      "owners,entityLink,testSuite,testDefinition,computePassedFailedRowCount,useDynamicAssertion";
  public static final String FAILED_ROWS_SAMPLE_EXTENSION = "testCase.failedRowsSample";

  public TestCaseRepository() {
    super(
        COLLECTION_PATH,
        TEST_CASE,
        TestCase.class,
        Entity.getCollectionDAO().testCaseDAO(),
        PATCH_FIELDS,
        UPDATE_FIELDS);
    supportsSearch = true;
    // Add the canonical name for test case results
    // As test case result` does not have its own repository
    EntityTimeSeriesInterface.CANONICAL_ENTITY_NAME_MAP.put(
        Entity.TEST_CASE_RESULT.toLowerCase(Locale.ROOT), Entity.TEST_CASE_RESULT);
  }

  @Override
  public void setFields(TestCase test, Fields fields) {
    test.setTestSuites(
        fields.contains(Entity.FIELD_TEST_SUITES) ? getTestSuites(test) : test.getTestSuites());
    test.setTestSuite(fields.contains(TEST_SUITE_FIELD) ? getTestSuite(test) : test.getTestSuite());
    test.setTestDefinition(
        fields.contains(TEST_DEFINITION) ? getTestDefinition(test) : test.getTestDefinition());
    test.setTestCaseResult(
        fields.contains(TEST_CASE_RESULT) ? getTestCaseResult(test) : test.getTestCaseResult());
    test.setIncidentId(
        fields.contains(INCIDENTS_FIELD) ? getIncidentId(test) : test.getIncidentId());
    test.setTags(fields.contains(FIELD_TAGS) ? getTestCaseTags(test) : test.getTags());
  }

  @Override
  public void setInheritedFields(TestCase testCase, Fields fields) {
    EntityLink entityLink = EntityLink.parse(testCase.getEntityLink());
    Table table = Entity.getEntity(entityLink, "owners,domain,tags,columns", ALL);
    inheritOwners(testCase, fields, table);
    inheritDomain(testCase, fields, table);
    inheritTags(testCase, fields, table);
  }

  private void inheritTags(TestCase testCase, Fields fields, Table table) {
    if (fields.contains(FIELD_TAGS)) {
      EntityLink entityLink = EntityLink.parse(testCase.getEntityLink());
      List<TagLabel> tags = new ArrayList<>(table.getTags());
      if (entityLink.getFieldName() != null && entityLink.getFieldName().equals("columns")) {
        // if we have a column test case get the columns tags as well
        table.getColumns().stream()
            .filter(column -> column.getName().equals(entityLink.getArrayFieldName()))
            .findFirst()
            .ifPresent(column -> tags.addAll(column.getTags()));
      }
      testCase.setTags(tags);
    }
  }

  @Override
  public EntityInterface getParentEntity(TestCase entity, String fields) {
    return Entity.getEntity(entity.getTestSuite(), fields, ALL);
  }

  @Override
  public void clearFields(TestCase test, Fields fields) {
    test.setTestSuites(fields.contains("testSuites") ? test.getTestSuites() : null);
    test.setTestSuite(fields.contains(TEST_SUITE) ? test.getTestSuite() : null);
    test.setTestDefinition(fields.contains(TEST_DEFINITION) ? test.getTestDefinition() : null);
    test.setTestCaseResult(fields.contains(TEST_CASE_RESULT) ? test.getTestCaseResult() : null);
  }

  public RestUtil.PatchResponse<TestCaseResult> patchTestCaseResults(
      String fqn, Long timestamp, JsonPatch patch, String updatedBy) {
    // TODO: REMOVED ONCE DEPRECATED IN TEST CASE RESOURCE
    TestCaseResultRepository testCaseResultRepository =
        (TestCaseResultRepository) Entity.getEntityTimeSeriesRepository(Entity.TEST_CASE_RESULT);
    return testCaseResultRepository.patchTestCaseResults(fqn, timestamp, patch, updatedBy);
  }

  @Override
  public void setFullyQualifiedName(TestCase test) {
    EntityLink entityLink = EntityLink.parse(test.getEntityLink());
    test.setFullyQualifiedName(
        FullyQualifiedName.add(
            entityLink.getFullyQualifiedFieldValue(),
            EntityInterfaceUtil.quoteName(test.getName())));
    test.setEntityFQN(entityLink.getFullyQualifiedFieldValue());
  }

  @Override
  public void prepare(TestCase test, boolean update) {
    EntityLink entityLink = EntityLink.parse(test.getEntityLink());
    EntityUtil.validateEntityLink(entityLink);

    // validate test definition and test suite
    TestSuite testSuite = Entity.getEntity(test.getTestSuite(), "", Include.NON_DELETED);
    test.setTestSuite(testSuite.getEntityReference());

    TestDefinition testDefinition =
        Entity.getEntity(test.getTestDefinition(), "", Include.NON_DELETED);
    test.setTestDefinition(testDefinition.getEntityReference());

    validateTestParameters(test.getParameterValues(), testDefinition.getParameterDefinition());
  }

  private EntityReference getTestSuite(TestCase test) throws EntityNotFoundException {
    // `testSuite` field returns the executable `testSuite` linked to that testCase
    List<CollectionDAO.EntityRelationshipRecord> records =
        findFromRecords(test.getId(), entityType, Relationship.CONTAINS, TEST_SUITE);
    for (CollectionDAO.EntityRelationshipRecord testSuiteId : records) {
      TestSuite testSuite = Entity.getEntity(TEST_SUITE, testSuiteId.getId(), "", Include.ALL);
      if (Boolean.TRUE.equals(testSuite.getExecutable())) {
        return testSuite.getEntityReference();
      }
    }
    throw new EntityNotFoundException(
        String.format(
                "Error occurred when retrieving executable test suite for testCase %s. ",
                test.getName())
            + "No executable test suite was found.");
  }

  private List<TestSuite> getTestSuites(TestCase test) {
    // `testSuites` field returns all the `testSuite` (executable and logical) linked to that
    // testCase
    List<CollectionDAO.EntityRelationshipRecord> records =
        findFromRecords(test.getId(), entityType, Relationship.CONTAINS, TEST_SUITE);
    return records.stream()
        .map(
            testSuiteId ->
                Entity.<TestSuite>getEntity(
                        TEST_SUITE, testSuiteId.getId(), "owners,domain", Include.ALL, false)
                    .withInherited(true)
                    .withChangeDescription(null))
        .toList();
  }

  private EntityReference getTestDefinition(TestCase test) {
    return getFromEntityRef(test.getId(), Relationship.CONTAINS, TEST_DEFINITION, true);
  }

  private void validateTestParameters(
      List<TestCaseParameterValue> parameterValues, List<TestCaseParameter> parameterDefinition) {
    if (parameterValues != null) {

      if (parameterDefinition.isEmpty() && !parameterValues.isEmpty()) {
        throw new IllegalArgumentException(
            "Parameter Values doesn't match Test Definition Parameters");
      }
      Map<String, Object> values = new HashMap<>();

      for (TestCaseParameterValue testCaseParameterValue : parameterValues) {
        values.put(testCaseParameterValue.getName(), testCaseParameterValue.getValue());
      }
      for (TestCaseParameter parameter : parameterDefinition) {
        if (Boolean.TRUE.equals(parameter.getRequired())
            && (!values.containsKey(parameter.getName())
                || values.get(parameter.getName()) == null)) {
          throw new IllegalArgumentException(
              "Required parameter " + parameter.getName() + " is not passed in parameterValues");
        }
        validateParameterRule(parameter, values);
      }
    }
  }

  @Override
  public void storeEntity(TestCase test, boolean update) {
    EntityReference testSuite = test.getTestSuite();
    EntityReference testDefinition = test.getTestDefinition();
    TestCaseResult testCaseResult = test.getTestCaseResult();

    // Don't store testCaseResult, owner, database, href and tags as JSON.
    // Build it on the fly based on relationships
    test.withTestSuite(null).withTestDefinition(null).withTestCaseResult(null);
    store(test, update);

    // Restore the relationships
    test.withTestSuite(testSuite)
        .withTestDefinition(testDefinition)
        .withTestCaseResult(testCaseResult);
  }

  @Override
  public void storeRelationships(TestCase test) {
    EntityLink entityLink = EntityLink.parse(test.getEntityLink());
    EntityUtil.validateEntityLink(entityLink);
    // Add relationship from testSuite to test
    addRelationship(
        test.getTestSuite().getId(), test.getId(), TEST_SUITE, TEST_CASE, Relationship.CONTAINS);
    // Add relationship from test definition to test
    addRelationship(
        test.getTestDefinition().getId(),
        test.getId(),
        TEST_DEFINITION,
        TEST_CASE,
        Relationship.CONTAINS);
  }

  @Override
  protected void postDelete(TestCase test) {
    super.postDelete(test);
    // Update test suite with new test case in search index
    TestSuiteRepository testSuiteRepository =
        (TestSuiteRepository) Entity.getEntityRepository(Entity.TEST_SUITE);
    TestSuite testSuite = Entity.getEntity(test.getTestSuite(), "*", ALL);
    TestSuite original = TestSuiteRepository.copyTestSuite(testSuite);
    testSuiteRepository.postUpdate(original, testSuite);
    deleteTestCaseFailedRowsSample(test.getId());
  }

  @Override
  protected void postCreate(TestCase test) {
    super.postCreate(test);
    // Update test suite with new test case in search index
    TestSuiteRepository testSuiteRepository =
        (TestSuiteRepository) Entity.getEntityRepository(Entity.TEST_SUITE);
    TestSuite testSuite = Entity.getEntity(test.getTestSuite(), "*", ALL);
    TestSuite original = TestSuiteRepository.copyTestSuite(testSuite);
    testSuiteRepository.postUpdate(original, testSuite);
  }

  public RestUtil.PutResponse<TestCaseResult> addTestCaseResult(
      String updatedBy, UriInfo uriInfo, String fqn, TestCaseResult testCaseResult) {
    // TODO: REMOVED ONCE DEPRECATED IN TEST CASE RESOURCE
    CreateTestCaseResult createTestCaseResult =
        new CreateTestCaseResult()
            .withTimestamp(testCaseResult.getTimestamp())
            .withTestCaseStatus(testCaseResult.getTestCaseStatus())
            .withResult(testCaseResult.getResult())
            .withSampleData(testCaseResult.getSampleData())
            .withTestResultValue(testCaseResult.getTestResultValue())
            .withPassedRows(testCaseResult.getPassedRows())
            .withFailedRows(testCaseResult.getFailedRows())
            .withPassedRowsPercentage(testCaseResult.getPassedRowsPercentage())
            .withFailedRowsPercentage(testCaseResult.getFailedRowsPercentage())
            .withIncidentId(testCaseResult.getIncidentId())
            .withMaxBound(testCaseResult.getMaxBound())
            .withMinBound(testCaseResult.getMinBound());

    TestCaseResultRepository testCaseResultRepository =
        (TestCaseResultRepository) Entity.getEntityTimeSeriesRepository(TEST_CASE_RESULT);
    Response response =
        testCaseResultRepository.addTestCaseResult(updatedBy, uriInfo, fqn, createTestCaseResult);
    return new RestUtil.PutResponse<>(
        Response.Status.CREATED, (TestCaseResult) response.getEntity(), ENTITY_UPDATED);
  }

  @Transaction
  @Override
  protected void deleteChildren(
      List<CollectionDAO.EntityRelationshipRecord> children, boolean hardDelete, String updatedBy) {
    if (hardDelete) {
      for (CollectionDAO.EntityRelationshipRecord entityRelationshipRecord : children) {
        LOG.info(
            "Recursively {} deleting {} {}",
            hardDelete ? "hard" : "soft",
            entityRelationshipRecord.getType(),
            entityRelationshipRecord.getId());
        TestCaseResolutionStatusRepository testCaseResolutionStatusRepository =
            (TestCaseResolutionStatusRepository)
                Entity.getEntityTimeSeriesRepository(Entity.TEST_CASE_RESOLUTION_STATUS);
        for (CollectionDAO.EntityRelationshipRecord child : children) {
          testCaseResolutionStatusRepository.deleteById(child.getId(), hardDelete);
        }
      }
    }
  }

  @Transaction
  @Override
  protected void cleanup(TestCase entityInterface) {
    super.cleanup(entityInterface);
    deleteAllTestCaseResults(entityInterface.getFullyQualifiedName());
  }

  public RestUtil.PutResponse<TestCaseResult> deleteTestCaseResult(
      String updatedBy, String fqn, Long timestamp) {
    // TODO: REMOVED ONCE DEPRECATED IN TEST CASE RESOURCE
    TestCaseResultRepository testCaseResultRepository =
        (TestCaseResultRepository) Entity.getEntityTimeSeriesRepository(TEST_CASE_RESULT);
    Response response = testCaseResultRepository.deleteTestCaseResult(fqn, timestamp).toResponse();
    return new RestUtil.PutResponse<>(
        Response.Status.OK, (TestCaseResult) response.getEntity(), ENTITY_DELETED);
  }

  private void deleteAllTestCaseResults(String fqn) {
    // Delete all the test case results
    TestCaseResultRepository testCaseResultRepository =
        (TestCaseResultRepository) Entity.getEntityTimeSeriesRepository(TEST_CASE_RESULT);
    testCaseResultRepository.deleteAllTestCaseResults(fqn);
  }

  @SneakyThrows
  private TestCaseResult getTestCaseResult(TestCase testCase) {
    TestCaseResult testCaseResult = null;
    if (testCase.getTestCaseResult() != null) {
      // we'll return the saved state if it exists otherwise we'll fetch it from the database
      // Should be the case if listing from the search repo. as the test case result
      // is stored with the test case entity (denormalized)
      return testCase.getTestCaseResult();
    }
    SearchListFilter searchListFilter = new SearchListFilter();
    searchListFilter.addQueryParam("testCaseFQN", testCase.getFullyQualifiedName());
    TestCaseResultRepository timeSeriesRepository =
        (TestCaseResultRepository) getEntityTimeSeriesRepository(TEST_CASE_RESULT);
    try {
      testCaseResult =
          timeSeriesRepository.latestFromSearch(Fields.EMPTY_FIELDS, searchListFilter, null);
    } catch (Exception e) {
      LOG.debug(
          "Error fetching test case result from search. Fetching from test case results from database",
          e);
    }
    if (nullOrEmpty(testCaseResult)) {
      testCaseResult =
          timeSeriesRepository.listLastTestCaseResult(testCase.getFullyQualifiedName());
    }
    return testCaseResult;
  }

  public ResultList<TestCaseResult> getTestCaseResults(String fqn, Long startTs, Long endTs) {
    // TODO: REMOVED ONCE DEPRECATED IN TEST CASE RESOURCE
    TestCaseResultRepository testCaseResultRepository =
        (TestCaseResultRepository) Entity.getEntityTimeSeriesRepository(TEST_CASE_RESULT);
    return testCaseResultRepository.getTestCaseResults(fqn, startTs, endTs);
  }

  /**
   * Check all the test case results that have an ongoing incident and get the stateId of the
   * incident
   */
  private UUID getIncidentId(TestCase test) {
    UUID ongoingIncident = null;

    String json =
        daoCollection.dataQualityDataTimeSeriesDao().getLatestRecord(test.getFullyQualifiedName());
    TestCaseResult latestTestCaseResult = JsonUtils.readValue(json, TestCaseResult.class);

    if (!nullOrEmpty(latestTestCaseResult)) {
      ongoingIncident = latestTestCaseResult.getIncidentId();
    }

    return ongoingIncident;
  }

  private List<TagLabel> getTestCaseTags(TestCase test) {
    EntityLink entityLink = EntityLink.parse(test.getEntityLink());
    Table table = Entity.getEntity(entityLink, "tags,columns", ALL);
    List<TagLabel> tags = new ArrayList<>(table.getTags());
    if (entityLink.getFieldName() != null && entityLink.getFieldName().equals("columns")) {
      // if we have a column test case get the columns tags as well
      table.getColumns().stream()
          .filter(column -> column.getName().equals(entityLink.getArrayFieldName()))
          .findFirst()
          .ifPresent(column -> tags.addAll(column.getTags()));
    }
    return tags;
  }

  public int getTestCaseCount(List<UUID> testCaseIds) {
    return daoCollection.testCaseDAO().countOfTestCases(testCaseIds);
  }

  public void isTestSuiteExecutable(String testSuiteFqn) {
    TestSuite testSuite = Entity.getEntityByName(Entity.TEST_SUITE, testSuiteFqn, null, null);
    if (Boolean.FALSE.equals(testSuite.getExecutable())) {
      throw new IllegalArgumentException(
          "Test suite "
              + testSuite.getName()
              + " is not executable. Cannot create test cases for non-executable test suites.");
    }
  }

  @Transaction
  public RestUtil.PutResponse<TestSuite> addTestCasesToLogicalTestSuite(
      TestSuite testSuite, List<UUID> testCaseIds) {
    bulkAddToRelationship(
        testSuite.getId(), testCaseIds, TEST_SUITE, TEST_CASE, Relationship.CONTAINS);
    for (UUID testCaseId : testCaseIds) {
      TestCase testCase = Entity.getEntity(Entity.TEST_CASE, testCaseId, "*", Include.ALL);
      ChangeDescription change =
          new ChangeDescription()
              .withFieldsUpdated(
                  List.of(
                      new FieldChange()
                          .withName("testSuites")
                          .withNewValue(testCase.getTestSuites())));
      testCase.setChangeDescription(change);
      postUpdate(testCase, testCase);
    }
    return new RestUtil.PutResponse<>(Response.Status.OK, testSuite, LOGICAL_TEST_CASE_ADDED);
  }

  @Transaction
  public RestUtil.DeleteResponse<TestCase> deleteTestCaseFromLogicalTestSuite(
      UUID testSuiteId, UUID testCaseId) {
    TestCase testCase = Entity.getEntity(Entity.TEST_CASE, testCaseId, null, null);
    deleteRelationship(testSuiteId, TEST_SUITE, testCaseId, TEST_CASE, Relationship.CONTAINS);
    TestCase updatedTestCase = Entity.getEntity(Entity.TEST_CASE, testCaseId, "*", Include.ALL);
    ChangeDescription change =
        new ChangeDescription()
            .withFieldsUpdated(
                List.of(
                    new FieldChange()
                        .withName("testSuites")
                        .withNewValue(updatedTestCase.getTestSuites())));
    updatedTestCase.setChangeDescription(change);
    postUpdate(testCase, updatedTestCase);
    testCase.setTestSuite(updatedTestCase.getTestSuite());
    testCase.setTestSuites(updatedTestCase.getTestSuites());
    return new RestUtil.DeleteResponse<>(testCase, ENTITY_DELETED);
  }

  @Override
  public EntityUpdater getUpdater(TestCase original, TestCase updated, Operation operation) {
    return new TestUpdater(original, updated, operation);
  }

  @Override
  public FeedRepository.TaskWorkflow getTaskWorkflow(FeedRepository.ThreadContext threadContext) {
    validateTaskThread(threadContext);
    TaskType taskType = threadContext.getThread().getTask().getType();
    if (EntityUtil.isTestCaseFailureResolutionTask(taskType)) {
      return new TestCaseRepository.TestCaseFailureResolutionTaskWorkflow(threadContext);
    }
    return super.getTaskWorkflow(threadContext);
  }

  @Transaction
  public TestCase addFailedRowsSample(
      TestCase testCase, TableData tableData, boolean validateColumns) {
    EntityLink entityLink = EntityLink.parse(testCase.getEntityLink());
    Table table = Entity.getEntity(entityLink, FIELD_OWNERS, ALL);
    // Validate all the columns
    if (validateColumns) {
      for (String columnName : tableData.getColumns()) {
        validateColumn(table, columnName);
      }
    }
    // Make sure each row has number values for all the columns
    for (List<Object> row : tableData.getRows()) {
      if (row.size() != tableData.getColumns().size()) {
        throw new IllegalArgumentException(
            String.format(
                "Number of columns is %d but row has %d sample values",
                tableData.getColumns().size(), row.size()));
      }
    }
    daoCollection
        .entityExtensionDAO()
        .insert(
            testCase.getId(),
            FAILED_ROWS_SAMPLE_EXTENSION,
            "failedRowsSample",
            JsonUtils.pojoToJson(tableData));
    setFieldsInternal(testCase, Fields.EMPTY_FIELDS);
    // deep copy the test case to avoid updating the cached entity
    testCase = JsonUtils.deepCopy(testCase, TestCase.class);
    return testCase.withFailedRowsSample(tableData);
  }

  @Transaction
  public TestCase addInspectionQuery(UriInfo uri, UUID testCaseId, String sql) {
    TestCase original = get(uri, testCaseId, getFields("*"));
    TestCase updated =
        JsonUtils.readValue(JsonUtils.pojoToJson(original), TestCase.class)
            .withInspectionQuery(sql);
    EntityUpdater entityUpdater = getUpdater(original, updated, Operation.PATCH);
    entityUpdater.update();
    return updated;
  }

  @Transaction
  public RestUtil.DeleteResponse<TableData> deleteTestCaseFailedRowsSample(UUID id) {
    daoCollection.entityExtensionDAO().delete(id, FAILED_ROWS_SAMPLE_EXTENSION);
    return new RestUtil.DeleteResponse<>(null, ENTITY_DELETED);
  }

  public static class TestCaseFailureResolutionTaskWorkflow extends FeedRepository.TaskWorkflow {
    final TestCaseResolutionStatusRepository testCaseResolutionStatusRepository;
    final CollectionDAO.DataQualityDataTimeSeriesDAO dataQualityDataTimeSeriesDao;

    TestCaseFailureResolutionTaskWorkflow(FeedRepository.ThreadContext threadContext) {
      super(threadContext);
      this.testCaseResolutionStatusRepository =
          (TestCaseResolutionStatusRepository)
              Entity.getEntityTimeSeriesRepository(Entity.TEST_CASE_RESOLUTION_STATUS);

      this.dataQualityDataTimeSeriesDao = Entity.getCollectionDAO().dataQualityDataTimeSeriesDao();
    }

    /**
     * If the task is resolved, we'll resolve the Incident with the given reason
     */
    @Override
    @Transaction
    public TestCase performTask(String userName, ResolveTask resolveTask) {

      // We need to get the latest test case resolution status to get the state id
      TestCaseResolutionStatus latestTestCaseResolutionStatus =
          testCaseResolutionStatusRepository.getLatestRecord(resolveTask.getTestCaseFQN());

      if (latestTestCaseResolutionStatus == null) {
        throw new EntityNotFoundException(
            String.format(
                "Failed to find test case resolution status for %s", resolveTask.getTestCaseFQN()));
      }
      User user = getEntityByName(Entity.USER, userName, "", Include.ALL);
      TestCaseResolutionStatus testCaseResolutionStatus =
          new TestCaseResolutionStatus()
              .withId(UUID.randomUUID())
              .withStateId(latestTestCaseResolutionStatus.getStateId())
              .withTimestamp(System.currentTimeMillis())
              .withTestCaseResolutionStatusType(TestCaseResolutionStatusTypes.Resolved)
              .withTestCaseResolutionStatusDetails(
                  new Resolved()
                      .withTestCaseFailureComment(resolveTask.getNewValue())
                      .withTestCaseFailureReason(resolveTask.getTestCaseFailureReason())
                      .withResolvedBy(user.getEntityReference()))
              .withUpdatedAt(System.currentTimeMillis())
              .withTestCaseReference(latestTestCaseResolutionStatus.getTestCaseReference())
              .withUpdatedBy(user.getEntityReference());

      EntityReference testCaseReference = testCaseResolutionStatus.getTestCaseReference();
      testCaseResolutionStatus.setTestCaseReference(null);
      Entity.getCollectionDAO()
          .testCaseResolutionStatusTimeSeriesDao()
          .insert(
              testCaseReference.getFullyQualifiedName(),
              Entity.TEST_CASE_RESOLUTION_STATUS,
              JsonUtils.pojoToJson(testCaseResolutionStatus));
      testCaseResolutionStatus.setTestCaseReference(testCaseReference);
      testCaseResolutionStatusRepository.storeRelationship(testCaseResolutionStatus);
      testCaseResolutionStatusRepository.postCreate(testCaseResolutionStatus);

      // Return the TestCase with the StateId to avoid any unnecessary PATCH when resolving the task
      // in the feed repo,
      // since the `threadContext.getAboutEntity()` will give us the task with the `incidentId`
      // informed, which
      // we'll remove here.
      TestCase testCaseEntity =
          Entity.getEntity(testCaseResolutionStatus.getTestCaseReference(), "", Include.ALL);
      return testCaseEntity.withIncidentId(latestTestCaseResolutionStatus.getStateId());
    }

    /**
     * If we close the task, we'll flag the incident as Resolved as a False Positive, if it is not
     * resolved yet. Closing the task means that the incident is not applicable.
     */
    @Override
    @Transaction
    public void closeTask(String userName, CloseTask closeTask) {
      TestCaseResolutionStatus latestTestCaseResolutionStatus =
          testCaseResolutionStatusRepository.getLatestRecord(closeTask.getTestCaseFQN());
      if (latestTestCaseResolutionStatus == null) {
        return;
      }

      if (latestTestCaseResolutionStatus
          .getTestCaseResolutionStatusType()
          .equals(TestCaseResolutionStatusTypes.Resolved)) {
        // if the test case is already resolved then we'll return. We don't need to update the state
        return;
      }

      User user = getEntityByName(Entity.USER, userName, "", Include.ALL);
      TestCaseResolutionStatus testCaseResolutionStatus =
          new TestCaseResolutionStatus()
              .withId(UUID.randomUUID())
              .withStateId(latestTestCaseResolutionStatus.getStateId())
              .withTimestamp(System.currentTimeMillis())
              .withTestCaseResolutionStatusType(TestCaseResolutionStatusTypes.Resolved)
              .withTestCaseResolutionStatusDetails(
                  new Resolved()
                      .withTestCaseFailureComment(closeTask.getComment())
                      // If we close the task directly we won't know the reason
                      .withTestCaseFailureReason(TestCaseFailureReasonType.FalsePositive)
                      .withResolvedBy(user.getEntityReference()))
              .withUpdatedAt(System.currentTimeMillis())
              .withTestCaseReference(latestTestCaseResolutionStatus.getTestCaseReference())
              .withUpdatedBy(user.getEntityReference());

      EntityReference testCaseReference = testCaseResolutionStatus.getTestCaseReference();
      testCaseResolutionStatus.setTestCaseReference(null);
      Entity.getCollectionDAO()
          .testCaseResolutionStatusTimeSeriesDao()
          .insert(
              testCaseReference.getFullyQualifiedName(),
              Entity.TEST_CASE_RESOLUTION_STATUS,
              JsonUtils.pojoToJson(testCaseResolutionStatus));
      testCaseResolutionStatus.setTestCaseReference(testCaseReference);
      testCaseResolutionStatusRepository.storeRelationship(testCaseResolutionStatus);
      testCaseResolutionStatusRepository.postCreate(testCaseResolutionStatus);
    }
  }

  public class TestUpdater extends EntityUpdater {
    public TestUpdater(TestCase original, TestCase updated, Operation operation) {
      super(original, updated, operation);
    }

    @Transaction
    @Override
    public void entitySpecificUpdate() {
      EntityLink origEntityLink = EntityLink.parse(original.getEntityLink());
      EntityReference origTableRef = EntityUtil.validateEntityLink(origEntityLink);

      EntityLink updatedEntityLink = EntityLink.parse(updated.getEntityLink());
      EntityReference updatedTableRef = EntityUtil.validateEntityLink(updatedEntityLink);

      updateFromRelationship(
          "entity",
          updatedTableRef.getType(),
          origTableRef,
          updatedTableRef,
          Relationship.CONTAINS,
          TEST_CASE,
          updated.getId());
      updateFromRelationship(
          TEST_SUITE_FIELD,
          TEST_SUITE,
          original.getTestSuite(),
          updated.getTestSuite(),
          Relationship.HAS,
          TEST_CASE,
          updated.getId());
      updateFromRelationship(
          TEST_DEFINITION,
          TEST_DEFINITION,
          original.getTestDefinition(),
          updated.getTestDefinition(),
          Relationship.CONTAINS,
          TEST_CASE,
          updated.getId());
      recordChange("parameterValues", original.getParameterValues(), updated.getParameterValues());
      recordChange("inspectionQuery", original.getInspectionQuery(), updated.getInspectionQuery());
      recordChange(
          "computePassedFailedRowCount",
          original.getComputePassedFailedRowCount(),
          updated.getComputePassedFailedRowCount());
      recordChange(
          "useDynamicAssertion",
          original.getUseDynamicAssertion(),
          updated.getUseDynamicAssertion());
      recordChange("testCaseStatus", original.getTestCaseStatus(), updated.getTestCaseStatus());
    }
  }

  public TableData getSampleData(TestCase testCase, boolean authorizePII) {
    Table table = Entity.getEntity(EntityLink.parse(testCase.getEntityLink()), FIELD_OWNERS, ALL);
    // Validate the request content
    TableData sampleData =
        JsonUtils.readValue(
            daoCollection
                .entityExtensionDAO()
                .getExtension(testCase.getId(), FAILED_ROWS_SAMPLE_EXTENSION),
            TableData.class);
    if (sampleData == null) {
      throw new EntityNotFoundException(
          entityNotFound(FAILED_ROWS_SAMPLE_EXTENSION, testCase.getId()));
    }
    // Set the column tags. Will be used to mask the sample data
    if (!authorizePII) {
      populateEntityFieldTags(
          Entity.TABLE, table.getColumns(), table.getFullyQualifiedName(), true);
      List<TagLabel> tags = daoCollection.tagUsageDAO().getTags(table.getFullyQualifiedName());
      table.setTags(tags);
      return maskSampleData(sampleData, table, table.getColumns());
    }
    return sampleData;
  }

  private void validateParameterRule(TestCaseParameter parameter, Map<String, Object> values) {
    if (parameter.getValidationRule() != null) {
      TestCaseParameterValidationRule testCaseParameterValidationRule =
          parameter.getValidationRule();
      String parameterFieldToValidateAgainst =
          testCaseParameterValidationRule.getParameterField(); // parameter name to validate against
      Object valueToValidateAgainst =
          values.get(parameterFieldToValidateAgainst); // value to validate against
      Object valueToValidate = values.get(parameter.getName()); // value to validate

      if (valueToValidateAgainst != null && valueToValidate != null) {
        // we only validate if the value to validate are not null
        compareValue(
            valueToValidate.toString(),
            valueToValidateAgainst.toString(),
            testCaseParameterValidationRule.getRule());
      }
    }
  }

  private void compareValue(
      String valueToValidate,
      String valueToValidateAgainst,
      TestCaseParameterValidationRuleType validationRule) {
    Double valueToValidateDouble = parseStringToDouble(valueToValidate);
    Double valueToValidateAgainstDouble = parseStringToDouble(valueToValidateAgainst);
    if (valueToValidateDouble != null && valueToValidateAgainstDouble != null) {
      compareAndValidateParameterRule(
          validationRule, valueToValidateDouble, valueToValidateAgainstDouble);
    } else {
      LOG.warn(
          "One of the 2 values to compare is not a number. Cannot compare values {} and {}. Skipping parameter validation",
          valueToValidate,
          valueToValidateAgainst);
    }
  }

  private Double parseStringToDouble(String value) {
    try {
      return Double.parseDouble(value);
    } catch (NumberFormatException e) {
      LOG.warn("Failed to parse value {} to double", value, e);
      return null;
    }
  }

  private void compareAndValidateParameterRule(
      TestCaseParameterValidationRuleType validationRule,
      Double valueToValidate,
      Double valueToValidateAgainst) {
    String message = "Value %s %s %s";
    switch (validationRule) {
      case GREATER_THAN_OR_EQUALS -> {
        if (valueToValidate < valueToValidateAgainst) {
          throw new IllegalArgumentException(
              String.format(
                  message, valueToValidate, " is not greater than ", valueToValidateAgainst));
        }
      }
      case LESS_THAN_OR_EQUALS -> {
        if (valueToValidate > valueToValidateAgainst) {
          throw new IllegalArgumentException(
              String.format(
                  message, valueToValidate, " is not less than ", valueToValidateAgainst));
        }
      }
      case EQUALS -> {
        // we'll compare the values with a tolerance of 0.0001 as we are dealing with double values
        if (Math.abs(valueToValidate - valueToValidateAgainst) > 0.0001) {
          throw new IllegalArgumentException(
              String.format(message, valueToValidate, " is not equal to ", valueToValidateAgainst));
        }
      }
      case NOT_EQUALS -> {
        // we'll compare the values with a tolerance of 0.0001 as we are dealing with double values
        if ((Math.abs(valueToValidate - valueToValidateAgainst) < 0.0001)) {
          throw new IllegalArgumentException(
              String.format(message, valueToValidate, " is equal to ", valueToValidateAgainst));
        }
      }
    }
  }
}
