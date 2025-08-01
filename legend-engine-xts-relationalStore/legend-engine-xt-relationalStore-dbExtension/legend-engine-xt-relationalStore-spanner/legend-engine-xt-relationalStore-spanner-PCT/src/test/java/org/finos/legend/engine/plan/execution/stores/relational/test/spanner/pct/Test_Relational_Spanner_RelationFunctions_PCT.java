// Copyright 2025 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.finos.legend.engine.plan.execution.stores.relational.test.spanner.pct;

import junit.framework.Test;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.engine.plan.execution.stores.relational.connection.tests.api.TestConnectionIntegrationLoader;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.relational.connection.DatabaseType;
import org.finos.legend.engine.pure.runtime.testConnection.CoreExternalTestConnectionCodeRepositoryProvider;
import org.finos.legend.engine.test.shared.framework.TestServerResource;
import org.finos.legend.pure.code.core.RelationCodeRepositoryProvider;
import org.finos.legend.pure.m3.pct.reports.config.PCTReportConfiguration;
import org.finos.legend.pure.m3.pct.reports.config.exclusion.AdapterQualifier;
import org.finos.legend.pure.m3.pct.reports.config.exclusion.ExclusionSpecification;
import org.finos.legend.pure.m3.pct.reports.model.Adapter;
import org.finos.legend.pure.m3.pct.shared.model.ReportScope;
import org.finos.legend.pure.runtime.java.compiled.testHelper.PureTestBuilderCompiled;

import static org.finos.legend.engine.test.shared.framework.PureTestHelperFramework.wrapSuite;


public class Test_Relational_Spanner_RelationFunctions_PCT extends PCTReportConfiguration
{
    private static final ReportScope reportScope = RelationCodeRepositoryProvider.relationFunctions;
    private static final Adapter adapter = CoreExternalTestConnectionCodeRepositoryProvider.spannerAdapter;
    private static final String platform = "compiled";
    private static final MutableList<ExclusionSpecification> expectedFailures = Lists.mutable.with(
            // ListAgg
            one("meta::pure::functions::relation::tests::extend::testOLAPAggStringWithPartitionAndOrderASCUnboundedWindow_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::extend::testOLAPAggStringWithPartitionAndOrderUnboundedWindow_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::extend::testOLAPAggStringWithPartitionAndOrderUnboundedWindow_MultipleExpressions_Function_1__Boolean_1_", "\"Common table expression not supported on DB Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::extend::testOLAPAggStringWithPartitionAndUnboundedWindow_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::extend::testOLAPAggWithPartitionAndOrderUnboundedWindowMultipleColumns_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::extend::testOLAPAggWithPartitionAndOrderUnboundedWindowMultipleColumns_MultipleExpressions_Function_1__Boolean_1_", "\"Common table expression not supported on DB Spanner\""),

            //asOfJoin
            one("meta::pure::functions::relation::tests::asOfJoin::testAsOfJoinWithKeyMatch_Function_1__Boolean_1_", "\"AsOfJoins are not supported in the generic generator!\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::asOfJoin::testAsOfJoinWithKeyMatch_MultipleExpressions_Function_1__Boolean_1_", "\"Common table expression not supported on DB Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::asOfJoin::testSimpleAsOfJoin_Function_1__Boolean_1_", "\"AsOfJoins are not supported in the generic generator!\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::asOfJoin::testSimpleAsOfJoin_MultipleExpressions_Function_1__Boolean_1_", "\"Common table expression not supported on DB Spanner\"", AdapterQualifier.unsupportedFeature),

            //composition
            one("meta::pure::functions::relation::tests::composition::testExtendFilter_Function_1__Boolean_1_", "\"[unsupported-api] The function 'toString' (state: [Where, false]) is not supported yet\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::composition::testWindowFunctionsAfterProject_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::composition::test_Distinct_GroupBy_Filter_Function_1__Boolean_1_", "Error while executing: insert into", AdapterQualifier.needsInvestigation),
            one("meta::pure::functions::relation::tests::composition::test_Extend_Filter_Select_ComplexGroupBy_Pivot_Function_1__Boolean_1_", "\"pivot is not supported\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::composition::test_Extend_Filter_Select_GroupBy_Pivot_Extend_Sort_Limit_Function_1__Boolean_1_", "\"pivot is not supported\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::composition::test_Extend_Filter_Select_Pivot_GroupBy_Extend_Sort_Function_1__Boolean_1_", "\"pivot is not supported\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::composition::test_GroupBy_Distinct_Filter_Function_1__Boolean_1_", "Error while executing: insert into", AdapterQualifier.needsInvestigation),
            one("meta::pure::functions::relation::tests::composition::test_Pivot_Filter_Function_1__Boolean_1_", "\"pivot is not supported\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::composition::testOLAPAggCastWithPartitionWindow_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::composition::testOLAPCastAggWithPartitionWindow_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::composition::testOLAPCastExtractAggWithPartitionWindow_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::composition::testOLAPCastExtractCastAggWithPartitionWindow_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::composition::testGroupByFilterExtendFilter_Function_1__Boolean_1_", "\"QUALIFY grammar is not supported\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::composition::testExtendWindowFilter_Function_1__Boolean_1_", "\"QUALIFY grammar is not supported\"", AdapterQualifier.unsupportedFeature),

            //concatenate
            one("meta::pure::functions::relation::tests::concatenate::testSimpleConcatenate_MultipleExpressions_Function_1__Boolean_1_", "\"Common table expression not supported on DB Spanner\"", AdapterQualifier.unsupportedFeature),

            //cumulativeDistribution
            one("meta::pure::functions::relation::tests::cumulativeDistribution::testOLAPWithPartitionAndOrderCummulativeDistribution_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),

            //denseRank
            one("meta::pure::functions::relation::tests::denseRank::testOLAPWithPartitionAndOrderDenseRank_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),

            //distinct
            one("meta::pure::functions::relation::tests::distinct::testDistinctAll_Function_1__Boolean_1_", "Error while executing: insert into", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::distinct::testDistinctAll_MultipleExpressions_Function_1__Boolean_1_", "\"Common table expression not supported on DB Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::distinct::testDistinctMultiple_Function_1__Boolean_1_", "Error while executing: insert into", AdapterQualifier.needsInvestigation),
            one("meta::pure::functions::relation::tests::distinct::testDistinctMultiple_MultipleExpressions_Function_1__Boolean_1_", "\"Common table expression not supported on DB Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::distinct::testDistinctSingle_Function_1__Boolean_1_", "Error while executing: insert into", AdapterQualifier.needsInvestigation),
            one("meta::pure::functions::relation::tests::distinct::testDistinctSingle_MultipleExpressions_Function_1__Boolean_1_", "\"Common table expression not supported on DB Spanner\"", AdapterQualifier.unsupportedFeature),

            //drop
            one("meta::pure::functions::relation::tests::drop::testSimpleDrop_MultipleExpressions_Function_1__Boolean_1_", "\"Common table expression not supported on DB Spanner\"", AdapterQualifier.unsupportedFeature),

            //extend
            one("meta::pure::functions::relation::tests::extend::testOLAPAggIntegerWithPartitionAndOrderWindow_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::extend::testOLAPAggIntegerWithPartitionAndOrderWindow_MultipleExpressions_Function_1__Boolean_1_", "\"Common table expression not supported on DB Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::extend::testOLAPAggNoWindowChainedWithSimple_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::extend::testOLAPAggNoWindowChainedWithSimple_MultipleExpressions_Function_1__Boolean_1_", "\"Common table expression not supported on DB Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::extend::testOLAPAggNoWindowMultipleColums_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::extend::testOLAPAggNoWindowMultipleColums_MultipleExpressions_Function_1__Boolean_1_", "\"Common table expression not supported on DB Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::extend::testOLAPAggNoWindow_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::extend::testOLAPAggNoWindow_MultipleExpressions_Function_1__Boolean_1_", "\"Common table expression not supported on DB Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::extend::testOLAPAggStringWithPartitionAndOrderWindow_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::extend::testOLAPAggStringWithPartitionAndOrderWindow_MultipleExpressions_Function_1__Boolean_1_", "\"Common table expression not supported on DB Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::extend::testOLAPAggWithOrderWindow_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::extend::testOLAPAggWithOrderWindow_MultipleExpressions_Function_1__Boolean_1_", "\"Common table expression not supported on DB Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::extend::testOLAPAggWithPartitionAndOrderWindowMultipleColumns_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::extend::testOLAPAggWithPartitionAndOrderWindowMultipleColumns_MultipleExpressions_Function_1__Boolean_1_", "\"Common table expression not supported on DB Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::extend::testOLAPAggWithPartitionWindowMultipleColumns_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::extend::testOLAPAggWithPartitionWindowMultipleColumns_MultipleExpressions_Function_1__Boolean_1_", "\"Common table expression not supported on DB Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::extend::testOLAPAggWithPartitionWindow_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::extend::testOLAPWithMultiplePartitionsAndOrderWindowMultipleColumns_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::extend::testOLAPWithMultiplePartitionsAndOrderWindowMultipleColumns_MultipleExpressions_Function_1__Boolean_1_", "\"Common table expression not supported on DB Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::extend::testOLAPWithPartitionAndMultipleOrderWindowMultipleColumnsWithFilter_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::extend::testOLAPWithPartitionAndMultipleOrderWindowMultipleColumns_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::extend::testOLAPWithPartitionAndOrderWindowMultipleColumns_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::extend::testOLAPWithPartitionAndOrderWindowMultipleColumns_MultipleExpressions_Function_1__Boolean_1_", "\"Common table expression not supported on DB Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::extend::testSimpleExtendFloat_MultipleExpressions_Function_1__Boolean_1_", "\"Common table expression not supported on DB Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::extend::testSimpleExtendInt_MultipleExpressions_Function_1__Boolean_1_", "\"Common table expression not supported on DB Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::extend::testSimpleExtendStrShared_Function_1__Boolean_1_", "\"[unsupported-api] The function 'toString' (state: [Select, false]) is not supported yet\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::extend::testSimpleExtendStr_MultipleExpressions_Function_1__Boolean_1_", "\"Common table expression not supported on DB Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::extend::testSimpleMultipleColumns_Function_1__Boolean_1_", "org.finos.legend.engine.spanner.jdbc.shaded.com.google.cloud.spanner.jdbc.JdbcSqlExceptionFactory$JdbcSqlExceptionImpl: INVALID_ARGUMENT: org.finos.legend.engine.spanner.jdbc.shaded.io.grpc.StatusRuntimeException: INVALID_ARGUMENT: Unable to resolve argument type. Please consider adding an explicit cast", AdapterQualifier.needsInvestigation),
            one("meta::pure::functions::relation::tests::extend::testSimpleMultipleColumns_MultipleExpressions_Function_1__Boolean_1_", "\"Common table expression not supported on DB Spanner\"", AdapterQualifier.unsupportedFeature),

            //filter
            one("meta::pure::functions::relation::tests::filter::testSimpleFilter_MultipleExpressions_Function_1__Boolean_1_", "\"Common table expression not supported on DB Spanner\"", AdapterQualifier.unsupportedFeature),

            //first
            one("meta::pure::functions::relation::tests::first::testOLAPWithPartitionAndOrderFirstWindow_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),

            //groupBy
            one("meta::pure::functions::relation::tests::groupBy::testSimpleGroupBy_MultipleMultiple_MultipleExpressions_Function_1__Boolean_1_", "\"Common table expression not supported on DB Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::groupBy::testSimpleGroupBy_MultipleSingle_MultipleExpressions_Function_1__Boolean_1_", "\"Common table expression not supported on DB Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::groupBy::testSimpleGroupBy_SingleMultiple_MultipleExpressions_Function_1__Boolean_1_", "\"Common table expression not supported on DB Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::groupBy::testSimpleGroupBy_SingleSingle_MultipleExpressions_Function_1__Boolean_1_", "\"Common table expression not supported on DB Spanner\"", AdapterQualifier.unsupportedFeature),

            //join
            one("meta::pure::functions::relation::tests::join::testSimpleJoin_MultipleExpressions_Function_1__Boolean_1_", "\"Common table expression not supported on DB Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::join::testJoin_forFailedJoinWhenNoRowsMatchJoinCondition_Function_1__Boolean_1_", "\"Common table expression not supported on DB Spanner\"", AdapterQualifier.unsupportedFeature),

            //lag
            one("meta::pure::functions::relation::tests::lag::testOLAPWithPartitionAndOrderWindowUsingLag_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),

            //last
            one("meta::pure::functions::relation::tests::last::testOLAPWithPartitionAndOrderLastWindow_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),

            //lead
            one("meta::pure::functions::relation::tests::lead::testOLAPWithPartitionAndOrderWindow_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),

            //limit
            one("meta::pure::functions::relation::tests::limit::testSimpleLimit_MultipleExpressions_Function_1__Boolean_1_", "\"Common table expression not supported on DB Spanner\"", AdapterQualifier.unsupportedFeature),

            //nth
            one("meta::pure::functions::relation::tests::nth::testOLAPWithPartitionAndOrderNthWindow2_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::nth::testOLAPWithPartitionAndOrderNthWindow_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),

            //ntile
            one("meta::pure::functions::relation::tests::ntile::testOLAPWithPartitionAndOrderNTile_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),

            //percentRank
            one("meta::pure::functions::relation::tests::percentRank::testOLAPWithPartitionAndOrderPercentRank_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),

            //pivot
            pack("meta::pure::functions::relation::tests::pivot", "\"pivot is not supported\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::pivot::testPivot_MultipleMultiple_MultipleExpressions_Function_1__Boolean_1_", "\"Common table expression not supported on DB Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::pivot::testPivot_MultipleSingle_MultipleExpressions_Function_1__Boolean_1_", "\"Common table expression not supported on DB Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::pivot::testPivot_SingleMultiple_MultipleExpressions_Function_1__Boolean_1_", "\"Common table expression not supported on DB Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::pivot::testPivot_SingleSingle_MultipleExpressions_Function_1__Boolean_1_", "\"Common table expression not supported on DB Spanner\"", AdapterQualifier.unsupportedFeature),

            //project
            one("meta::pure::functions::relation::tests::project::testSimpleRelationProject_Function_1__Boolean_1_", "\"[unsupported-api] The function 'toString' (state: [Select, false]) is not supported yet\"", AdapterQualifier.unsupportedFeature),

            //rank
            one("meta::pure::functions::relation::tests::rank::testOLAPWithPartitionAndOrderRank_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),

            //rename
            one("meta::pure::functions::relation::tests::rename::testSimpleRename_MultipleExpressions_Function_1__Boolean_1_", "\"Common table expression not supported on DB Spanner\"", AdapterQualifier.unsupportedFeature),

            //rowNumber
            one("meta::pure::functions::relation::tests::rowNumber::testOLAPWithPartitionAndRowNumber_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),

            //select
            one("meta::pure::functions::relation::tests::select::testMultiColsSelect_MultipleExpressions_Function_1__Boolean_1_", "\"Common table expression not supported on DB Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::select::testSelectAll_MultipleExpressions_Function_1__Boolean_1_", "\"Common table expression not supported on DB Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::select::testSingleColSelect_MultipleExpressions_Function_1__Boolean_1_", "\"Common table expression not supported on DB Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::select::testSingleSelectWithQuotedColumn_Function_1__Boolean_1_", "Error while executing: Create Table", AdapterQualifier.needsInvestigation),
            one("meta::pure::functions::relation::tests::select::testSingleSelectWithQuotedColumn_MultipleExpressions_Function_1__Boolean_1_", "\"Common table expression not supported on DB Spanner\"", AdapterQualifier.unsupportedFeature),

            //size
            one("meta::pure::functions::relation::tests::size::testComparisonOperationAfterSize_MultipleExpressions_Function_1__Boolean_1_", "\"Common table expression not supported on DB Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::size::testGroupBySize_MultipleExpressions_Function_1__Boolean_1_", "\"Common table expression not supported on DB Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::size::testSimpleSize_MultipleExpressions_Function_1__Boolean_1_", "\"Common table expression not supported on DB Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::size::testSize_Relation_Window_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.needsInvestigation),

            //slice
            one("meta::pure::functions::relation::tests::slice::testSimpleSlice_MultipleExpressions_Function_1__Boolean_1_", "\"Common table expression not supported on DB Spanner\"", AdapterQualifier.unsupportedFeature),

            //sort
            one("meta::pure::functions::relation::tests::sort::testSimpleSort_MultipleExpressions_Function_1__Boolean_1_", "\"Common table expression not supported on DB Spanner\"", AdapterQualifier.unsupportedFeature),

            //testWrite
            one("meta::pure::functions::relation::tests::write::testWrite_Function_1__Boolean_1_", "\"\nexpected: 5\nactual:   0\"", AdapterQualifier.needsInvestigation),

            // Spanner does not support window functions
            one("meta::pure::functions::relation::tests::over::testRows_CurrentRow_CurrentRow_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRows_CurrentRow_NFollowing_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRows_CurrentRow_UnboundedFollowing_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRows_NFollowing_NFollowing_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRows_NFollowing_UnboundedFollowing_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRows_NPreceding_CurrentRow_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRows_NPreceding_NFollowing_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRows_NPreceding_NPreceding_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRows_NPreceding_UnboundedFollowing_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRows_UnboundedPreceding_CurrentRow_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRows_UnboundedPreceding_CurrentRow_WithMultiplePartitions_WithSingleOrderBy_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRows_UnboundedPreceding_NFollowing_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRows_UnboundedPreceding_NPreceding_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRows_UnboundedPreceding_UnboundedFollowing_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRows_UnboundedPreceding_UnboundedFollowing_WithMultiplePartitions_WithoutOrderBy_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRows_UnboundedPreceding_UnboundedFollowing_WithSinglePartition_WithoutOrderBy_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRange_CurrentRow_UnboundedFollowing_WithSinglePartition_WithOrderByASC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRange_CurrentRow_UnboundedFollowing_WithSinglePartition_WithOrderByDESC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRange_NFollowing_NFollowing_WithSinglePartition_WithOrderByASC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRange_NFollowing_NFollowing_WithSinglePartition_WithOrderByDESC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRange_NFollowing_UnboundedFollowing_WithSinglePartition_WithOrderByASC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRange_NFollowing_UnboundedFollowing_WithSinglePartition_WithOrderByDESC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRange_NPreceding_NFollowing_WithSinglePartition_WithOrderByASC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRange_NPreceding_NFollowing_WithSinglePartition_WithOrderByDESC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRange_NPreceding_NPreceding_WithSinglePartition_WithOrderByASC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRange_NPreceding_NPreceding_WithSinglePartition_WithOrderByDESC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRange_NPreceding_UnboundedFollowing_WithSinglePartition_WithOrderByASC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRange_NPreceding_UnboundedFollowing_WithSinglePartition_WithOrderByDESC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRange_UnboundedPreceding_CurrentRow_WithSinglePartition_WithOrderByASC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRange_UnboundedPreceding_CurrentRow_WithSinglePartition_WithOrderByDESC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRange_UnboundedPreceding_NFollowing_WithSinglePartition_WithOrderByASC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRange_UnboundedPreceding_NFollowing_WithSinglePartition_WithOrderByDESC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRange_UnboundedPreceding_NPreceding_WithSinglePartition_WithOrderByASC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRange_UnboundedPreceding_NPreceding_WithSinglePartition_WithOrderByDESC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRange_WithNumbers_CurrentRow_NFollowing_WithoutPartition_WithSingleOrderBy_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRange_WithNumbers_NFollowing_NFollowing_WithoutPartition_WithSingleOrderBy_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRangeInterval_CurrentRow_CurrentRow_WithSinglePartition_WithOrderByASC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRangeInterval_CurrentRow_CurrentRow_WithSinglePartition_WithOrderByDESC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRangeInterval_CurrentRow_NFollowing_WithSinglePartition_WithOrderByASC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRangeInterval_CurrentRow_NFollowing_WithSinglePartition_WithOrderByDESC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRangeInterval_CurrentRow_UnboundedFollowing_WithSinglePartition_WithOrderByASC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRangeInterval_CurrentRow_UnboundedFollowing_WithSinglePartition_WithOrderByDESC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRangeInterval_NFollowing_NFollowing_WithDifferentDurationUnits_WithSinglePartition_WithOrderByASC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRangeInterval_NFollowing_NFollowing_WithDifferentDurationUnits_WithSinglePartition_WithOrderByDESC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRangeInterval_NFollowing_NFollowing_WithSinglePartition_WithOrderByASC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRangeInterval_NFollowing_NFollowing_WithSinglePartition_WithOrderByDESC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRangeInterval_NFollowing_UnboundedFollowing_WithSinglePartition_WithOrderByASC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRangeInterval_NFollowing_UnboundedFollowing_WithSinglePartition_WithOrderByDESC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRangeInterval_NPreceding_CurrentRow_WithSinglePartition_WithOrderByASC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRangeInterval_NPreceding_CurrentRow_WithSinglePartition_WithOrderByDESC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRangeInterval_NPreceding_NFollowing_WithSinglePartition_WithOrderByASC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRangeInterval_NPreceding_NFollowing_WithSinglePartition_WithOrderByDESC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRangeInterval_NPreceding_NPreceding_WithSinglePartition_WithOrderByASC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRangeInterval_NPreceding_NPreceding_WithSinglePartition_WithOrderByDESC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRangeInterval_NPreceding_UnboundedFollowing_WithSinglePartition_WithOrderByASC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRangeInterval_NPreceding_UnboundedFollowing_WithSinglePartition_WithOrderByDESC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRangeInterval_UnboundedPreceding_CurrentRow_WithSinglePartition_WithOrderByASC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRangeInterval_UnboundedPreceding_CurrentRow_WithSinglePartition_WithOrderByDESC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRangeInterval_UnboundedPreceding_NFollowing_WithSinglePartition_WithOrderByASC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRangeInterval_UnboundedPreceding_NFollowing_WithSinglePartition_WithOrderByDESC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRangeInterval_UnboundedPreceding_NPreceding_WithSinglePartition_WithOrderByASC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRangeInterval_UnboundedPreceding_NPreceding_WithSinglePartition_WithOrderByDESC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRange_ExplicitOffsets_WithNullValues_WithSinglePartition_WithOrderByASC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRange_ExplicitOffsets_WithNullValues_WithSinglePartition_WithOrderByDESC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRange_NPreceding_UnboundedFollowing_WithNullValues_WithSinglePartition_WithOrderByASC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::over::testRange_UnboundedPreceding_NFollowing_WithNullValues_WithSinglePartition_WithOrderByDESC_Function_1__Boolean_1_", "\"[unsupported-api] Window Columns not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),

            one("meta::pure::functions::relation::tests::composition::testVariantArrayColumn_reverse_Function_1__Boolean_1_", "\"[unsupported-api] The function 'toVariant' (state: [Select, false]) is not supported yet\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::composition::testVariantArrayColumn_sort_Function_1__Boolean_1_", "\"[unsupported-api] The function 'toVariant' (state: [Select, false]) is not supported yet\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::composition::testVariantColumn_extend_indexExtraction_filter_Function_1__Boolean_1_", "\"[unsupported-api] Semi structured array element processing not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::composition::testVariantColumn_functionComposition_Function_1__Boolean_1_", "\"[unsupported-api] The function 'array_size' (state: [Where, false]) is not supported yet\"", AdapterQualifier.unsupportedFeature),

            one("meta::pure::functions::relation::tests::filter::testVariantColumn_filterOnIndexExtractionValue_Function_1__Boolean_1_", "\"[unsupported-api] Semi structured array element processing not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::filter::testVariantColumn_filterOnKeyExtractionValue_Function_1__Boolean_1_", "\"[unsupported-api] Semi structured array element processing not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::filter::testVariantColumn_filterOutputFromLambda_Function_1__Boolean_1_", "\"[unsupported-api] The function 'array_size' (state: [Where, false]) is not supported yet\"", AdapterQualifier.unsupportedFeature),

            one("meta::pure::functions::relation::tests::extend::testVariantColumn_Function_1__Boolean_1_", "Error while executing: Create Table", AdapterQualifier.needsInvestigation),
            one("meta::pure::functions::relation::tests::extend::testVariantColumn_map_Function_1__Boolean_1_", "\"[unsupported-api] The function 'toVariant' (state: [Select, false]) is not supported yet\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::extend::testVariantColumn_fold_Function_1__Boolean_1_", "\"[unsupported-api] relational lambda processing not supported for Database Type: Spanner\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::extend::testVariantColumn_filter_Function_1__Boolean_1_", "\"[unsupported-api] The function 'toVariant' (state: [Select, false]) is not supported yet\"", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::extend::testVariantColumn_indexExtraction_Function_1__Boolean_1_", "[unsupported-api] Semi structured array element processing not supported for Database Type: Spanner", AdapterQualifier.unsupportedFeature),
            one("meta::pure::functions::relation::tests::extend::testVariantColumn_keyExtraction_Function_1__Boolean_1_", "[unsupported-api] Semi structured array element processing not supported for Database Type: Spanner", AdapterQualifier.unsupportedFeature)
    );

    public static Test suite()
    {
        return wrapSuite(
                () -> true,
                () -> PureTestBuilderCompiled.buildPCTTestSuite(reportScope, expectedFailures, adapter),
                () -> false,
                Lists.mutable.with((TestServerResource) TestConnectionIntegrationLoader.extensions().select(c -> c.getDatabaseType() == DatabaseType.Spanner).getFirst())
        );
    }

    @Override
    public MutableList<ExclusionSpecification> expectedFailures()
    {
        return expectedFailures;
    }

    @Override
    public ReportScope getReportScope()
    {
        return reportScope;
    }

    @Override
    public Adapter getAdapter()
    {
        return adapter;
    }

    @Override
    public String getPlatform()
    {
        return platform;
    }
}
