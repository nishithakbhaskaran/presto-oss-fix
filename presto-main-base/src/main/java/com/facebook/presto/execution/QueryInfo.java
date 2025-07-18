/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.execution;

import com.facebook.presto.SessionRepresentation;
import com.facebook.presto.common.ErrorCode;
import com.facebook.presto.common.ErrorType;
import com.facebook.presto.common.resourceGroups.QueryType;
import com.facebook.presto.common.transaction.TransactionId;
import com.facebook.presto.cost.StatsAndCosts;
import com.facebook.presto.spi.PrestoWarning;
import com.facebook.presto.spi.QueryId;
import com.facebook.presto.spi.analyzer.UpdateInfo;
import com.facebook.presto.spi.eventlistener.CTEInformation;
import com.facebook.presto.spi.eventlistener.PlanOptimizerInformation;
import com.facebook.presto.spi.function.SqlFunctionId;
import com.facebook.presto.spi.function.SqlInvokedFunction;
import com.facebook.presto.spi.memory.MemoryPoolId;
import com.facebook.presto.spi.plan.PlanNode;
import com.facebook.presto.spi.plan.PlanNodeId;
import com.facebook.presto.spi.prestospark.PrestoSparkExecutionContext;
import com.facebook.presto.spi.resourceGroups.ResourceGroupId;
import com.facebook.presto.spi.security.SelectedRole;
import com.facebook.presto.sql.planner.CanonicalPlanWithInfo;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.facebook.presto.util.QueryInfoUtils.computeQueryHash;
import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

@Immutable
public class QueryInfo
{
    private final QueryId queryId;
    private final SessionRepresentation session;
    private final QueryState state;
    private final MemoryPoolId memoryPool;
    private final boolean scheduled;
    private final URI self;
    private final List<String> fieldNames;
    private final String query;
    private final String queryHash;
    // expand the original query to a more accurate one if the data flow indicated by the original query is too obscure.
    private final Optional<String> expandedQuery;
    private final Optional<String> preparedQuery;
    private final QueryStats queryStats;
    private final Optional<String> setCatalog;
    private final Optional<String> setSchema;
    private final Map<String, String> setSessionProperties;
    private final Set<String> resetSessionProperties;
    private final Map<String, SelectedRole> setRoles;
    private final Map<String, String> addedPreparedStatements;
    private final Set<String> deallocatedPreparedStatements;
    private final Optional<TransactionId> startedTransactionId;
    private final boolean clearTransactionId;
    private final UpdateInfo updateInfo;
    private final Optional<StageInfo> outputStage;
    private final ExecutionFailureInfo failureInfo;
    private final ErrorType errorType;
    private final ErrorCode errorCode;
    private final List<PrestoWarning> warnings;
    private final Set<Input> inputs;
    private final Optional<Output> output;
    private final boolean finalQueryInfo;
    private final Optional<ResourceGroupId> resourceGroupId;
    private final Optional<QueryType> queryType;
    // failedTasks is only available for final query info because the construction is expensive.
    private final Optional<List<TaskId>> failedTasks;
    // RuntimeOptimizedStages is only available for final query info, because it is appended during runtime.
    private final Optional<List<StageId>> runtimeOptimizedStages;
    private final Map<SqlFunctionId, SqlInvokedFunction> addedSessionFunctions;
    private final Set<SqlFunctionId> removedSessionFunctions;
    private final StatsAndCosts planStatsAndCosts;
    private final List<PlanOptimizerInformation> optimizerInformation;
    private final List<CTEInformation> cteInformationList;
    private final Set<String> scalarFunctions;
    private final Set<String> aggregateFunctions;
    private final Set<String> windowFunctions;
    // Using a list rather than map, to avoid implementing map key deserializer
    private final List<CanonicalPlanWithInfo> planCanonicalInfo;
    private Map<PlanNodeId, PlanNode> planIdNodeMap;
    private final Optional<PrestoSparkExecutionContext> prestoSparkExecutionContext;

    @JsonCreator
    public QueryInfo(
            @JsonProperty("queryId") QueryId queryId,
            @JsonProperty("session") SessionRepresentation session,
            @JsonProperty("state") QueryState state,
            @JsonProperty("memoryPool") MemoryPoolId memoryPool,
            @JsonProperty("scheduled") boolean scheduled,
            @JsonProperty("self") URI self,
            @JsonProperty("fieldNames") List<String> fieldNames,
            @JsonProperty("query") String query,
            @JsonProperty("expandedQuery") Optional<String> expandedQuery,
            @JsonProperty("preparedQuery") Optional<String> preparedQuery,
            @JsonProperty("queryStats") QueryStats queryStats,
            @JsonProperty("setCatalog") Optional<String> setCatalog,
            @JsonProperty("setSchema") Optional<String> setSchema,
            @JsonProperty("setSessionProperties") Map<String, String> setSessionProperties,
            @JsonProperty("resetSessionProperties") Set<String> resetSessionProperties,
            @JsonProperty("setRoles") Map<String, SelectedRole> setRoles,
            @JsonProperty("addedPreparedStatements") Map<String, String> addedPreparedStatements,
            @JsonProperty("deallocatedPreparedStatements") Set<String> deallocatedPreparedStatements,
            @JsonProperty("startedTransactionId") Optional<TransactionId> startedTransactionId,
            @JsonProperty("clearTransactionId") boolean clearTransactionId,
            @JsonProperty("updateInfo") UpdateInfo updateInfo,
            @JsonProperty("outputStage") Optional<StageInfo> outputStage,
            @JsonProperty("failureInfo") ExecutionFailureInfo failureInfo,
            @JsonProperty("errorCode") ErrorCode errorCode,
            @JsonProperty("warnings") List<PrestoWarning> warnings,
            @JsonProperty("inputs") Set<Input> inputs,
            @JsonProperty("output") Optional<Output> output,
            @JsonProperty("finalQueryInfo") boolean finalQueryInfo,
            @JsonProperty("resourceGroupId") Optional<ResourceGroupId> resourceGroupId,
            @JsonProperty("queryType") Optional<QueryType> queryType,
            @JsonProperty("failedTasks") Optional<List<TaskId>> failedTasks,
            @JsonProperty("runtimeOptimizedStages") Optional<List<StageId>> runtimeOptimizedStages,
            @JsonProperty("addedSessionFunctions") Map<SqlFunctionId, SqlInvokedFunction> addedSessionFunctions,
            @JsonProperty("removedSessionFunctions") Set<SqlFunctionId> removedSessionFunctions,
            @JsonProperty("planStatsAndCosts") StatsAndCosts planStatsAndCosts,
            @JsonProperty("optimizerInformation") List<PlanOptimizerInformation> optimizerInformation,
            @JsonProperty("cteInformation") List<CTEInformation> cteInformationList,
            @JsonProperty("scalarFunctions") Set<String> scalarFunctions,
            @JsonProperty("aggregateFunctions") Set<String> aggregateFunctions,
            @JsonProperty("windowFunctions") Set<String> windowFunctions,
            List<CanonicalPlanWithInfo> planCanonicalInfo,
            Map<PlanNodeId, PlanNode> planIdNodeMap,
            @JsonProperty("prestoSparkExecutionContext") Optional<PrestoSparkExecutionContext> prestoSparkExecutionContext)
    {
        requireNonNull(queryId, "queryId is null");
        requireNonNull(session, "session is null");
        requireNonNull(state, "state is null");
        requireNonNull(self, "self is null");
        requireNonNull(fieldNames, "fieldNames is null");
        requireNonNull(queryStats, "queryStats is null");
        requireNonNull(setCatalog, "setCatalog is null");
        requireNonNull(setSchema, "setSchema is null");
        requireNonNull(setSessionProperties, "setSessionProperties is null");
        requireNonNull(resetSessionProperties, "resetSessionProperties is null");
        requireNonNull(addedPreparedStatements, "addedPreparedStatements is null");
        requireNonNull(deallocatedPreparedStatements, "deallocatedPreparedStatements is null");
        requireNonNull(startedTransactionId, "startedTransactionId is null");
        requireNonNull(query, "query is null");
        requireNonNull(expandedQuery, "expandedQuery is null");
        requireNonNull(preparedQuery, "preparedQuery is null");
        requireNonNull(outputStage, "outputStage is null");
        requireNonNull(inputs, "inputs is null");
        requireNonNull(output, "output is null");
        requireNonNull(resourceGroupId, "resourceGroupId is null");
        requireNonNull(warnings, "warnings is null");
        requireNonNull(queryType, "queryType is null");
        requireNonNull(failedTasks, "failedTasks is null");
        requireNonNull(runtimeOptimizedStages, "runtimeOptimizedStages is null");
        requireNonNull(addedSessionFunctions, "addedSessionFunctions is null");
        requireNonNull(removedSessionFunctions, "removedSessionFunctions is null");
        requireNonNull(planStatsAndCosts, "planStatsAndCosts is null");
        requireNonNull(optimizerInformation, "optimizerInformation is null");
        requireNonNull(cteInformationList, "cteInformationList is null");
        requireNonNull(scalarFunctions, "scalarFunctions is null");
        requireNonNull(aggregateFunctions, "aggregateFunctions is null");
        requireNonNull(windowFunctions, "windowFunctions is null");
        requireNonNull(prestoSparkExecutionContext, "prestoSparkExecutionContext is null");

        this.queryId = queryId;
        this.session = session;
        this.state = state;
        this.memoryPool = requireNonNull(memoryPool, "memoryPool is null");
        this.scheduled = scheduled;
        this.self = self;
        this.fieldNames = ImmutableList.copyOf(fieldNames);
        this.query = query;
        this.queryHash = computeQueryHash(query);
        this.expandedQuery = expandedQuery;
        this.preparedQuery = preparedQuery;
        this.queryStats = queryStats;
        this.setCatalog = setCatalog;
        this.setSchema = setSchema;
        this.setSessionProperties = ImmutableMap.copyOf(setSessionProperties);
        this.resetSessionProperties = ImmutableSet.copyOf(resetSessionProperties);
        this.setRoles = ImmutableMap.copyOf(setRoles);
        this.addedPreparedStatements = ImmutableMap.copyOf(addedPreparedStatements);
        this.deallocatedPreparedStatements = ImmutableSet.copyOf(deallocatedPreparedStatements);
        this.startedTransactionId = startedTransactionId;
        this.clearTransactionId = clearTransactionId;
        this.updateInfo = updateInfo;
        this.outputStage = outputStage;
        this.failureInfo = failureInfo;
        this.errorType = errorCode == null ? null : errorCode.getType();
        this.errorCode = errorCode;
        this.warnings = ImmutableList.copyOf(warnings);
        this.inputs = ImmutableSet.copyOf(inputs);
        this.output = output;
        this.finalQueryInfo = finalQueryInfo;
        if (finalQueryInfo) {
            checkArgument(state.isDone(), "finalQueryInfo without a terminal query state: %s", state);
        }
        this.resourceGroupId = resourceGroupId;
        this.queryType = queryType;
        this.failedTasks = failedTasks;
        this.runtimeOptimizedStages = runtimeOptimizedStages;
        this.addedSessionFunctions = ImmutableMap.copyOf(addedSessionFunctions);
        this.removedSessionFunctions = ImmutableSet.copyOf(removedSessionFunctions);
        this.planStatsAndCosts = planStatsAndCosts;
        this.optimizerInformation = optimizerInformation;
        this.cteInformationList = cteInformationList;
        this.scalarFunctions = scalarFunctions;
        this.aggregateFunctions = aggregateFunctions;
        this.windowFunctions = windowFunctions;
        this.planCanonicalInfo = planCanonicalInfo == null ? ImmutableList.of() : planCanonicalInfo;
        this.planIdNodeMap = planIdNodeMap == null ? ImmutableMap.of() : ImmutableMap.copyOf(planIdNodeMap);
        this.prestoSparkExecutionContext = prestoSparkExecutionContext;
    }

    @JsonProperty
    public QueryId getQueryId()
    {
        return queryId;
    }

    @JsonProperty
    public SessionRepresentation getSession()
    {
        return session;
    }

    @JsonProperty
    public QueryState getState()
    {
        return state;
    }

    @JsonProperty
    public MemoryPoolId getMemoryPool()
    {
        return memoryPool;
    }

    @JsonProperty
    public boolean isScheduled()
    {
        return scheduled;
    }

    @JsonProperty
    public URI getSelf()
    {
        return self;
    }

    @JsonProperty
    public List<String> getFieldNames()
    {
        return fieldNames;
    }

    @JsonProperty
    public String getQuery()
    {
        return query;
    }

    @JsonProperty
    public String getQueryHash()
    {
        return queryHash;
    }

    @JsonProperty
    public Optional<String> getExpandedQuery()
    {
        return expandedQuery;
    }

    @JsonProperty
    public Optional<String> getPreparedQuery()
    {
        return preparedQuery;
    }

    @JsonProperty
    public QueryStats getQueryStats()
    {
        return queryStats;
    }

    @JsonProperty
    public Optional<String> getSetCatalog()
    {
        return setCatalog;
    }

    @JsonProperty
    public Optional<String> getSetSchema()
    {
        return setSchema;
    }

    @JsonProperty
    public Map<String, String> getSetSessionProperties()
    {
        return setSessionProperties;
    }

    @JsonProperty
    public Set<String> getResetSessionProperties()
    {
        return resetSessionProperties;
    }

    @JsonProperty
    public Map<String, SelectedRole> getSetRoles()
    {
        return setRoles;
    }

    @JsonProperty
    public Map<String, String> getAddedPreparedStatements()
    {
        return addedPreparedStatements;
    }

    @JsonProperty
    public Set<String> getDeallocatedPreparedStatements()
    {
        return deallocatedPreparedStatements;
    }

    @JsonProperty
    public Optional<TransactionId> getStartedTransactionId()
    {
        return startedTransactionId;
    }

    @JsonProperty
    public boolean isClearTransactionId()
    {
        return clearTransactionId;
    }

    @Nullable
    @JsonProperty
    public UpdateInfo getUpdateInfo()
    {
        return updateInfo;
    }

    @JsonProperty
    public Optional<StageInfo> getOutputStage()
    {
        return outputStage;
    }

    @Nullable
    @JsonProperty
    public ExecutionFailureInfo getFailureInfo()
    {
        return failureInfo;
    }

    @Nullable
    @JsonProperty
    public ErrorType getErrorType()
    {
        return errorType;
    }

    @Nullable
    @JsonProperty
    public ErrorCode getErrorCode()
    {
        return errorCode;
    }

    @JsonProperty
    public List<PrestoWarning> getWarnings()
    {
        return warnings;
    }

    @JsonProperty
    public boolean isFinalQueryInfo()
    {
        return finalQueryInfo;
    }

    @JsonProperty
    public Set<Input> getInputs()
    {
        return inputs;
    }

    @JsonProperty
    public Optional<Output> getOutput()
    {
        return output;
    }

    @JsonProperty
    public Optional<ResourceGroupId> getResourceGroupId()
    {
        return resourceGroupId;
    }

    @JsonProperty
    public Optional<QueryType> getQueryType()
    {
        return queryType;
    }

    @JsonProperty
    public Optional<List<TaskId>> getFailedTasks()
    {
        return failedTasks;
    }

    @JsonProperty
    public Optional<List<StageId>> getRuntimeOptimizedStages()
    {
        return runtimeOptimizedStages;
    }

    @JsonProperty
    public Map<SqlFunctionId, SqlInvokedFunction> getAddedSessionFunctions()
    {
        return addedSessionFunctions;
    }

    @JsonProperty
    public Set<SqlFunctionId> getRemovedSessionFunctions()
    {
        return removedSessionFunctions;
    }

    @JsonProperty
    public StatsAndCosts getPlanStatsAndCosts()
    {
        return planStatsAndCosts;
    }

    @JsonProperty
    public List<PlanOptimizerInformation> getOptimizerInformation()
    {
        return optimizerInformation;
    }

    @JsonProperty
    public List<CTEInformation> getCteInformationList()
    {
        return cteInformationList;
    }

    @JsonProperty
    public Set<String> getScalarFunctions()
    {
        return scalarFunctions;
    }

    @JsonProperty
    public Set<String> getAggregateFunctions()
    {
        return aggregateFunctions;
    }

    @JsonProperty
    public Set<String> getWindowFunctions()
    {
        return windowFunctions;
    }

    @JsonProperty
    public Optional<PrestoSparkExecutionContext> getPrestoSparkExecutionContext()
    {
        return prestoSparkExecutionContext;
    }

    // Don't serialize this field because it can be big
    public List<CanonicalPlanWithInfo> getPlanCanonicalInfo()
    {
        return planCanonicalInfo;
    }

    public Map<PlanNodeId, PlanNode> getPlanIdNodeMap()
    {
        return planIdNodeMap;
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("queryId", queryId)
                .add("state", state)
                .add("fieldNames", fieldNames)
                .toString();
    }
}
