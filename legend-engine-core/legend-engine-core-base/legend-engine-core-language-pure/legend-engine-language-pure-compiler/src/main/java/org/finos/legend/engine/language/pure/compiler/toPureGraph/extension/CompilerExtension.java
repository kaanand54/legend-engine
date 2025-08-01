// Copyright 2020 Goldman Sachs
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

package org.finos.legend.engine.language.pure.compiler.toPureGraph.extension;

import org.eclipse.collections.api.RichIterable;
import org.eclipse.collections.api.block.function.Function;
import org.eclipse.collections.api.block.function.Function2;
import org.eclipse.collections.api.block.function.Function3;
import org.eclipse.collections.api.block.procedure.Procedure;
import org.eclipse.collections.api.block.procedure.Procedure2;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.api.set.MutableSet;
import org.eclipse.collections.api.tuple.Pair;
import org.eclipse.collections.impl.factory.Maps;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.CompileContext;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.ProcessingContext;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.PureModel;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.handlers.FunctionExpressionBuilderRegistrationInfo;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.handlers.FunctionHandlerDispatchBuilderInfo;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.handlers.FunctionHandlerRegistrationInfo;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.handlers.Handlers;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.handlers.IncludedMappingHandler;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.validator.MappingValidatorContext;
import org.finos.legend.engine.protocol.pure.v1.model.context.PackageableElementPointer;
import org.finos.legend.engine.protocol.pure.v1.model.context.PureModelContextData;
import org.finos.legend.engine.protocol.pure.v1.model.data.EmbeddedData;
import org.finos.legend.engine.protocol.pure.v1.model.executionOption.ExecutionOption;
import org.finos.legend.engine.protocol.pure.m3.PackageableElement;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.mapping.AssociationMapping;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.mapping.ClassMapping;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.mapping.aggregationAware.AggregationAwareClassMapping;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.mapping.mappingTest.InputData;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.runtime.EngineRuntime;
import org.finos.legend.engine.protocol.pure.v1.model.test.Test;
import org.finos.legend.engine.protocol.pure.v1.model.test.assertion.TestAssertion;
import org.finos.legend.engine.protocol.pure.m3.valuespecification.ValueSpecification;
import org.finos.legend.engine.protocol.pure.dsl.store.valuespecification.constant.classInstance.RelationStoreAccessor;
import org.finos.legend.engine.protocol.pure.v1.model.valueSpecification.raw.executionContext.ExecutionContext;
import org.finos.legend.engine.shared.core.extension.LegendLanguageExtension;
import org.finos.legend.engine.shared.core.function.Function4;
import org.finos.legend.engine.shared.core.function.Procedure3;
import org.finos.legend.pure.generated.Root_meta_core_runtime_Connection;
import org.finos.legend.pure.generated.Root_meta_core_runtime_EngineRuntime;
import org.finos.legend.pure.generated.Root_meta_pure_data_EmbeddedData;
import org.finos.legend.pure.generated.Root_meta_pure_executionPlan_ExecutionOption;
import org.finos.legend.pure.generated.Root_meta_pure_runtime_ExecutionContext;
import org.finos.legend.pure.generated.Root_meta_pure_test_assertion_TestAssertion;
import org.finos.legend.pure.m3.coreinstance.meta.pure.mapping.AssociationImplementation;
import org.finos.legend.pure.m3.coreinstance.meta.pure.mapping.EmbeddedSetImplementation;
import org.finos.legend.pure.m3.coreinstance.meta.pure.mapping.Mapping;
import org.finos.legend.pure.m3.coreinstance.meta.pure.mapping.SetImplementation;
import org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.function.LambdaFunction;
import org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.valuespecification.InstanceValue;
import org.finos.legend.pure.m3.coreinstance.meta.pure.store.Store;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

public interface CompilerExtension extends LegendLanguageExtension
{
    Iterable<? extends Processor<?>> getExtraProcessors();

    @Override
    default String type()
    {
        return "Compiler";
    }

    @Deprecated
    default List<Function4<ValueSpecification, CompileContext, List<String>, ProcessingContext, org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.valuespecification.ValueSpecification>> getExtraValueSpecificationProcessors()
    {
        return Collections.emptyList();
    }

    default Map<String, Function3<Object, CompileContext, ProcessingContext, org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.valuespecification.ValueSpecification>> getExtraClassInstanceProcessors()
    {
        return Maps.mutable.empty();
    }

    default Map<String, Procedure2<Object, Set<PackageableElementPointer>>> getExtraClassInstancePrerequisiteElementsPassProcessors()
    {
        return Maps.mutable.empty();
    }

    default List<Function3<LambdaFunction, CompileContext, ProcessingContext, LambdaFunction>> getExtraLambdaPostProcessor()
    {
        return Collections.emptyList();
    }

    default List<Function3<ClassMapping, Mapping, CompileContext, Pair<SetImplementation, RichIterable<EmbeddedSetImplementation>>>> getExtraClassMappingFirstPassProcessors()
    {
        return Collections.emptyList();
    }

    default List<Procedure3<ClassMapping, Mapping, CompileContext>> getExtraClassMappingSecondPassProcessors()
    {
        return Collections.emptyList();
    }

    default List<Procedure3<ClassMapping, CompileContext, Set<PackageableElementPointer>>> getExtraClassMappingPrerequisiteElementsPassProcessors()
    {
        return Collections.emptyList();
    }

    default List<Procedure3<AggregationAwareClassMapping, Mapping, CompileContext>> getExtraAggregationAwareClassMappingFirstPassProcessors()
    {
        return Collections.emptyList();
    }

    default List<Procedure3<AggregationAwareClassMapping, Mapping, CompileContext>> getExtraAggregationAwareClassMappingSecondPassProcessors()
    {
        return Collections.emptyList();
    }

    default List<Procedure2<AggregationAwareClassMapping, Set<PackageableElementPointer>>> getExtraAggregationAwareClassMappingPrerequisiteElementsPassProcessors()
    {
        return Collections.emptyList();
    }

    default List<Function3<AssociationMapping, Mapping, CompileContext, AssociationImplementation>> getExtraAssociationMappingProcessors()
    {
        return Collections.emptyList();
    }

    default List<Procedure2<AssociationMapping, Set<PackageableElementPointer>>> getExtraAssociationMappingPrerequisiteElementsPassProcessors()
    {
        return Collections.emptyList();
    }

    default List<Function2<org.finos.legend.engine.protocol.pure.v1.model.packageableElement.connection.Connection, CompileContext, Root_meta_core_runtime_Connection>> getExtraConnectionValueProcessors()
    {
        return Collections.emptyList();
    }

    default List<Procedure3<org.finos.legend.engine.protocol.pure.v1.model.packageableElement.connection.Connection, Root_meta_core_runtime_Connection, CompileContext>> getExtraConnectionSecondPassProcessors()
    {
        return Collections.emptyList();
    }

    default List<Procedure2<InputData, CompileContext>> getExtraMappingTestInputDataProcessors()
    {
        return Collections.emptyList();
    }

    default List<Procedure2<InputData, Set<PackageableElementPointer>>> getExtraMappingTestInputDataPrerequisiteElementsPassProcessors()
    {
        return Collections.emptyList();
    }

    default List<Function<Handlers, List<FunctionHandlerDispatchBuilderInfo>>> getExtraFunctionHandlerDispatchBuilderInfoCollectors()
    {
        return Collections.emptyList();
    }

    default List<Function<Handlers, List<FunctionExpressionBuilderRegistrationInfo>>> getExtraFunctionExpressionBuilderRegistrationInfoCollectors()
    {
        return Collections.emptyList();
    }

    default List<Function<Handlers, List<FunctionHandlerRegistrationInfo>>> getExtraFunctionHandlerRegistrationInfoCollectors()
    {
        return Collections.emptyList();
    }

    default List<Function4<RelationStoreAccessor, Store, CompileContext, ProcessingContext, org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.valuespecification.ValueSpecification>> getExtraRelationStoreAccessorProcessors()
    {
        return Collections.emptyList();
    }

    default MutableMap<String, MutableSet<String>> getExtraSubtypesForFunctionMatching()
    {
        return Maps.mutable.empty();
    }

    /**
     * FIXME: to be removed
     */
    @FunctionalInterface
    interface Procedure8<T1, T2, T3, T4, T5, T6, T7, T8>
    {
        void value(T1 var1, T2 var2, T3 var3, T4 var4, T5 var5, T6 var6, T7 var7, T8 var8);
    }

    // MISC

    default List<Procedure2<PackageableElement, MutableMap<String, String>>> getExtraStoreStatBuilders()
    {
        return Collections.emptyList();
    }

    default List<Function2<ExecutionContext, CompileContext, Root_meta_pure_runtime_ExecutionContext>> getExtraExecutionContextProcessors()
    {
        return Collections.emptyList();
    }

    default List<Procedure3<SetImplementation, Set<String>, CompileContext>> getExtraSetImplementationSourceScanners()
    {
        return Collections.emptyList();
    }

    default List<Procedure2<PureModel, PureModelContextData>> getExtraPostValidators()
    {
        return Collections.emptyList();
    }

    default List<BiConsumer<PureModel, MappingValidatorContext>> getExtraMappingPostValidators()
    {
        return Collections.emptyList();
    }

    default List<Function3<org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.PackageableElement, CompileContext, ProcessingContext, InstanceValue>> getExtraValueSpecificationBuilderForFuncExpr()
    {
        return Collections.emptyList();
    }

    /**
     * Needed to register paths for `pathToElement` to work on old graph
     */
    default List<Procedure<Procedure2<String, List<String>>>> getExtraElementForPathToElementRegisters()
    {
        return Collections.emptyList();
    }

    default List<Function2<ExecutionOption, CompileContext, Root_meta_pure_executionPlan_ExecutionOption>> getExtraExecutionOptionProcessors()
    {
        return Collections.emptyList();
    }

    default List<Function3<EmbeddedData, CompileContext, ProcessingContext, Root_meta_pure_data_EmbeddedData>> getExtraEmbeddedDataProcessors()
    {
        return Collections.emptyList();
    }

    default List<Procedure3<Set<PackageableElementPointer>, EmbeddedData, CompileContext>> getExtraEmbeddedDataPrerequisiteElementsPassProcessors()
    {
        return Collections.emptyList();
    }

    default List<Function3<Test, CompileContext, ProcessingContext, org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.testable.Test>> getExtraTestProcessors()
    {
        return Collections.emptyList();
    }

    default List<Procedure3<Set<PackageableElementPointer>, Test, CompileContext>> getExtraTestPrerequisiteElementsPassProcessors()
    {
        return Collections.emptyList();
    }

    default List<Function3<TestAssertion, CompileContext, ProcessingContext, Root_meta_pure_test_assertion_TestAssertion>> getExtraTestAssertionProcessors()
    {
        return Collections.emptyList();
    }

    default List<Procedure3<Set<PackageableElementPointer>, TestAssertion, CompileContext>> getExtraTestAssertionPrerequisiteElementsPassProcessors()
    {
        return Collections.emptyList();
    }

    default List<Function2<org.finos.legend.engine.protocol.pure.v1.model.packageableElement.runtime.EngineRuntime, CompileContext, Root_meta_core_runtime_EngineRuntime>> getExtraRuntimeValueProcessors()
    {
        return Collections.emptyList();
    }

    default List<Procedure3<org.finos.legend.engine.protocol.pure.v1.model.packageableElement.runtime.EngineRuntime, Root_meta_core_runtime_EngineRuntime, CompileContext>> getExtraRuntimeThirdPassProcessors()
    {
        return Collections.emptyList();
    }

    default Map<String, IncludedMappingHandler> getExtraIncludedMappingHandlers()
    {
        return Maps.mutable.empty();
    }

    default CompilerExtension build()
    {
        throw new RuntimeException("CompilerExtension build method is not implement for " + this.getClass().getSimpleName());
    }
}
