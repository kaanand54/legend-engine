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
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MapIterable;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.api.set.ImmutableSet;
import org.eclipse.collections.api.set.MutableSet;
import org.eclipse.collections.api.tuple.Pair;
import org.eclipse.collections.impl.utility.Iterate;
import org.eclipse.collections.impl.utility.LazyIterate;
import org.eclipse.collections.impl.utility.ListIterate;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.CompileContext;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.ProcessingContext;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.PureModel;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.handlers.FunctionExpressionBuilderRegistrationInfo;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.handlers.FunctionHandlerDispatchBuilderInfo;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.handlers.FunctionHandlerRegistrationInfo;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.handlers.Handlers;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.handlers.IncludedMappingHandler;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.validator.MappingValidatorContext;
import org.finos.legend.engine.protocol.pure.m3.SourceInformation;
import org.finos.legend.engine.protocol.pure.v1.model.context.EngineErrorType;
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
import org.finos.legend.engine.protocol.pure.dsl.store.valuespecification.constant.classInstance.RelationStoreAccessor;
import org.finos.legend.engine.protocol.pure.v1.model.valueSpecification.raw.executionContext.ExecutionContext;
import org.finos.legend.engine.shared.core.function.Function4;
import org.finos.legend.engine.shared.core.function.Procedure3;
import org.finos.legend.engine.shared.core.operational.errorManagement.EngineException;
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
import org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.valuespecification.ValueSpecification;
import org.finos.legend.pure.m3.coreinstance.meta.pure.store.Store;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

public class CompilerExtensions
{
    private static final Logger LOGGER = LoggerFactory.getLogger(CompilerExtensions.class);

    @SuppressWarnings("unchecked")
    private static final ImmutableSet<Class<? extends PackageableElement>> FORBIDDEN_PROCESSOR_CLASSES = Sets.immutable.with(
            PackageableElement.class,
            org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.Store.class
    );

    private final ImmutableList<CompilerExtension> extensions;
    private final MapIterable<Class<? extends PackageableElement>, Processor<?>> extraProcessors;
    private final ImmutableList<Function3<ClassMapping, Mapping, CompileContext, Pair<SetImplementation, RichIterable<EmbeddedSetImplementation>>>> extraClassMappingFirstPassProcessors;
    private final ImmutableList<Procedure3<ClassMapping, Mapping, CompileContext>> extraClassMappingSecondPassProcessors;
    private final ImmutableList<Procedure3<AggregationAwareClassMapping, Mapping, CompileContext>> extraAggregationAwareClassMappingFirstPassProcessors;
    private final ImmutableList<Procedure3<AggregationAwareClassMapping, Mapping, CompileContext>> extraAggregationAwareClassMappingSecondPassProcessors;
    private final ImmutableList<Procedure2<AggregationAwareClassMapping, Set<PackageableElementPointer>>> extraAggregationAwareClassMappingPrerequisiteElementsPassProcessors;
    private final ImmutableList<Procedure3<ClassMapping, CompileContext, Set<PackageableElementPointer>>> extraClassMappingPrerequisiteElementsPassProcessors;
    private final ImmutableList<Function3<AssociationMapping, Mapping, CompileContext, AssociationImplementation>> extraAssociationMappingProcessors;
    private final ImmutableList<Procedure2<AssociationMapping, Set<PackageableElementPointer>>> extraAssociationMappingPrerequisiteElementsPassProcessors;
    private final ImmutableList<Function2<org.finos.legend.engine.protocol.pure.v1.model.packageableElement.connection.Connection, CompileContext, Root_meta_core_runtime_Connection>> extraConnectionValueProcessors;
    private final ImmutableList<Procedure3<org.finos.legend.engine.protocol.pure.v1.model.packageableElement.connection.Connection, Root_meta_core_runtime_Connection, CompileContext>> extraConnectionSecondPassProcessors;
    private final ImmutableList<Procedure2<InputData, CompileContext>> extraMappingTestInputDataProcessors;
    private final ImmutableList<Procedure2<InputData, Set<PackageableElementPointer>>> extraMappingTestInputDataPrerequisiteElementsPassProcessors;
    private final ImmutableList<Function<Handlers, List<FunctionHandlerDispatchBuilderInfo>>> extraFunctionHandlerDispatchBuilderInfoCollectors;
    private final ImmutableList<Function<Handlers, List<FunctionExpressionBuilderRegistrationInfo>>> extraFunctionExpressionBuilderRegistrationInfoCollectors;
    private final ImmutableList<Function<Handlers, List<FunctionHandlerRegistrationInfo>>> extraFunctionHandlerRegistrationInfoCollectors;
    private final ImmutableList<Function4<org.finos.legend.engine.protocol.pure.m3.valuespecification.ValueSpecification, CompileContext, List<String>, ProcessingContext, ValueSpecification>> extraValueSpecificationProcessors;
    private final ImmutableList<Function3<LambdaFunction, CompileContext, ProcessingContext, LambdaFunction>> extraLambdaPostProcessors;
    private final ImmutableList<Procedure2<PackageableElement, MutableMap<String, String>>> extraStoreStatBuilders;
    private final ImmutableList<Function2<ExecutionContext, CompileContext, Root_meta_pure_runtime_ExecutionContext>> extraExecutionContextProcessors;
    private final ImmutableList<Procedure<Procedure2<String, List<String>>>> extraElementForPathToElementRegisters;
    private final ImmutableList<Procedure3<SetImplementation, Set<String>, CompileContext>> extraSetImplementationSourceScanners;
    private final ImmutableList<Procedure2<PureModel, PureModelContextData>> extraPostValidators;
    private final ImmutableList<Function2<ExecutionOption, CompileContext, Root_meta_pure_executionPlan_ExecutionOption>> extraExecutionOptionProcessors;
    private final ImmutableList<Function3<EmbeddedData, CompileContext, ProcessingContext, Root_meta_pure_data_EmbeddedData>> extraEmbeddedDataProcessors;
    private final ImmutableList<Procedure3<Set<PackageableElementPointer>, EmbeddedData, CompileContext>> extraEmbeddedDataPrerequisiteElementsPassProcessors;
    private final ImmutableList<Function3<Test, CompileContext, ProcessingContext, org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.testable.Test>> extraTestProcessors;
    private final ImmutableList<Procedure3<Set<PackageableElementPointer>, Test, CompileContext>> extraTestPrerequisiteElementsPassProcessors;
    private final ImmutableList<Function3<TestAssertion, CompileContext, ProcessingContext, Root_meta_pure_test_assertion_TestAssertion>> extraTestAssertionProcessors;
    private final ImmutableList<Procedure3<Set<PackageableElementPointer>, TestAssertion, CompileContext>> extraTestAssertionPrerequisiteElementsPassProcessors;
    private final Map<String, Function3<Object, CompileContext, ProcessingContext, ValueSpecification>> extraClassInstanceProcessors;
    private final Map<String, Procedure2<Object, Set<PackageableElementPointer>>> extraClassInstancePrerequisiteElementsPassProcessors;
    private final ImmutableList<BiConsumer<PureModel, MappingValidatorContext>> extraMappingPostValidators;
    private final ImmutableList<Function3<org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.PackageableElement, CompileContext, ProcessingContext, InstanceValue>> extraValueSpecificationBuilderForFuncExpr;
    private final ImmutableList<Function4<RelationStoreAccessor, Store, CompileContext, ProcessingContext, org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.valuespecification.ValueSpecification>> extraRelationStoreAccessorProcessors;
    private final ImmutableList<Function2<org.finos.legend.engine.protocol.pure.v1.model.packageableElement.runtime.EngineRuntime, CompileContext, Root_meta_core_runtime_EngineRuntime>> extraRuntimeValueProcessors;
    private final ImmutableList<Procedure3<org.finos.legend.engine.protocol.pure.v1.model.packageableElement.runtime.EngineRuntime, Root_meta_core_runtime_EngineRuntime, CompileContext>> extraRuntimeSecondPassProcessors;

    private final Map<String, IncludedMappingHandler> extraIncludedMappingHandlers;
    private final MutableMap<String, MutableSet<String>> extraSubTypesForFunctionMatching;

    private CompilerExtensions(Iterable<? extends CompilerExtension> extensions)
    {
        this.extensions = Lists.immutable.withAll(extensions);
        this.extraProcessors = indexProcessors(this.extensions);
        this.extraClassMappingFirstPassProcessors = this.extensions.flatCollect(CompilerExtension::getExtraClassMappingFirstPassProcessors);
        this.extraAggregationAwareClassMappingFirstPassProcessors = this.extensions.flatCollect(CompilerExtension::getExtraAggregationAwareClassMappingFirstPassProcessors);
        this.extraAggregationAwareClassMappingSecondPassProcessors = this.extensions.flatCollect(CompilerExtension::getExtraAggregationAwareClassMappingSecondPassProcessors);
        this.extraAggregationAwareClassMappingPrerequisiteElementsPassProcessors = this.extensions.flatCollect(CompilerExtension::getExtraAggregationAwareClassMappingPrerequisiteElementsPassProcessors);
        this.extraClassMappingSecondPassProcessors = this.extensions.flatCollect(CompilerExtension::getExtraClassMappingSecondPassProcessors);
        this.extraClassMappingPrerequisiteElementsPassProcessors = this.extensions.flatCollect(CompilerExtension::getExtraClassMappingPrerequisiteElementsPassProcessors);
        this.extraAssociationMappingProcessors = this.extensions.flatCollect(CompilerExtension::getExtraAssociationMappingProcessors);
        this.extraAssociationMappingPrerequisiteElementsPassProcessors = this.extensions.flatCollect(CompilerExtension::getExtraAssociationMappingPrerequisiteElementsPassProcessors);
        this.extraConnectionValueProcessors = this.extensions.flatCollect(CompilerExtension::getExtraConnectionValueProcessors);
        this.extraConnectionSecondPassProcessors = this.extensions.flatCollect(CompilerExtension::getExtraConnectionSecondPassProcessors);
        this.extraMappingTestInputDataProcessors = this.extensions.flatCollect(CompilerExtension::getExtraMappingTestInputDataProcessors);
        this.extraMappingTestInputDataPrerequisiteElementsPassProcessors = this.extensions.flatCollect(CompilerExtension::getExtraMappingTestInputDataPrerequisiteElementsPassProcessors);
        this.extraFunctionHandlerDispatchBuilderInfoCollectors = this.extensions.flatCollect(CompilerExtension::getExtraFunctionHandlerDispatchBuilderInfoCollectors);
        this.extraFunctionExpressionBuilderRegistrationInfoCollectors = this.extensions.flatCollect(CompilerExtension::getExtraFunctionExpressionBuilderRegistrationInfoCollectors);
        this.extraFunctionHandlerRegistrationInfoCollectors = this.extensions.flatCollect(CompilerExtension::getExtraFunctionHandlerRegistrationInfoCollectors);
        this.extraValueSpecificationProcessors = this.extensions.flatCollect(CompilerExtension::getExtraValueSpecificationProcessors);
        this.extraLambdaPostProcessors = this.extensions.flatCollect(CompilerExtension::getExtraLambdaPostProcessor);
        this.extraStoreStatBuilders = this.extensions.flatCollect(CompilerExtension::getExtraStoreStatBuilders);
        this.extraExecutionContextProcessors = this.extensions.flatCollect(CompilerExtension::getExtraExecutionContextProcessors);
        this.extraElementForPathToElementRegisters = this.extensions.flatCollect(CompilerExtension::getExtraElementForPathToElementRegisters);
        this.extraSetImplementationSourceScanners = this.extensions.flatCollect(CompilerExtension::getExtraSetImplementationSourceScanners);
        this.extraPostValidators = this.extensions.flatCollect(CompilerExtension::getExtraPostValidators);
        this.extraExecutionOptionProcessors = this.extensions.flatCollect(CompilerExtension::getExtraExecutionOptionProcessors);
        this.extraEmbeddedDataProcessors = this.extensions.flatCollect(CompilerExtension::getExtraEmbeddedDataProcessors);
        this.extraEmbeddedDataPrerequisiteElementsPassProcessors = this.extensions.flatCollect(CompilerExtension::getExtraEmbeddedDataPrerequisiteElementsPassProcessors);
        this.extraTestProcessors = this.extensions.flatCollect(CompilerExtension::getExtraTestProcessors);
        this.extraTestPrerequisiteElementsPassProcessors = this.extensions.flatCollect(CompilerExtension::getExtraTestPrerequisiteElementsPassProcessors);
        this.extraTestAssertionProcessors = this.extensions.flatCollect(CompilerExtension::getExtraTestAssertionProcessors);
        this.extraTestAssertionPrerequisiteElementsPassProcessors = this.extensions.flatCollect(CompilerExtension::getExtraTestAssertionPrerequisiteElementsPassProcessors);
        this.extraClassInstanceProcessors = Maps.mutable.empty();
        this.extensions.forEach(e -> extraClassInstanceProcessors.putAll(e.getExtraClassInstanceProcessors()));
        this.extraClassInstancePrerequisiteElementsPassProcessors = Maps.mutable.empty();
        this.extensions.forEach(e -> extraClassInstancePrerequisiteElementsPassProcessors.putAll(e.getExtraClassInstancePrerequisiteElementsPassProcessors()));
        this.extraMappingPostValidators = this.extensions.flatCollect(CompilerExtension::getExtraMappingPostValidators);
        this.extraValueSpecificationBuilderForFuncExpr = this.extensions.flatCollect(CompilerExtension::getExtraValueSpecificationBuilderForFuncExpr);
        this.extraIncludedMappingHandlers = Maps.mutable.empty();
        this.extensions.forEach(e -> extraIncludedMappingHandlers.putAll(e.getExtraIncludedMappingHandlers()));
        this.extraRelationStoreAccessorProcessors = this.extensions.flatCollect(CompilerExtension::getExtraRelationStoreAccessorProcessors);
        this.extraRuntimeValueProcessors = this.extensions.flatCollect(CompilerExtension::getExtraRuntimeValueProcessors);
        this.extraRuntimeSecondPassProcessors = this.extensions.flatCollect(CompilerExtension::getExtraRuntimeThirdPassProcessors);
        this.extraSubTypesForFunctionMatching = Maps.mutable.empty();
        this.extensions.forEach(
                e -> e.getExtraSubtypesForFunctionMatching().keysView().forEach(
                        k -> extraSubTypesForFunctionMatching.getIfAbsentPut(k, Sets.mutable::empty).addAll(e.getExtraSubtypesForFunctionMatching().get(k))
                )
        );
    }

    public List<CompilerExtension> getExtensions()
    {
        return this.extensions.castToList();
    }

    public Iterable<? extends Processor<?>> getExtraProcessors()
    {
        return this.extraProcessors.valuesView();
    }

    public Processor<?> getExtraProcessorOrThrow(PackageableElement element)
    {
        Processor<?> processor = getExtraProcessor(element);
        if (processor == null)
        {
            throw new UnsupportedOperationException("No extra processor available for element " + element.getPath() + " of type " + element.getClass().getName());
        }
        return processor;
    }

    public Processor<?> getExtraProcessor(PackageableElement element)
    {
        return getExtraProcessor(element.getClass());
    }

    public Processor<?> getExtraProcessorOrThrow(java.lang.Class<? extends PackageableElement> cls)
    {
        Processor<?> processor = getExtraProcessor(cls);
        if (processor == null)
        {
            throw new UnsupportedOperationException("No extra processor available for type " + cls.getName());
        }
        return processor;
    }

    public Processor<?> getExtraProcessor(java.lang.Class<? extends PackageableElement> cls)
    {
        return this.extraProcessors.isEmpty() ? null : getExtraProcessor_recursive(cls);
    }

    private Processor<?> getExtraProcessor_recursive(java.lang.Class<?> cls)
    {
        Processor<?> processor = this.extraProcessors.get(cls);
        if (processor != null)
        {
            return processor;
        }
        if (FORBIDDEN_PROCESSOR_CLASSES.contains(cls))
        {
            return null;
        }
        // We can ignore interfaces in this search, since PackageableElement is itself a class (not an interface)
        java.lang.Class<?> superClass = cls.getSuperclass();
        return (superClass == null) ? null : getExtraProcessor_recursive(superClass);
    }

    public List<Function3<ClassMapping, Mapping, CompileContext, Pair<SetImplementation, RichIterable<EmbeddedSetImplementation>>>> getExtraClassMappingFirstPassProcessors()
    {
        return this.extraClassMappingFirstPassProcessors.castToList();
    }

    public List<Procedure3<ClassMapping, Mapping, CompileContext>> getExtraClassMappingSecondPassProcessors()
    {
        return this.extraClassMappingSecondPassProcessors.castToList();
    }

    public List<Procedure3<AggregationAwareClassMapping, Mapping, CompileContext>> getExtraAggregationAwareClassMappingFirstPassProcessors()
    {
        return this.extraAggregationAwareClassMappingFirstPassProcessors.castToList();
    }

    public List<Procedure3<AggregationAwareClassMapping, Mapping, CompileContext>> getExtraAggregationAwareClassMappingSecondPassProcessors()
    {
        return this.extraAggregationAwareClassMappingSecondPassProcessors.castToList();
    }

    public List<Procedure2<AggregationAwareClassMapping, Set<PackageableElementPointer>>> getExtraAggregationAwareClassMappingPrerequisiteElementsPassProcessors()
    {
        return this.extraAggregationAwareClassMappingPrerequisiteElementsPassProcessors.castToList();
    }

    public List<Procedure3<ClassMapping, CompileContext, Set<PackageableElementPointer>>> getExtraClassMappingPrerequisiteElementsPassProcessors()
    {
        return this.extraClassMappingPrerequisiteElementsPassProcessors.castToList();
    }

    public List<Function3<AssociationMapping, Mapping, CompileContext, AssociationImplementation>> getExtraAssociationMappingProcessors()
    {
        return this.extraAssociationMappingProcessors.castToList();
    }

    public List<Procedure2<AssociationMapping, Set<PackageableElementPointer>>> getExtraAssociationMappingPrerequisiteElementsPassProcessors()
    {
        return this.extraAssociationMappingPrerequisiteElementsPassProcessors.castToList();
    }

    public List<Function2<org.finos.legend.engine.protocol.pure.v1.model.packageableElement.connection.Connection, CompileContext, Root_meta_core_runtime_Connection>> getExtraConnectionValueProcessors()
    {
        return this.extraConnectionValueProcessors.castToList();
    }

    public List<Procedure3<org.finos.legend.engine.protocol.pure.v1.model.packageableElement.connection.Connection, Root_meta_core_runtime_Connection, CompileContext>> getExtraConnectionSecondPassProcessors()
    {
        return this.extraConnectionSecondPassProcessors.castToList();
    }

    public List<Procedure2<InputData, CompileContext>> getExtraMappingTestInputDataProcessors()
    {
        return this.extraMappingTestInputDataProcessors.castToList();
    }

    public List<Procedure2<InputData, Set<PackageableElementPointer>>> getExtraMappingTestInputDataPrerequisiteElementsPassProcessors()
    {
        return this.extraMappingTestInputDataPrerequisiteElementsPassProcessors.castToList();
    }

    public List<Function<Handlers, List<FunctionHandlerDispatchBuilderInfo>>> getExtraFunctionHandlerDispatchBuilderInfoCollectors()
    {
        return this.extraFunctionHandlerDispatchBuilderInfoCollectors.castToList();
    }

    public List<Function<Handlers, List<FunctionExpressionBuilderRegistrationInfo>>> getExtraFunctionExpressionBuilderRegistrationInfoCollectors()
    {
        return this.extraFunctionExpressionBuilderRegistrationInfoCollectors.castToList();
    }

    public List<Function<Handlers, List<FunctionHandlerRegistrationInfo>>> getExtraFunctionHandlerRegistrationInfoCollectors()
    {
        return this.extraFunctionHandlerRegistrationInfoCollectors.castToList();
    }

    public List<Function4<org.finos.legend.engine.protocol.pure.m3.valuespecification.ValueSpecification, CompileContext, List<String>, ProcessingContext, ValueSpecification>> getExtraValueSpecificationProcessors()
    {
        return this.extraValueSpecificationProcessors.castToList();
    }

    public List<Function3<LambdaFunction, CompileContext, ProcessingContext, LambdaFunction>> getExtraLambdaPostProcessors()
    {
        return this.extraLambdaPostProcessors.castToList();
    }

    public List<Procedure2<PackageableElement, MutableMap<String, String>>> getExtraStoreStatBuilders()
    {
        return this.extraStoreStatBuilders.castToList();
    }

    public List<Function2<ExecutionContext, CompileContext, Root_meta_pure_runtime_ExecutionContext>> getExtraExecutionContextProcessors()
    {
        return this.extraExecutionContextProcessors.castToList();
    }

    public List<Function2<ExecutionOption, CompileContext, Root_meta_pure_executionPlan_ExecutionOption>> getExtraExecutionOptionProcessors()
    {
        return this.extraExecutionOptionProcessors.castToList();
    }

    public List<Function3<EmbeddedData, CompileContext, ProcessingContext, Root_meta_pure_data_EmbeddedData>> getExtraEmbeddedDataProcessors()
    {
        return this.extraEmbeddedDataProcessors.castToList();
    }

    public List<Procedure3<Set<PackageableElementPointer>, EmbeddedData, CompileContext>> getExtraEmbeddedDataPrerequisiteElementsPassProcessors()
    {
        return this.extraEmbeddedDataPrerequisiteElementsPassProcessors.castToList();
    }

    public List<Function3<Test, CompileContext, ProcessingContext, org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.testable.Test>> getExtraTestProcessors()
    {
        return this.extraTestProcessors.castToList();
    }

    public List<Procedure3<Set<PackageableElementPointer>, Test, CompileContext>> getExtraTestPrerequisiteElementsPassProcessors()
    {
        return this.extraTestPrerequisiteElementsPassProcessors.castToList();
    }

    public List<Function3<TestAssertion, CompileContext, ProcessingContext, Root_meta_pure_test_assertion_TestAssertion>> getExtraTestAssertionProcessors()
    {
        return this.extraTestAssertionProcessors.castToList();
    }

    public List<Procedure3<Set<PackageableElementPointer>, TestAssertion, CompileContext>> getExtraTestAssertionPrerequisiteElementsPassProcessors()
    {
        return this.extraTestAssertionPrerequisiteElementsPassProcessors.castToList();
    }

    public List<Procedure<Procedure2<String, List<String>>>> getExtraElementForPathToElementRegisters()
    {
        return this.extraElementForPathToElementRegisters.castToList();
    }

    public List<Procedure3<SetImplementation, Set<String>, CompileContext>> getExtraSetImplementationSourceScanners()
    {
        return this.extraSetImplementationSourceScanners.castToList();
    }

    public List<Procedure2<PureModel, PureModelContextData>> getExtraPostValidators()
    {
        return this.extraPostValidators.castToList();
    }

    public List<BiConsumer<PureModel, MappingValidatorContext>> getExtraMappingPostValidators()
    {
        return this.extraMappingPostValidators.castToList();
    }

    public List<Function2<org.finos.legend.engine.protocol.pure.v1.model.packageableElement.runtime.EngineRuntime, CompileContext, Root_meta_core_runtime_EngineRuntime>> getExtraRuntimeValueProcessors()
    {
        return this.extraRuntimeValueProcessors.castToList();
    }

    public List<Procedure3<org.finos.legend.engine.protocol.pure.v1.model.packageableElement.runtime.EngineRuntime, Root_meta_core_runtime_EngineRuntime, CompileContext>> getExtraRuntimeThirdPassProcessors()
    {
        return this.extraRuntimeSecondPassProcessors.castToList();
    }

    public ImmutableList<Function3<org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.PackageableElement, CompileContext, ProcessingContext, InstanceValue>> getExtraValueSpecificationBuilderForFuncExpr()
    {
        return this.extraValueSpecificationBuilderForFuncExpr;
    }

    public MutableMap<String, MutableSet<String>> getExtraSubTypesForFunctionMatching()
    {
        return extraSubTypesForFunctionMatching;
    }

    public List<Processor<?>> sortExtraProcessors()
    {
        return sortExtraProcessors(getExtraProcessors(), false);
    }

    public List<Processor<?>> sortExtraProcessors(Iterable<? extends Processor<?>> processors)
    {
        return sortExtraProcessors(processors, true);
    }

    private List<Processor<?>> sortExtraProcessors(Iterable<? extends Processor<?>> processors, boolean validateProcessors)
    {
        // Collect processor pre-requisites. Those without pre-requisites can go straight into the results list.
        MutableList<Processor<?>> results = Lists.mutable.empty();
        MutableMap<Processor<?>, Collection<java.lang.Class<? extends PackageableElement>>> withPrerequisites = Maps.mutable.empty();
        MutableMap<Class<? extends PackageableElement>, Processor<?>> processorsByClass = Maps.mutable.empty();
        processorsByClass.forEach(p -> processorsByClass.put(p.getElementClass(), p));

        processors.forEach(p ->
        {
            // Validate that the processor is part of this set of extensions
            if (validateProcessors && (p != this.extraProcessors.get(p.getElementClass())))
            {
                throw new IllegalArgumentException("Unknown processor: " + p);
            }
            withPrerequisites.getIfAbsentPut(p, Lists.mutable::empty).addAll(p.getPrerequisiteClasses());
            p.getReversePrerequisiteClasses().forEach(x -> withPrerequisites.getIfAbsentPut(processorsByClass.get(x), Lists.mutable::empty).add(x));
        });

        RichIterable<Pair<Processor<?>, Collection<Class<? extends PackageableElement>>>> emptyOnes = withPrerequisites.keyValuesView().select(x -> x.getTwo().isEmpty());
        results.addAll(emptyOnes.collect(Pair::getOne).toList());
        results.forEach(withPrerequisites::remove);

        // If there are processors with pre-requisites, we need to add them to the results list in an appropriate order.
        if (withPrerequisites.notEmpty())
        {
            // We transform the pre-requisite classes into pre-requisite processors.
            MutableMap<Processor<?>, RichIterable<? extends Processor<?>>> remaining = Maps.mutable.empty();
            withPrerequisites.forEach((processor, prerequisiteClasses) ->
            {
                // We only need to be concerned about pre-requisite processors that are not already in the results list,
                // since the ones already in the results list will go before any not already in that list.
                //
                // Note that there might be duplicate processors in this list, but that's ok. The cost of eliminating
                // the duplication is not worth the benefit.
                MutableList<Processor<?>> prerequisiteProcessors = Lists.mutable.ofInitialCapacity(prerequisiteClasses.size());
                withPrerequisites.keysView().select(p -> (p != processor) && Iterate.anySatisfy(prerequisiteClasses, c -> c.isAssignableFrom(p.getElementClass())), prerequisiteProcessors);
                LazyIterate.collect(prerequisiteClasses, this::getExtraProcessor)
                        .select(p -> (p != null) && (p != processor) && withPrerequisites.containsKey(p))
                        .forEach(prerequisiteProcessors::add);
                if (prerequisiteProcessors.isEmpty())
                {
                    // No pre-requisite processors that are not already in results: add to results
                    results.add(processor);
                }
                else
                {
                    remaining.put(processor, prerequisiteProcessors);
                }
            });

            // Now we start adding processors with pre-requisites to the results list. If a processor has no pre-
            // requisites among the other remaining processors, then all of its pre-requisites are already ahead of it
            // in the results list and so we can add it.
            //
            // We repeat this process until either there are no more remaining processors or we are unable to add any
            // remaining processors to the results list. The latter case indicates some sort of loop among the pre-
            // requisites, so we cannot put them in a consistent order and we must throw.
            int remainingProcessorsCount = remaining.size();
            while (remainingProcessorsCount > 0)
            {
                Iterator<Map.Entry<Processor<?>, RichIterable<? extends Processor<?>>>> iterator = remaining.entrySet().iterator();
                while (iterator.hasNext())
                {
                    Map.Entry<Processor<?>, RichIterable<? extends Processor<?>>> entry = iterator.next();
                    if (entry.getValue().noneSatisfy(remaining::containsKey))
                    {
                        // If a processor has no pre-requisites among the remaining processors, we can add it to the
                        // results list and remove it from the remaining processors.
                        results.add(entry.getKey());
                        iterator.remove();
                    }
                }
                int newSize = remaining.size();
                if (newSize == remainingProcessorsCount)
                {
                    // This means that all of the remaining processors have a pre-requisite of some other remaining
                    // processor. This implies that there's some sort of loop, and we cannot consistently order the
                    // remaining processors.
                    throw new EngineException(remaining.keysView().makeString("Could not consistently order the following processors: ", ", ", ""), SourceInformation.getUnknownSourceInformation(), EngineErrorType.COMPILATION);
                }
                remainingProcessorsCount = newSize;
            }
        }

        return results;
    }

    public static CompilerExtensions fromExtensions(CompilerExtension... extensions)
    {
        return fromExtensions(Lists.immutable.with(extensions));
    }

    public static CompilerExtensions fromExtensions(Iterable<? extends CompilerExtension> extensions)
    {
        return new CompilerExtensions(extensions);
    }

    public static CompilerExtensions fromAvailableExtensions()
    {
        return fromExtensions(ListIterate.collect(CompilerExtensionLoader.extensions(), CompilerExtension::build));
    }

    public static void logAvailableExtensions()
    {
        if (LOGGER.isDebugEnabled())
        {
            LOGGER.debug(LazyIterate.collect(CompilerExtensionLoader.extensions(), extension -> "- " + extension.getClass().getSimpleName()).makeString("Compiler extension(s) loaded:\n", "\n", ""));
        }
    }

    private static MutableMap<java.lang.Class<? extends PackageableElement>, Processor<?>> indexProcessors(Iterable<? extends CompilerExtension> extensions)
    {
        MutableMap<java.lang.Class<? extends PackageableElement>, Processor<?>> index = Maps.mutable.empty();
        for (Processor<?> processor : LazyIterate.flatCollect(extensions, CompilerExtension::getExtraProcessors))
        {
            java.lang.Class<? extends PackageableElement> processorClass = processor.getElementClass();
            if (FORBIDDEN_PROCESSOR_CLASSES.contains(processorClass))
            {
                throw new IllegalArgumentException("Processor not allowed for class: " + processorClass.getName());
            }
            if (index.put(processorClass, processor) != null)
            {
                throw new IllegalArgumentException("Conflicting processors for class: " + processorClass.getName());
            }
        }
        return index;
    }

    public Map<String, Function3<Object, CompileContext, ProcessingContext, ValueSpecification>> getExtraClassInstanceProcessors()
    {
        return extraClassInstanceProcessors;
    }

    public Map<String, Procedure2<Object, Set<PackageableElementPointer>>> getExtraClassInstancePrerequisiteElementsPassProcessors()
    {
        return extraClassInstancePrerequisiteElementsPassProcessors;
    }

    public IncludedMappingHandler getExtraIncludedMappingHandlers(String classType)
    {
        return this.extraIncludedMappingHandlers.get(classType);
    }

    public ImmutableList<Function4<RelationStoreAccessor, Store, CompileContext, ProcessingContext, ValueSpecification>> getExtraRelationStoreAccessorProcessors()
    {
        return this.extraRelationStoreAccessorProcessors;
    }
}
