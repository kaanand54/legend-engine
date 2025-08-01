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

package org.finos.legend.engine.language.pure.compiler.toPureGraph.handlers;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.collections.api.RichIterable;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.api.set.ImmutableSet;
import org.eclipse.collections.api.set.MutableSet;
import org.eclipse.collections.api.tuple.Pair;
import org.eclipse.collections.impl.factory.Lists;
import org.eclipse.collections.impl.factory.Maps;
import org.eclipse.collections.impl.factory.Sets;
import org.eclipse.collections.impl.list.mutable.FastList;
import org.eclipse.collections.impl.map.mutable.UnifiedMap;
import org.eclipse.collections.impl.utility.LazyIterate;
import org.eclipse.collections.impl.utility.ListIterate;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.*;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.extension.CompilerExtensions;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.handlers.builder.CompositeFunctionExpressionBuilder;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.handlers.builder.FunctionExpressionBuilder;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.handlers.builder.MultiHandlerFunctionExpressionBuilder;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.handlers.builder.RequiredInferenceSimilarSignatureFunctionExpressionBuilder;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.handlers.inference.*;
import org.finos.legend.engine.protocol.pure.m3.SourceInformation;
import org.finos.legend.engine.protocol.pure.v1.model.context.EngineErrorType;
import org.finos.legend.engine.protocol.pure.m3.valuespecification.constant.PackageableType;
import org.finos.legend.engine.protocol.pure.m3.valuespecification.Variable;
import org.finos.legend.engine.protocol.pure.m3.valuespecification.AppliedFunction;
import org.finos.legend.engine.protocol.pure.m3.valuespecification.constant.classInstance.ClassInstance;
import org.finos.legend.engine.protocol.pure.m3.valuespecification.Collection;
import org.finos.legend.engine.protocol.pure.m3.function.LambdaFunction;
import org.finos.legend.engine.protocol.pure.v1.model.context.PackageableElementPointer;
import org.finos.legend.engine.protocol.pure.v1.model.context.PackageableElementType;
import org.finos.legend.engine.protocol.pure.v1.model.valueSpecification.raw.classInstance.AggregateValue;
import org.finos.legend.engine.protocol.pure.v1.model.valueSpecification.raw.classInstance.TDSAggregateValue;
import org.finos.legend.engine.protocol.pure.v1.model.valueSpecification.raw.classInstance.TdsOlapAggregation;
import org.finos.legend.engine.protocol.pure.v1.model.valueSpecification.raw.classInstance.TdsOlapRank;
import org.finos.legend.engine.protocol.pure.m3.valuespecification.constant.classInstance.relation.ColSpec;
import org.finos.legend.engine.protocol.pure.m3.valuespecification.constant.classInstance.relation.ColSpecArray;
import org.finos.legend.engine.shared.core.operational.Assert;
import org.finos.legend.engine.shared.core.operational.errorManagement.EngineException;
import org.finos.legend.pure.generated.*;
import org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.function.FunctionDefinition;
import org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.multiplicity.Multiplicity;
import org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.relation.Column;
import org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.relation.RelationType;
import org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.relation.RelationTypeAccessor;
import org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.type.Any;
import org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.type.FunctionType;
import org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.type.generics.GenericType;
import org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.valuespecification.*;
import org.finos.legend.pure.m3.navigation.M3Paths;
import org.finos.legend.pure.m3.navigation.ProcessorSupport;
import org.finos.legend.pure.m3.navigation.relation._Column;
import org.finos.legend.pure.m3.navigation.relation._RelationType;
import org.finos.legend.pure.m4.coreinstance.CoreInstance;
import org.finos.legend.pure.m4.exception.PureCompilationException;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.finos.legend.pure.generated.platform_pure_essential_meta_graph_elementToPath.Root_meta_pure_functions_meta_elementToPath_PackageableElement_1__String_1_;

public class Handlers
{
    private static final MutableMap<String, MutableSet<String>> taxoMap = buildTaxoMap();

    private static final String PACKAGE_SEPARATOR = org.finos.legend.pure.m3.navigation.PackageableElement.PackageableElement.DEFAULT_PATH_SEPARATOR;
    private static final String META_PACKAGE_NAME = "meta";

    private Set<String> registeredMetaPackages = Sets.mutable.empty();

    private static Collection toCollection(org.finos.legend.engine.protocol.pure.m3.valuespecification.ValueSpecification vs)
    {
        if (vs instanceof Collection)
        {
            return (Collection) vs;
        }
        return new Collection(Lists.mutable.with(vs));
    }

    private static void updateTwoParamsLambda(Object lambda, GenericType newGenericType, org.finos.legend.engine.protocol.pure.m3.multiplicity.Multiplicity m)
    {
        if (lambda instanceof LambdaFunction)
        {
            Variable variable = ((LambdaFunction) lambda).parameters.get(0);
            updateVariableType(variable, newGenericType);
            variable.multiplicity = m;

            Variable variable2 = ((LambdaFunction) lambda).parameters.get(1);
            updateVariableType(variable2, newGenericType);
            variable2.multiplicity = m;
        }
    }

    private static void updateTwoParamsLambdaDiffTypes(Object lambda, GenericType newGenericType, GenericType newGenericType2, org.finos.legend.engine.protocol.pure.m3.multiplicity.Multiplicity m, org.finos.legend.engine.protocol.pure.m3.multiplicity.Multiplicity m2)
    {
        if (lambda instanceof LambdaFunction)
        {
            Variable variable = ((LambdaFunction) lambda).parameters.get(0);
            updateVariableType(variable, newGenericType);
            variable.multiplicity = m;

            Variable variable2 = ((LambdaFunction) lambda).parameters.get(1);
            updateVariableType(variable2, newGenericType2);
            variable2.multiplicity = m2;
        }
    }

    private static void updateSimpleLambda(Object lambda, GenericType newGenericType, org.finos.legend.engine.protocol.pure.m3.multiplicity.Multiplicity m, CompileContext cc)
    {
        if (lambda instanceof LambdaFunction)
        {
            List<Variable> params = ((LambdaFunction) lambda).parameters;
            if (params.size() > 1)
            {
                Variable p = params.get(0);
                updateVariableType(p, cc.newGenericType(cc.pureModel.getType(M3Paths.Relation), Lists.fixedSize.of(newGenericType)));
                p.multiplicity = new org.finos.legend.engine.protocol.pure.m3.multiplicity.Multiplicity(1, 1);
                Variable f = params.get(1);
                updateVariableType(f, cc.newGenericType(cc.pureModel.getType("meta::pure::functions::relation::_Window"), Lists.mutable.with(cc.pureModel.getGenericType(cc.pureModel.getType(M3Paths.Any)))));
                f.multiplicity = new org.finos.legend.engine.protocol.pure.m3.multiplicity.Multiplicity(1, 1);
            }
            Variable variable = params.get(params.size() - 1);
            updateVariableType(variable, newGenericType);
            variable.multiplicity = m;
        }
    }

    private static void updateVariableType(Variable variable, GenericType newGenericType)
    {
        variable.genericType = CompileContext.convertGenericType(newGenericType);
    }


    private static void updateLambdaCollection(List<org.finos.legend.engine.protocol.pure.m3.valuespecification.ValueSpecification> parameters, GenericType gt, org.finos.legend.engine.protocol.pure.m3.multiplicity.Multiplicity mul, int offset, CompileContext cc)
    {
        toCollection(parameters.get(offset)).values.forEach(l -> updateSimpleLambda(l, gt, mul, cc));
    }

    private static void updateLambdaWithCol(GenericType gt2, org.finos.legend.engine.protocol.pure.m3.valuespecification.ValueSpecification l, CompileContext cc)
    {
        if (l instanceof AppliedFunction)
        {
            updateSimpleLambda(((AppliedFunction) l).parameters.get(0), gt2, new org.finos.legend.engine.protocol.pure.m3.multiplicity.Multiplicity(1, 1), cc);
        }
    }

    private static void updateTDSRowLambda(List<Variable> vars)
    {
        Variable variable = vars.get(0);
        variable.genericType = new org.finos.legend.engine.protocol.pure.m3.type.generics.GenericType(new PackageableType("meta::pure::tds::TDSRow"));
        variable.multiplicity = new org.finos.legend.engine.protocol.pure.m3.multiplicity.Multiplicity(1, 1);
        Variable variable2 = vars.get(1);
        variable2.genericType = new org.finos.legend.engine.protocol.pure.m3.type.generics.GenericType(new PackageableType("meta::pure::tds::TDSRow"));
        variable2.multiplicity = new org.finos.legend.engine.protocol.pure.m3.multiplicity.Multiplicity(1, 1);
    }

    public static void aggInference(Object obj, GenericType gt, int mapOffset, int aggOffset, ValueSpecificationBuilder valueSpecificationBuilder)
    {
        LambdaFunction aggFirstLambda = null;
        LambdaFunction aggSecondLambda = null;
        obj = obj instanceof ClassInstance ? ((ClassInstance) obj).value : obj;
        if (obj instanceof AppliedFunction)
        {
            aggFirstLambda = ((LambdaFunction) ((AppliedFunction) obj).parameters.get(mapOffset));
            aggSecondLambda = ((LambdaFunction) ((AppliedFunction) obj).parameters.get(aggOffset));
        }
        else if (obj instanceof AggregateValue)
        {
            aggFirstLambda = ((AggregateValue) obj).mapFn;
            aggSecondLambda = ((AggregateValue) obj).aggregateFn;
        }
        else if (obj instanceof TDSAggregateValue)
        {
            aggFirstLambda = ((TDSAggregateValue) obj).mapFn;
            aggSecondLambda = ((TDSAggregateValue) obj).aggregateFn;
        }
        if (aggFirstLambda != null && aggSecondLambda != null)
        {
            CompileContext cc = valueSpecificationBuilder.getContext();
            updateSimpleLambda(aggFirstLambda, gt, org.finos.legend.engine.protocol.pure.m3.multiplicity.Multiplicity.PURE_ONE, cc);
            ValueSpecification processLambda = aggFirstLambda.accept(valueSpecificationBuilder);
            updateSimpleLambda(aggSecondLambda, funcReturnType(processLambda, cc.pureModel), new org.finos.legend.engine.protocol.pure.m3.multiplicity.Multiplicity(), cc);
        }
    }

    private static void aggInferenceAll(List<org.finos.legend.engine.protocol.pure.m3.valuespecification.ValueSpecification> parameters, GenericType gt, int mapOffset, int aggOffset, ValueSpecificationBuilder valueSpecificationBuilder)
    {
        if (parameters.get(2) instanceof Collection)
        {
            ((Collection) parameters.get(2)).values.forEach(a -> aggInference(a, gt, mapOffset, aggOffset, valueSpecificationBuilder));
        }
        else
        {
            aggInference(parameters.get(2), gt, mapOffset, aggOffset, valueSpecificationBuilder);
        }
    }

    public static final ParametersInference ExtendInference = (parameters, valueSpecificationBuilder) ->
    {
        CompileContext cc = valueSpecificationBuilder.getContext();
        ValueSpecification firstProcessedParameter = parameters.get(0).accept(valueSpecificationBuilder);
        GenericType gt = firstProcessedParameter._genericType();
        MutableList<ValueSpecification> processedParameters;
        if (parameters.size() == 3) // This implies that we're using over() with a window
        {
            if (parameters.get(1) instanceof AppliedFunction)// && ((AppliedFunction) parameters.get(1)).function.endsWith("over"))
            {
                AppliedFunction windowFunction = (AppliedFunction) parameters.get(1);
                windowFunction.parameters.stream().filter(x ->
                {
                    if (!(x instanceof AppliedFunction))
                    {
                        return true;
                    }
                    AppliedFunction appliedFunction = (AppliedFunction) x;
                    ImmutableSet<String> frameFunctions = Sets.immutable.with("meta::pure::functions::relation::rows", "rows", "meta::pure::functions::relation::_range", "_range");
                    return !frameFunctions.contains(appliedFunction.function);
                }).forEach(x ->
                {
                    processColumn(x, gt, cc);
                    processSort(x, gt, cc, valueSpecificationBuilder, cc.pureModel.getExecutionSupport().getProcessorSupport());
                });
            }
            ValueSpecification secondProcessedParameter = parameters.get(1).accept(valueSpecificationBuilder);
            processedParameters = Lists.mutable.with(firstProcessedParameter, secondProcessedParameter);
        }
        else
        {
            processedParameters = Lists.mutable.with(firstProcessedParameter);
        }
        if (taxoMap.get("cov_relation_Relation").contains(gt._rawType().getName()))
        {
            Object funcCol = ((ClassInstance) parameters.get(parameters.size() - 1)).value;

            if (funcCol instanceof ColSpecArray)
            {
                ((ColSpecArray) funcCol).colSpecs.forEach(col ->
                {
                    if (col.function2 == null)
                    {
                        updateSimpleLambda(col.function1, gt._typeArguments().getFirst(), new org.finos.legend.engine.protocol.pure.m3.multiplicity.Multiplicity(1, 1), cc);
                    }
                    else
                    {
                        processSingleAggColSpec(col, firstProcessedParameter, valueSpecificationBuilder);
                    }
                });
            }
            else if (funcCol instanceof ColSpec)
            {
                if (((ColSpec) funcCol).function2 == null)
                {
                    updateSimpleLambda(((ColSpec) funcCol).function1, gt._typeArguments().getFirst(), new org.finos.legend.engine.protocol.pure.m3.multiplicity.Multiplicity(1, 1), cc);
                }
                else
                {
                    processSingleAggColSpec((ColSpec) funcCol, processedParameters.get(0), valueSpecificationBuilder);
                }
            }
            else
            {
                throw new RuntimeException("Not supported " + funcCol.getClass());
            }
            return Lists.mutable.withAll(processedParameters).with(parameters.get(parameters.size() - 1).accept(valueSpecificationBuilder));
        }
        else
        {
            toCollection(parameters.get(1)).values.forEach(l -> updateLambdaWithCol(cc.pureModel.getGenericType("meta::pure::tds::TDSRow"), l, cc));
            List<ValueSpecification> results = Lists.mutable.with(firstProcessedParameter);
            parameters.stream().skip(1).map(p -> p.accept(valueSpecificationBuilder)).forEach(results::add);
            return results;
        }
    };

    public static TypeAndMultiplicity GroupByReturnInference(List<ValueSpecification> ps, PureModel pureModel)
    {
        MutableList<RelationType<?>> types = Lists.mutable.of((RelationType<?>) ps.get(1)._genericType()._typeArguments().getLast()._rawType());
        if (ps.size() == 3)
        {
            types.add((RelationType<?>) ps.get(2)._genericType()._typeArguments().getLast()._rawType());
        }
        return getTypeAndMultiplicity(types, pureModel);
    }

    public static TypeAndMultiplicity PivotReturnInference(List<ValueSpecification> ps, PureModel pureModel)
    {
        ProcessorSupport processorSupport = pureModel.getExecutionSupport().getProcessorSupport();
        try
        {
            RelationType<?> relType = _RelationType.build(
                    Lists.mutable.empty(),
                    null,
                    processorSupport
            );
            return res(
                    new Root_meta_pure_metamodel_type_generics_GenericType_Impl("", null, pureModel.getClass(M3Paths.GenericType))
                            ._rawType(pureModel.getType(M3Paths.Relation))
                            ._typeArguments(Lists.fixedSize.of(new Root_meta_pure_metamodel_type_generics_GenericType_Impl("", null, pureModel.getClass(M3Paths.GenericType))._rawType(relType))),
                    "one",
                    pureModel
            );
        }
        catch (PureCompilationException e)
        {
            throw new EngineException(e.getInfo(), null, EngineErrorType.COMPILATION);
        }
    }

    public static TypeAndMultiplicity ProjectReturnInference(List<ValueSpecification> ps, PureModel pureModel)
    {
        return getTypeAndMultiplicity(Lists.mutable.with((RelationType<?>) ps.get(1)._genericType()._typeArguments().getLast()._rawType()), pureModel);
    }

    private Multiplicity flattenMultiplicity(Multiplicity m, PureModel pm)
    {
        return (m._lowerBound()._value() >= 1) ? pm.getMultiplicity("one") : pm.getMultiplicity("zeroone");
    }
        
    // Graph projections of the form $x.firm.employees have 1..Many or 0..Many multiplicity but project is supposed to 
    // return a flattened, exploded relation. Hence, we have a special inference to override inferred multiplicity from 
    // the passed FuncColSpec.
    public TypeAndMultiplicity GraphProjectReturnInference(List<ValueSpecification> ps, PureModel pureModel)
    {
        RelationType<?> relTypeFromColSpec = (RelationType<?>) ps.get(1)._genericType()._typeArguments().getLast()._rawType();
        ProcessorSupport processorSupport = pureModel.getExecutionSupport().getProcessorSupport();
        try
        {
            RelationType<?> newRelType = _RelationType.build(
                    Lists.mutable
                        .withAll(relTypeFromColSpec._columns())
                        .collect(c -> _Column.getColumnInstance(c._name(), false, _Column.getColumnType(c), flattenMultiplicity(_Column.getColumnMultiplicity(c), pureModel), null, processorSupport)),
                    null,
                    processorSupport
            );
            return res(
                    new Root_meta_pure_metamodel_type_generics_GenericType_Impl("", null, pureModel.getClass(M3Paths.GenericType))
                            ._rawType(pureModel.getType(M3Paths.Relation))
                            ._typeArguments(Lists.fixedSize.of(new Root_meta_pure_metamodel_type_generics_GenericType_Impl("", null, pureModel.getClass(M3Paths.GenericType))._rawType(newRelType))),
                    "one",
                    pureModel
            );
        }
        catch (PureCompilationException e)
        {
            throw new EngineException(e.getInfo(), null, EngineErrorType.COMPILATION);
        }
    }

    private static TypeAndMultiplicity getTypeAndMultiplicity(MutableList<RelationType<?>> types, PureModel pureModel)
    {
        ProcessorSupport processorSupport = pureModel.getExecutionSupport().getProcessorSupport();
        try
        {
            RelationType<?> relType =
                    _RelationType.build(
                            types.flatCollect(RelationTypeAccessor::_columns)
                                    .collect(c -> _Column.getColumnInstance(c._name(), false, _Column.getColumnType(c), _Column.getColumnMultiplicity(c), null, processorSupport)),
                            null,
                            processorSupport
                    );


            return res(
                    new Root_meta_pure_metamodel_type_generics_GenericType_Impl("", null, pureModel.getClass(M3Paths.GenericType))
                            ._rawType(pureModel.getType(M3Paths.Relation))
                            ._typeArguments(Lists.fixedSize.of(new Root_meta_pure_metamodel_type_generics_GenericType_Impl("", null, pureModel.getClass(M3Paths.GenericType))._rawType(relType))),
                    "one",
                    pureModel
            );
        }
        catch (PureCompilationException e)
        {
            throw new EngineException(e.getMessage().replace("Compilation error at ??, ", ""), null, EngineErrorType.COMPILATION);
        }
    }

    public static TypeAndMultiplicity JoinReturnInference(List<ValueSpecification> ps, PureModel pureModel)
    {
        return getTypeAndMultiplicity(
                Lists.mutable.with(
                        (RelationType<?>) ps.get(0)._genericType()._typeArguments().getLast()._rawType(),
                        (RelationType<?>) ps.get(1)._genericType()._typeArguments().getLast()._rawType()
                ),
                pureModel
        );
    }

    public static TypeAndMultiplicity ExtendReturnInference(List<ValueSpecification> ps, PureModel pureModel)
    {
        ProcessorSupport processorSupport = pureModel.getExecutionSupport().getProcessorSupport();
        try
        {
            RelationType<?> relType = _RelationType.build(
                    Lists.mutable
                            .withAll((RichIterable<Column<?, ?>>) ((RelationType<?>) ps.get(0)._genericType()._typeArguments().getFirst()._rawType())._columns())
                            .withAll((RichIterable<Column<?, ?>>) ((RelationType<?>) ps.get(ps.size() - 1)._genericType()._typeArguments().getLast()._rawType())._columns())
                            .collect(c -> (CoreInstance) _Column.getColumnInstance(c._name(), false, _Column.getColumnType(c), _Column.getColumnMultiplicity(c), null, processorSupport)),
                    null,
                    processorSupport
            );
            return res(
                    new Root_meta_pure_metamodel_type_generics_GenericType_Impl("", null, pureModel.getClass(M3Paths.GenericType))
                            ._rawType(pureModel.getType(M3Paths.Relation))
                            ._typeArguments(Lists.fixedSize.of(new Root_meta_pure_metamodel_type_generics_GenericType_Impl("", null, pureModel.getClass(M3Paths.GenericType))._rawType(relType))),
                    "one",
                    pureModel
            );
        }
        catch (PureCompilationException e)
        {
            throw new EngineException(e.getInfo(), null, EngineErrorType.COMPILATION);
        }

    }

    public static TypeAndMultiplicity OverReturnInference(List<ValueSpecification> ps, PureModel pureModel)
    {

        return res(
                new Root_meta_pure_metamodel_type_generics_GenericType_Impl("", null, pureModel.getClass(M3Paths.GenericType))
                        ._rawType(pureModel.getType("meta::pure::functions::relation::_Window"))
                        // Should eventually write the right type...
                        ._typeArguments(Lists.mutable.with(pureModel.getGenericType(pureModel.getType("meta::pure::metamodel::type::Any")))),
                "one",
                pureModel
        );
    }


    public static final ParametersInference LambdaCollectionInference = (parameters, valueSpecificationBuilder) ->
    {
        ValueSpecification firstProcessedParameter = parameters.get(0).accept(valueSpecificationBuilder);
        updateLambdaCollection(parameters, firstProcessedParameter._genericType(), new org.finos.legend.engine.protocol.pure.m3.multiplicity.Multiplicity(1, 1), 1, valueSpecificationBuilder.getContext());
        return Stream.concat(Stream.of(firstProcessedParameter), parameters.stream().skip(1).map(p -> p.accept(valueSpecificationBuilder))).collect(Collectors.toList());
    };

    public static final ParametersInference TDSContainsInference = (parameters, valueSpecificationBuilder) ->
    {
        ValueSpecification firstProcessedParameter = parameters.get(0).accept(valueSpecificationBuilder);
        updateLambdaCollection(parameters, firstProcessedParameter._genericType(), new org.finos.legend.engine.protocol.pure.m3.multiplicity.Multiplicity(1, 1), 1, valueSpecificationBuilder.getContext());
        updateTDSRowLambda(((LambdaFunction) parameters.get(4)).parameters);
        return Stream.concat(Stream.of(firstProcessedParameter), parameters.stream().skip(1).map(p -> p.accept(valueSpecificationBuilder))).collect(Collectors.toList());
    };

    public static final ParametersInference EvalInference = (parameters, valueSpecificationBuilder) ->
    {
        ValueSpecification secondProcessedParameter = parameters.get(1).accept(valueSpecificationBuilder);
        updateLambdaCollection(parameters, secondProcessedParameter._genericType(), new org.finos.legend.engine.protocol.pure.m3.multiplicity.Multiplicity(1, 1), 0, valueSpecificationBuilder.getContext());
        ValueSpecification firstProcessedParameter = parameters.get(0).accept(valueSpecificationBuilder);
        return Stream.concat(Stream.of(firstProcessedParameter, secondProcessedParameter), parameters.stream().skip(2).map(p -> p.accept(valueSpecificationBuilder))).collect(Collectors.toList());
    };

    public static final ParametersInference EvalInference2 = (parameters, valueSpecificationBuilder) ->
    {
        ValueSpecification secondProcessedParameter = parameters.get(1).accept(valueSpecificationBuilder);
        ValueSpecification thirdProcessedParameter = parameters.get(2).accept(valueSpecificationBuilder);
        updateTwoParamsLambdaDiffTypes(parameters.get(0), secondProcessedParameter._genericType(), thirdProcessedParameter._genericType(), new org.finos.legend.engine.protocol.pure.m3.multiplicity.Multiplicity(1, 1), new org.finos.legend.engine.protocol.pure.m3.multiplicity.Multiplicity(1, 1));
        ValueSpecification firstProcessedParameter = parameters.get(0).accept(valueSpecificationBuilder);
        return Stream.concat(Stream.of(firstProcessedParameter, secondProcessedParameter, thirdProcessedParameter), parameters.stream().skip(3).map(p -> p.accept(valueSpecificationBuilder))).collect(Collectors.toList());
    };

    public static final ParametersInference EvalColInference = (parameters, valueSpecificationBuilder) ->
    {
        ValueSpecification vs = parameters.get(1).accept(valueSpecificationBuilder);
        RelationType<?> type = (RelationType<?>) vs._genericType()._rawType();

        ColSpec colSpec = (ColSpec) ((ClassInstance) parameters.get(0)).value;
        Column<?, ?> found = findColumn(type, colSpec, valueSpecificationBuilder.getContext().pureModel.getExecutionSupport().getProcessorSupport());
        colSpec.genericType = CompileContext.convertGenericType(_Column.getColumnType(found));
        colSpec.multiplicity = CompileContext.convertMultiplicity(_Column.getColumnMultiplicity(found));

        return Lists.mutable.with(
                parameters.get(0).accept(valueSpecificationBuilder),
                vs
        );
    };

    public static final ParametersInference RenameColInference = (parameters, valueSpecificationBuilder) ->
    {
        CompileContext cc = valueSpecificationBuilder.getContext();
        ProcessorSupport ps = cc.pureModel.getExecutionSupport().getProcessorSupport();

        ValueSpecification vs = parameters.get(0).accept(valueSpecificationBuilder);
        RelationType<?> type = (RelationType<?>) vs._genericType()._typeArguments().getFirst()._rawType();

        ColSpec firstCol = (ColSpec) ((ClassInstance) parameters.get(1)).value;
        ColSpec secondCol = (ColSpec) ((ClassInstance) parameters.get(2)).value;

        org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.relation.Column<?, ?> foundColumn = findColumn(type, firstCol, ps);

        return Lists.mutable.with(
                vs,
                wrapInstanceValue(buildColSpec(foundColumn, cc.pureModel, ps), cc.pureModel),
                wrapInstanceValue(buildColSpec(secondCol.name, _Column.getColumnType(foundColumn), _Column.getColumnMultiplicity(foundColumn), cc.pureModel, ps), cc.pureModel)
        );
    };

    public static final ParametersInference SelectColInference = (parameters, valueSpecificationBuilder) ->
            processRelationAndColSpecParams(parameters, valueSpecificationBuilder, null);

    public static InstanceValue wrapInstanceValue(Any val, PureModel pureModel)
    {
        return wrapInstanceValue(Lists.mutable.with(val), val._classifierGenericType(), "one", pureModel);
    }

    public static InstanceValue wrapInstanceValue(MutableList<? extends Any> values, GenericType genericType, String multiplicity, PureModel pureModel)
    {
        return new Root_meta_pure_metamodel_valuespecification_InstanceValue_Impl("", null, pureModel.getClass(M3Paths.InstanceValue))
                ._multiplicity(pureModel.getMultiplicity(multiplicity))
                ._genericType(genericType)
                ._values(values);
    }

    public static org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.relation.ColSpec<?> buildColSpec(String name, GenericType colType, Multiplicity multiplicity, PureModel pureModel, ProcessorSupport ps)
    {
        GenericType firstGenericType = new Root_meta_pure_metamodel_type_generics_GenericType_Impl("", null, pureModel.getClass(M3Paths.GenericType))
                ._rawType(
                        _RelationType.build(
                                Lists.mutable.with(_Column.getColumnInstance(name, false, colType, multiplicity, null, ps)),
                                null,
                                ps
                        )
                );

        return new Root_meta_pure_metamodel_relation_ColSpec_Impl<>("", null, pureModel.getClass("meta::pure::metamodel::relation::ColSpec"))
                ._name(name)
                ._classifierGenericType(
                        new Root_meta_pure_metamodel_type_generics_GenericType_Impl("", null, pureModel.getClass(M3Paths.GenericType))
                                ._rawType(pureModel.getClass("meta::pure::metamodel::relation::ColSpec"))
                                ._typeArguments(Lists.mutable.with(firstGenericType))
                );
    }

    public static org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.relation.ColSpec<?> buildColSpec(org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.relation.Column<?, ?> col, PureModel pureModel, ProcessorSupport ps)
    {
        return buildColSpec(col._name(), _Column.getColumnType(col), _Column.getColumnMultiplicity(col), pureModel, ps);
    }

    public static final ParametersInference LambdaColCollectionInference = (parameters, valueSpecificationBuilder) ->
    {
        CompileContext cc = valueSpecificationBuilder.getContext();
        ValueSpecification firstProcessedParameter = parameters.get(0).accept(valueSpecificationBuilder);
        GenericType gt = firstProcessedParameter._genericType();
        if (parameters.get(1) instanceof ClassInstance)
        {
            final GenericType gt2 = taxoMap.get("cov_relation_Relation").contains(gt._rawType().getName()) ? gt._typeArguments().getFirst() : gt;
            ((ColSpecArray) ((ClassInstance) parameters.get(1)).value).colSpecs.forEach(col -> updateSimpleLambda(col.function1, gt2, new org.finos.legend.engine.protocol.pure.m3.multiplicity.Multiplicity(1, 1), cc));
        }
        else
        {
            final GenericType gt2 = gt._rawType()._name().equals("TabularDataSet") || gt._rawType()._name().equals("TableTDS") ? cc.pureModel.getGenericType("meta::pure::tds::TDSRow") : gt;
            toCollection(parameters.get(1)).values.forEach(l -> updateLambdaWithCol(gt2, l, cc));
        }
        return Stream.concat(Stream.of(firstProcessedParameter), parameters.stream().skip(1).map(p -> p.accept(valueSpecificationBuilder))).collect(Collectors.toList());
    };

    public static final ParametersInference SortColumnInference = (parameters, valueSpecificationBuilder) ->
    {
        CompileContext cc = valueSpecificationBuilder.getContext();
        ProcessorSupport processorSupport = cc.pureModel.getExecutionSupport().getProcessorSupport();
        ValueSpecification firstProcessedParameter = parameters.get(0).accept(valueSpecificationBuilder);
        GenericType gt = firstProcessedParameter._genericType();
        if (taxoMap.get("cov_relation_Relation").contains(gt._rawType().getName()))
        {
            processSort(parameters.get(1), gt, cc, valueSpecificationBuilder, processorSupport);
        }
        return Lists.mutable.with(firstProcessedParameter, parameters.get(1).accept(valueSpecificationBuilder));
    };

    private static void processSort(org.finos.legend.engine.protocol.pure.m3.valuespecification.ValueSpecification parameter, GenericType gt, CompileContext cc, ValueSpecificationBuilder valueSpecificationBuilder, ProcessorSupport processorSupport)
    {
        RelationType<?> type = (RelationType<?>) gt._typeArguments().getFirst()._rawType();

        if (checkColumn(parameter))
        {
            processAscendingDescending((AppliedFunction) parameter, type, cc, valueSpecificationBuilder, processorSupport);
            ColSpec column = (ColSpec) ((ClassInstance) ((AppliedFunction) parameter).parameters.get(0)).value;
            Column<?, ?> foundColumn = findColumn(type, column, processorSupport);
            column.genericType = CompileContext.convertGenericType(_Column.getColumnType(foundColumn));
            column.multiplicity = CompileContext.convertMultiplicity(_Column.getColumnMultiplicity(foundColumn));
        }
        else if (parameter instanceof Collection)
        {
            ListIterate.forEach(((Collection) parameter).values, c -> processAscendingDescending((AppliedFunction) c, type, cc, valueSpecificationBuilder, processorSupport));
        }
        else if (!(parameter instanceof ClassInstance))
        {
            try
            {
                parameter.accept(valueSpecificationBuilder);
            }
            catch (Exception e)
            {
                throw new EngineException("Can't infer the type of the function parameter within over", parameter.sourceInformation, EngineErrorType.COMPILATION);
            }
        }
    }

    private static boolean checkColumn(org.finos.legend.engine.protocol.pure.m3.valuespecification.ValueSpecification vs)
    {
        return vs instanceof AppliedFunction &&
                ((AppliedFunction) vs).parameters.size() == 1 &&
                ((AppliedFunction) vs).parameters.get(0) instanceof ClassInstance &&
                ((ClassInstance) ((AppliedFunction) vs).parameters.get(0)).value instanceof ColSpec;
    }

    private static void processAscendingDescending(AppliedFunction af, RelationType<?> type, CompileContext cc, ValueSpecificationBuilder valueSpecificationBuilder, ProcessorSupport processorSupport)
    {
        if (checkColumn(af))
        {
            ColSpec column = (ColSpec) ((ClassInstance) af.parameters.get(0)).value;
            org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.relation.Column<?, ?> foundColumn = findColumn(type, column, processorSupport);
            column.genericType = CompileContext.convertGenericType(_Column.getColumnType(foundColumn));
            column.multiplicity = CompileContext.convertMultiplicity(_Column.getColumnMultiplicity(foundColumn));
        }
        else
        {
            af.accept(valueSpecificationBuilder);
        }
    }

    private static org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.relation.Column<?, ?> findColumn(RelationType<?> type, ColSpec colSpec, ProcessorSupport processorSupport)
    {
        org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.relation.Column<?, ?> foundColumn = (type._columns().select(c -> c._name().equals(colSpec.name)).getFirst());
        if (foundColumn == null)
        {
            throw new EngineException("The column '" + colSpec.name + "' can't be found in the relation " + _RelationType.print(type, processorSupport), colSpec.sourceInformation, EngineErrorType.COMPILATION);
        }
        return foundColumn;
    }

    private static GenericType getGenericReturnTypeForEvalCol(List<ValueSpecification> ps)
    {
        return ((RelationType<?>)ps.get(0)._genericType()._typeArguments().getFirst()._rawType())._columns().getOnly()._classifierGenericType()._typeArguments().getLast();
    }

    public static final ParametersInference LambdaInference = (parameters, valueSpecificationBuilder) ->
    {
        List<ValueSpecification> firstPassProcessed = parameters.stream().map(p -> p instanceof LambdaFunction ? null : p.accept(valueSpecificationBuilder)).collect(Collectors.toList());
        updateSimpleLambda(parameters.get(1), firstPassProcessed.get(0)._genericType(), new org.finos.legend.engine.protocol.pure.m3.multiplicity.Multiplicity(1, 1), valueSpecificationBuilder.getContext());
        return ListIterate.zip(firstPassProcessed, parameters).collect(p -> p.getOne() != null ? p.getOne() : p.getTwo().accept(valueSpecificationBuilder));
    };

    public static final ParametersInference TwoParameterLambdaInference = (parameters, valueSpecificationBuilder) ->
    {
        List<ValueSpecification> firstPassProcessed = parameters.stream().map(p -> p instanceof LambdaFunction ? null : p.accept(valueSpecificationBuilder)).collect(Collectors.toList());
        updateTwoParamsLambda(parameters.get(1), firstPassProcessed.get(0)._genericType(), new org.finos.legend.engine.protocol.pure.m3.multiplicity.Multiplicity(1, 1));
        return ListIterate.zip(firstPassProcessed, parameters).collect(p -> p.getOne() != null ? p.getOne() : p.getTwo().accept(valueSpecificationBuilder));
    };

    public static final ParametersInference TwoParameterLambdaInferenceDiffTypes = (parameters, valueSpecificationBuilder) ->
    {
        List<ValueSpecification> firstPassProcessed = parameters.stream().map(p -> p instanceof LambdaFunction ? null : p.accept(valueSpecificationBuilder)).collect(Collectors.toList());

        Multiplicity mul = firstPassProcessed.get(2)._multiplicity();
        org.finos.legend.engine.protocol.pure.m3.multiplicity.Multiplicity m2 = new org.finos.legend.engine.protocol.pure.m3.multiplicity.Multiplicity();
        m2.lowerBound = mul._lowerBound()._value().intValue();
        if (mul._upperBound()._value() != null)
        {
            m2.setUpperBound(mul._upperBound()._value().intValue());
        }

        updateTwoParamsLambdaDiffTypes(parameters.get(1), firstPassProcessed.get(0)._genericType(), firstPassProcessed.get(2)._genericType(), new org.finos.legend.engine.protocol.pure.m3.multiplicity.Multiplicity(1, 1), m2);
        return ListIterate.zip(firstPassProcessed, parameters).collect(p -> p.getOne() != null ? p.getOne() : p.getTwo().accept(valueSpecificationBuilder));
    };

    public static final ParametersInference TDSFilterInference = (parameters, valueSpecificationBuilder) ->
    {
        CompileContext cc = valueSpecificationBuilder.getContext();
        ValueSpecification firstProcessedParameter = parameters.get(0).accept(valueSpecificationBuilder);
        List<ValueSpecification> result = Lists.mutable.with(firstProcessedParameter);
        GenericType gt = firstProcessedParameter._genericType();
        String gtName = gt._rawType()._name();
        if ("TabularDataSet".equals(gtName) || "TableTDS".equals(gtName))
        {
            updateSimpleLambda(parameters.get(1), cc.pureModel.getGenericType("meta::pure::tds::TDSRow"), new org.finos.legend.engine.protocol.pure.m3.multiplicity.Multiplicity(1, 1), cc);
            parameters.stream().skip(1).map(p -> p.accept(valueSpecificationBuilder)).forEach(result::add);
        }
        else if (taxoMap.get("cov_relation_Relation").contains(gt._rawType().getName()))
        {
            updateSimpleLambda(parameters.get(1), gt._typeArguments().getFirst(), new org.finos.legend.engine.protocol.pure.m3.multiplicity.Multiplicity(1, 1), cc);
            parameters.stream().skip(1).map(p -> p.accept(valueSpecificationBuilder)).forEach(result::add);
        }
        else
        {
            List<ValueSpecification> firstPassProcessed = parameters.stream().skip(1).map(p -> p instanceof LambdaFunction ? null : p.accept(valueSpecificationBuilder)).collect(Collectors.toList());
            updateSimpleLambda(parameters.get(1), parameters.size() != 0 && parameters.get(0) instanceof LambdaFunction ? firstPassProcessed.get(0)._genericType() : firstProcessedParameter._genericType(), new org.finos.legend.engine.protocol.pure.m3.multiplicity.Multiplicity(1, 1), cc);
            return LazyIterate.zip(LazyIterate.concatenate(Lists.fixedSize.of(firstProcessedParameter), firstPassProcessed), parameters).collect(p -> p.getOne() != null ? p.getOne() : p.getTwo().accept(valueSpecificationBuilder)).toList();
        }
        return result;
    };

    public static final ParametersInference AsOfJoinInference = (parameters, valueSpecificationBuilder) ->
    {
        ValueSpecification firstProcessedParameter = parameters.get(0).accept(valueSpecificationBuilder);
        ValueSpecification secondProcessedParameter = parameters.get(1).accept(valueSpecificationBuilder);
        org.finos.legend.engine.protocol.pure.m3.multiplicity.Multiplicity one = new org.finos.legend.engine.protocol.pure.m3.multiplicity.Multiplicity(1, 1);
        updateTwoParamsLambdaDiffTypes(parameters.get(2), firstProcessedParameter._genericType()._typeArguments().getFirst(), secondProcessedParameter._genericType()._typeArguments().getFirst(), one, one);
        if (parameters.size() == 4)
        {
            updateTwoParamsLambdaDiffTypes(parameters.get(3), firstProcessedParameter._genericType()._typeArguments().getFirst(), secondProcessedParameter._genericType()._typeArguments().getFirst(), one, one);
        }
        MutableList<ValueSpecification> base = Lists.mutable.with(firstProcessedParameter).with(secondProcessedParameter).with(parameters.get(2).accept(valueSpecificationBuilder));
        return parameters.size() == 4 ? base.with(parameters.get(3).accept(valueSpecificationBuilder)) : base;
    };


    public static final ParametersInference JoinInference = (parameters, valueSpecificationBuilder) ->
    {
        ValueSpecification firstProcessedParameter = parameters.get(0).accept(valueSpecificationBuilder);
        MutableList<ValueSpecification> result = Lists.mutable.with(firstProcessedParameter);
        GenericType gt = firstProcessedParameter._genericType();

        if ("TabularDataSet".equals(gt._rawType()._name()))
        {
            updateTDSRowLambda(((LambdaFunction) parameters.get(3)).parameters);
            parameters.stream().skip(1).map(p -> p.accept(valueSpecificationBuilder)).forEach(result::add);
        }
        else if (taxoMap.get("cov_relation_Relation").contains(gt._rawType().getName()))
        {
            ValueSpecification secondProcessedParameter = parameters.get(1).accept(valueSpecificationBuilder);
            org.finos.legend.engine.protocol.pure.m3.multiplicity.Multiplicity one = new org.finos.legend.engine.protocol.pure.m3.multiplicity.Multiplicity(1, 1);
            updateTwoParamsLambdaDiffTypes(parameters.get(3), firstProcessedParameter._genericType()._typeArguments().getFirst(), secondProcessedParameter._genericType()._typeArguments().getFirst(), one, one);
            result.with(secondProcessedParameter).with(parameters.get(2).accept(valueSpecificationBuilder)).with(parameters.get(3).accept(valueSpecificationBuilder));
        }
        else
        {
            parameters.stream().skip(1).map(p -> p.accept(valueSpecificationBuilder)).forEach(result::add);
        }
        return result;
    };

    public static final ParametersInference TDSAggInference = (parameters, valueSpecificationBuilder) ->
    {
        CompileContext cc = valueSpecificationBuilder.getContext();
        ValueSpecification firstProcessedParameter = parameters.get(0).accept(valueSpecificationBuilder);
        MutableList<ValueSpecification> result = Lists.mutable.with(firstProcessedParameter);
        GenericType gt = firstProcessedParameter._genericType();

        String gtName = gt._rawType()._name();
        if ("TabularDataSet".equals(gtName) || "TableTDS".equals(gtName))
        {
            aggInferenceAll(parameters, cc.pureModel.getGenericType("meta::pure::tds::TDSRow"), 1, 2, valueSpecificationBuilder);
            parameters.stream().skip(1).map(p -> p.accept(valueSpecificationBuilder)).forEach(result::add);
        }
        else if (taxoMap.get("cov_relation_Relation").contains(gt._rawType().getName()))
        {
            boolean containsGroupByCols = parameters.size() == 3;
            int aggSpecParamIndex = containsGroupByCols ? 2 : 1;
            if (containsGroupByCols)
            {
                processColumn(parameters.get(1), gt, cc);
            }

            Object aggCol = ((ClassInstance) parameters.get(aggSpecParamIndex)).value;
            if (aggCol instanceof ColSpecArray)
            {
                ((ColSpecArray) aggCol).colSpecs.forEach(c -> processSingleAggColSpec(c, firstProcessedParameter, valueSpecificationBuilder));
            }
            else if (aggCol instanceof ColSpec)
            {
                processSingleAggColSpec((ColSpec) aggCol, firstProcessedParameter, valueSpecificationBuilder);
            }
            else
            {
                throw new RuntimeException("Not supported " + aggCol.getClass());
            }
            parameters.stream().skip(1).map(p -> p.accept(valueSpecificationBuilder)).forEach(result::add);
        }
        else
        {
            parameters.stream().skip(1).map(p -> p.accept(valueSpecificationBuilder)).forEach(result::add);
        }
        return result;

    };

    public static final ParametersInference DistinctInference = (parameters, valueSpecificationBuilder) ->
    {
        ValueSpecification firstProcessedParameter = parameters.get(0).accept(valueSpecificationBuilder);
        MutableList<ValueSpecification> result = Lists.mutable.with(firstProcessedParameter);
        GenericType gt = firstProcessedParameter._genericType();

        if (taxoMap.get("cov_relation_Relation").contains(gt._rawType().getName()))
        {
            processColumn(parameters.get(1), gt, valueSpecificationBuilder.getContext());
            result.with(parameters.get(1).accept(valueSpecificationBuilder));
        }
        else
        {
            throw new RuntimeException("Not possible!");
        }
        return result;
    };

    public static TypeAndMultiplicity DistinctReturnInference(List<ValueSpecification> ps, PureModel pureModel)
    {
        ProcessorSupport processorSupport = pureModel.getExecutionSupport().getProcessorSupport();
        try
        {
            RelationType<?> relType = _RelationType.build(
                    Lists.mutable
                            .withAll((RichIterable<Column<?, ?>>) ((RelationType<?>) ps.get(1)._genericType()._typeArguments().getFirst()._rawType())._columns())
                            .collect(c -> (CoreInstance) _Column.getColumnInstance(c._name(), false, _Column.getColumnType(c), _Column.getColumnMultiplicity(c), null, processorSupport)),
                    null,
                    processorSupport
            );
            return res(
                    new Root_meta_pure_metamodel_type_generics_GenericType_Impl("", null, pureModel.getClass(M3Paths.GenericType))
                            ._rawType(pureModel.getType(M3Paths.Relation))
                            ._typeArguments(Lists.fixedSize.of(new Root_meta_pure_metamodel_type_generics_GenericType_Impl("", null, pureModel.getClass(M3Paths.GenericType))._rawType(relType))),
                    "one",
                    pureModel
            );
        }
        catch (PureCompilationException e)
        {
            throw new EngineException(e.getInfo(), null, EngineErrorType.COMPILATION);
        }

    }

    public static List<ValueSpecification> processRelationAndColSpecParams(List<org.finos.legend.engine.protocol.pure.m3.valuespecification.ValueSpecification> parameters, ValueSpecificationBuilder valueSpecificationBuilder, String columnType)
    {
        ValueSpecification vs = parameters.get(0).accept(valueSpecificationBuilder);
        RelationType<?> type = (RelationType<?>) vs._genericType()._typeArguments().getFirst()._rawType();

        Object obj = ((ClassInstance) parameters.get(1)).value;
        MutableList<ColSpec> specs = obj instanceof ColSpec ? Lists.mutable.with((ColSpec) obj) : Lists.mutable.withAll(((ColSpecArray) obj).colSpecs);

        specs.forEach(colSpec ->
        {
            Column<?, ?> found = findColumn(type, colSpec, valueSpecificationBuilder.getContext().pureModel.getExecutionSupport().getProcessorSupport());
            String colType = _Column.getColumnType(found)._rawType()._name();
            if (StringUtils.isNotBlank(columnType) && !taxoMap.get("cov_" + columnType).contains(colType))
            {
                throw new EngineException("The column '" + colSpec.name + "' must be of type " + columnType + ", found: " + colType, colSpec.sourceInformation, EngineErrorType.COMPILATION);
            }
            colSpec.genericType = CompileContext.convertGenericType(_Column.getColumnType(found));
            colSpec.multiplicity = CompileContext.convertMultiplicity(_Column.getColumnMultiplicity(found));
        });

        return Lists.mutable.with(
                vs,
                parameters.get(1).accept(valueSpecificationBuilder)
        );
    }

    private static void processColumn(Object parameter, GenericType gt, CompileContext cc)
    {
        if (parameter instanceof ClassInstance)
        {
            Object firstParam = ((ClassInstance) parameter).value;
            RelationType<?> relationType = (RelationType<?>) gt._typeArguments().getFirst()._rawType();
            (firstParam instanceof ColSpec ? Lists.mutable.with((ColSpec) firstParam) : Lists.mutable.withAll(((ColSpecArray) firstParam).colSpecs)).forEach(c ->
            {
                Column<?, ?> found = findColumn(relationType, c, cc.pureModel.getExecutionSupport().getProcessorSupport());
                c.genericType = CompileContext.convertGenericType(_Column.getColumnType(found));
                c.multiplicity = CompileContext.convertMultiplicity(_Column.getColumnMultiplicity(found));
            });
        }
    }

    private static void processSingleAggColSpec(ColSpec colSpec, ValueSpecification firstProcessedParameter, ValueSpecificationBuilder valueSpecificationBuilder)
    {
        CompileContext cc = valueSpecificationBuilder.getContext();
        updateSimpleLambda(colSpec.function1, firstProcessedParameter._genericType()._typeArguments().getFirst(), new org.finos.legend.engine.protocol.pure.m3.multiplicity.Multiplicity(1, 1), cc);
        FunctionDefinition<?> lambda = (FunctionDefinition<?>) ((InstanceValue) colSpec.function1.accept(valueSpecificationBuilder))._values().getFirst();
        updateSimpleLambda(colSpec.function2, lambda._expressionSequence().getLast()._genericType(), new org.finos.legend.engine.protocol.pure.m3.multiplicity.Multiplicity(0, null), cc);
    }

    public static final ParametersInference TDSOLAPInference = (parameters, valueSpecificationBuilder) ->
    {
        CompileContext cc = valueSpecificationBuilder.getContext();
        parameters.forEach(parameter ->
        {
            Object param = parameter instanceof ClassInstance ? ((ClassInstance) parameter).value : parameter;
            if (param instanceof TdsOlapRank)
            {
                updateSimpleLambda(((TdsOlapRank) param).function, cc.pureModel.getGenericType("meta::pure::metamodel::type::Any"), new org.finos.legend.engine.protocol.pure.m3.multiplicity.Multiplicity(), cc);
            }
            else if (param instanceof TdsOlapAggregation)
            {
                updateSimpleLambda(((TdsOlapAggregation) param).function, cc.pureModel.getGenericType("Number"), new org.finos.legend.engine.protocol.pure.m3.multiplicity.Multiplicity(), cc);
            }
            if (parameter instanceof LambdaFunction)
            {
                updateSimpleLambda(parameter, cc.pureModel.getGenericType("meta::pure::tds::TDSRow"), new org.finos.legend.engine.protocol.pure.m3.multiplicity.Multiplicity(), cc);
            }
        });

        return parameters.stream().map(p -> p.accept(valueSpecificationBuilder)).collect(Collectors.toList());
    };

    public static final ParametersInference OLAPFuncTDSInference = (parameters, valueSpecificationBuilder) ->
    {
        CompileContext cc = valueSpecificationBuilder.getContext();
        updateSimpleLambda(parameters.get(0), cc.pureModel.getGenericType("meta::pure::tds::TDSRow"), new org.finos.legend.engine.protocol.pure.m3.multiplicity.Multiplicity(), cc);
        return parameters.stream().map(p -> p.accept(valueSpecificationBuilder)).collect(Collectors.toList());
    };

    public static final ParametersInference OLAPFuncNumInference = (parameters, valueSpecificationBuilder) ->
    {
        CompileContext cc = valueSpecificationBuilder.getContext();
        updateSimpleLambda(parameters.get(1), cc.pureModel.getGenericType("Number"), new org.finos.legend.engine.protocol.pure.m3.multiplicity.Multiplicity(), cc);
        return parameters.stream().map(p -> p.accept(valueSpecificationBuilder)).collect(Collectors.toList());
    };

    public static final ParametersInference LambdaAndAggInference = (parameters, valueSpecificationBuilder) ->
    {
        // Main Lambda
        ValueSpecification firstProcessedParameter = parameters.get(0).accept(valueSpecificationBuilder);
        GenericType gt = firstProcessedParameter._genericType();
        updateLambdaCollection(parameters, gt, new org.finos.legend.engine.protocol.pure.m3.multiplicity.Multiplicity(1, 1), 1, valueSpecificationBuilder.getContext());
        aggInferenceAll(parameters, gt, 0, 1, valueSpecificationBuilder);
        return Stream.concat(Stream.of(firstProcessedParameter), parameters.stream().skip(1).map(p -> p.accept(valueSpecificationBuilder))).collect(Collectors.toList());
    };

    private final Map<String, FunctionExpressionBuilder> map = UnifiedMap.newMap();
    private final Map<String, Dispatch> dispatchMap;
    private final PureModel pureModel;
    private static final String Nil = "Nil";

    /**
     * NOTE: we only need to pass in the model and not compile context, because the handler's sole job is to register function
     * handlers, it should not be context-aware. We may revise that decision but to assert that fact, we will pass just the Pure
     * model here instead
     */
    public Handlers(PureModel pureModel)
    {
        this.pureModel = pureModel;
        this.dispatchMap = buildDispatch();

        registerAdditionalSubtypes(pureModel.getContext().getCompilerExtensions());

        registerMathBitwise();
        registerMathInequalities();
        registerMaxMin();
        registerAlgebra();
        registerOlapMath();
        registerAggregations();
        registerStdDeviations();
        registerVariance();
        registerCovariance();
        registerTrigo();
        registerStrings();
        registerDates();
        registerTDS();
        registerJson();
        registerRuntimeHelper();
        registerAsserts();
        registerUnitFunctions();
        registerCalendarFunctions();

        register(grp(LambdaInference, h("meta::pure::functions::collection::sortBy_T_m__Function_$0_1$__T_m_", false, ps -> res(ps.get(0)._genericType(), ps.get(0)._multiplicity()), ps -> true)));

        register(grp(LambdaInference,
                // meta::pure::functions::collection::map<T,V|m>(value:T[m], func:Function<{T[1]->V[1]}>[1]):V[m];
                h("meta::pure::functions::collection::map_T_m__Function_1__V_m_", true, ps -> res(funcReturnType(ps.get(1)), ps.get(0)._multiplicity()), ps -> isOne(funcType(ps.get(1)._genericType())._returnMultiplicity())),
                // meta::pure::functions::collection::map<T,V>(value:T[0..1], func:Function<{T[1]->V[0..1]}>[1]):V[0..1];
                h("meta::pure::functions::collection::map_T_$0_1$__Function_1__V_$0_1$_", true, ps -> res(funcReturnType(ps.get(1)), "zeroOne"), ps -> matchZeroOne(ps.get(0)._multiplicity()) && matchZeroOne(funcType(ps.get(1)._genericType())._returnMultiplicity())),
                // meta::pure::functions::collection::map<T,V>(value:T[*], func:Function<{T[1]->V[*]}>[1]):V[*];
                h("meta::pure::functions::collection::map_T_MANY__Function_1__V_MANY_", true, ps -> res(funcReturnType(ps.get(1)), "zeroMany"), ps -> true)));

        register(m(
                        grp(TDSFilterInference,
                                // meta::pure::functions::relation::filter<T>(rel:Relation<T>[1], f:Function<{T[1]->Boolean[1]}>[1]):Relation<T>[1]
                                h("meta::pure::functions::relation::filter_Relation_1__Function_1__Relation_1_", true, ps -> res(ps.get(0)._genericType(), "one"), ps -> typeOne(ps.get(0), taxoMap.get("cov_relation_Relation"))),
                                // meta::pure::tds::filter(tds:TabularDataSet[1], f:Function<{TDSRow[1]->Boolean[1]}>[1]):TabularDataSet[1]
                                h("meta::pure::tds::filter_TabularDataSet_1__Function_1__TabularDataSet_1_", false, ps -> res("meta::pure::tds::TabularDataSet", "one"), ps -> typeOne(ps.get(0), "TabularDataSet"))
                        ),
                        // meta::pure::functions::collection::filter<T>(value:T[*], func:Function<{T[1]->Boolean[1]}>[1]):T[*];
                        grp(LambdaInference, h("meta::pure::functions::collection::filter_T_MANY__Function_1__T_MANY_", true, ps -> res(ps.get(0)._genericType(), "zeroMany"), ps -> true))
                )
        );

        register(m(
                        // meta::pure::tds::project<T>(set:T[*], paths:Path<T,Any|*>[*]):TabularDataSet[1]
                        m(h("meta::pure::tds::project_T_MANY__Path_MANY__TabularDataSet_1_", false, ps -> res("meta::pure::tds::TabularDataSet", "one"), ps -> ps.size() == 2 && typeMany(ps.get(1), "Path"))),
                        //meta::pure::functions::relation::project<C,T>(cl:C[*], x:FuncColSpecArray<{C[1]->Any[*]},T>[1]):Relation<T>[1];
                        // meta::pure::tds::project<K>(set:K[*], functions:Function<{K[1]->Any[*]}>[*], ids:String[*]):TabularDataSet[1]
                        grp(LambdaCollectionInference, h("meta::pure::tds::project_K_MANY__Function_MANY__String_MANY__TabularDataSet_1_", false, ps -> res("meta::pure::tds::TabularDataSet", "one"), ps -> ps.size() == 3)),
                        grp(LambdaColCollectionInference,
                                //meta::pure::tds::project(tds:TabularDataSet[1], columnFunctions:ColumnSpecification<TDSRow>[*]):TabularDataSet[1]
                                h("meta::pure::tds::project_TabularDataSet_1__ColumnSpecification_MANY__TabularDataSet_1_", false, ps -> res("meta::pure::tds::TabularDataSet", "one"), ps -> typeOne(ps.get(0), "TabularDataSet")),
                                // meta::pure::tds::project<T>(set:T[*], columnSpecifications:ColumnSpecification<T>[*]):TabularDataSet[1]
                                h("meta::pure::tds::project_T_MANY__ColumnSpecification_MANY__TabularDataSet_1_", false, ps -> res("meta::pure::tds::TabularDataSet", "one"), ps -> true),
                                h("meta::pure::functions::relation::project_Relation_1__FuncColSpecArray_1__Relation_1_", true, ps -> ProjectReturnInference(ps, this.pureModel), ps -> true),
                                //meta::pure::functions::relation::project<C,T>(cl:C[*], x:FuncColSpecArray<{C[1]->Any[*]},T>[1]):Relation<T>[1];
                                h("meta::pure::functions::relation::project_C_MANY__FuncColSpecArray_1__Relation_1_", true, ps -> GraphProjectReturnInference(ps, this.pureModel), ps -> true)
                        )
                )
        );

        register(m(
                        // meta::pure::tds::groupBy<T,U>(tds:TabularDataSet[1], columns:String[*], aggValues:meta::pure::tds::AggregateValue<T,U>[*]):TabularDataSet[1]
                        grp(TDSAggInference,
                                h("meta::pure::tds::groupBy_TabularDataSet_1__String_MANY__AggregateValue_MANY__TabularDataSet_1_", false, ps -> res("meta::pure::tds::TabularDataSet", "one"), ps -> typeOne(ps.get(0), "TabularDataSet")),
                                h("meta::pure::functions::relation::groupBy_Relation_1__ColSpecArray_1__AggColSpec_1__Relation_1_", true, ps -> GroupByReturnInference(ps, this.pureModel), ps -> true),
                                h("meta::pure::functions::relation::groupBy_Relation_1__ColSpecArray_1__AggColSpecArray_1__Relation_1_", true, ps -> GroupByReturnInference(ps, this.pureModel), ps -> true),
                                h("meta::pure::functions::relation::groupBy_Relation_1__ColSpec_1__AggColSpecArray_1__Relation_1_", true, ps -> GroupByReturnInference(ps, this.pureModel), ps -> true),
                                h("meta::pure::functions::relation::groupBy_Relation_1__ColSpec_1__AggColSpec_1__Relation_1_", true, ps -> GroupByReturnInference(ps, this.pureModel), ps -> true)
                        ),
                        // meta::pure::functions::collection::groupBy<K,V,U>(set:K[*], functions:meta::pure::metamodel::function::Function<{K[1]->Any[*]}>[*], aggValues:meta::pure::functions::collection::AggregateValue<K,V,U>[*], ids:String[*]):TabularDataSet[1]
                        grp(LambdaAndAggInference, h("meta::pure::tds::groupBy_K_MANY__Function_MANY__AggregateValue_MANY__String_MANY__TabularDataSet_1_", false, ps -> res("meta::pure::tds::TabularDataSet", "one"), ps -> true))
                )
        );

        register(
                grp(TDSAggInference,
                    h("meta::pure::functions::relation::aggregate_Relation_1__AggColSpec_1__Relation_1_", true, ps -> GroupByReturnInference(ps, this.pureModel), ps -> true),
                    h("meta::pure::functions::relation::aggregate_Relation_1__AggColSpecArray_1__Relation_1_", true, ps -> GroupByReturnInference(ps, this.pureModel), ps -> true)
                )
        );

        register(
                grp(TDSAggInference,
                        h("meta::pure::functions::relation::pivot_Relation_1__ColSpecArray_1__AggColSpec_1__Relation_1_", true, ps -> PivotReturnInference(ps, this.pureModel), ps -> true),
                        h("meta::pure::functions::relation::pivot_Relation_1__ColSpecArray_1__AggColSpecArray_1__Relation_1_", true, ps -> PivotReturnInference(ps, this.pureModel), ps -> true),
                        h("meta::pure::functions::relation::pivot_Relation_1__ColSpec_1__AggColSpecArray_1__Relation_1_", true, ps -> PivotReturnInference(ps, this.pureModel), ps -> true),
                        h("meta::pure::functions::relation::pivot_Relation_1__ColSpec_1__AggColSpec_1__Relation_1_", true, ps -> PivotReturnInference(ps, this.pureModel), ps -> true)
                )
        );

        // meta::pure::tds::extend(tds:TabularDataSet[1], newColumnFunctions:BasicColumnSpecification<TDSRow >[*]):TabularDataSet[1]
        register(m(
                        grp(ExtendInference,
                                h("meta::pure::functions::relation::extend_Relation_1__FuncColSpec_1__Relation_1_", true, ps -> ExtendReturnInference(ps, this.pureModel), ps -> true),
                                h("meta::pure::functions::relation::extend_Relation_1__FuncColSpecArray_1__Relation_1_", true, ps -> ExtendReturnInference(ps, this.pureModel), ps -> true),
                                h("meta::pure::functions::relation::extend_Relation_1__AggColSpec_1__Relation_1_", true, ps -> ExtendReturnInference(ps, this.pureModel), ps -> true),
                                h("meta::pure::functions::relation::extend_Relation_1__AggColSpecArray_1__Relation_1_", true, ps -> ExtendReturnInference(ps, this.pureModel), ps -> true),
                                h("meta::pure::tds::extend_TabularDataSet_1__BasicColumnSpecification_MANY__TabularDataSet_1_", false, ps -> res("meta::pure::tds::TabularDataSet", "one"), ps -> true)
                        ),
                        grp(ExtendInference,
                                h("meta::pure::functions::relation::extend_Relation_1___Window_1__AggColSpecArray_1__Relation_1_", true, ps -> ExtendReturnInference(ps, this.pureModel), ps -> true),
                                h("meta::pure::functions::relation::extend_Relation_1___Window_1__AggColSpec_1__Relation_1_", true, ps -> ExtendReturnInference(ps, this.pureModel), ps -> true),
                                h("meta::pure::functions::relation::extend_Relation_1___Window_1__FuncColSpecArray_1__Relation_1_", true, ps -> ExtendReturnInference(ps, this.pureModel), ps -> true),
                                h("meta::pure::functions::relation::extend_Relation_1___Window_1__FuncColSpec_1__Relation_1_", true, ps -> ExtendReturnInference(ps, this.pureModel), ps -> true)
                        )
                )
        );

        register(
                h("meta::pure::functions::relation::unbounded__UnboundedFrameValue_1_", false, ps -> res("meta::pure::functions::relation::UnboundedFrameValue", "one"), ps -> true)
        );

        register(m(
                h("meta::pure::functions::relation::rows_Integer_1__Integer_1__Rows_1_", false, ps -> res("meta::pure::functions::relation::Rows", "one"), ps -> ps.size() == 2 && typeOne(ps.get(0), "Integer") && typeOne(ps.get(1), "Integer")),
                h("meta::pure::functions::relation::rows_UnboundedFrameValue_1__Integer_1__Rows_1_", false, ps -> res("meta::pure::functions::relation::Rows", "one"), ps -> ps.size() == 2 && typeOne(ps.get(0), "UnboundedFrameValue") && typeOne(ps.get(1), "Integer")),
                h("meta::pure::functions::relation::rows_Integer_1__UnboundedFrameValue_1__Rows_1_", false, ps -> res("meta::pure::functions::relation::Rows", "one"), ps -> ps.size() == 2 && typeOne(ps.get(0), "Integer")),
                h("meta::pure::functions::relation::rows_UnboundedFrameValue_1__UnboundedFrameValue_1__Rows_1_", false, ps -> res("meta::pure::functions::relation::Rows", "one"), ps -> ps.size() == 2 && typeOne(ps.get(0), "UnboundedFrameValue"))
        ));

        register(m(
                        m(
                                h("meta::pure::functions::relation::_range_Number_1__Number_1___Range_1_", false, ps -> res("meta::pure::functions::relation::_Range", "one"), ps -> ps.size() == 2 && typeOne(ps.get(0), taxoMap.get("cov_Number")) && typeOne(ps.get(1), taxoMap.get("cov_Number"))),
                                h("meta::pure::functions::relation::_range_UnboundedFrameValue_1__Number_1___Range_1_", false, ps -> res("meta::pure::functions::relation::_Range", "one"), ps -> ps.size() == 2 && typeOne(ps.get(0), "UnboundedFrameValue") && typeOne(ps.get(1), taxoMap.get("cov_Number"))),
                                h("meta::pure::functions::relation::_range_Number_1__UnboundedFrameValue_1___Range_1_", false, ps -> res("meta::pure::functions::relation::_Range", "one"), ps -> ps.size() == 2 && typeOne(ps.get(0), taxoMap.get("cov_Number"))),
                                h("meta::pure::functions::relation::_range_UnboundedFrameValue_1__UnboundedFrameValue_1___Range_1_", false, ps -> res("meta::pure::functions::relation::_Range", "one"), ps -> ps.size() == 2 && typeOne(ps.get(0), "UnboundedFrameValue"))
                        ),
                        m(
                                h("meta::pure::functions::relation::_range_Integer_1__DurationUnit_1__UnboundedFrameValue_1___RangeInterval_1_", false, ps -> res("meta::pure::functions::relation::_RangeInterval", "one"), ps -> ps.size() == 3 && typeOne(ps.get(0), "Integer")),
                                h("meta::pure::functions::relation::_range_UnboundedFrameValue_1__Integer_1__DurationUnit_1___RangeInterval_1_", false, ps -> res("meta::pure::functions::relation::_RangeInterval", "one"), ps -> ps.size() == 3)
                        ),
                        m(
                                h("meta::pure::functions::relation::_range_Integer_1__DurationUnit_1__Integer_1__DurationUnit_1___RangeInterval_1_", false, ps -> res("meta::pure::functions::relation::_RangeInterval", "one"), ps -> ps.size() == 4)
                        )
        ));

        register(m(
                        m(
                                h("meta::pure::functions::relation::over_ColSpec_1___Window_1_", false, ps -> OverReturnInference(ps, this.pureModel), ps -> Lists.fixedSize.of(ps.get(0)._genericType()), ps -> true),
                                h("meta::pure::functions::relation::over_ColSpecArray_1___Window_1_", false, ps -> OverReturnInference(ps, this.pureModel), ps -> Lists.fixedSize.of(ps.get(0)._genericType()), ps -> true),
                                h("meta::pure::functions::relation::over_SortInfo_MANY___Window_1_", false, ps -> OverReturnInference(ps, this.pureModel), ps -> Lists.fixedSize.of(ps.get(0)._genericType()), ps -> true)),
                        m(
                                h("meta::pure::functions::relation::over_ColSpecArray_1__SortInfo_MANY___Window_1_", false, ps -> OverReturnInference(ps, this.pureModel), ps -> Lists.fixedSize.of(ps.get(0)._genericType()), ps -> true),
                                h("meta::pure::functions::relation::over_ColSpecArray_1__Rows_1___Window_1_", false, ps -> OverReturnInference(ps, this.pureModel), ps -> Lists.fixedSize.of(ps.get(0)._genericType()), ps -> true),
                                h("meta::pure::functions::relation::over_ColSpec_1__Rows_1___Window_1_", false, ps -> OverReturnInference(ps, this.pureModel), ps -> Lists.fixedSize.of(ps.get(0)._genericType()), ps -> true),
                                h("meta::pure::functions::relation::over_ColSpec_1__SortInfo_MANY___Window_1_", false, ps -> OverReturnInference(ps, this.pureModel), ps -> Lists.fixedSize.of(ps.get(0)._genericType()), ps -> true),
                                h("meta::pure::functions::relation::over_SortInfo_1___RangeInterval_1___Window_1_", false, ps -> OverReturnInference(ps, this.pureModel), ps -> Lists.fixedSize.of(ps.get(0)._genericType()), ps -> ps.size() == 2 && typeOne(ps.get(1), "meta::pure::functions::relation::_RangeInterval")),
                                h("meta::pure::functions::relation::over_SortInfo_1___Range_1___Window_1_", false, ps -> OverReturnInference(ps, this.pureModel), ps -> Lists.fixedSize.of(ps.get(0)._genericType()), ps -> true)),
                        m(
                                h("meta::pure::functions::relation::over_ColSpec_1__SortInfo_1___RangeInterval_1___Window_1_", false, ps -> OverReturnInference(ps, this.pureModel), ps -> Lists.fixedSize.of(ps.get(0)._genericType()), ps -> ps.size() == 3 && typeOne(ps.get(0), taxoMap.get("cov_relation_ColSpec")) && typeOne(ps.get(2), "meta::pure::functions::relation::_RangeInterval")),
                                h("meta::pure::functions::relation::over_ColSpec_1__SortInfo_1___Range_1___Window_1_", false, ps -> OverReturnInference(ps, this.pureModel), ps -> Lists.fixedSize.of(ps.get(0)._genericType()), ps -> true),
                                h("meta::pure::functions::relation::over_ColSpecArray_1__SortInfo_1___RangeInterval_1___Window_1_", false, ps -> OverReturnInference(ps, this.pureModel), ps -> Lists.fixedSize.of(ps.get(0)._genericType()), ps -> ps.size() == 3 && typeOne(ps.get(0), taxoMap.get("cov_relation_ColSpecArray")) && typeOne(ps.get(2), "meta::pure::functions::relation::_RangeInterval")),
                                h("meta::pure::functions::relation::over_ColSpecArray_1__SortInfo_1___Range_1___Window_1_", false, ps -> OverReturnInference(ps, this.pureModel), ps -> Lists.fixedSize.of(ps.get(0)._genericType()), ps -> true),
                                h("meta::pure::functions::relation::over_ColSpec_1__SortInfo_MANY__Rows_1___Window_1_", false, ps -> OverReturnInference(ps, this.pureModel), ps -> Lists.fixedSize.of(ps.get(0)._genericType()), ps -> true),
                                h("meta::pure::functions::relation::over_ColSpecArray_1__SortInfo_MANY__Rows_1___Window_1_", false, ps -> OverReturnInference(ps, this.pureModel), ps -> Lists.fixedSize.of(ps.get(0)._genericType()), ps -> true))
                )
        );

        register(grp(LambdaInference, h("meta::pure::functions::collection::exists_T_MANY__Function_1__Boolean_1_", true, ps -> res("Boolean", "one"), ps -> true)));

        register(grp(LambdaInference, h("meta::pure::functions::collection::forAll_T_MANY__Function_1__Boolean_1_", true, ps -> res("Boolean", "one"), ps -> true)));

        register(grp(LambdaAndAggInference, h("meta::pure::tds::groupByWithWindowSubset_K_MANY__Function_MANY__AggregateValue_MANY__String_MANY__String_MANY__String_MANY__TabularDataSet_1_", false, ps -> res("meta::pure::tds::TabularDataSet", "one"), ps -> true)));

        register(m(
                        // meta::pure::functions::lang::eval<T,V|m,n>(func:Function<{T[n]->V[m]}>[1], param:T[n]):V[m];
                        grp(EvalInference,
                                h("meta::pure::functions::lang::eval_Function_1__T_n__V_m_", true, ps -> res(funcReturnType(ps.get(0)), funcReturnMul(ps.get(0))))
                        ),
                        // meta::pure::functions::lang::eval<V|m>(func:Function<{->V[m]}>[1]):V[m];
                        m(h("meta::pure::functions::lang::eval_Function_1__V_m_", true, ps -> res(funcReturnType(ps.get(0)), funcReturnMul(ps.get(0))))),
                        grp(EvalInference2,
                                h("meta::pure::functions::lang::eval_Function_1__T_n__U_p__V_m_", true, ps -> res(funcReturnType(ps.get(0)), funcReturnMul(ps.get(0))))
                        )
                )
        );

        // Inference in the context of the parent
        register(m(m(h("meta::pure::tds::agg_String_1__FunctionDefinition_1__FunctionDefinition_1__AggregateValue_1_", false, ps -> res(CompileContext.newGenericType(this.pureModel.getType("meta::pure::tds::AggregateValue"), Lists.fixedSize.of(funcReturnType(ps.get(1)), funcReturnType(ps.get(2))), this.pureModel), "one"), ps -> Lists.fixedSize.of(funcReturnType(ps.get(1)), funcReturnType(ps.get(2))), ps -> ps.size() == 3 && typeOne(ps.get(0), "String"))),
                m(h("meta::pure::functions::collection::agg_FunctionDefinition_1__FunctionDefinition_1__AggregateValue_1_", false, ps -> res("meta::pure::functions::collection::AggregateValue", "one"), ps -> true))));


        register(m(m(h("meta::pure::tds::col_Function_1__String_1__String_1__BasicColumnSpecification_1_", false, ps -> res(CompileContext.newGenericType(this.pureModel.getType("meta::pure::tds::BasicColumnSpecification"), Lists.fixedSize.of(funcType(ps.get(0)._genericType())._parameters().getOnly()._genericType()), pureModel),
                        "one"), ps -> Lists.fixedSize.of(funcType(ps.get(0)._genericType())._parameters().getOnly()._genericType()), ps -> ps.size() == 3)),
                m(h("meta::pure::tds::col_Function_1__String_1__BasicColumnSpecification_1_", false, ps -> res(CompileContext.newGenericType(this.pureModel.getType("meta::pure::tds::BasicColumnSpecification"), Lists.fixedSize.of(funcType(ps.get(0)._genericType())._parameters().getOnly()._genericType()), pureModel),
                        "one"), ps -> Lists.fixedSize.of(funcType(ps.get(0)._genericType())._parameters().getOnly()._genericType()), ps -> true))));
        // ----------------------------

        register(
                m(
                        m(
                                h("meta::pure::functions::flow::coalesce_T_$0_1$__T_1__T_1_", false, ps -> res(MostCommonType.mostCommon(ListIterate.collect(ps, ValueSpecification::_genericType), this.pureModel), "one"), ps -> ps.size() == 2 && isOne(ps.get(1)._multiplicity())),
                                h("meta::pure::functions::flow::coalesce_T_$0_1$__T_$0_1$__T_$0_1$_", false, ps -> res(MostCommonType.mostCommon(ListIterate.collect(ps, ValueSpecification::_genericType), this.pureModel), "zeroOne"), ps -> ps.size() == 2)
                        ),
                        m(
                                h("meta::pure::functions::flow::coalesce_T_$0_1$__T_$0_1$__T_1__T_1_", false, ps -> res(MostCommonType.mostCommon(ListIterate.collect(ps, ValueSpecification::_genericType), this.pureModel), "one"), ps -> ps.size() == 3 && isOne(ps.get(2)._multiplicity())),
                                h("meta::pure::functions::flow::coalesce_T_$0_1$__T_$0_1$__T_$0_1$__T_$0_1$_", false, ps -> res(MostCommonType.mostCommon(ListIterate.collect(ps, ValueSpecification::_genericType), this.pureModel), "zeroOne"), ps -> ps.size() == 3)
                        ),
                        m(
                                h("meta::pure::functions::flow::coalesce_T_$0_1$__T_$0_1$__T_$0_1$__T_1__T_1_", false, ps -> res(MostCommonType.mostCommon(ListIterate.collect(ps, ValueSpecification::_genericType), this.pureModel), "one"), ps -> ps.size() == 4 && isOne(ps.get(3)._multiplicity())),
                                h("meta::pure::functions::flow::coalesce_T_$0_1$__T_$0_1$__T_$0_1$__T_$0_1$__T_$0_1$_", false, ps -> res(MostCommonType.mostCommon(ListIterate.collect(ps, ValueSpecification::_genericType), this.pureModel), "zeroOne"), ps -> ps.size() == 4)
                        )
                )
        );

        register(h("meta::pure::functions::collection::isEmpty_Any_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> matchZeroOne(ps.get(0)._multiplicity())),
                h("meta::pure::functions::collection::isEmpty_Any_MANY__Boolean_1_", true, ps -> res("Boolean", "one"), ps -> true));
        register(h("meta::pure::functions::collection::isNotEmpty_Any_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> matchZeroOne(ps.get(0)._multiplicity())),
                h("meta::pure::functions::collection::isNotEmpty_Any_MANY__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> true));

        register("meta::pure::functions::boolean::eq_Any_1__Any_1__Boolean_1_", true, ps -> res("Boolean", "one"));
        register("meta::pure::functions::boolean::equal_Any_MANY__Any_MANY__Boolean_1_", true, ps -> res("Boolean", "one"));
        register("meta::pure::functions::boolean::is_Any_1__Any_1__Boolean_1_", true, ps -> res("Boolean", "one"));
        register("meta::pure::functions::boolean::equalJsonStrings_String_1__String_1__Boolean_1_", true, ps -> res("Boolean", "one"));

        register("meta::pure::functions::constraints::warn_Boolean_1__String_1__Boolean_1_", false, ps -> res("Boolean", "one"));

        register("meta::pure::functions::lang::subType_Any_m__T_1__T_m_", false, ps -> res(ps.get(1)._genericType(), ps.get(0)._multiplicity()));
        register(h("meta::pure::functions::lang::whenSubType_Any_1__T_1__T_$0_1$_", false, ps -> res(ps.get(1)._genericType(), "zeroOne"), ps -> isOne(ps.get(0)._multiplicity())),
                h("meta::pure::functions::lang::whenSubType_Any_$0_1$__T_1__T_$0_1$_", false, ps -> res(ps.get(1)._genericType(), "zeroOne"), ps -> isZeroOne(ps.get(0)._multiplicity())),
                h("meta::pure::functions::lang::whenSubType_Any_MANY__T_1__T_MANY_", false, ps -> res(ps.get(1)._genericType(), "zeroMany"), ps -> true));
        register("meta::pure::functions::lang::orElse_T_$0_1$__T_1__T_1_", false, ps -> res(ps.get(0)._genericType(), "one"));

        register(h("meta::pure::functions::string::contains_String_1__String_1__Boolean_1_", true, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), "String") && typeOne(ps.get(1), "String")),
                h("meta::pure::functions::string::contains_String_$0_1$__String_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), "String") && typeOne(ps.get(1), "String")),
                h("meta::pure::functions::collection::contains_Any_MANY__Any_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> true));

        register("meta::pure::functions::collection::containsAny_Any_MANY__Any_MANY__Boolean_1_", false, ps -> res("Boolean", "one"));

        register("meta::pure::functions::collection::objectReferenceIn_Any_1__String_MANY__Boolean_1_", false, ps -> res("Boolean", "one"));

        register(h("meta::pure::functions::collection::in_Any_1__Any_MANY__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> isOne(ps.get(0)._multiplicity())),
                h("meta::pure::functions::collection::in_Any_$0_1$__Any_MANY__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> isZeroOne(ps.get(0)._multiplicity())));

        register(h("meta::pure::functions::boolean::xor_Boolean_1__Boolean_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 2));

        register(h("meta::pure::tds::take_TabularDataSet_1__Integer_1__TabularDataSet_1_", false, ps -> res("meta::pure::tds::TabularDataSet", "one"), ps -> typeOne(ps.get(0), "TabularDataSet")),
                h("meta::pure::functions::collection::take_T_MANY__Integer_1__T_MANY_", true, ps -> res(ps.get(0)._genericType(), "zeroMany"), ps -> true));

        register(h("meta::pure::functions::lang::if_Boolean_1__Function_1__Function_1__T_m_", true, ps -> res(MostCommonType.mostCommon(Lists.fixedSize.of(funcReturnType(ps.get(1)), funcReturnType(ps.get(2))), this.pureModel), MostCommonMultiplicity.mostCommon(Lists.fixedSize.of(funcReturnMul(ps.get(1)), funcReturnMul(ps.get(2))), this.pureModel)), ps -> Lists.immutable.of(MostCommonType.mostCommon(Lists.fixedSize.of(funcReturnType(ps.get(1)), funcReturnType(ps.get(2))), this.pureModel)), ps -> true));

        register(m(m(h("meta::pure::functions::boolean::and_Boolean_1__Boolean_1__Boolean_1_", true, ps -> res("Boolean", "one"), ps -> ps.size() == 2)),
                m(h("meta::pure::functions::collection::and_Boolean_MANY__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> true))));

        register(m(m(h("meta::pure::functions::boolean::or_Boolean_1__Boolean_1__Boolean_1_", true, ps -> res("Boolean", "one"), ps -> ps.size() == 2)),
                m(h("meta::pure::functions::collection::or_Boolean_$1_MANY$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 1 && matchOneMany(ps.get(0)._multiplicity())),
                        h("meta::pure::functions::collection::or_Boolean_MANY__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> true))));

        register("meta::pure::tds::tdsRows_TabularDataSet_1__TDSRow_MANY_", false, ps -> res("meta::pure::tds::TDSRow", "zeroMany"));
        register("meta::pure::functions::boolean::not_Boolean_1__Boolean_1_", true, ps -> res("Boolean", "one"));

        register("meta::pure::functions::boolean::isTrue_Boolean_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"));
        register("meta::pure::functions::boolean::isFalse_Boolean_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"));

        register("meta::pure::functions::relation::cumulativeDistribution_Relation_1___Window_1__T_1__Float_1_", true, ps -> res("Float", "one"));
        register("meta::pure::functions::relation::ntile_Relation_1__T_1__Integer_1__Integer_1_", true, ps -> res("Integer", "one"));
        register("meta::pure::functions::relation::percentRank_Relation_1___Window_1__T_1__Float_1_", true, ps -> res("Float", "one"));
        register("meta::pure::functions::relation::lag_Relation_1__T_1__Integer_1__T_$0_1$_", false, ps -> res(ps.get(1)._genericType(), "one"));
        register("meta::pure::functions::relation::lag_Relation_1__T_1__T_$0_1$_", false, ps -> res(ps.get(1)._genericType(), "one"));
        register("meta::pure::functions::relation::lead_Relation_1__T_1__Integer_1__T_$0_1$_", false, ps -> res(ps.get(1)._genericType(), "one"));
        register("meta::pure::functions::relation::lead_Relation_1__T_1__T_$0_1$_", false, ps -> res(ps.get(1)._genericType(), "one"));
        register("meta::pure::functions::relation::nth_Relation_1___Window_1__T_1__Integer_1__T_$0_1$_", true, ps -> res(ps.get(0)._genericType()._typeArguments().getFirst(), "zeroOne"));

        register(m(
                        h("meta::pure::functions::relation::size_Relation_1__Integer_1_", true, ps -> res("Integer", "one"), ps -> true),
                        h("meta::pure::functions::collection::size_Any_MANY__Integer_1_", true, ps -> res("Integer", "one"), ps -> true)
                )
        );

        register(h("meta::pure::functions::collection::pair_U_1__V_1__Pair_1_", false, ps -> res(CompileContext.newGenericType(this.pureModel.getType("meta::pure::functions::collection::Pair"), Lists.fixedSize.ofAll(ps.stream().map(ValueSpecificationAccessor::_genericType).collect(Collectors.toList())), this.pureModel), "one"), ps -> Lists.mutable.with(ps.get(0)._genericType(), ps.get(1)._genericType()), ps -> true));

        register(h("meta::pure::functions::multiplicity::toOne_T_MANY__T_1_", true, ps -> res(ps.get(0)._genericType(), "one"), ps -> Lists.mutable.with(ps.get(0)._genericType()), ps -> true));

        register(m(
                m(h("meta::pure::functions::string::indexOf_String_1__String_1__Integer_1_", true, ps -> res("Integer", "one"), ps -> ps.size() == 2 && typeOne(ps.get(0), "String") && typeOne(ps.get(1), "String")),
                        h("meta::pure::functions::collection::indexOf_T_MANY__T_1__Integer_1_", true, ps -> res("Integer", "one"), ps -> ps.size() == 2)),
                m(h("meta::pure::functions::string::indexOf_String_1__String_1__Integer_1__Integer_1_", true, ps -> res("Integer", "one"), p -> true))));


        register(m(m(m(h("meta::pure::functions::collection::removeDuplicates_T_MANY__T_MANY_", false, ps -> res(ps.get(0)._genericType(), "zeroMany"), ps -> ps.size() == 1)),
                        // meta::pure::functions::collection::removeDuplicates<T>(col:T[*], eql:Function<{T[1],T[1]->Boolean[1]}>[1]):T[*]
                        grp(TwoParameterLambdaInference, h("meta::pure::functions::collection::removeDuplicates_T_MANY__Function_1__T_MANY_", false, ps -> res(ps.get(0)._genericType(), "zeroMany"), p -> p.size() == 2))),
                grp((parameters, vsb) ->
                {
                    List<ValueSpecification> firstPassProcessed = parameters.stream().map(p -> p instanceof LambdaFunction ? null : p.accept(vsb)).collect(Collectors.toList());
                    updateSimpleLambda(parameters.get(1), firstPassProcessed.get(0)._genericType(), new org.finos.legend.engine.protocol.pure.m3.multiplicity.Multiplicity(1, 1), vsb.getContext());
                    updateTwoParamsLambda(parameters.get(2), firstPassProcessed.get(0)._genericType(), new org.finos.legend.engine.protocol.pure.m3.multiplicity.Multiplicity(1, 1));
                    return ListIterate.zip(firstPassProcessed, parameters).collect(p -> p.getOne() != null ? p.getOne() : p.getTwo().accept(vsb));
                }, h("meta::pure::functions::collection::removeDuplicates_T_MANY__Function_$0_1$__Function_$0_1$__T_MANY_", true, ps -> res(ps.get(0)._genericType(), "zeroMany"), p -> p.size() == 3))));

        register(h("meta::pure::tds::concatenate_TabularDataSet_1__TabularDataSet_1__TabularDataSet_1_", false, ps -> res("meta::pure::tds::TabularDataSet", "one"), ps -> "TabularDataSet".equals(ps.get(0)._genericType()._rawType()._name())),
                h("meta::pure::functions::relation::concatenate_Relation_1__Relation_1__Relation_1_", true, ps ->
                        {
                            ProcessorSupport processorSupport = this.pureModel.getExecutionSupport().getProcessorSupport();
                            GenericType firstRelationType = ps.get(0)._genericType()._typeArguments().getFirst();
                            GenericType secondRelationType = ps.get(1)._genericType()._typeArguments().getFirst();
                            if (!_RelationType.canConcatenate(firstRelationType, secondRelationType, processorSupport))
                            {
                                throw new EngineException("The two relations are incompatible and can't be concatenated " + org.finos.legend.pure.m3.navigation.generictype.GenericType.print(firstRelationType, processorSupport) + " and " + org.finos.legend.pure.m3.navigation.generictype.GenericType.print(secondRelationType, processorSupport), EngineErrorType.COMPILATION);
                            }
                            return res(
                                    new Root_meta_pure_metamodel_type_generics_GenericType_Impl("", null, this.pureModel.getClass(M3Paths.GenericType))
                                            ._rawType(this.pureModel.getType(M3Paths.Relation))
                                            ._typeArguments(Lists.fixedSize.of(_RelationType.merge(firstRelationType, secondRelationType, true, this.pureModel.getExecutionSupport().getProcessorSupport()))),
                                    "one"
                            );
                        },
                        ps -> true),
                h("meta::pure::functions::collection::concatenate_T_MANY__T_MANY__T_MANY_", true, ps -> res(ps.get(0)._genericType(), "zeroMany"), ps -> true));

        register(m(h("meta::pure::functions::collection::greatest_X_$1_MANY$__X_1_", false, ps -> res(ps.get(0)._genericType(), "one"), ps -> matchOneMany(ps.get(0)._multiplicity())),
                h("meta::pure::functions::collection::greatest_X_MANY__X_$0_1$_", false, ps -> res(ps.get(0)._genericType(), "zeroOne"), ps -> true)));

        register(m(h("meta::pure::functions::collection::least_X_$1_MANY$__X_1_", false, ps -> res(ps.get(0)._genericType(), "one"), ps -> matchOneMany(ps.get(0)._multiplicity())),
                h("meta::pure::functions::collection::least_X_MANY__X_$0_1$_", false, ps -> res(ps.get(0)._genericType(), "zeroOne"), ps -> true)));

        register(m(
                        m(h("meta::pure::functions::collection::first_T_MANY__T_$0_1$_", true, ps -> res(ps.get(0)._genericType(), "zeroOne"))),
                        m(h("meta::pure::functions::relation::first_Relation_1___Window_1__T_1__T_$0_1$_", true, ps -> res(ps.get(2)._genericType(), "zeroOne")))
                )
        );

        register(m(
                        m(h("meta::pure::functions::collection::last_T_MANY__T_$0_1$_", true, ps -> res(ps.get(0)._genericType(), "zeroOne"))),
                        m(h("meta::pure::functions::relation::last_Relation_1___Window_1__T_1__T_$0_1$_", true, ps -> res(ps.get(2)._genericType(), "zeroOne")))
                )
        );

        register("meta::pure::functions::meta::enumName_Enumeration_1__String_1_", true, ps -> res("String", "one"));
        register("meta::pure::functions::lang::extractEnumValue_Enumeration_1__String_1__T_1_", true, ps -> res(ps.get(0)._genericType()._typeArguments().getFirst(), "one"));
        register("meta::pure::functions::meta::enumValues_Enumeration_1__T_MANY_", true, ps -> res(ps.get(0)._genericType()._typeArguments().getFirst(), "zeroMany"));

        register(h("meta::pure::tds::drop_TabularDataSet_1__Integer_1__TabularDataSet_1_", false, ps -> res("meta::pure::tds::TabularDataSet", "one"), ps -> "TabularDataSet".equals(ps.get(0)._genericType()._rawType()._name())),
                h("meta::pure::functions::relation::drop_Relation_1__Integer_1__Relation_1_", true, ps -> res(ps.get(0)._genericType(), "one"), ps -> true),
                h("meta::pure::functions::collection::drop_T_MANY__Integer_1__T_MANY_", true, ps -> res(ps.get(0)._genericType(), "zeroMany"), ps -> true));

        register("meta::pure::functions::multiplicity::toOneMany_T_MANY__T_$1_MANY$_", true, ps -> res(ps.get(0)._genericType(), "oneMany"));
        register("meta::pure::functions::lang::letFunction_String_1__T_m__T_m_", true, ps -> res(ps.get(1)._genericType(), ps.get(1)._multiplicity()));
        register("meta::pure::functions::lang::new_Class_1__String_1__KeyExpression_MANY__T_1_", true, ps -> res(ps.get(0)._genericType()._typeArguments().getFirst(), "one"));
        register("meta::pure::functions::collection::count_Any_MANY__Integer_1_", false, ps -> res("Integer", "one"));

        register(m(m(h("meta::pure::functions::collection::getAll_Class_1__T_MANY_", true, ps -> res(ps.get(0)._genericType()._typeArguments().getFirst(), "zeroMany"), ps -> ps.size() == 1)),
                m(h("meta::pure::functions::collection::getAll_Class_1__Date_1__T_MANY_", true, ps -> res(ps.get(0)._genericType()._typeArguments().getFirst(), "zeroMany"), ps -> ps.size() == 2)),
                m(h("meta::pure::functions::collection::getAll_Class_1__Date_1__Date_1__T_MANY_", true, ps -> res(ps.get(0)._genericType()._typeArguments().getFirst(), "zeroMany"), ps -> ps.size() == 3))));
        register(h("meta::pure::functions::collection::getAllVersions_Class_1__T_MANY_", true, ps -> res(ps.get(0)._genericType()._typeArguments().getFirst(), "zeroMany"), ps -> ps.size() == 1));
        register("meta::pure::functions::collection::getAllVersionsInRange_Class_1__Date_1__Date_1__T_MANY_", true, ps -> res(ps.get(0)._genericType()._typeArguments().getFirst(), "zeroMany"));
        register("meta::pure::functions::collection::getAllForEachDate_Class_1__Date_MANY__T_MANY_", false, ps -> res(ps.get(0)._genericType()._typeArguments().getFirst(), "zeroMany"));

        register(m(
                        grp(DistinctInference, h("meta::pure::functions::relation::distinct_Relation_1__ColSpecArray_1__Relation_1_", true, ps -> DistinctReturnInference(ps, this.pureModel), ps -> ps.size() == 2)),
                        m(
                                h("meta::pure::functions::relation::distinct_Relation_1__Relation_1_", true, ps -> res(ps.get(0)._genericType(), "one"), ps -> typeOne(ps.get(0), taxoMap.get("cov_relation_Relation"))),
                                h("meta::pure::tds::distinct_TabularDataSet_1__TabularDataSet_1_", false, ps -> res("meta::pure::tds::TabularDataSet", "one"), ps -> "TabularDataSet".equals(ps.get(0)._genericType()._rawType()._name())),
                                h("meta::pure::functions::collection::distinct_T_MANY__T_MANY_", false, ps -> res(ps.get(0)._genericType(), "zeroMany"), ps -> true)
                        )
                )
        );

        register(m(
                        m(h("meta::pure::functions::collection::isDistinct_T_MANY__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 1)),
                        m(h("meta::pure::functions::collection::isDistinct_T_MANY__RootGraphFetchTree_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 2))
                )
        );

        register("meta::pure::functions::collection::isEqual_T_1__T_1__RootGraphFetchTree_1__Boolean_1_", false, ps -> res("Boolean", "one"));

        register(m(m(h("meta::pure::functions::collection::uniqueValueOnly_T_MANY__T_$0_1$__T_$0_1$_", false, ps -> res(ps.get(0)._genericType(), "zeroOne"), ps -> ps.size() == 2)),
                m(h("meta::pure::functions::collection::uniqueValueOnly_T_MANY__T_$0_1$_", false, ps -> res(ps.get(0)._genericType(), "zeroOne"), ps -> true))));

        register(h("meta::pure::tds::limit_TabularDataSet_1__Integer_1__TabularDataSet_1_", false, ps -> res("meta::pure::tds::TabularDataSet", "one"), ps -> typeOne(ps.get(0), "TabularDataSet") && typeOne(ps.get(1), "Integer")),
                h("meta::pure::tds::limit_TabularDataSet_1__Integer_$0_1$__TabularDataSet_1_", false, ps -> res("meta::pure::tds::TabularDataSet", "one"), ps -> typeOne(ps.get(0), "TabularDataSet") && typeZeroOne(ps.get(1), "Integer")),
                h("meta::pure::functions::relation::limit_Relation_1__Integer_1__Relation_1_", true, ps -> res(ps.get(0)._genericType(), "one"), ps -> typeOne(ps.get(0), taxoMap.get("cov_relation_Relation"))),
                h("meta::pure::functions::collection::limit_T_MANY__Integer_1__T_MANY_", false, ps -> res(ps.get(0)._genericType(), "zeroMany"), ps -> true));

        register(h("meta::pure::tds::slice_TabularDataSet_1__Integer_1__Integer_1__TabularDataSet_1_", false, ps -> res("meta::pure::tds::TabularDataSet", "one"), ps -> typeOne(ps.get(0), "TabularDataSet")),
                h("meta::pure::functions::relation::slice_Relation_1__Integer_1__Integer_1__Relation_1_", true, ps -> res(ps.get(0)._genericType(), "one"), ps -> true),
                h("meta::pure::functions::collection::slice_T_MANY__Integer_1__Integer_1__T_MANY_", true, ps -> res(ps.get(0)._genericType(), "zeroMany"), ps -> true)
        );

        register(
                h("meta::pure::functions::collection::paginated_T_MANY__Integer_1__Integer_1__T_MANY_", false, ps -> res(ps.get(0)._genericType(), "zeroMany"), ps -> true),
                h("meta::pure::tds::paginated_TabularDataSet_1__Integer_1__Integer_1__TabularDataSet_1_", false, ps -> res("meta::pure::tds::TabularDataSet", "one"), ps -> typeOne(ps.get(0), "TabularDataSet"))
        );

        register("meta::pure::functions::lang::cast_Any_m__T_1__T_m_", true, ps ->
        {
            // Support casting to a relation
            try
            {
                RelationType<?> rel = (RelationType<?>) ps.get(1)._genericType()._typeArguments().getFirst()._rawType();
                return res(
                        new Root_meta_pure_metamodel_type_generics_GenericType_Impl("", null, this.pureModel.getClass(M3Paths.GenericType))
                                ._rawType(this.pureModel.getType(M3Paths.Relation))
                                ._typeArguments(Lists.fixedSize.of(new Root_meta_pure_metamodel_type_generics_GenericType_Impl("", null, this.pureModel.getClass(M3Paths.GenericType))._rawType(rel))),
                        "one"
                );
            }
            catch (Exception e)
            {
                return res(ps.get(1)._genericType(), ps.get(0)._multiplicity());
            }
        });

        register("meta::pure::functions::collection::at_T_MANY__Integer_1__T_1_", true, ps -> res(ps.get(0)._genericType(), "one"));

        register(m(
                        m(h("meta::pure::graphFetch::execution::graphFetch_T_MANY__RootGraphFetchTree_1__T_MANY_", false, ps -> res(ps.get(0)._genericType(), "zeroMany"), ps -> ps.size() == 2)),
                        m(h("meta::pure::graphFetch::execution::graphFetch_T_MANY__RootGraphFetchTree_1__Integer_1__T_MANY_", false, ps -> res(ps.get(0)._genericType(), "zeroMany"), ps -> ps.size() == 3))
                )
        );
        register("meta::pure::executionPlan::featureFlag::withFeatureFlags_T_MANY__Enum_MANY__T_MANY_", false, ps -> res(ps.get(0)._genericType(), "zeroMany"));
        register(m(
                        m(h("meta::pure::graphFetch::execution::graphFetchChecked_T_MANY__RootGraphFetchTree_1__Checked_MANY_", false, ps -> res(CompileContext.newGenericType(this.pureModel.getType("meta::pure::dataQuality::Checked"), ps.get(0)._genericType(), this.pureModel), "zeroMany"), ps -> Lists.mutable.with(ps.get(0)._genericType()._typeArguments().getFirst()), ps -> ps.size() == 2)),
                        m(h("meta::pure::graphFetch::execution::graphFetchChecked_T_MANY__RootGraphFetchTree_1__Integer_1__Checked_MANY_", false, ps -> res(CompileContext.newGenericType(this.pureModel.getType("meta::pure::dataQuality::Checked"), ps.get(0)._genericType(), pureModel), "zeroMany"), ps -> Lists.mutable.with(ps.get(0)._genericType()._typeArguments().getFirst()), ps -> ps.size() == 3))
                )
        );

        register("meta::pure::graphFetch::execution::graphFetchUnexpanded_T_MANY__RootGraphFetchTree_1__T_MANY_", false, ps -> res(ps.get(0)._genericType(), "zeroMany"));
        register("meta::pure::graphFetch::execution::graphFetchCheckedUnexpanded_T_MANY__RootGraphFetchTree_1__Checked_MANY_", false, ps -> res(CompileContext.newGenericType(this.pureModel.getType("meta::pure::dataQuality::Checked"), ps.get(0)._genericType(), pureModel), "zeroMany"));
        register(m(
                        m(h("meta::pure::graphFetch::execution::serialize_Checked_MANY__RootGraphFetchTree_1__String_1_", false, ps -> res("String", "one"), ps -> ps.size() == 2 && "Checked".equals(ps.get(0)._genericType()._rawType()._name()))),
                        m(h("meta::pure::graphFetch::execution::serialize_T_MANY__RootGraphFetchTree_1__String_1_", false, ps -> res("String", "one"), ps -> ps.size() == 2)),
                        m(h("meta::pure::graphFetch::execution::serialize_Checked_MANY__RootGraphFetchTree_1__AlloySerializationConfig_1__String_1_", false, ps -> res("String", "one"), ps -> ps.size() == 3 && "Checked".equals(ps.get(0)._genericType()._rawType()._name()) && "AlloySerializationConfig".equals(ps.get(2)._genericType()._rawType()._name()))),
                        m(h("meta::pure::graphFetch::execution::serialize_T_MANY__RootGraphFetchTree_1__AlloySerializationConfig_1__String_1_", false, ps -> res("String", "one"), ps -> ps.size() == 3))
                )
        );

        register(h("meta::pure::graphFetch::calculateSourceTree_RootGraphFetchTree_1__Mapping_1__Extension_MANY__RootGraphFetchTree_1_", false, ps -> res("meta::pure::graphFetch::RootGraphFetchTree", "one"), ps -> true));
        register(m(m(grp(TwoParameterLambdaInferenceDiffTypes, h("meta::pure::functions::lang::match_Any_MANY__Function_$1_MANY$__P_o__T_m_", true, ps -> res(funcReturnType(ps.get(1)), funcReturnMul(ps.get(1))), ps -> ps.size() == 3))),
                m(h("meta::pure::functions::lang::match_Any_MANY__Function_$1_MANY$__T_m_", true, ps -> res(funcReturnType(ps.get(1)), funcReturnMul(ps.get(1))), ps -> ps.size() == 2))));
        register("meta::pure::functions::meta::instanceOf_Any_1__Type_1__Boolean_1_", true, ps -> res("Boolean", "one"));
        register("meta::pure::functions::collection::union_T_MANY__T_MANY__T_MANY_", false, ps -> res(ps.get(0)._genericType(), "zeroMany"));
        register("meta::pure::functions::collection::reverse_T_m__T_m_", true, ps -> res(ps.get(0)._genericType(), ps.get(0)._multiplicity()));
        register(
                m(
                        m(h("meta::pure::functions::date::add_StrictDate_1__Duration_1__StrictDate_1_", false, ps -> res("StrictDate", "one"), ps -> typeOne(ps.get(1), "Duration") && typeOne(ps.get(0), "StrictDate"))),
                        m(
                                m(h("meta::pure::functions::collection::add_T_MANY__T_1__T_$1_MANY$_", true, ps -> res(ps.get(0)._genericType(), "oneMany"), ps -> ps.size() == 2)),
                                m(h("meta::pure::functions::collection::add_T_MANY__Integer_1__T_1__T_$1_MANY$_", true, ps -> res(ps.get(0)._genericType(), "oneMany"), ps -> ps.size() == 3))
                        )
                )
        );

        register(m(m(h("meta::pure::functions::collection::dropAt_T_MANY__Integer_1__T_MANY_", false, ps -> res(ps.get(0)._genericType(), "zeroMany"), ps -> ps.size() == 2)),
                m(h("meta::pure::functions::collection::dropAt_T_MANY__Integer_1__Integer_1__T_MANY_", false, ps -> res(ps.get(0)._genericType(), "zeroMany"), ps -> ps.size() == 3))));

        register("meta::pure::functions::collection::zip_T_MANY__U_MANY__Pair_MANY_", true, ps -> res(CompileContext.newGenericType(this.pureModel.getType(M3Paths.Pair), Lists.fixedSize.ofAll(ps.stream().map(ValueSpecificationAccessor::_genericType).collect(Collectors.toList())), pureModel), "oneMany"));
        register(m(grp(LambdaInference, h("meta::pure::functions::collection::removeDuplicatesBy_T_MANY__Function_1__T_MANY_", false, ps -> res(ps.get(0)._genericType(), "zeroMany"), p -> true))));
        register("meta::pure::functions::collection::containsAll_Any_MANY__Any_MANY__Boolean_1_", false, ps -> res("Boolean", "one"));

        register("meta::pure::functions::meta::id_Any_1__String_1_", true, ps -> res("String", "one"));
        register("meta::pure::functions::meta::typePath_Any_1__String_1_", false, ps -> res("String", "one"));
        register("meta::pure::functions::meta::typeName_Any_1__String_1_", false, ps -> res("String", "one"));

        register("meta::pure::functions::meta::type_Any_MANY__Type_1_", false, ps -> res("meta::pure::metamodel::type::Type", "one"));
        register("meta::pure::functions::lang::compare_T_1__T_1__Integer_1_", true, ps -> res("Integer", "one"));
        // meta::pure::functions::collection::fold<T,V|m>(value:T[*], func:Function<{T[1],V[m]->V[m]}>[1], accumulator:V[m]):V[m], note return type is V and not T
        register(m(grp(TwoParameterLambdaInferenceDiffTypes, h("meta::pure::functions::collection::fold_T_MANY__Function_1__V_m__V_m_", true, ps -> res(ps.get(2)._genericType(), ps.get(2)._multiplicity()), p -> true))));

        register(m(m(h("meta::pure::functions::collection::range_Integer_1__Integer_1__Integer_1__Integer_MANY_", true, ps -> res("Integer", "zeroMany"), ps -> ps.size() == 3)),
                m(h("meta::pure::functions::collection::range_Integer_1__Integer_1__Integer_MANY_", false, ps -> res("Integer", "zeroMany"), ps -> ps.size() == 2)),
                m(h("meta::pure::functions::collection::range_Integer_1__Integer_MANY_", false, ps -> res("Integer", "zeroMany"), ps -> ps.size() == 1))));
        register("meta::pure::functions::collection::tail_T_MANY__T_MANY_", true, ps -> res(ps.get(0)._genericType(), "zeroMany"));
        register("meta::pure::functions::collection::head_T_MANY__T_$0_1$_", false, ps -> res(ps.get(0)._genericType(), "zeroOne"));
        register("meta::pure::functions::collection::oneOf_Boolean_MANY__Boolean_1_", false, ps -> res("Boolean", "one"));
        register("meta::pure::functions::collection::defaultIfEmpty_T_MANY__T_$1_MANY$__T_$1_MANY$_", false, ps -> res(MostCommonType.mostCommon(Lists.fixedSize.of(ps.get(0)._genericType(), ps.get(1)._genericType()), pureModel), "oneMany"));

        register("meta::pure::functions::string::isUUID_String_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"));

        register(m(h("meta::pure::mutation::save_T_MANY__RootGraphFetchTree_1__Mapping_1__Runtime_1__T_MANY_", false, ps -> res(ps.get(0)._genericType(), "zeroMany"), ps -> true)));

        register("meta::pure::tds::extensions::firstNotNull_T_MANY__T_$0_1$_", false, ps -> res(ps.get(0)._genericType(), "zeroOne"));

        register("meta::pure::functions::hash::hash_String_1__HashType_1__String_1_", true, ps -> res("String", "one"));
        register("meta::pure::functions::hash::hashCode_Any_MANY__Integer_1_", true, ps -> res("Integer", "one"));

        // Variant
        register("meta::pure::functions::variant::convert::fromJson_String_1__Variant_1_", true, ps -> res(M3Paths.Variant, "one"));
        register("meta::pure::functions::variant::convert::toJson_Variant_1__String_1_", true, ps -> res("String", "one"));
        register("meta::pure::functions::variant::convert::to_Variant_$0_1$__T_$0_1$__T_$0_1$_", true, ps -> res(ps.get(1)._genericType(), "zeroOne"));
        register("meta::pure::functions::variant::convert::toMany_Variant_$0_1$__T_$0_1$__T_MANY_", true, ps -> res(ps.get(1)._genericType(), "zeroMany"));
        register("meta::pure::functions::variant::convert::toVariant_Any_MANY__Variant_1_", true, ps -> res(M3Paths.Variant, "one"));
        register(
            m(
                    h("meta::pure::functions::collection::get_Map_1__U_1__V_$0_1$_", true, ps -> res(ps.get(0)._genericType()._typeArguments().getLast(), "zeroOne"), ps -> ps.size() == 2 && typeOne(ps.get(0), "Map")),
                    h("meta::pure::functions::variant::navigation::get_Variant_$0_1$__String_1__Variant_$0_1$_", false, ps -> res(M3Paths.Variant, "zeroOne"), ps -> ps.size() == 2 && typeZeroOne(ps.get(0), "Variant") && typeOne(ps.get(1), "String")),
                    h("meta::pure::functions::variant::navigation::get_Variant_$0_1$__Integer_1__Variant_$0_1$_", false, ps -> res(M3Paths.Variant, "zeroOne"), ps -> ps.size() == 2 && typeZeroOne(ps.get(0), "Variant") && typeOne(ps.get(1), "Integer"))
            )
        );

        register("meta::pure::functions::collection::newMap_Pair_MANY__Map_1_", true, ps -> res(CompileContext.newGenericType(this.pureModel.getType(M3Paths.Map), ListIterate.collect(ps, ValueSpecificationAccessor::_genericType), pureModel), "one"));
        register("meta::pure::functions::collection::list_U_MANY__List_1_", false, ps -> res(CompileContext.newGenericType(this.pureModel.getType(M3Paths.List), ps.get(0)._genericType(), pureModel), "one"));

        // Extensions
        CompileContext context = this.pureModel.getContext();
        ListIterate.flatCollect(context.getCompilerExtensions().getExtraFunctionExpressionBuilderRegistrationInfoCollectors(), collector -> collector.valueOf(this)).forEach(this::register);
        ListIterate.flatCollect(context.getCompilerExtensions().getExtraFunctionHandlerRegistrationInfoCollectors(), collector -> collector.valueOf(this)).forEach(this::register);
    }


    private void registerAsserts()
    {
        register(m(m(h("meta::pure::functions::asserts::assert_Boolean_1__Function_1__Boolean_1_", true, ps -> res("Boolean", "one"), ps -> ps.size() == 2 && !typeOne(ps.get(1), "String"))),
                m(h("meta::pure::functions::asserts::assert_Boolean_1__String_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 2 && typeOne(ps.get(1), "String"))),
                m((h("meta::pure::functions::asserts::assert_Boolean_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 1))),
                m(h("meta::pure::functions::asserts::assert_Boolean_1__String_1__Any_MANY__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 3))));

        register(m(m(h("meta::pure::functions::asserts::assertContains_Any_MANY__Any_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 2)),
                m(h("meta::pure::functions::asserts::assertContains_Any_MANY__Any_1__String_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 3 && typeOne(ps.get(2), "String"))),
                m(h("meta::pure::functions::asserts::assertContains_Any_MANY__Any_1__String_1__Any_MANY__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 4)),
                m(h("meta::pure::functions::asserts::assertContains_Any_MANY__Any_1__Function_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 3 && !typeOne(ps.get(2), "String")))));

        register(m(m(h("meta::pure::functions::asserts::assertEmpty_Any_MANY__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 1)),
                m(h("meta::pure::functions::asserts::assertEmpty_Any_MANY__String_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 2 && typeOne(ps.get(1), "String"))),
                m(h("meta::pure::functions::asserts::assertEmpty_Any_MANY__String_1__Any_MANY__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 3)),
                m(h("meta::pure::functions::asserts::assertEmpty_Any_MANY__Function_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 2 && !typeOne(ps.get(1), "String")))));

        register(m(
                m(h("meta::pure::functions::asserts::assertEq_Any_1__Any_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 2)),
                m(h("meta::pure::functions::asserts::assertEq_Any_1__Any_1__String_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 3 && typeOne(ps.get(2), "String"))),
                m(h("meta::pure::functions::asserts::assertEq_Any_1__Any_1__String_1__Any_MANY__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 4))
        ));

        register(m(m(h("meta::pure::functions::asserts::assertEqWithinTolerance_Number_1__Number_1__Number_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 3)),
                m(h("meta::pure::functions::asserts::assertEqWithinTolerance_Number_1__Number_1__Number_1__String_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 4 && typeOne(ps.get(3), "String"))),
                m(h("meta::pure::functions::asserts::assertEqWithinTolerance_Number_1__Number_1__Number_1__String_1__Any_MANY__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 5)),
                m(h("meta::pure::functions::asserts::assertEqWithinTolerance_Number_1__Number_1__Number_1__Function_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 4 && !typeOne(ps.get(3), "String")))));

        register(m(m(h("meta::pure::functions::asserts::assertEquals_Any_MANY__Any_MANY__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 2)),
                m(h("meta::pure::functions::asserts::assertEquals_Any_MANY__Any_MANY__String_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 3 && typeOne(ps.get(2), "String"))),
                m(h("meta::pure::functions::asserts::assertEquals_Any_MANY__Any_MANY__String_1__Any_MANY__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 4)),
                m(h("meta::pure::functions::asserts::assertEquals_Any_MANY__Any_MANY__Function_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 3 && !typeOne(ps.get(2), "String")))));

        register(m(m(h("meta::pure::functions::asserts::assertFalse_Boolean_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 1)),
                m(h("meta::pure::functions::asserts::assertFalse_Boolean_1__String_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 2 && typeOne(ps.get(1), "String"))),
                m(h("meta::pure::functions::asserts::assertFalse_Boolean_1__String_1__Any_MANY__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 3)),
                m(h("meta::pure::functions::asserts::assertFalse_Boolean_1__Function_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 2 && !typeOne(ps.get(1), "String")))));

        register(m(m(h("meta::pure::functions::asserts::assertInstanceOf_Any_1__Type_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 2)),
                m(h("meta::pure::functions::asserts::assertInstanceOf_Any_1__Type_1__String_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 3 && typeOne(ps.get(2), "String"))),
                m(h("meta::pure::functions::asserts::assertInstanceOf_Any_1__Type_1__String_1__Any_MANY__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 4)),
                m(h("meta::pure::functions::asserts::assertInstanceOf_Any_1__Type_1__Function_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 3 && !typeOne(ps.get(2), "String")))));

        register(m(m(h("meta::pure::functions::asserts::assertIs_Any_1__Any_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 2)),
                m(h("meta::pure::functions::asserts::assertIs_Any_1__Any_1__String_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 3 && typeOne(ps.get(2), "String"))),
                m(h("meta::pure::functions::asserts::assertIs_Any_1__Any_1__String_1__Any_MANY__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 4)),
                m(h("meta::pure::functions::asserts::assertIs_Any_1__Any_1__Function_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 3 && !typeOne(ps.get(2), "String")))));

        register(m(m(h("meta::pure::functions::asserts::assertIsNot_Any_1__Any_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 2)),
                m(h("meta::pure::functions::asserts::assertIsNot_Any_1__Any_1__String_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 3 && typeOne(ps.get(2), "String"))),
                m(h("meta::pure::functions::asserts::assertIsNot_Any_1__Any_1__String_1__Any_MANY__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 4)),
                m(h("meta::pure::functions::asserts::assertIsNot_Any_1__Any_1__Function_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 3 && !typeOne(ps.get(2), "String")))));

        register(m(m(h("meta::pure::functions::asserts::assertNotContains_Any_MANY__Any_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 2)),
                m(h("meta::pure::functions::asserts::assertNotContains_Any_MANY__Any_1__String_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 3 && typeOne(ps.get(2), "String"))),
                m(h("meta::pure::functions::asserts::assertNotContains_Any_MANY__Any_1__String_1__Any_MANY__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 4)),
                m(h("meta::pure::functions::asserts::assertNotContains_Any_MANY__Any_1__Function_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 3 && !typeOne(ps.get(2), "String")))));

        register(m(m(h("meta::pure::functions::asserts::assertNotEmpty_Any_MANY__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 1)),
                m(h("meta::pure::functions::asserts::assertNotEmpty_Any_MANY__String_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 2 && typeOne(ps.get(1), "String"))),
                m(h("meta::pure::functions::asserts::assertNotEmpty_Any_MANY__String_1__Any_MANY__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 3)),
                m(h("meta::pure::functions::asserts::assertNotEmpty_Any_MANY__Function_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 2 && !typeOne(ps.get(1), "String")))));

        register(m(m(h("meta::pure::functions::asserts::assertNotEq_Any_1__Any_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 2)),
                m(h("meta::pure::functions::asserts::assertNotEq_Any_1__Any_1__String_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 3 && typeOne(ps.get(2), "String"))),
                m(h("meta::pure::functions::asserts::assertNotEq_Any_1__Any_1__String_1__Any_MANY__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 4)),
                m(h("meta::pure::functions::asserts::assertNotEq_Any_1__Any_1__Function_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 3 && !typeOne(ps.get(2), "String")))));

        register(m(m(h("meta::pure::functions::asserts::assertNotEquals_Any_MANY__Any_MANY__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 2)),
                m(h("meta::pure::functions::asserts::assertNotEquals_Any_MANY__Any_MANY__String_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 3 && typeOne(ps.get(2), "String"))),
                m(h("meta::pure::functions::asserts::assertNotEquals_Any_MANY__Any_MANY__String_1__Any_MANY__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 4)),
                m(h("meta::pure::functions::asserts::assertNotEquals_Any_MANY__Any_MANY__Function_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 3 && !typeOne(ps.get(2), "String")))));

        register(m(m(h("meta::pure::functions::asserts::assertNotSize_Any_MANY__Integer_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 2)),
                m(h("meta::pure::functions::asserts::assertNotSize_Any_MANY__Integer_1__String_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 3 && typeOne(ps.get(2), "String"))),
                m(h("meta::pure::functions::asserts::assertNotSize_Any_MANY__Integer_1__String_1__Any_MANY__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 4)),
                m(h("meta::pure::functions::asserts::assertNotSize_Any_MANY__Integer_1__Function_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 3 && !typeOne(ps.get(2), "String")))));

        register(m(m(h("meta::pure::functions::asserts::assertSameElements_Any_MANY__Any_MANY__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 2)),
                m(h("meta::pure::functions::asserts::assertSameElements_Any_MANY__Any_MANY__String_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 3 && typeOne(ps.get(2), "String"))),
                m(h("meta::pure::functions::asserts::assertSameElements_Any_MANY__Any_MANY__String_1__Any_MANY__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 4)),
                m(h("meta::pure::functions::asserts::assertSameElements_Any_MANY__Any_MANY__Function_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 3 && !typeOne(ps.get(2), "String")))));

        register(m(m(h("meta::pure::functions::asserts::assertSize_Any_MANY__Integer_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 2)),
                m(h("meta::pure::functions::asserts::assertSize_Any_MANY__Integer_1__String_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 3 && typeOne(ps.get(2), "String"))),
                m(h("meta::pure::functions::asserts::assertSize_Any_MANY__Integer_1__String_1__Any_MANY__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 4)),
                m(h("meta::pure::functions::asserts::assertSize_Any_MANY__Integer_1__Function_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 3 && !typeOne(ps.get(2), "String")))));

        register(m(m(h("meta::pure::functions::asserts::fail__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 0)),
                m(h("meta::pure::functions::asserts::fail_String_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 1 && typeOne(ps.get(0), "String"))),
                m(h("meta::pure::functions::asserts::fail_String_1__Any_MANY__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 2)),
                m(h("meta::pure::functions::asserts::fail_Function_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 1 && !typeOne(ps.get(0), "String")))));
    }

    private void registerTDS()
    {
        register(grp(RenameColInference, h("meta::pure::functions::relation::rename_Relation_1__ColSpec_1__ColSpec_1__Relation_1_", true, ps ->
        {
            ProcessorSupport processorSupport = this.pureModel.getExecutionSupport().getProcessorSupport();
            RelationType<?> rel = (RelationType<?>) ps.get(0)._genericType()._typeArguments().getFirst()._rawType();
            org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.relation.ColSpec firstCol = (org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.relation.ColSpec) ((InstanceValue) ps.get(1))._values().getFirst();
            org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.relation.ColSpec secondCol = (org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.relation.ColSpec) ((InstanceValue) ps.get(2))._values().getFirst();
            RelationType<?> relType = _RelationType.build(
                    rel._columns().collect(c ->
                    {
                        if (c._name().equals(firstCol._name()))
                        {
                            return (CoreInstance) _Column.getColumnInstance(secondCol._name(), false, _Column.getColumnType(c), _Column.getColumnMultiplicity(c), null, processorSupport);
                        }
                        else
                        {
                            return (CoreInstance) _Column.getColumnInstance(c._name(), false, _Column.getColumnType(c), _Column.getColumnMultiplicity(c), null, processorSupport);
                        }
                    }).toList(),
                    null,
                    processorSupport
            );
            return res(
                    new Root_meta_pure_metamodel_type_generics_GenericType_Impl("", null, this.pureModel.getClass(M3Paths.GenericType))
                            ._rawType(this.pureModel.getType(M3Paths.Relation))
                            ._typeArguments(Lists.fixedSize.of(new Root_meta_pure_metamodel_type_generics_GenericType_Impl("", null, this.pureModel.getClass(M3Paths.GenericType))._rawType(relType))),
                    "one"
            );
        }, ps -> true)));

        register(grp(SelectColInference,
                        h("meta::pure::functions::relation::select_Relation_1__ColSpec_1__Relation_1_", true, ps -> getTypeAndMultiplicity(Lists.mutable.with((RelationType<?>) ps.get(1)._genericType()._typeArguments().getLast()._rawType()), pureModel), ps -> true),
                        h("meta::pure::functions::relation::select_Relation_1__ColSpecArray_1__Relation_1_", true, ps -> getTypeAndMultiplicity(Lists.mutable.with((RelationType<?>) ps.get(1)._genericType()._typeArguments().getLast()._rawType()), pureModel), ps -> true)
                )
        );

        register(grp(EvalColInference, h("meta::pure::functions::relation::eval_ColSpec_1__T_1__Z_MANY_", false,  ps -> res(getGenericReturnTypeForEvalCol(ps), "zeroMany"), ps -> Lists.fixedSize.of(getGenericReturnTypeForEvalCol(ps), ps.get(1)._genericType()), ps -> typeOne(ps.get(0), taxoMap.get("cov_relation_ColSpec")))));

        register(h("meta::pure::functions::relation::select_Relation_1__Relation_1_", true, ps -> res(ps.get(0)._genericType(), "one"), ps -> true));

        register(h("meta::pure::tds::renameColumns_TabularDataSet_1__Pair_MANY__TabularDataSet_1_", false, ps -> res("meta::pure::tds::TabularDataSet", "one"), ps -> true));

        register(m(grp(LambdaColCollectionInference, h("meta::pure::tds::projectWithColumnSubset_T_MANY__ColumnSpecification_MANY__String_MANY__TabularDataSet_1_", false, ps -> res("meta::pure::tds::TabularDataSet", "one"), ps -> "ColumnSpecification".equals(ps.get(1)._genericType()._rawType()._name()))),
                grp(LambdaCollectionInference, h("meta::pure::tds::projectWithColumnSubset_T_MANY__Function_MANY__String_MANY__String_MANY__TabularDataSet_1_", false, ps -> res("meta::pure::tds::TabularDataSet", "one"), ps -> true))));

        register("meta::pure::tds::restrict_TabularDataSet_1__String_MANY__TabularDataSet_1_", false, ps -> res("meta::pure::tds::TabularDataSet", "one"));
        register("meta::pure::tds::restrictDistinct_TabularDataSet_1__String_MANY__TabularDataSet_1_", false, ps -> res("meta::pure::tds::TabularDataSet", "one"));

        register("meta::pure::tds::asc_String_1__SortInformation_1_", false, ps -> res("meta::pure::tds::SortInformation", "one"));
        register("meta::pure::tds::desc_String_1__SortInformation_1_", false, ps -> res("meta::pure::tds::SortInformation", "one"));

        register("meta::pure::functions::relation::write_Relation_1__RelationElementAccessor_1__Integer_1_", true, ps -> res("Integer", "one"));

        register(h("meta::pure::functions::relation::ascending_ColSpec_1__SortInfo_1_", false, ps -> res("meta::pure::functions::relation::SortInfo", "one"), ps -> Lists.fixedSize.of(ps.get(0)._genericType()._typeArguments().getFirst()), ps -> true));
        register(h("meta::pure::functions::relation::descending_ColSpec_1__SortInfo_1_", false, ps -> res("meta::pure::functions::relation::SortInfo", "one"), ps -> Lists.fixedSize.of(ps.get(0)._genericType()._typeArguments().getFirst()), ps -> true));

        register(grp(JoinInference, h("meta::pure::functions::relation::join_Relation_1__Relation_1__JoinKind_1__Function_1__Relation_1_", true, ps -> JoinReturnInference(ps, this.pureModel), ps -> true)));

        register(m(
                        grp(AsOfJoinInference,
                                h("meta::pure::functions::relation::asOfJoin_Relation_1__Relation_1__Function_1__Function_1__Relation_1_", true, ps -> JoinReturnInference(ps, this.pureModel), ps -> true)
                        ),
                        grp(AsOfJoinInference,
                                h("meta::pure::functions::relation::asOfJoin_Relation_1__Relation_1__Function_1__Relation_1_", true, ps -> JoinReturnInference(ps, this.pureModel), ps -> true)
                        )
                )
        );

        register(
                m(
                        m(h("meta::pure::functions::collection::sort_T_m__T_m_", false, ps -> res(ps.get(0)._genericType(), ps.get(0)._multiplicity()), ps -> ps.size() == 1)),
                        m(grp(SortColumnInference, h("meta::pure::functions::relation::sort_Relation_1__SortInfo_MANY__Relation_1_", true, ps -> res(ps.get(0)._genericType(), "one"), ps -> true))),
                        m(grp(TwoParameterLambdaInference, h("meta::pure::functions::collection::sort_T_m__Function_$0_1$__T_m_", false, ps -> res(ps.get(0)._genericType(), ps.get(0)._multiplicity()), ps -> ps.size() == 2))),
                        m(grp(LambdaInference, h("meta::pure::functions::collection::sort_T_m__Function_$0_1$__Function_$0_1$__T_m_", true, ps -> res(ps.get(0)._genericType(), ps.get(0)._multiplicity()), ps -> ps.size() == 3))),
                        m(h("meta::pure::tds::sort_TabularDataSet_1__String_1__SortDirection_1__TabularDataSet_1_", false, ps -> res("meta::pure::tds::TabularDataSet", "one"), ps -> ps.size() == 3)),
                        m(
                                h("meta::pure::tds::sort_TabularDataSet_1__String_MANY__TabularDataSet_1_", false, ps -> res("meta::pure::tds::TabularDataSet", "one"), ps -> ps.size() == 2 && "String".equals(ps.get(1)._genericType()._rawType()._name())),
                                h("meta::pure::tds::sort_TabularDataSet_1__SortInformation_MANY__TabularDataSet_1_", false, ps -> res("meta::pure::tds::TabularDataSet", "one"), ps -> true)
                        )
                )
        );


        register(
                m(
                        m(
                                h("meta::pure::mapping::from_FunctionDefinition_1__Runtime_1__T_m_", false, ps -> res(funcReturnType(ps.get(0)), funcReturnMul(ps.get(0))), ps -> ps.size() == 2 && typeOne(ps.get(0), taxoMap.get("cov_function_FunctionDefinition")) && typeOne(ps.get(1), taxoMap.get("cov_runtime_Runtime"))),
                                h("meta::pure::mapping::from_FunctionDefinition_1__PackageableRuntime_1__T_m_", false, ps -> res(funcReturnType(ps.get(0)), funcReturnMul(ps.get(0))), ps -> ps.size() == 2 && typeOne(ps.get(0), taxoMap.get("cov_function_FunctionDefinition")) && typeOne(ps.get(1), taxoMap.get("cov_runtime_PackageableRuntime")))
                        ),
                        m(
                                h("meta::pure::mapping::from_T_m__Runtime_1__T_m_", false, ps -> res(ps.get(0)._genericType(), ps.get(0)._multiplicity()), ps -> ps.size() == 2 && typeOne(ps.get(1), taxoMap.get("cov_runtime_Runtime"))),
                                h("meta::pure::mapping::from_T_m__PackageableRuntime_1__T_m_", false, ps -> res(ps.get(0)._genericType(), ps.get(0)._multiplicity()), ps -> ps.size() == 2 && typeOne(ps.get(1), taxoMap.get("cov_runtime_PackageableRuntime")))
                        ),
                        m(
                                h("meta::pure::mapping::from_FunctionDefinition_1__Mapping_1__Runtime_1__T_m_", false, ps -> res(funcReturnType(ps.get(0)), funcReturnMul(ps.get(0))), ps -> ps.size() == 3 && typeOne(ps.get(0), taxoMap.get("cov_function_FunctionDefinition")) && typeOne(ps.get(2), taxoMap.get("cov_runtime_Runtime"))),
                                h("meta::pure::mapping::from_FunctionDefinition_1__Mapping_1__PackageableRuntime_1__T_m_", false, ps -> res(funcReturnType(ps.get(0)), funcReturnMul(ps.get(0))), ps -> ps.size() == 3 && typeOne(ps.get(0), taxoMap.get("cov_function_FunctionDefinition")) && typeOne(ps.get(2), taxoMap.get("cov_runtime_PackageableRuntime")))
                        ),
                        m(
                                h("meta::pure::mapping::from_TabularDataSet_1__Mapping_1__Runtime_1__TabularDataSet_1_", false, ps -> res("meta::pure::tds::TabularDataSet", "one"), ps -> ps.size() == 3 && "TabularDataSet".equals(ps.get(0)._genericType()._rawType()._name()) && typeOne(ps.get(2), taxoMap.get("cov_runtime_Runtime"))),
                                h("meta::pure::mapping::from_TabularDataSet_1__Mapping_1__PackageableRuntime_1__TabularDataSet_1_", false, ps -> res("meta::pure::tds::TabularDataSet", "one"), ps -> ps.size() == 3 && "TabularDataSet".equals(ps.get(0)._genericType()._rawType()._name()) && typeOne(ps.get(2), taxoMap.get("cov_runtime_PackageableRuntime"))),
                                h("meta::pure::mapping::from_T_m__Mapping_1__Runtime_1__T_m_", false, ps -> res(ps.get(0)._genericType(), ps.get(0)._multiplicity()), ps -> ps.size() == 3 && typeOne(ps.get(2), taxoMap.get("cov_runtime_Runtime"))),
                                h("meta::pure::mapping::from_T_m__Mapping_1__PackageableRuntime_1__T_m_", false, ps -> res(ps.get(0)._genericType(), ps.get(0)._multiplicity()), ps -> ps.size() == 3 && typeOne(ps.get(2), taxoMap.get("cov_runtime_PackageableRuntime")))
                        ),
                        m(
                                h("meta::pure::mapping::from_TabularDataSet_1__Mapping_1__Runtime_1__ExecutionContext_1__TabularDataSet_1_", false, ps -> res(ps.get(0)._genericType(), ps.get(0)._multiplicity()), ps -> ps.size() == 4 && typeOne(ps.get(2), taxoMap.get("cov_runtime_Runtime"))),
                                h("meta::pure::mapping::from_TabularDataSet_1__Mapping_1__PackageableRuntime_1__ExecutionContext_1__TabularDataSet_1_", false, ps -> res(ps.get(0)._genericType(), ps.get(0)._multiplicity()), ps -> ps.size() == 4 && typeOne(ps.get(2), taxoMap.get("cov_runtime_PackageableRuntime")))
                        )
                )
        );

        register(
                m(
                        m(
                                h("meta::pure::mapping::with_T_m__Runtime_1__T_m_", false, ps -> res(ps.get(0)._genericType(), ps.get(0)._multiplicity()), ps -> ps.size() == 2 && typeOne(ps.get(1), taxoMap.get("cov_runtime_Runtime"))),
                                h("meta::pure::mapping::with_T_m__PackageableRuntime_1__T_m_", false, ps -> res(ps.get(0)._genericType(), ps.get(0)._multiplicity()), ps -> ps.size() == 2 && typeOne(ps.get(1), taxoMap.get("cov_runtime_PackageableRuntime")))
                        ),
                        m(
                                h("meta::pure::mapping::with_T_m__Mapping_1__Runtime_1__T_m_", false, ps -> res(ps.get(0)._genericType(), ps.get(0)._multiplicity()), ps -> ps.size() == 3 && typeOne(ps.get(2), taxoMap.get("cov_runtime_Runtime"))),
                                h("meta::pure::mapping::with_T_m__Mapping_1__PackageableRuntime_1__T_m_", false, ps -> res(ps.get(0)._genericType(), ps.get(0)._multiplicity()), ps -> ps.size() == 3 && typeOne(ps.get(2), taxoMap.get("cov_runtime_PackageableRuntime")))
                        )
                )
        );

        register(m(grp(LambdaCollectionInference, h("meta::pure::tds::tdsContains_T_1__Function_MANY__TabularDataSet_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 3)),
                grp(TDSContainsInference, h("meta::pure::tds::tdsContains_T_1__Function_MANY__String_MANY__TabularDataSet_1__Function_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> true))));

        register(m(m(grp(TDSOLAPInference, h("meta::pure::tds::olapGroupBy_TabularDataSet_1__String_MANY__SortInformation_$0_1$__OlapOperation_1__String_1__TabularDataSet_1_", false, ps -> res("meta::pure::tds::TabularDataSet", "one"), ps -> ps.size() == 5 && "OlapOperation".equals(ps.get(3)._genericType()._rawType()._name()))),
                m(grp(TDSOLAPInference, h("meta::pure::tds::olapGroupBy_TabularDataSet_1__String_MANY__SortInformation_$0_1$__FunctionDefinition_1__String_1__TabularDataSet_1_", false, ps -> res("meta::pure::tds::TabularDataSet", "one"), ps -> ps.size() == 5 && "FunctionDefinition".equals(ps.get(3)._genericType()._rawType()._name())))),
                m(grp(TDSOLAPInference, h("meta::pure::tds::olapGroupBy_TabularDataSet_1__String_MANY__OlapOperation_1__String_1__TabularDataSet_1_", false, ps -> res("meta::pure::tds::TabularDataSet", "one"), ps -> ps.size() == 4 && typeMany(ps.get(1), "String") && "OlapOperation".equals(ps.get(2)._genericType()._rawType()._name())))),
                m(grp(TDSOLAPInference, h("meta::pure::tds::olapGroupBy_TabularDataSet_1__String_MANY__FunctionDefinition_1__String_1__TabularDataSet_1_", false, ps -> res("meta::pure::tds::TabularDataSet", "one"), ps -> ps.size() == 4 && typeMany(ps.get(1), "String") && "FunctionDefinition".equals(ps.get(2)._genericType()._rawType()._name())))),
                m(grp(TDSOLAPInference, h("meta::pure::tds::olapGroupBy_TabularDataSet_1__SortInformation_$0_1$__OlapOperation_1__String_1__TabularDataSet_1_", false, ps -> res("meta::pure::tds::TabularDataSet", "one"), ps -> ps.size() == 4 && "OlapOperation".equals(ps.get(2)._genericType()._rawType()._name())))),
                m(grp(TDSOLAPInference, h("meta::pure::tds::olapGroupBy_TabularDataSet_1__SortInformation_$0_1$__FunctionDefinition_1__String_1__TabularDataSet_1_", false, ps -> res("meta::pure::tds::TabularDataSet", "one"), ps -> ps.size() == 4 && "SortInformation".equals(ps.get(1)._genericType()._rawType()._name()) && "FunctionDefinition".equals(ps.get(2)._genericType()._rawType()._name())))),
                m(grp(TDSOLAPInference, h("meta::pure::tds::olapGroupBy_TabularDataSet_1__OlapOperation_1__String_1__TabularDataSet_1_", false, ps -> res("meta::pure::tds::TabularDataSet", "one"), ps -> ps.size() == 3 && "OlapOperation".equals(ps.get(1)._genericType()._rawType()._name())))),
                m(grp(TDSOLAPInference, h("meta::pure::tds::olapGroupBy_TabularDataSet_1__FunctionDefinition_1__String_1__TabularDataSet_1_", false, ps -> res("meta::pure::tds::TabularDataSet", "one"), ps -> ps.size() == 3 && "FunctionDefinition".equals(ps.get(1)._genericType()._rawType()._name()))))
        )));

        register(
                m(m(grp(OLAPFuncNumInference, h("meta::pure::tds::func_String_1__FunctionDefinition_1__TdsOlapAggregation_1_", false, ps -> res("meta::pure::tds::TdsOlapAggregation", "one"), ps -> ps.size() == 2))),
                        m(grp(OLAPFuncTDSInference, h("meta::pure::tds::func_FunctionDefinition_1__TdsOlapRank_1_", false, ps -> res("meta::pure::tds::TdsOlapRank", "one"), ps -> ps.size() == 1)))));
    }

    private void registerDates()
    {
        register(h("meta::pure::functions::date::dateDiff_Date_1__Date_1__DurationUnit_1__Integer_1_", true, ps -> res("Integer", "one"), ps -> typeOne(ps.get(0), taxoMap.get("cov_Date")) && typeOne(ps.get(1), taxoMap.get("cov_Date"))),
                h("meta::pure::functions::date::dateDiff_Date_$0_1$__Date_$0_1$__DurationUnit_1__Integer_$0_1$_", false, ps -> res("Integer", "zeroOne"), ps -> true));

        register(h("meta::pure::functions::date::datePart_Date_1__Date_1_", true, ps -> res("Date", "one"), ps -> typeOne(ps.get(0), taxoMap.get("cov_Date"))),
                h("meta::pure::functions::date::datePart_Date_$0_1$__Date_$0_1$_", false, ps -> res("Date", "zeroOne"), ps -> typeZeroOne(ps.get(0), taxoMap.get("cov_Date"))));

        register(h("meta::pure::functions::date::dayOfWeek_Date_1__DayOfWeek_1_", false, ps -> res("meta::pure::functions::date::DayOfWeek", "one"), ps -> typeOne(ps.get(0), taxoMap.get("cov_Date"))),
                h("meta::pure::functions::date::dayOfWeek_Integer_1__DayOfWeek_1_", false, ps -> res("meta::pure::functions::date::DayOfWeek", "one"), ps -> typeOne(ps.get(0), "Integer")));

        register(h("meta::pure::functions::date::daysOfMonth_Date_1__Integer_MANY_", false, ps -> res("Integer", "zeroMany"), ps -> true));

        register("meta::pure::functions::date::firstDayOfMonth_Date_1__Date_1_", false, ps -> res("Date", "one"));
        register("meta::pure::functions::date::firstDayOfQuarter_Date_1__StrictDate_1_", false, ps -> res("StrictDate", "one"));
        register("meta::pure::functions::date::firstDayOfThisMonth__Date_1_", false, ps -> res("Date", "one"));
        register("meta::pure::functions::date::firstDayOfThisQuarter__StrictDate_1_", false, ps -> res("StrictDate", "one"));
        register("meta::pure::functions::date::firstDayOfThisWeek__Date_1_", false, ps -> res("Date", "one"));
        register("meta::pure::functions::date::firstDayOfThisYear__Date_1_", false, ps -> res("Date", "one"));
        register("meta::pure::functions::date::firstDayOfWeek_Date_1__Date_1_", false, ps -> res("Date", "one"));
        register("meta::pure::functions::date::firstDayOfYear_Date_1__Date_1_", false, ps -> res("Date", "one"));
        register("meta::pure::functions::date::firstHourOfDay_Date_1__DateTime_1_", false, ps -> res("DateTime", "one"));
        register("meta::pure::functions::date::firstMinuteOfHour_Date_1__DateTime_1_", false, ps -> res("DateTime", "one"));
        register("meta::pure::functions::date::firstSecondOfMinute_Date_1__DateTime_1_", false, ps -> res("DateTime", "one"));
        register("meta::pure::functions::date::firstMillisecondOfSecond_Date_1__DateTime_1_", false, ps -> res("DateTime", "one"));

        register("meta::pure::functions::date::hasYear_Date_1__Boolean_1_", false, ps -> res("Boolean", "one"));

        register(h("meta::pure::functions::date::isAfterDay_Date_1__Date_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), taxoMap.get("cov_Date")) && typeOne(ps.get(1), taxoMap.get("cov_Date"))),
                h("meta::pure::functions::date::isAfterDay_Date_$0_1$__Date_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), taxoMap.get("cov_Date")) && typeOne(ps.get(1), taxoMap.get("cov_Date"))),
                h("meta::pure::functions::date::isAfterDay_Date_1__Date_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), taxoMap.get("cov_Date")) && typeZeroOne(ps.get(1), taxoMap.get("cov_Date"))),
                h("meta::pure::functions::date::isAfterDay_Date_$0_1$__Date_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), taxoMap.get("cov_Date")) && typeZeroOne(ps.get(1), taxoMap.get("cov_Date"))));

        register(h("meta::pure::functions::date::isBeforeDay_Date_1__Date_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), taxoMap.get("cov_Date")) && typeOne(ps.get(1), taxoMap.get("cov_Date"))),
                h("meta::pure::functions::date::isBeforeDay_Date_$0_1$__Date_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), taxoMap.get("cov_Date")) && typeOne(ps.get(1), taxoMap.get("cov_Date"))),
                h("meta::pure::functions::date::isBeforeDay_Date_1__Date_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), taxoMap.get("cov_Date")) && typeZeroOne(ps.get(1), taxoMap.get("cov_Date"))),
                h("meta::pure::functions::date::isBeforeDay_Date_$0_1$__Date_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), taxoMap.get("cov_Date")) && typeZeroOne(ps.get(1), taxoMap.get("cov_Date"))));

        register(h("meta::pure::functions::date::isOnDay_Date_1__Date_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), taxoMap.get("cov_Date")) && typeOne(ps.get(1), taxoMap.get("cov_Date"))),
                h("meta::pure::functions::date::isOnDay_Date_$0_1$__Date_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), taxoMap.get("cov_Date")) && typeOne(ps.get(1), taxoMap.get("cov_Date"))),
                h("meta::pure::functions::date::isOnDay_Date_1__Date_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), taxoMap.get("cov_Date")) && typeZeroOne(ps.get(1), taxoMap.get("cov_Date"))),
                h("meta::pure::functions::date::isOnDay_Date_$0_1$__Date_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), taxoMap.get("cov_Date")) && typeZeroOne(ps.get(1), taxoMap.get("cov_Date"))));

        register(h("meta::pure::functions::date::isOnOrAfterDay_Date_1__Date_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), taxoMap.get("cov_Date")) && typeOne(ps.get(1), taxoMap.get("cov_Date"))),
                h("meta::pure::functions::date::isOnOrAfterDay_Date_$0_1$__Date_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), taxoMap.get("cov_Date")) && typeOne(ps.get(1), taxoMap.get("cov_Date"))),
                h("meta::pure::functions::date::isOnOrAfterDay_Date_1__Date_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), taxoMap.get("cov_Date")) && typeZeroOne(ps.get(1), taxoMap.get("cov_Date"))),
                h("meta::pure::functions::date::isOnOrAfterDay_Date_$0_1$__Date_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), taxoMap.get("cov_Date")) && typeZeroOne(ps.get(1), taxoMap.get("cov_Date"))));

        register(h("meta::pure::functions::date::isOnOrBeforeDay_Date_1__Date_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), taxoMap.get("cov_Date")) && typeOne(ps.get(1), taxoMap.get("cov_Date"))),
                h("meta::pure::functions::date::isOnOrBeforeDay_Date_$0_1$__Date_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), taxoMap.get("cov_Date")) && typeOne(ps.get(1), taxoMap.get("cov_Date"))),
                h("meta::pure::functions::date::isOnOrBeforeDay_Date_1__Date_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), taxoMap.get("cov_Date")) && typeZeroOne(ps.get(1), taxoMap.get("cov_Date"))),
                h("meta::pure::functions::date::isOnOrBeforeDay_Date_$0_1$__Date_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), taxoMap.get("cov_Date")) && typeZeroOne(ps.get(1), taxoMap.get("cov_Date"))));

        register(h("meta::pure::functions::date::monthNumber_Date_1__Integer_1_", true, ps -> res("Integer", "one"), ps -> typeOne(ps.get(0), taxoMap.get("cov_Date"))),
                h("meta::pure::functions::date::monthNumber_Date_$0_1$__Integer_$0_1$_", false, ps -> res("Integer", "zeroOne"), ps -> typeZeroOne(ps.get(0), taxoMap.get("cov_Date"))));

        register(h("meta::pure::functions::date::month_Date_1__Month_1_", false, ps -> res("meta::pure::functions::date::Month", "one"), ps -> typeOne(ps.get(0), taxoMap.get("cov_Date"))),
                h("meta::pure::functions::date::month_Integer_1__Month_1_", false, ps -> res("meta::pure::functions::date::Month", "one"), ps -> typeOne(ps.get(0), "Integer")));

        register(m(m(h("meta::pure::functions::date::mostRecentDayOfWeek_Date_1__DayOfWeek_1__Date_1_", false, ps -> res("Date", "one"), ps -> ps.size() == 2)),
                m(h("meta::pure::functions::date::mostRecentDayOfWeek_DayOfWeek_1__Date_1_", false, ps -> res("Date", "one"), ps -> true))));

        register(m(m(h("meta::pure::functions::date::previousDayOfWeek_Date_1__DayOfWeek_1__Date_1_", false, ps -> res("Date", "one"), ps -> ps.size() == 2)),
                m(h("meta::pure::functions::date::previousDayOfWeek_DayOfWeek_1__Date_1_", false, ps -> res("Date", "one"), ps -> true))));

        register("meta::pure::functions::date::quarterNumber_Date_1__Integer_1_", false, ps -> res("Integer", "one"));

        register(h("meta::pure::functions::date::quarter_Date_1__Quarter_1_", false, ps -> res("meta::pure::functions::date::Quarter", "one"), ps -> typeOne(ps.get(0), taxoMap.get("cov_Date"))),
                h("meta::pure::functions::date::quarter_Integer_1__Quarter_1_", false, ps -> res("meta::pure::functions::date::Quarter", "one"), ps -> typeOne(ps.get(0), "Integer")));

        register(h("meta::pure::functions::date::weekOfYear_Date_1__Integer_1_", true, ps -> res("Integer", "one"), ps -> isOne(ps.get(0)._multiplicity())),
                h("meta::pure::functions::date::weekOfYear_Date_$0_1$__Integer_$0_1$_", false, ps -> res("Integer", "zeroOne"), ps -> true));

        register(h("meta::pure::functions::date::year_Date_1__Integer_1_", true, ps -> res("Integer", "one"), ps -> typeOne(ps.get(0), taxoMap.get("cov_Date"))),
                h("meta::pure::functions::date::year_Date_$0_1$__Integer_$0_1$_", false, ps -> res("Integer", "zeroOne"), ps -> typeZeroOne(ps.get(0), taxoMap.get("cov_Date"))));

        register("meta::pure::functions::date::adjust_Date_1__Integer_1__DurationUnit_1__Date_1_", true, ps -> res("Date", "one"));
        register("meta::pure::functions::date::convertTimeZone_DateTime_1__String_1__String_1__String_1_", false, ps -> res("String", "one"));

        register("meta::pure::functions::date::timeBucket_DateTime_1__Integer_1__DurationUnit_1__DateTime_1_", true, ps -> res("DateTime", "one"));
        register("meta::pure::functions::date::timeBucket_DateTime_$0_1$__Integer_1__DurationUnit_1__DateTime_$0_1$_", false, ps -> res("DateTime", "zeroOne"));
        register("meta::pure::functions::date::timeBucket_StrictDate_1__Integer_1__DurationUnit_1__StrictDate_1_", true, ps -> res("StrictDate", "one"));
        register("meta::pure::functions::date::timeBucket_StrictDate_$0_1$__Integer_1__DurationUnit_1__StrictDate_$0_1$_", false, ps -> res("StrictDate", "zeroOne"));

        register(m(m(h("meta::pure::functions::date::date_Integer_1__Date_1_", true, ps -> res("Date", "one"), ps -> ps.size() == 1)),
                m(h("meta::pure::functions::date::date_Integer_1__Integer_1__Date_1_", true, ps -> res("Date", "one"), ps -> ps.size() == 2)),
                m(h("meta::pure::functions::date::date_Integer_1__Integer_1__Integer_1__StrictDate_1_", true, ps -> res("StrictDate", "one"), ps -> ps.size() == 3)),
                m(h("meta::pure::functions::date::date_Integer_1__Integer_1__Integer_1__Integer_1__DateTime_1_", true, ps -> res("DateTime", "one"), ps -> ps.size() == 4)),
                m(h("meta::pure::functions::date::date_Integer_1__Integer_1__Integer_1__Integer_1__Integer_1__DateTime_1_", true, ps -> res("DateTime", "one"), ps -> ps.size() == 5)),
                m(h("meta::pure::functions::date::date_Integer_1__Integer_1__Integer_1__Integer_1__Integer_1__Number_1__DateTime_1_", true, ps -> res("DateTime", "one"), ps -> ps.size() == 6))));

        register("meta::pure::functions::date::dayOfMonth_Date_1__Integer_1_", true, ps -> res("Integer", "one"));

        register(m(m(h("meta::pure::functions::date::dayOfWeekNumber_Date_1__Integer_1_", true, ps -> res("Integer", "one"), ps -> ps.size() == 1)),
                m(h("meta::pure::functions::date::dayOfWeekNumber_Date_1__DayOfWeek_1__Integer_1_", false, ps -> res("Integer", "one"), ps -> ps.size() == 2))));

        register("meta::pure::functions::date::dayOfYear_Date_1__Integer_1_", true, ps -> res("Integer", "one"));

        register(h("meta::pure::functions::date::hasDay_Date_1__Boolean_1_", true, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), taxoMap.get("cov_Date"))));
        register(h("meta::pure::functions::date::hasHour_Date_1__Boolean_1_", true, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), taxoMap.get("cov_Date"))));
        register(h("meta::pure::functions::date::hasMinute_Date_1__Boolean_1_", true, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), taxoMap.get("cov_Date"))));
        register(h("meta::pure::functions::date::hasMonth_Date_1__Boolean_1_", true, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), taxoMap.get("cov_Date"))));
        register(h("meta::pure::functions::date::hasSecond_Date_1__Boolean_1_", true, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), taxoMap.get("cov_Date"))));
        register(h("meta::pure::functions::date::hasSubsecondWithAtLeastPrecision_Date_1__Integer_1__Boolean_1_", true, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), taxoMap.get("cov_Date")) && typeOne(ps.get(1), "Integer")));
        register(h("meta::pure::functions::date::hasSubsecond_Date_1__Boolean_1_", true, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), taxoMap.get("cov_Date"))));

        register("meta::pure::functions::date::hour_Date_1__Integer_1_", true, ps -> res("Integer", "one"));
        register("meta::pure::functions::date::minute_Date_1__Integer_1_", true, ps -> res("Integer", "one"));
        register("meta::pure::functions::date::second_Date_1__Integer_1_", true, ps -> res("Integer", "one"));

        register("meta::pure::functions::date::now__DateTime_1_", true, ps -> res("DateTime", "one"));
        register("meta::pure::functions::date::today__StrictDate_1_", true, ps -> res("StrictDate", "one"));

        register(m(m(h("meta::pure::functions::date::toEpochValue_Date_1__Integer_1_", false, ps -> res("Integer", "one"), ps -> ps.size() == 1)),
                m(h("meta::pure::functions::date::toEpochValue_Date_1__DurationUnit_1__Integer_1_", false, ps -> res("Integer", "one"), ps -> ps.size() == 2))));
    }

    private void registerStrings()
    {
        register("meta::pure::functions::string::ascii_String_1__Integer_1_", true, ps -> res("Integer", "one"));
        register("meta::pure::functions::string::char_Integer_1__String_1_", true, ps -> res("String", "one"));
        register("meta::pure::functions::string::decodeBase64_String_1__String_1_", true, ps -> res("String", "one"));
        register("meta::pure::functions::string::encodeBase64_String_1__String_1_", true, ps -> res("String", "one"));
        register(h("meta::pure::functions::string::endsWith_String_1__String_1__Boolean_1_", true, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), "String")),
                h("meta::pure::functions::string::endsWith_String_$0_1$__String_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), "String")));
        register("meta::pure::functions::string::equalIgnoreCase_String_1__String_1__Boolean_1_", false, ps -> res("Boolean", "one"));
        register("meta::pure::functions::string::humanize_String_1__String_1_", false, ps -> res("String", "one"));
        register("meta::pure::functions::string::isLowerCase_String_1__Boolean_1_", false, ps -> res("Boolean", "one"));
        register("meta::pure::functions::string::isUpperCase_String_1__Boolean_1_", false, ps -> res("Boolean", "one"));
        register(m(m(h("meta::pure::functions::string::joinStrings_String_MANY__String_1__String_1_", false, ps -> res("String", "one"), ps -> ps.size() == 2)),
                m(h("meta::pure::functions::string::joinStrings_String_MANY__String_1__String_1__String_1__String_1_", true, ps -> res("String", "one"), ps -> ps.size() == 4)),
                m(h("meta::pure::functions::string::joinStrings_String_MANY__String_1_", false, ps -> res("String", "one"), ps -> true))));
        register("meta::pure::functions::string::lastIndexOf_String_1__String_1__Integer_1_", false, ps -> res("Integer", "one"));
        register(m(m(h("meta::pure::functions::string::makeCamelCase_String_1__Boolean_1__String_1_", false, ps -> res("String", "one"), ps -> ps.size() == 2)),
                m(h("meta::pure::functions::string::makeCamelCase_String_1__String_1_", false, ps -> res("String", "one"), ps -> true))));
        register(m(m(h("meta::pure::functions::string::isDigit_String_1__Integer_1__Integer_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 3)),
                m(h("meta::pure::functions::string::isDigit_String_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> true))));
        register(m(m(h("meta::pure::functions::string::isLetter_String_1__Integer_1__Integer_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> ps.size() == 3)),
                m(h("meta::pure::functions::string::isLetter_String_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> true))));
        register(m(m(h("meta::pure::functions::string::makeString_Pair_MANY__String_1_", false, ps -> res("String", "one"), ps -> ps.size() == 1 && typeMany(ps.get(0), "Pair")),
                        h("meta::pure::functions::string::makeString_Any_MANY__String_1_", false, ps -> res("String", "one"), ps -> ps.size() == 1)),
                m(h("meta::pure::functions::string::makeString_Any_MANY__String_1__String_1_", false, ps -> res("String", "one"), ps -> ps.size() == 2)),
                m(h("meta::pure::functions::string::makeString_Any_MANY__String_1__String_1__String_1__String_1_", false, ps -> res("String", "one"), ps -> ps.size() == 4))));
        register("meta::pure::functions::string::splitOnCamelCase_String_1__String_MANY_", false, ps -> res("String", "zeroMany"));
        register(h("meta::pure::functions::string::startsWith_String_1__String_1__Boolean_1_", true, ps -> res("Boolean", "one"), ps -> isOne(ps.get(0)._multiplicity())),
                h("meta::pure::functions::string::startsWith_String_$0_1$__String_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> true));
        register("meta::pure::functions::string::substringAfter_String_1__String_1__String_1_", false, ps -> res("String", "one"));
        register("meta::pure::functions::string::substringBefore_String_1__String_1__String_1_", false, ps -> res("String", "one"));
        register("meta::pure::functions::string::chunk_String_1__Integer_1__String_MANY_", true, ps -> res("String", "zeroMany"));
        register("meta::pure::functions::string::format_String_1__Any_MANY__String_1_", true, ps -> res("String", "one"));
        register("meta::pure::functions::string::length_String_1__Integer_1_", true, ps -> res("Integer", "one"));
        register("meta::pure::functions::string::parseBoolean_String_1__Boolean_1_", true, ps -> res("Boolean", "one"));
        register("meta::pure::functions::string::parseDate_String_1__Date_1_", true, ps -> res("Date", "one"));
        register("meta::pure::functions::string::parseFloat_String_1__Float_1_", true, ps -> res("Float", "one"));
        register("meta::pure::functions::string::parseInteger_String_1__Integer_1_", true, ps -> res("Integer", "one"));
        register("meta::pure::functions::string::parseDecimal_String_1__Decimal_1_", true, ps -> res("Decimal", "one"));
        register("meta::pure::functions::string::repeatString_String_$0_1$__Integer_1__String_$0_1$_", false, ps -> res("String", "zeroOne"));
        register("meta::pure::functions::string::replace_String_1__String_1__String_1__String_1_", true, ps -> res("String", "one"));
        register("meta::pure::functions::string::reverseString_String_1__String_1_", true, ps -> res("String", "one"));
        register("meta::pure::functions::string::split_String_1__String_1__String_MANY_", true, ps -> res("String", "zeroMany"));
        register("meta::pure::functions::string::splitPart_String_$0_1$__String_1__Integer_1__String_$0_1$_", false, ps -> res("String", "zeroOne"));
        register(m(m(h("meta::pure::functions::string::substring_String_1__Integer_1__Integer_1__String_1_", true, ps -> res("String", "one"), ps -> ps.size() == 3)),
                m(h("meta::pure::functions::string::substring_String_1__Integer_1__String_1_", true, ps -> res("String", "one"), ps -> true))));
        register("meta::pure::functions::string::toLower_String_1__String_1_", true, ps -> res("String", "one"));
        register("meta::pure::functions::string::toLowerFirstCharacter_String_1__String_1_", false, ps -> res("String", "one"));
        register("meta::pure::functions::string::toString_Any_1__String_1_", true, ps -> res("String", "one"));
        register("meta::pure::functions::string::toUpper_String_1__String_1_", true, ps -> res("String", "one"));
        register("meta::pure::functions::string::toUpperFirstCharacter_String_1__String_1_", false, ps -> res("String", "one"));
        register("meta::pure::functions::string::trim_String_1__String_1_", true, ps -> res("String", "one"));
        register("meta::pure::functions::string::ltrim_String_1__String_1_", true, ps -> res("String", "one"));
        register("meta::pure::functions::string::rtrim_String_1__String_1_", true, ps -> res("String", "one"));
        register("meta::pure::functions::string::left_String_1__Integer_1__String_1_", false, ps -> res("String", "one"));
        register("meta::pure::functions::string::right_String_1__Integer_1__String_1_", false, ps -> res("String", "one"));
        register(m(m(h("meta::pure::functions::string::lpad_String_1__Integer_1__String_1_", false, ps -> res("String", "one"), ps -> ps.size() == 2)),
                m(h("meta::pure::functions::string::lpad_String_1__Integer_1__String_1__String_1_", false, ps -> res("String", "one"), ps -> true))));

        register(m(m(h("meta::pure::functions::string::rpad_String_1__Integer_1__String_1_", false, ps -> res("String", "one"), ps -> ps.size() == 2)),
                m(h("meta::pure::functions::string::rpad_String_1__Integer_1__String_1__String_1_", false, ps -> res("String", "one"), ps -> true))));
        register("meta::pure::functions::string::matches_String_1__String_1__Boolean_1_", true, ps -> res("Boolean", "one"));
        register("meta::pure::functions::string::isAlphaNumeric_String_1__Boolean_1_", false, ps -> res("Boolean", "one"));
        register("meta::pure::functions::string::isNoLongerThan_String_$0_1$__Integer_1__Boolean_1_", false, ps -> res("Boolean", "one"));
        register("meta::pure::functions::string::isNoShorterThan_String_$0_1$__Integer_1__Boolean_1_", false, pp -> res("Boolean", "one"));
        register(m(m(h("meta::pure::functions::string::encodeUrl_String_1__String_1_", false, ps -> res("String", "one"), ps -> ps.size() == 1)),
                m(h("meta::pure::functions::string::encodeUrl_String_1__String_1__String_1_", true, ps -> res("String", "one"), ps -> ps.size() == 2))));
        register(m(m(h("meta::pure::functions::string::decodeUrl_String_1__String_1_", false, ps -> res("String", "one"), ps -> ps.size() == 1)),
                m(h("meta::pure::functions::string::decodeUrl_String_1__String_1__String_1_", true, ps -> res("String", "one"), ps -> ps.size() == 2))));
        register("meta::pure::functions::string::jaroWinklerSimilarity_String_1__String_1__Float_1_", true, ps -> res("Float", "one"));
        register("meta::pure::functions::string::levenshteinDistance_String_1__String_1__Integer_1_", true, ps -> res("Integer", "one"));

        register("meta::pure::functions::string::generation::generateGuid__String_1_", true, ps -> res("String", "one"));
    }

    private void registerTrigo()
    {
        register("meta::pure::functions::math::cos_Number_1__Float_1_", true, ps -> res("Float", "one"));
        register("meta::pure::functions::math::cosh_Number_1__Float_1_", true, ps -> res("Float", "one"));
        register("meta::pure::functions::math::cot_Number_1__Float_1_", true, ps -> res("Float", "one"));
        register("meta::pure::functions::math::sin_Number_1__Float_1_", true, ps -> res("Float", "one"));
        register("meta::pure::functions::math::sinh_Number_1__Float_1_", true, ps -> res("Float", "one"));
        register("meta::pure::functions::math::tan_Number_1__Float_1_", true, ps -> res("Float", "one"));
        register("meta::pure::functions::math::tanh_Number_1__Float_1_", true, ps -> res("Float", "one"));
        register("meta::pure::functions::math::asin_Number_1__Float_1_", true, ps -> res("Float", "one"));
        register("meta::pure::functions::math::acos_Number_1__Float_1_", true, ps -> res("Float", "one"));
        register("meta::pure::functions::math::atan_Number_1__Float_1_", true, ps -> res("Float", "one"));
        register("meta::pure::functions::math::atan2_Number_1__Number_1__Float_1_", true, ps -> res("Float", "one"));
        register("meta::pure::functions::math::toDegrees_Number_1__Float_1_", false, ps -> res("Float", "one"));
        register("meta::pure::functions::math::toRadians_Number_1__Float_1_", false, ps -> res("Float", "one"));
        register("meta::pure::functions::math::pi__Float_1_", false, ps -> res("Float", "one"));
        register("meta::pure::functions::math::earthRadius__Float_1_", false, ps -> res("Float", "one"));
        register("meta::pure::functions::math::distanceHaversineDegrees_Number_1__Number_1__Number_1__Number_1__Number_1_", false, ps -> res("Number", "one"));
        register("meta::pure::functions::math::distanceHaversineRadians_Number_1__Number_1__Number_1__Number_1__Number_1_", false, ps -> res("Number", "one"));
        register("meta::pure::functions::math::distanceSphericalLawOfCosinesDegrees_Number_1__Number_1__Number_1__Number_1__Number_1_", false, ps -> res("Number", "one"));
        register("meta::pure::functions::math::distanceSphericalLawOfCosinesRadians_Number_1__Number_1__Number_1__Number_1__Number_1_", false, ps -> res("Number", "one"));
    }

    private void registerMathBitwise()
    {
        register("meta::pure::functions::math::bitAnd_Integer_1__Integer_1__Integer_1_", true, ps -> res("Integer", "one"));
        register("meta::pure::functions::math::bitNot_Integer_1__Integer_1_", true, ps -> res("Integer", "one"));
        register("meta::pure::functions::math::bitOr_Integer_1__Integer_1__Integer_1_", true, ps -> res("Integer", "one"));
        register("meta::pure::functions::math::bitXor_Integer_1__Integer_1__Integer_1_", true, ps -> res("Integer", "one"));
        register("meta::pure::functions::math::bitShiftLeft_Integer_1__Integer_1__Integer_1_", true, ps -> res("Integer", "one"));
        register("meta::pure::functions::math::bitShiftRight_Integer_1__Integer_1__Integer_1_", true, ps -> res("Integer", "one"));
    }

    private void registerMathInequalities()
    {
        register(h("meta::pure::functions::boolean::greaterThan_Boolean_1__Boolean_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), "Boolean") && typeOne(ps.get(1), "Boolean")),
                h("meta::pure::functions::boolean::greaterThan_Boolean_1__Boolean_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), "Boolean") && typeZeroOne(ps.get(1), "Boolean")),
                h("meta::pure::functions::boolean::greaterThan_Boolean_$0_1$__Boolean_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), "Boolean") && typeOne(ps.get(1), "Boolean")),
                h("meta::pure::functions::boolean::greaterThan_Boolean_$0_1$__Boolean_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), "Boolean") && typeZeroOne(ps.get(1), "Boolean")),
                h("meta::pure::functions::boolean::greaterThan_Date_1__Date_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), taxoMap.get("cov_Date")) && typeOne(ps.get(1), taxoMap.get("cov_Date"))),
                h("meta::pure::functions::boolean::greaterThan_Date_1__Date_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), taxoMap.get("cov_Date")) && typeZeroOne(ps.get(1), taxoMap.get("cov_Date"))),
                h("meta::pure::functions::boolean::greaterThan_Date_$0_1$__Date_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), taxoMap.get("cov_Date")) && typeOne(ps.get(1), taxoMap.get("cov_Date"))),
                h("meta::pure::functions::boolean::greaterThan_Date_$0_1$__Date_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), taxoMap.get("cov_Date")) && typeZeroOne(ps.get(1), taxoMap.get("cov_Date"))),
                h("meta::pure::functions::boolean::greaterThan_Number_1__Number_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), taxoMap.get("cov_Number")) && typeOne(ps.get(1), taxoMap.get("cov_Number"))),
                h("meta::pure::functions::boolean::greaterThan_Number_1__Number_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), taxoMap.get("cov_Number")) && typeZeroOne(ps.get(1), taxoMap.get("cov_Number"))),
                h("meta::pure::functions::boolean::greaterThan_Number_$0_1$__Number_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), taxoMap.get("cov_Number")) && typeOne(ps.get(1), taxoMap.get("cov_Number"))),
                h("meta::pure::functions::boolean::greaterThan_Number_$0_1$__Number_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), taxoMap.get("cov_Number")) && typeZeroOne(ps.get(1), taxoMap.get("cov_Number"))),
                h("meta::pure::functions::boolean::greaterThan_String_1__String_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), "String") && typeOne(ps.get(1), "String")),
                h("meta::pure::functions::boolean::greaterThan_String_1__String_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), "String") && typeZeroOne(ps.get(1), "String")),
                h("meta::pure::functions::boolean::greaterThan_String_$0_1$__String_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), "String") && typeOne(ps.get(1), "String")),
                h("meta::pure::functions::boolean::greaterThan_String_$0_1$__String_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), "String") && typeZeroOne(ps.get(1), "String")));

        register(h("meta::pure::functions::boolean::greaterThanEqual_Boolean_1__Boolean_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), "Boolean") && typeOne(ps.get(1), "Boolean")),
                h("meta::pure::functions::boolean::greaterThanEqual_Boolean_1__Boolean_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), "Boolean") && typeZeroOne(ps.get(1), "Boolean")),
                h("meta::pure::functions::boolean::greaterThanEqual_Boolean_$0_1$__Boolean_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), "Boolean") && typeOne(ps.get(1), "Boolean")),
                h("meta::pure::functions::boolean::greaterThanEqual_Boolean_$0_1$__Boolean_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), "Boolean") && typeZeroOne(ps.get(1), "Boolean")),
                h("meta::pure::functions::boolean::greaterThanEqual_Date_1__Date_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), taxoMap.get("cov_Date")) && typeOne(ps.get(1), taxoMap.get("cov_Date"))),
                h("meta::pure::functions::boolean::greaterThanEqual_Date_1__Date_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), taxoMap.get("cov_Date")) && typeZeroOne(ps.get(1), taxoMap.get("cov_Date"))),
                h("meta::pure::functions::boolean::greaterThanEqual_Date_$0_1$__Date_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), taxoMap.get("cov_Date")) && typeOne(ps.get(1), taxoMap.get("cov_Date"))),
                h("meta::pure::functions::boolean::greaterThanEqual_Date_$0_1$__Date_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), taxoMap.get("cov_Date")) && typeZeroOne(ps.get(1), taxoMap.get("cov_Date"))),
                h("meta::pure::functions::boolean::greaterThanEqual_Number_1__Number_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), taxoMap.get("cov_Number")) && typeOne(ps.get(1), taxoMap.get("cov_Number"))),
                h("meta::pure::functions::boolean::greaterThanEqual_Number_1__Number_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), taxoMap.get("cov_Number")) && typeZeroOne(ps.get(1), taxoMap.get("cov_Number"))),
                h("meta::pure::functions::boolean::greaterThanEqual_Number_$0_1$__Number_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), taxoMap.get("cov_Number")) && typeOne(ps.get(1), taxoMap.get("cov_Number"))),
                h("meta::pure::functions::boolean::greaterThanEqual_Number_$0_1$__Number_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), taxoMap.get("cov_Number")) && typeZeroOne(ps.get(1), taxoMap.get("cov_Number"))),
                h("meta::pure::functions::boolean::greaterThanEqual_String_1__String_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), "String") && typeOne(ps.get(1), "String")),
                h("meta::pure::functions::boolean::greaterThanEqual_String_1__String_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), "String") && typeZeroOne(ps.get(1), "String")),
                h("meta::pure::functions::boolean::greaterThanEqual_String_$0_1$__String_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), "String") && typeOne(ps.get(1), "String")),
                h("meta::pure::functions::boolean::greaterThanEqual_String_$0_1$__String_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), "String") && typeZeroOne(ps.get(1), "String")));

        register(h("meta::pure::functions::boolean::lessThan_Boolean_1__Boolean_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), "Boolean") && typeOne(ps.get(1), "Boolean")),
                h("meta::pure::functions::boolean::lessThan_Boolean_1__Boolean_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), "Boolean") && typeZeroOne(ps.get(1), "Boolean")),
                h("meta::pure::functions::boolean::lessThan_Boolean_$0_1$__Boolean_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), "Boolean") && typeOne(ps.get(1), "Boolean")),
                h("meta::pure::functions::boolean::lessThan_Boolean_$0_1$__Boolean_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), "Boolean") && typeZeroOne(ps.get(1), "Boolean")),
                h("meta::pure::functions::boolean::lessThan_Date_1__Date_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), taxoMap.get("cov_Date")) && typeOne(ps.get(1), taxoMap.get("cov_Date"))),
                h("meta::pure::functions::boolean::lessThan_Date_1__Date_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), taxoMap.get("cov_Date")) && typeZeroOne(ps.get(1), taxoMap.get("cov_Date"))),
                h("meta::pure::functions::boolean::lessThan_Date_$0_1$__Date_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), taxoMap.get("cov_Date")) && typeOne(ps.get(1), taxoMap.get("cov_Date"))),
                h("meta::pure::functions::boolean::lessThan_Date_$0_1$__Date_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), taxoMap.get("cov_Date")) && typeZeroOne(ps.get(1), taxoMap.get("cov_Date"))),
                h("meta::pure::functions::boolean::lessThan_Number_1__Number_1__Boolean_1_", true, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), taxoMap.get("cov_Number")) && typeOne(ps.get(1), taxoMap.get("cov_Number"))),
                h("meta::pure::functions::boolean::lessThan_Number_1__Number_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), taxoMap.get("cov_Number")) && typeZeroOne(ps.get(1), taxoMap.get("cov_Number"))),
                h("meta::pure::functions::boolean::lessThan_Number_$0_1$__Number_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), taxoMap.get("cov_Number")) && typeOne(ps.get(1), taxoMap.get("cov_Number"))),
                h("meta::pure::functions::boolean::lessThan_Number_$0_1$__Number_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), taxoMap.get("cov_Number")) && typeZeroOne(ps.get(1), taxoMap.get("cov_Number"))),
                h("meta::pure::functions::boolean::lessThan_String_1__String_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), "String") && typeOne(ps.get(1), "String")),
                h("meta::pure::functions::boolean::lessThan_String_1__String_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), "String") && typeZeroOne(ps.get(1), "String")),
                h("meta::pure::functions::boolean::lessThan_String_$0_1$__String_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), "String") && typeOne(ps.get(1), "String")),
                h("meta::pure::functions::boolean::lessThan_String_$0_1$__String_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), "String") && typeZeroOne(ps.get(1), "String")));

        register(h("meta::pure::functions::boolean::lessThanEqual_Boolean_1__Boolean_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), "Boolean") && typeOne(ps.get(1), "Boolean")),
                h("meta::pure::functions::boolean::lessThanEqual_Boolean_1__Boolean_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), "Boolean") && typeZeroOne(ps.get(1), "Boolean")),
                h("meta::pure::functions::boolean::lessThanEqual_Boolean_$0_1$__Boolean_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), "Boolean") && typeOne(ps.get(1), "Boolean")),
                h("meta::pure::functions::boolean::lessThanEqual_Boolean_$0_1$__Boolean_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), "Boolean") && typeZeroOne(ps.get(1), "Boolean")),
                h("meta::pure::functions::boolean::lessThanEqual_Date_1__Date_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), taxoMap.get("cov_Date")) && typeOne(ps.get(1), taxoMap.get("cov_Date"))),
                h("meta::pure::functions::boolean::lessThanEqual_Date_1__Date_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), taxoMap.get("cov_Date")) && typeZeroOne(ps.get(1), taxoMap.get("cov_Date"))),
                h("meta::pure::functions::boolean::lessThanEqual_Date_$0_1$__Date_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), taxoMap.get("cov_Date")) && typeOne(ps.get(1), taxoMap.get("cov_Date"))),
                h("meta::pure::functions::boolean::lessThanEqual_Date_$0_1$__Date_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), taxoMap.get("cov_Date")) && typeZeroOne(ps.get(1), taxoMap.get("cov_Date"))),
                h("meta::pure::functions::boolean::lessThanEqual_Number_1__Number_1__Boolean_1_", true, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), taxoMap.get("cov_Number")) && typeOne(ps.get(1), taxoMap.get("cov_Number"))),
                h("meta::pure::functions::boolean::lessThanEqual_Number_1__Number_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), taxoMap.get("cov_Number")) && typeZeroOne(ps.get(1), taxoMap.get("cov_Number"))),
                h("meta::pure::functions::boolean::lessThanEqual_Number_$0_1$__Number_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), taxoMap.get("cov_Number")) && typeOne(ps.get(1), taxoMap.get("cov_Number"))),
                h("meta::pure::functions::boolean::lessThanEqual_Number_$0_1$__Number_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), taxoMap.get("cov_Number")) && typeZeroOne(ps.get(1), taxoMap.get("cov_Number"))),
                h("meta::pure::functions::boolean::lessThanEqual_String_1__String_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), "String") && typeOne(ps.get(1), "String")),
                h("meta::pure::functions::boolean::lessThanEqual_String_1__String_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeOne(ps.get(0), "String") && typeZeroOne(ps.get(1), "String")),
                h("meta::pure::functions::boolean::lessThanEqual_String_$0_1$__String_1__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), "String") && typeOne(ps.get(1), "String")),
                h("meta::pure::functions::boolean::lessThanEqual_String_$0_1$__String_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), "String") && typeZeroOne(ps.get(1), "String")));

        register(h("meta::pure::functions::boolean::between_StrictDate_$0_1$__StrictDate_$0_1$__StrictDate_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), taxoMap.get("cov_StrictDate")) && typeZeroOne(ps.get(1), taxoMap.get("cov_StrictDate")) && typeZeroOne(ps.get(2), taxoMap.get("cov_StrictDate"))),
                h("meta::pure::functions::boolean::between_DateTime_$0_1$__DateTime_$0_1$__DateTime_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), taxoMap.get("cov_DateTime")) && typeZeroOne(ps.get(1), taxoMap.get("cov_DateTime")) && typeZeroOne(ps.get(2), taxoMap.get("cov_DateTime"))),
                h("meta::pure::functions::boolean::between_Number_$0_1$__Number_$0_1$__Number_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), taxoMap.get("cov_Number")) && typeZeroOne(ps.get(1), taxoMap.get("cov_Number")) && typeZeroOne(ps.get(2), taxoMap.get("cov_Number"))),
                h("meta::pure::functions::boolean::between_String_$0_1$__String_$0_1$__String_$0_1$__Boolean_1_", false, ps -> res("Boolean", "one"), ps -> typeZeroOne(ps.get(0), Sets.mutable.with("String", "Nil")) && typeZeroOne(ps.get(1), Sets.mutable.with("String", "Nil")) && typeZeroOne(ps.get(2), Sets.mutable.with("String", "Nil"))));

    }

    private void registerMaxMin()
    {
        register(m(
                m(h("meta::pure::functions::math::max_Integer_1__Integer_1__Integer_1_", false, ps -> res("Integer", "one"), ps -> ps.size() == 2 && typeOne(ps.get(0), "Integer") && typeOne(ps.get(1), "Integer")),
                        h("meta::pure::functions::math::max_Float_1__Float_1__Float_1_", false, ps -> res("Float", "one"), ps -> ps.size() == 2 && typeOne(ps.get(0), "Float") && typeOne(ps.get(1), "Float")),
                        h("meta::pure::functions::math::max_Number_1__Number_1__Number_1_", false, ps -> res("Number", "one"), ps -> ps.size() == 2 && typeOne(ps.get(0), "Number") && typeOne(ps.get(1), "Number")),
                        h("meta::pure::functions::date::max_DateTime_1__DateTime_1__DateTime_1_", false, ps -> res("DateTime", "one"), ps -> ps.size() == 2 && typeOne(ps.get(0), "DateTime") && typeOne(ps.get(1), "DateTime")),
                        h("meta::pure::functions::date::max_StrictDate_1__StrictDate_1__StrictDate_1_", false, ps -> res("StrictDate", "one"), ps -> ps.size() == 2 && typeOne(ps.get(0), "StrictDate") && typeOne(ps.get(1), "StrictDate")),
                        h("meta::pure::functions::date::max_Date_1__Date_1__Date_1_", false, ps -> res("Date", "one"), ps -> ps.size() == 2 && typeOne(ps.get(0), "Date") && typeOne(ps.get(1), "Date"))),
                m(grp(TwoParameterLambdaInference, h("meta::pure::functions::collection::max_T_$1_MANY$__Function_1__T_1_", false, ps -> res(ps.get(0)._genericType(), "one"), ps -> ps.size() == 2))),
                m(h("meta::pure::functions::math::max_Integer_$1_MANY$__Integer_1_", false, ps -> res("Integer", "one"), ps -> typeOneMany(ps.get(0), "Integer")),
                        h("meta::pure::functions::math::max_Integer_MANY__Integer_$0_1$_", false, ps -> res("Integer", "zeroOne"), ps -> typeMany(ps.get(0), "Integer")),
                        h("meta::pure::functions::math::max_Float_$1_MANY$__Float_1_", false, ps -> res("Float", "one"), ps -> typeOneMany(ps.get(0), "Float")),
                        h("meta::pure::functions::math::max_Float_MANY__Float_$0_1$_", false, ps -> res("Float", "zeroOne"), ps -> typeMany(ps.get(0), "Float")),
                        h("meta::pure::functions::math::max_Number_$1_MANY$__Number_1_", false, ps -> res("Number", "one"), ps -> typeOneMany(ps.get(0), "Number")),
                        h("meta::pure::functions::math::max_Number_MANY__Number_$0_1$_", false, ps -> res("Number", "zeroOne"), ps -> typeMany(ps.get(0), "Number")),
                        h("meta::pure::functions::date::max_DateTime_MANY__DateTime_$0_1$_", false, ps -> res("DateTime", "zeroOne"), ps -> typeMany(ps.get(0), "DateTime")),
                        h("meta::pure::functions::date::max_StrictDate_MANY__StrictDate_$0_1$_", false, ps -> res("StrictDate", "zeroOne"), ps -> typeMany(ps.get(0), "StrictDate")),
                        h("meta::pure::functions::date::max_Date_MANY__Date_$0_1$_", false, ps -> res("Date", "zeroOne"), ps -> typeMany(ps.get(0), "Date")),
                        h("meta::pure::functions::collection::max_X_$1_MANY$__X_1_", false, ps -> res(ps.get(0)._genericType(), "one"), ps -> isOne(ps.get(0)._multiplicity())),
                        h("meta::pure::functions::collection::max_X_MANY__X_$0_1$_", false, ps -> res(ps.get(0)._genericType(), "zeroOne"), ps -> true))));

        register(m(
                m(h("meta::pure::functions::math::min_Integer_1__Integer_1__Integer_1_", false, ps -> res("Integer", "one"), ps -> ps.size() == 2 && typeOne(ps.get(0), "Integer") && typeOne(ps.get(1), "Integer")),
                        h("meta::pure::functions::math::min_Float_1__Float_1__Float_1_", false, ps -> res("Float", "one"), ps -> ps.size() == 2 && typeOne(ps.get(0), "Float") && typeOne(ps.get(1), "Float")),
                        h("meta::pure::functions::math::min_Number_1__Number_1__Number_1_", false, ps -> res("Number", "one"), ps -> ps.size() == 2 && typeOne(ps.get(0), "Number") && typeOne(ps.get(1), "Number")),
                        h("meta::pure::functions::date::min_DateTime_1__DateTime_1__DateTime_1_", false, ps -> res("DateTime", "one"), ps -> ps.size() == 2 && typeOne(ps.get(0), "DateTime") && typeOne(ps.get(1), "DateTime")),
                        h("meta::pure::functions::date::min_StrictDate_1__StrictDate_1__StrictDate_1_", false, ps -> res("StrictDate", "one"), ps -> ps.size() == 2 && typeOne(ps.get(0), "StrictDate") && typeOne(ps.get(1), "StrictDate")),
                        h("meta::pure::functions::date::min_Date_1__Date_1__Date_1_", false, ps -> res("Date", "one"), ps -> ps.size() == 2 && typeOne(ps.get(0), "Date") && typeOne(ps.get(1), "Date"))),
                m(grp(TwoParameterLambdaInference, h("meta::pure::functions::collection::min_T_$1_MANY$__Function_1__T_1_", false, ps -> res(ps.get(0)._genericType(), "one"), ps -> ps.size() == 2))),
                m(h("meta::pure::functions::math::min_Integer_$1_MANY$__Integer_1_", false, ps -> res("Integer", "one"), ps -> typeOneMany(ps.get(0), "Integer")),
                        h("meta::pure::functions::math::min_Integer_MANY__Integer_$0_1$_", false, ps -> res("Integer", "zeroOne"), ps -> typeMany(ps.get(0), "Integer")),
                        h("meta::pure::functions::math::min_Float_$1_MANY$__Float_1_", false, ps -> res("Float", "one"), ps -> typeOneMany(ps.get(0), "Float")),
                        h("meta::pure::functions::math::min_Float_MANY__Float_$0_1$_", false, ps -> res("Float", "zeroOne"), ps -> typeMany(ps.get(0), "Float")),
                        h("meta::pure::functions::math::min_Number_$1_MANY$__Number_1_", false, ps -> res("Number", "one"), ps -> typeOneMany(ps.get(0), "Number")),
                        h("meta::pure::functions::math::min_Number_MANY__Number_$0_1$_", false, ps -> res("Number", "zeroOne"), ps -> typeMany(ps.get(0), "Number")),
                        h("meta::pure::functions::date::min_DateTime_MANY__DateTime_$0_1$_", false, ps -> res("DateTime", "zeroOne"), ps -> typeMany(ps.get(0), "DateTime")),
                        h("meta::pure::functions::date::min_StrictDate_MANY__StrictDate_$0_1$_", false, ps -> res("StrictDate", "zeroOne"), ps -> typeMany(ps.get(0), "StrictDate")),
                        h("meta::pure::functions::date::min_Date_MANY__Date_$0_1$_", false, ps -> res("Date", "zeroOne"), ps -> typeMany(ps.get(0), "Date")),
                        h("meta::pure::functions::collection::min_X_$1_MANY$__X_1_", false, ps -> res(ps.get(0)._genericType(), "one"), ps -> isOne(ps.get(0)._multiplicity())),
                        h("meta::pure::functions::collection::min_X_MANY__X_$0_1$_", false, ps -> res(ps.get(0)._genericType(), "zeroOne"), ps -> true))));
    }

    private void registerAlgebra()
    {
        register("meta::pure::functions::math::sign_Number_1__Integer_1_", true, ps -> res("Integer", "one"));

        register(h("meta::pure::functions::math::minus_Integer_MANY__Integer_1_", true, ps -> res("Integer", "one"), ps -> typeMany(ps.get(0), "Integer")),
                h("meta::pure::functions::math::minus_Float_MANY__Float_1_", true, ps -> res("Float", "one"), ps -> typeMany(ps.get(0), "Float")),
                h("meta::pure::functions::math::minus_Decimal_MANY__Decimal_1_", true, ps -> res("Decimal", "one"), ps -> typeMany(ps.get(0), "Decimal")),
                h("meta::pure::functions::math::minus_Number_MANY__Number_1_", true, ps -> res("Number", "one"), ps -> typeMany(ps.get(0), "Number")));

        register(h("meta::pure::functions::math::times_Integer_MANY__Integer_1_", true, ps -> res("Integer", "one"), ps -> typeMany(ps.get(0), "Integer")),
                h("meta::pure::functions::math::times_Float_MANY__Float_1_", true, ps -> res("Float", "one"), ps -> typeMany(ps.get(0), "Float")),
                h("meta::pure::functions::math::times_Number_MANY__Number_1_", true, ps -> res("Number", "one"), ps -> typeMany(ps.get(0), "Number")),
                h("meta::pure::functions::math::times_Decimal_MANY__Decimal_1_", true, ps -> res("Decimal", "one"), ps -> typeMany(ps.get(0), "Decimal")));


        register(m(m(h("meta::pure::functions::math::divide_Number_1__Number_1__Float_1_", true, ps -> res("Float", "one"), ps -> ps.size() == 2)),
                m(h("meta::pure::functions::math::divide_Decimal_1__Decimal_1__Integer_1__Decimal_1_", true, ps -> res("Decimal", "one"), ps -> ps.size() == 3))));


        register(h("meta::pure::functions::string::plus_String_MANY__String_1_", false, ps -> res("String", "one"), ps -> ps.size() == 1 && typeMany(ps.get(0), "String")),
                h("meta::pure::functions::math::plus_Integer_MANY__Integer_1_", true, ps -> res("Integer", "one"), ps -> ps.size() == 1 && typeMany(ps.get(0), "Integer")),
                h("meta::pure::functions::math::plus_Float_MANY__Float_1_", true, ps -> res("Float", "one"), ps -> ps.size() == 1 && typeMany(ps.get(0), "Float")),
                h("meta::pure::functions::math::plus_Decimal_MANY__Decimal_1_", true, ps -> res("Decimal", "one"), ps -> ps.size() == 1 && typeMany(ps.get(0), "Decimal")),
                h("meta::pure::functions::math::plus_Number_MANY__Number_1_", true, ps -> res("Number", "one"), ps -> ps.size() == 1 && typeMany(ps.get(0), "Number")));

        register(h("meta::pure::functions::math::abs_Float_1__Float_1_", true, ps -> res("Float", "one"), ps -> typeOne(ps.get(0), "Float")),
                h("meta::pure::functions::math::abs_Integer_1__Integer_1_", true, ps -> res("Integer", "one"), ps -> typeOne(ps.get(0), "Integer")),
                h("meta::pure::functions::math::abs_Decimal_1__Decimal_1_", true, ps -> res("Decimal", "one"), ps -> typeOne(ps.get(0), "Decimal")),
                h("meta::pure::functions::math::abs_Number_1__Number_1_", true, ps -> res("Number", "one"), ps -> typeOne(ps.get(0), "Number"))
        );

        register("meta::pure::functions::math::cbrt_Number_1__Float_1_", true, ps -> res("Float", "one"));
        register("meta::pure::functions::math::ceiling_Number_1__Integer_1_", true, ps -> res("Integer", "one"));
        register("meta::pure::functions::math::exp_Number_1__Float_1_", true, ps -> res("Float", "one"));
        register("meta::pure::functions::math::floor_Number_1__Integer_1_", true, ps -> res("Integer", "one"));
        register("meta::pure::functions::math::log_Number_1__Float_1_", true, ps -> res("Float", "one"));
        register("meta::pure::functions::math::log10_Number_1__Float_1_", true, ps -> res("Float", "one"));
        register("meta::pure::functions::math::mod_Integer_1__Integer_1__Integer_1_", true, ps -> res("Integer", "one"));
        register("meta::pure::functions::math::pow_Number_1__Number_1__Number_1_", true, ps -> res("Number", "one"));
        register("meta::pure::functions::math::rem_Number_1__Number_1__Number_1_", true, ps -> res("Number", "one"));
        register("meta::pure::functions::math::sqrt_Number_1__Float_1_", true, ps -> res("Float", "one"));

        register(m(m(h("meta::pure::functions::math::round_Float_1__Integer_1__Float_1_", true, ps -> res("Float", "one"), ps -> ps.size() == 2 && typeOne(ps.get(0), "Float"))),
                m(h("meta::pure::functions::math::round_Decimal_1__Integer_1__Decimal_1_", true, ps -> res("Decimal", "one"), ps -> ps.size() == 2 && typeOne(ps.get(0), "Decimal"))),
                m(h("meta::pure::functions::math::round_Number_1__Integer_1_", true, ps -> res("Integer", "one"), ps -> true))));
        register("meta::pure::functions::math::toFloat_Number_1__Float_1_", true, ps -> res("Float", "one"));
        register("meta::pure::functions::math::toDecimal_Number_1__Decimal_1_", true, ps -> res("Decimal", "one"));


    }

    private void registerOlapMath()
    {
        ReturnInference resolve = ps -> res(CompileContext.newGenericType(this.pureModel.getType("meta::pure::functions::collection::Map"), FastList.newListWith(this.pureModel.getGenericType("meta::pure::metamodel::type::Any"), this.pureModel.getGenericType("Integer")), pureModel), "one");

        register(m(
                        m(h("meta::pure::functions::math::olap::rank_Any_MANY__Map_1_", false, resolve)),
                        m(h("meta::pure::functions::relation::rank_Relation_1___Window_1__T_1__Integer_1_", true, ps -> res("Integer", "one")))
                )
        );
        register(
                m(
                        m(h("meta::pure::functions::math::olap::denseRank_Any_MANY__Map_1_", false, resolve)),
                        m(h("meta::pure::functions::relation::denseRank_Relation_1___Window_1__T_1__Integer_1_", true, ps -> res("Integer", "one")))
                )
        );
        register(
                m(
                        m(h("meta::pure::functions::math::olap::rowNumber_Any_MANY__Map_1_", false, resolve)),
                        m(h("meta::pure::functions::relation::rowNumber_Relation_1__T_1__Integer_1_", true, ps -> res("Integer", "one")))
                )
        );
        register(h("meta::pure::functions::math::olap::averageRank_Any_MANY__Map_1_", false, resolve));
    }

    private void registerAggregations()
    {
        register(h("meta::pure::functions::math::average_Float_MANY__Float_1_", false, ps -> res("Float", "one"), ps -> typeMany(ps.get(0), "Float")),
                h("meta::pure::functions::math::average_Integer_MANY__Float_1_", false, ps -> res("Float", "one"), ps -> typeMany(ps.get(0), "Integer")),
                h("meta::pure::functions::math::average_Number_MANY__Float_1_", false, ps -> res("Float", "one"), ps -> typeMany(ps.get(0), "Number")));

        register(h("meta::pure::functions::math::mean_Float_MANY__Float_1_", false, ps -> res("Float", "one"), ps -> typeMany(ps.get(0), "Float")),
                h("meta::pure::functions::math::mean_Integer_MANY__Float_1_", false, ps -> res("Float", "one"), ps -> typeMany(ps.get(0), "Integer")),
                h("meta::pure::functions::math::mean_Number_MANY__Float_1_", false, ps -> res("Float", "one"), ps -> typeMany(ps.get(0), "Number")));

        register(
                m(
                        m(h("meta::pure::functions::math::maxBy_RowMapper_MANY__T_$0_1$_", false, ps -> res(ps.get(0)._genericType()._typeArguments().getFirst(), "zeroOne"), ps -> ps.size() == 1)),
                        m(h("meta::pure::functions::math::maxBy_RowMapper_MANY__Integer_1__T_MANY_", false, ps -> res(ps.get(0)._genericType()._typeArguments().getFirst(), "zeroMany"), ps -> typeMany(ps.get(0), "meta::pure::functions::math::mathUtility::RowMapper")),
                          h("meta::pure::functions::math::maxBy_T_MANY__Number_MANY__T_$0_1$_", false, ps -> res(ps.get(0)._genericType(), "zeroOne"), ps -> typeMany(ps.get(1), "Number"))
                        ),
                        m(h("meta::pure::functions::math::maxBy_T_MANY__Number_MANY__Integer_1__T_MANY_", false, ps -> res(ps.get(0)._genericType(), "zeroMany"), ps -> ps.size() == 3))
                )
        );

        register(
                m(
                        m(h("meta::pure::functions::math::minBy_RowMapper_MANY__T_$0_1$_", false, ps -> res(ps.get(0)._genericType()._typeArguments().getFirst(), "zeroOne"), ps -> ps.size() == 1)),
                        m(h("meta::pure::functions::math::minBy_RowMapper_MANY__Integer_1__T_MANY_", false, ps -> res(ps.get(0)._genericType()._typeArguments().getFirst(), "zeroMany"), ps -> typeMany(ps.get(0), "meta::pure::functions::math::mathUtility::RowMapper")),
                                h("meta::pure::functions::math::minBy_T_MANY__Number_MANY__T_$0_1$_", false, ps -> res(ps.get(0)._genericType(), "zeroOne"), ps -> typeMany(ps.get(1), "Number"))
                        ),
                        m(h("meta::pure::functions::math::minBy_T_MANY__Number_MANY__Integer_1__T_MANY_", false, ps -> res(ps.get(0)._genericType(), "zeroMany"), ps -> ps.size() == 3))
                )
        );

        register(h("meta::pure::functions::math::sum_Float_MANY__Float_1_", false, ps -> res("Float", "one"), ps -> typeMany(ps.get(0), "Float")),
                h("meta::pure::functions::math::sum_Integer_MANY__Integer_1_", false, ps -> res("Integer", "one"), ps -> typeMany(ps.get(0), "Integer")),
                h("meta::pure::functions::math::sum_Number_MANY__Number_1_", false, ps -> res("Number", "one"), ps -> typeMany(ps.get(0), "Number")));

        register(h("meta::pure::functions::math::median_Float_MANY__Float_1_", false, ps -> res("Float", "one"), ps -> typeMany(ps.get(0), "Float")),
                h("meta::pure::functions::math::median_Integer_MANY__Float_1_", false, ps -> res("Float", "one"), ps -> typeMany(ps.get(0), "Integer")),
                h("meta::pure::functions::math::median_Number_MANY__Float_1_", false, ps -> res("Float", "one"), ps -> typeMany(ps.get(0), "Number"))
        );

        register(h("meta::pure::functions::math::mode_Float_MANY__Float_1_", false, ps -> res("Float", "one"), ps -> typeMany(ps.get(0), "Float")),
                h("meta::pure::functions::math::mode_Integer_MANY__Integer_1_", false, ps -> res("Integer", "one"), ps -> typeMany(ps.get(0), "Integer")),
                h("meta::pure::functions::math::mode_Number_MANY__Number_1_", false, ps -> res("Number", "one"), ps -> typeMany(ps.get(0), "Number"))
        );

        register(m(m(h("meta::pure::functions::math::wavg_Number_MANY__Number_MANY__Float_1_", false, ps -> res("Float", "one"), ps -> typeMany(ps.get(0), "Number"))),
                m(h("meta::pure::functions::math::wavg_RowMapper_MANY__Float_1_", false, ps -> res("Float", "one"), ps -> ps.size() == 1))));

        register(h("meta::pure::functions::math::wavgUtility::wavgRowMapper_Number_$0_1$__Number_$0_1$__WavgRowMapper_1_", false, ps -> res("meta::pure::functions::math::wavgUtility::WavgRowMapper", "one"), ps -> typeZeroOne(ps.get(0), "Number")));

        register(h("meta::pure::functions::math::mathUtility::rowMapper_T_$0_1$__U_$0_1$__RowMapper_1_", false, ps -> res(CompileContext.newGenericType(this.pureModel.getType("meta::pure::functions::math::mathUtility::RowMapper"), Lists.fixedSize.ofAll(ps.stream().map(ValueSpecificationAccessor::_genericType).collect(Collectors.toList())), this.pureModel), "one"), ps -> Lists.mutable.with(ps.get(0)._genericType(), ps.get(1)._genericType()), ps -> true));

        register(h("meta::pure::functions::math::variance_Number_MANY__Boolean_1__Number_1_", false, ps -> res("Number", "one")));

        register(m(m(h("meta::pure::functions::math::percentile_Number_MANY__Float_1__Boolean_1__Boolean_1__Number_$0_1$_", false, ps -> res("Number", "zeroOne"), ps -> ps.size() == 4)),
                m(h("meta::pure::functions::math::percentile_Number_MANY__Float_1__Number_$0_1$_", false, ps -> res("Number", "zeroOne"), ps -> true))));
    }

    private void registerStdDeviations()
    {
        register(h("meta::pure::functions::math::stdDevPopulation_Number_$1_MANY$__Number_1_", false, ps -> res("Number", "one"), ps -> typeOneMany(ps.get(0), taxoMap.get("cov_Number"))),
                h("meta::pure::functions::math::stdDevPopulation_Number_MANY__Number_1_", false, ps -> res("Number", "one"), ps -> typeMany(ps.get(0), taxoMap.get("cov_Number"))));

        register(h("meta::pure::functions::math::stdDevSample_Number_$1_MANY$__Number_1_", false, ps -> res("Number", "one"), ps -> typeOneMany(ps.get(0), taxoMap.get("cov_Number"))),
                h("meta::pure::functions::math::stdDevSample_Number_MANY__Number_1_", false, ps -> res("Number", "one"), ps -> typeMany(ps.get(0), taxoMap.get("cov_Number"))));
    }

    private void registerVariance()
    {
        register("meta::pure::functions::math::variancePopulation_Number_MANY__Number_1_", false, ps -> res("Number", "one"));
        register("meta::pure::functions::math::varianceSample_Number_MANY__Number_1_", false, ps -> res("Number", "one"));
    }

    private void registerCovariance()
    {
        register(m(m(h("meta::pure::functions::math::covarPopulation_Number_MANY__Number_MANY__Number_$0_1$_", false, ps -> res("Number", "zeroOne"), ps -> typeMany(ps.get(0), "Number"))),
                m(h("meta::pure::functions::math::covarPopulation_RowMapper_MANY__Number_$0_1$_", false, ps -> res("Number", "zeroOne"), ps -> typeMany(ps.get(0), "meta::pure::functions::math::mathUtility::RowMapper")))));

        register(m(m(h("meta::pure::functions::math::covarSample_Number_MANY__Number_MANY__Number_$0_1$_", false, ps -> res("Number", "zeroOne"), ps -> typeMany(ps.get(0), "Number"))),
                m(h("meta::pure::functions::math::covarSample_RowMapper_MANY__Number_$0_1$_", false, ps -> res("Number", "zeroOne"), ps -> typeMany(ps.get(0), "meta::pure::functions::math::mathUtility::RowMapper")))));

        register(m(m(h("meta::pure::functions::math::corr_Number_MANY__Number_MANY__Number_$0_1$_", false, ps -> res("Number", "zeroOne"), ps -> typeMany(ps.get(0), "Number"))),
                m(h("meta::pure::functions::math::corr_RowMapper_MANY__Number_$0_1$_", false, ps -> res("Number", "zeroOne"), ps -> typeMany(ps.get(0), "meta::pure::functions::math::mathUtility::RowMapper")))));
    }

    private void registerJson()
    {
        register(m(m(h("meta::json::toJSON_Any_MANY__String_1_", false, ps -> res("String", "one"), ps -> ps.size() == 1)),
                m(h("meta::json::toJSON_T_MANY__LambdaFunction_MANY__String_1_", false, ps -> res(ps.get(0)._genericType(), "zeroMany"), ps -> ps.size() == 2 && typeMany(ps.get(1), "LambdaFunction")))));
    }

    private void registerRuntimeHelper()
    {
        register(h("meta::core::runtime::mergeRuntimes_Any_$1_MANY$__Runtime_1_", false, ps -> res("meta::core::runtime::Runtime", "one"), ps -> true));
        register(h("meta::core::runtime::getRuntimeWithModelConnection_Class_1__Any_MANY__Runtime_1_", false, ps -> res("meta::core::runtime::Runtime", "one"), ps -> true));
        register(h("meta::core::runtime::getRuntimeWithModelQueryConnection_Class_1__String_1__String_1__Runtime_1_", false, ps -> res("meta::core::runtime::Runtime", "one"), ps -> typeOne(ps.get(1), "String") && typeOne(ps.get(2), "String")),
                h("meta::core::runtime::getRuntimeWithModelQueryConnection_Class_1__String_1__Byte_MANY__Runtime_1_", false, ps -> res("meta::core::runtime::Runtime", "one"), ps -> typeOne(ps.get(1), "String") && typeMany(ps.get(2), "Byte")),
                h("meta::core::runtime::getRuntimeWithModelQueryConnection_Class_1__Binding_1__Byte_MANY__Runtime_1_", false, ps -> res("meta::core::runtime::Runtime", "one"), ps -> typeMany(ps.get(2), "Byte")),
                h("meta::core::runtime::getRuntimeWithModelQueryConnection_Class_1__Binding_1__String_1__Runtime_1_", false, ps -> res("meta::core::runtime::Runtime", "one"), ps -> typeOne(ps.get(2), "String")));
        register(h("meta::core::runtime::currentUserId__String_1_", true, ps -> res("String", "one"), ps -> true));
    }

    private void registerUnitFunctions()
    {
        register(h("meta::pure::executionPlan::engine::java::unitType_Any_1__String_1_", false, ps -> res("String", "one"), ps -> typeOneMany(ps.get(0), "String")));
        register(h("meta::pure::executionPlan::engine::java::unitValue_Any_1__Number_1_", false, ps -> res("Number", "one"), ps -> typeOneMany(ps.get(0), taxoMap.get("cov_Number"))));
        register(h("meta::pure::functions::meta::newUnit_Unit_1__Number_1__Any_1_", true, ps -> res("meta::pure::metamodel::type::Any", "one"), ps -> typeOneMany(ps.get(0), "Any")));
        register(h("meta::pure::executionPlan::engine::java::convert_Any_1__Unit_1__Any_1_", false, ps -> res("meta::pure::metamodel::type::Any", "one"), ps -> typeOneMany(ps.get(0), "Any")));
    }

    private void registerCalendarFunctions()
    {
        register("meta::pure::functions::date::calendar::annualized_Date_1__String_1__Date_1__Number_$0_1$__Number_$0_1$_", false, ps -> res("Number", "zeroOne"));
        register("meta::pure::functions::date::calendar::cme_Date_1__String_1__Date_1__Number_$0_1$__Number_$0_1$_", false, ps -> res("Number", "zeroOne"));
        register("meta::pure::functions::date::calendar::cw_Date_1__String_1__Date_1__Number_$0_1$__Number_$0_1$_", false, ps -> res("Number", "zeroOne"));
        register("meta::pure::functions::date::calendar::cw_fm_Date_1__String_1__Date_1__Number_$0_1$__Number_$0_1$_", false, ps -> res("Number", "zeroOne"));
        register("meta::pure::functions::date::calendar::CYMinus2_Date_1__String_1__Date_1__Number_$0_1$__Number_$0_1$_", false, ps -> res("Number", "zeroOne"));
        register("meta::pure::functions::date::calendar::CYMinus3_Date_1__String_1__Date_1__Number_$0_1$__Number_$0_1$_", false, ps -> res("Number", "zeroOne"));
        register("meta::pure::functions::date::calendar::mtd_Date_1__String_1__Date_1__Number_$0_1$__Number_$0_1$_", false, ps -> res("Number", "zeroOne"));
        register("meta::pure::functions::date::calendar::p12wa_Date_1__String_1__Date_1__Number_$0_1$__Number_$0_1$_", false, ps -> res("Number", "zeroOne"));
        register("meta::pure::functions::date::calendar::p12mtd_Date_1__String_1__Date_1__Number_$0_1$__Number_$0_1$_", false, ps -> res("Number", "zeroOne"));
        register("meta::pure::functions::date::calendar::p12wtd_Date_1__String_1__Date_1__Number_$0_1$__Number_$0_1$_", false, ps -> res("Number", "zeroOne"));
        register("meta::pure::functions::date::calendar::p4wa_Date_1__String_1__Date_1__Number_$0_1$__Number_$0_1$_", false, ps -> res("Number", "zeroOne"));
        register("meta::pure::functions::date::calendar::p4wtd_Date_1__String_1__Date_1__Number_$0_1$__Number_$0_1$_", false, ps -> res("Number", "zeroOne"));
        register("meta::pure::functions::date::calendar::p52wtd_Date_1__String_1__Date_1__Number_$0_1$__Number_$0_1$_", false, ps -> res("Number", "zeroOne"));
        register("meta::pure::functions::date::calendar::p52wa_Date_1__String_1__Date_1__Number_$0_1$__Number_$0_1$_", false, ps -> res("Number", "zeroOne"));
        register("meta::pure::functions::date::calendar::pma_Date_1__String_1__Date_1__Number_$0_1$__Number_$0_1$_", false, ps -> res("Number", "zeroOne"));
        register("meta::pure::functions::date::calendar::pmtd_Date_1__String_1__Date_1__Number_$0_1$__Number_$0_1$_", false, ps -> res("Number", "zeroOne"));
        register("meta::pure::functions::date::calendar::pqtd_Date_1__String_1__Date_1__Number_$0_1$__Number_$0_1$_", false, ps -> res("Number", "zeroOne"));
        register("meta::pure::functions::date::calendar::priorDay_Date_1__String_1__Date_1__Number_$0_1$__Number_$0_1$_", false, ps -> res("Number", "zeroOne"));
        register("meta::pure::functions::date::calendar::priorYear_Date_1__String_1__Date_1__Number_$0_1$__Number_$0_1$_", false, ps -> res("Number", "zeroOne"));
        register("meta::pure::functions::date::calendar::pw_Date_1__String_1__Date_1__Number_$0_1$__Number_$0_1$_", false, ps -> res("Number", "zeroOne"));
        register("meta::pure::functions::date::calendar::pw_fm_Date_1__String_1__Date_1__Number_$0_1$__Number_$0_1$_", false, ps -> res("Number", "zeroOne"));
        register("meta::pure::functions::date::calendar::pwa_Date_1__String_1__Date_1__Number_$0_1$__Number_$0_1$_", false, ps -> res("Number", "zeroOne"));
        register("meta::pure::functions::date::calendar::pwtd_Date_1__String_1__Date_1__Number_$0_1$__Number_$0_1$_", false, ps -> res("Number", "zeroOne"));
        register("meta::pure::functions::date::calendar::pymtd_Date_1__String_1__Date_1__Number_$0_1$__Number_$0_1$_", false, ps -> res("Number", "zeroOne"));
        register("meta::pure::functions::date::calendar::pyqtd_Date_1__String_1__Date_1__Number_$0_1$__Number_$0_1$_", false, ps -> res("Number", "zeroOne"));
        register("meta::pure::functions::date::calendar::pytd_Date_1__String_1__Date_1__Number_$0_1$__Number_$0_1$_", false, ps -> res("Number", "zeroOne"));
        register("meta::pure::functions::date::calendar::pywa_Date_1__String_1__Date_1__Number_$0_1$__Number_$0_1$_", false, ps -> res("Number", "zeroOne"));
        register("meta::pure::functions::date::calendar::pywtd_Date_1__String_1__Date_1__Number_$0_1$__Number_$0_1$_", false, ps -> res("Number", "zeroOne"));
        register("meta::pure::functions::date::calendar::qtd_Date_1__String_1__Date_1__Number_$0_1$__Number_$0_1$_", false, ps -> res("Number", "zeroOne"));
        register("meta::pure::functions::date::calendar::reportEndDay_Date_1__String_1__Date_1__Number_$0_1$__Number_$0_1$_", false, ps -> res("Number", "zeroOne"));
        register("meta::pure::functions::date::calendar::wtd_Date_1__String_1__Date_1__Number_$0_1$__Number_$0_1$_", false, ps -> res("Number", "zeroOne"));
        register("meta::pure::functions::date::calendar::ytd_Date_1__String_1__Date_1__Number_$0_1$__Number_$0_1$_", false, ps -> res("Number", "zeroOne"));
    }

    public Pair<SimpleFunctionExpression, List<ValueSpecification>> buildFunctionExpression(String functionName, List<org.finos.legend.engine.protocol.pure.m3.valuespecification.ValueSpecification> parameters, SourceInformation sourceInformation, ValueSpecificationBuilder valueSpecificationBuilder)
    {
        FunctionExpressionBuilder builder = getExpressionBuilder(functionName, sourceInformation, valueSpecificationBuilder);
        if (builder != null)
        {
            return builder.buildFunctionExpression(parameters, sourceInformation, valueSpecificationBuilder);
        }
        return null;
    }

    public FunctionExpressionBuilder getExpressionBuilder(String functionName, SourceInformation sourceInformation, ValueSpecificationBuilder valueSpecificationBuilder)
    {
        return valueSpecificationBuilder.getContext().resolveFunctionBuilder(functionName, this.registeredMetaPackages, this.map, sourceInformation, valueSpecificationBuilder.getProcessingContext());
    }

    public void collectPrerequisiteElementsFromUserDefinedFunctionHandlers(Set<PackageableElementPointer> prerequisiteElements, String functionName, int parametersSize)
    {
        FunctionExpressionBuilder functionExpressionBuilder = this.map.get(functionName);
        if (Objects.nonNull(functionExpressionBuilder))
        {
            functionExpressionBuilder.handlers().asLazy().selectInstancesOf(UserDefinedFunctionHandler.class)
                    .select(h -> h.getParametersSize() == parametersSize)
                    .forEach(h -> prerequisiteElements.add(new PackageableElementPointer(PackageableElementType.FUNCTION, h.getFullName())));
        }
    }

    private void registerMetaPackage(FunctionHandler... handlers)
    {
        for (FunctionHandler handler : handlers)
        {
            org.finos.legend.pure.m3.coreinstance.Package pkg = handler.getFunc() instanceof org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.PackageableElement ? ((org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.PackageableElement) handler.getFunc())._package() : null;
            if (pkg != null)
            {
                String path = Root_meta_pure_functions_meta_elementToPath_PackageableElement_1__String_1_(pkg, pureModel.getExecutionSupport());
                if (path.startsWith(this.META_PACKAGE_NAME + this.PACKAGE_SEPARATOR))
                {
                    registeredMetaPackages.add(path);
                }
            }
        }
    }

    private void register(String name, boolean isNative, ReturnInference inference)
    {
        register(new FunctionHandler(this.pureModel, name, isNative, inference));
    }

    public void register(FunctionHandler handler)
    {
        FunctionHandler[] handlers = {handler};
        register(handlers);
    }

    public synchronized void register(UserDefinedFunctionHandler handler)
    {
        String functionName = handler.getFunctionName();
        boolean functionRegisteredByName = isFunctionRegisteredByName(handler);
        if (!functionRegisteredByName)
        {
            map.put(functionName, new MultiHandlerFunctionExpressionBuilder(this.pureModel, handler));
        }
        else
        {
            Assert.assertFalse(isFunctionRegisteredBySignature(handler, functionRegisteredByName), () -> "Function '" + handler.getFunctionSignature() + "' is already registered");
            FunctionExpressionBuilder builder = map.get(functionName);
            if (builder.supportFunctionHandler(handler))
            {
                builder.addFunctionHandler(handler);
            }
            else
            {
                this.addFunctionHandler(handler, builder);
            }
        }
        map.get(functionName).handlers().forEach(this::mayReplace);
    }

    private void register(FunctionHandler... handlers)
    {
        MultiHandlerFunctionExpressionBuilder handler = new MultiHandlerFunctionExpressionBuilder(this.pureModel, handlers);
        Arrays.stream(handlers).forEach(this.pureModel::loadModelFromFunctionHandler);
        insertInMap(handler);
    }

    private void register(FunctionExpressionBuilder handler)
    {
        insertInMap(handler);
    }

    private void insertInMap(FunctionExpressionBuilder handler)
    {
        for (FunctionHandler h : handler.handlers())
        {
            mayReplace(h);
        }
        handler.handlers().forEach(this::registerMetaPackage);

        FunctionExpressionBuilder existing = map.get(handler.getFunctionName());
        if (existing == null)
        {
            map.put(handler.getFunctionName(), handler);
        }
        else
        {
            map.put(handler.getFunctionName(), new CompositeFunctionExpressionBuilder(new FunctionExpressionBuilder[]{existing, handler}));
        }
    }

    private void register(FunctionHandlerRegistrationInfo info)
    {
        if (info.coordinates == null || info.coordinates.isEmpty())
        {
            register(info.functionHandler);
        }
        else
        {
            FunctionExpressionBuilder functionExpressionBuilder = Objects.requireNonNull(this.map.get(info.functionHandler.getFunctionName()), "Can't find expression builder for function '" + info.functionHandler.getFunctionName() + "'");
            for (int i = 0; i < info.coordinates.size() - 1; ++i)
            {
                functionExpressionBuilder = functionExpressionBuilder.getFunctionExpressionBuilderAtIndex(info.coordinates.get(i));
            }
            functionExpressionBuilder.insertFunctionHandlerAtIndex(info.coordinates.get(info.coordinates.size() - 1), info.functionHandler);
        }
    }

    private void register(FunctionExpressionBuilderRegistrationInfo info)
    {
        if (info.coordinates == null || info.coordinates.isEmpty())
        {
            register(info.functionExpressionBuilder);
        }
        else
        {
            FunctionExpressionBuilder functionExpressionBuilder = Objects.requireNonNull(this.map.get(info.functionExpressionBuilder.getFunctionName()), "Can't find expression builder for function '" + info.functionExpressionBuilder.getFunctionName() + "'");
            for (int i = 0; i < info.coordinates.size() - 1; ++i)
            {
                functionExpressionBuilder = functionExpressionBuilder.getFunctionExpressionBuilderAtIndex(info.coordinates.get(i));
            }
            functionExpressionBuilder.insertFunctionExpressionBuilderAtIndex(info.coordinates.get(info.coordinates.size() - 1), info.functionExpressionBuilder);
        }
    }

    private void mayReplace(FunctionHandler handler)
    {
        Dispatch di = this.dispatchMap.get(handler.getFullName());
        if (di != null)
        {
            handler.setDispatch(di);
        }
    }

    public TypeAndMultiplicity res(GenericType genericType, Multiplicity mul)
    {
        return new TypeAndMultiplicity(genericType, mul);
    }

    public TypeAndMultiplicity res(GenericType genericType, String mul)
    {
        return res(genericType, mul, this.pureModel);
    }

    public static TypeAndMultiplicity res(GenericType genericType, String mul, PureModel pureModel)
    {
        return new TypeAndMultiplicity(genericType, pureModel.getMultiplicity(mul));
    }

    public TypeAndMultiplicity res(String type, String mul)
    {
        return new TypeAndMultiplicity(this.pureModel.getGenericType(type), this.pureModel.getMultiplicity(mul));
    }

    private TypeAndMultiplicity res(String type, Multiplicity mul)
    {
        return new TypeAndMultiplicity(this.pureModel.getGenericType(type), mul);
    }

    private boolean isFunctionRegisteredByName(UserDefinedFunctionHandler handler)
    {
        return map.containsKey(handler.getFunctionName());
    }

    private boolean isFunctionRegisteredBySignature(UserDefinedFunctionHandler handler, Boolean isFunctionNameAlreadyRegistered)
    {
        return isFunctionNameAlreadyRegistered && map.get(handler.getFunctionName()).handlers().stream().anyMatch(val -> val.getFunctionSignature().equals(handler.getFunctionSignature()));
    }

    public void addFunctionHandler(FunctionHandler handler, FunctionExpressionBuilder builder)
    {
        if (builder instanceof MultiHandlerFunctionExpressionBuilder)
        {
            addFunctionHandler(handler, (MultiHandlerFunctionExpressionBuilder) builder);
        }
        else
        {
            addFunctionHandler(handler, (CompositeFunctionExpressionBuilder) builder);
        }
    }

    private void addFunctionHandler(FunctionHandler handler, MultiHandlerFunctionExpressionBuilder multiHandlerFunctionExpressionBuilder)
    {
        MultiHandlerFunctionExpressionBuilder multiHandler = new MultiHandlerFunctionExpressionBuilder(this.pureModel, handler);
        CompositeFunctionExpressionBuilder compositeFunctionExpressionBuilder = new CompositeFunctionExpressionBuilder(new MultiHandlerFunctionExpressionBuilder[]{multiHandlerFunctionExpressionBuilder, multiHandler});
        map.put(handler.getFunctionName(), compositeFunctionExpressionBuilder);
    }

    private void addFunctionHandler(FunctionHandler handler, CompositeFunctionExpressionBuilder compositeFunctionExpressionBuilder)
    {
        compositeFunctionExpressionBuilder.getBuilders().add(new MultiHandlerFunctionExpressionBuilder(this.pureModel, handler));

    }

    // --------------------------------------------- Function expression builder ----------------------------------

    public FunctionHandler h(String name, boolean isNative, ReturnInference returnInference)
    {
        return new FunctionHandler(this.pureModel, name, isNative, returnInference);
    }

    public FunctionHandler h(String name, boolean isNative, ReturnInference returnInference, Dispatch dispatch)
    {
        return new FunctionHandler(this.pureModel, name, isNative, returnInference, dispatch);
    }

    public FunctionHandler h(String name, boolean isNative, ReturnInference returnInference, ResolveTypeParameterInference resolvedTypeParametersInference, Dispatch dispatch)
    {
        return new FunctionHandler(this.pureModel, name, isNative, returnInference, resolvedTypeParametersInference, dispatch);
    }

    public RequiredInferenceSimilarSignatureFunctionExpressionBuilder grp(ParametersInference parametersInference, FunctionHandler... handlers)
    {
        Arrays.stream(handlers).forEach(this.pureModel::loadModelFromFunctionHandler);
        return new RequiredInferenceSimilarSignatureFunctionExpressionBuilder(parametersInference, handlers, this.pureModel);
    }

    public MultiHandlerFunctionExpressionBuilder m(FunctionHandler... handlers)
    {
        Arrays.stream(handlers).forEach(this.pureModel::loadModelFromFunctionHandler);
        return new MultiHandlerFunctionExpressionBuilder(this.pureModel, handlers);
    }

    public CompositeFunctionExpressionBuilder m(FunctionExpressionBuilder... builders)
    {
        return new CompositeFunctionExpressionBuilder(builders);
    }


    //-------------------------------
    // Functions below to be deleted
    //-------------------------------

    public static GenericType funcReturnType(ValueSpecification vs, PureModel pm)
    {
        return funcType(vs._genericType(), pm)._returnType();
    }

    public static Multiplicity funcReturnMul(ValueSpecification vs, PureModel pm)
    {
        return funcType(vs._genericType(), pm)._returnMultiplicity();
    }

    private GenericType funcReturnType(ValueSpecification vs)
    {
        return funcType(vs._genericType(), this.pureModel)._returnType();
    }

    private GenericType funcParamType(ValueSpecification vs, int index)
    {
        List<VariableExpression> parameters = FastList.newList(funcType(vs._genericType(), this.pureModel)._parameters());
        return parameters.get(index)._genericType();
    }

    private Multiplicity funcReturnMul(ValueSpecification vs)
    {
        return funcType(vs._genericType())._returnMultiplicity();
    }

    public boolean typeOne(ValueSpecification vs, String type)
    {
        return typeOne(vs, Sets.mutable.with(type));
    }

    public boolean typeOne(ValueSpecification vs, MutableSet<String> type)
    {
        return isNilOrType(vs, type) && isOne(vs._multiplicity());
    }

    public boolean typeZeroOne(ValueSpecification vs, String type)
    {
        return typeZeroOne(vs, Sets.mutable.with(type));
    }

    public boolean typeZeroOne(ValueSpecification vs, MutableSet<String> type)
    {
        return isNilOrType(vs, type) && matchZeroOne(vs._multiplicity());
    }

    public boolean typeOneMany(ValueSpecification vs, String type)
    {
        return typeOneMany(vs, Sets.mutable.with(type));
    }

    public boolean typeOneMany(ValueSpecification vs, MutableSet<String> type)
    {
        return type.contains(vs._genericType()._rawType()._name()) && isMinimumOne(vs._multiplicity());
    }

    public boolean typeMany(ValueSpecification vs, String type)
    {
        return isNilOrType(vs, type);
    }

    public boolean typeMany(ValueSpecification vs, MutableSet<String> type)
    {
        return isNilOrType(vs, type);
    }

    private boolean isNilOrType(ValueSpecification vs, String type)
    {
        return isNilOrType(vs, Sets.mutable.with(type));
    }

    private boolean isNilOrType(ValueSpecification vs, MutableSet<String> type)
    {
        String vsType = vs._genericType()._rawType()._name();
        return Nil.equals(vsType) || type.contains(vsType);
    }

    private boolean isZeroOne(Multiplicity mul)
    {
        return mul._multiplicityParameter() == null && (mul._upperBound()._value() != null && mul._lowerBound()._value() == 0L && mul._upperBound()._value() == 1L);
    }

    // ---------------------------------------------------------------------


    //--------------------------
    // Required functions below
    //--------------------------

    private <T> boolean check(T val, Function<T, Boolean> func)
    {
        return func.apply(val);
    }

    private static FunctionType funcType(GenericType gt, PureModel pm)
    {
        if (gt._rawType()._name().equals("Path"))
        {
            RichIterable<? extends GenericType> g = gt._typeArguments();
            Multiplicity m = gt._multiplicityArguments().getFirst();
            return (FunctionType) PureModel.buildFunctionType(FastList.newListWith(new Root_meta_pure_metamodel_valuespecification_VariableExpression_Impl("", null, pm.getClass("meta::pure::metamodel::valuespecification::VariableExpression"))._genericType(g.getFirst())._multiplicity(pm.getMultiplicity("one"))), g.getLast(), m, pm)._rawType();
        }
        return (FunctionType) gt._typeArguments().getFirst()._rawType();
    }

    private FunctionType funcType(GenericType gt)
    {
        return funcType(gt, this.pureModel);
    }

    public boolean isOne(Multiplicity mul)
    {
        // mul._upperBound() != null && mul._lowerBound() both could be null if the multiplicity is a type param pulled from the Pure graph
        return mul._upperBound() != null && mul._lowerBound() != null && mul._upperBound()._value() != null && mul._lowerBound()._value() == 1L && mul._upperBound()._value() == 1L;
    }

    private boolean matchZeroOne(Multiplicity mul)
    {
        // engine doesn't support Generics at model level ... We assume that a typeParameter is *. The use case is Result<T,m> used in Service tests
        return mul._multiplicityParameter() == null && mul._upperBound()._value() != null && (mul._upperBound()._value() == 0L || mul._upperBound()._value() == 1L);
    }

    private boolean matchOneMany(Multiplicity mul)
    {
        return isMinimumOne(mul);
    }

    private boolean isMinimumOne(Multiplicity mul)
    {
        return mul._lowerBound()._value() >= 1L;
    }


    private void registerUnknown(Map<String, Dispatch> map)
    {
        map.put("meta::dsb::query::functions::filterReportDates_Any_1__Date_1__Date_1__Function_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(2)._genericType()._rawType()._name()) && isOne(ps.get(3)._multiplicity()) && ("Nil".equals(ps.get(3)._genericType()._rawType()._name()) || check(funcType(ps.get(3)._genericType()), (FunctionType ft) -> matchZeroOne(ft._returnMultiplicity()) && taxoMap.get("cov_Date").contains(ft._returnType()._rawType()._name()) && check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.size() == 1 && isOne(nps.get(0)._multiplicity())))));
        map.put("meta::dsb::query::functions::filterReportDates_Any_1__Date_1__Function_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && ("Nil".equals(ps.get(2)._genericType()._rawType()._name()) || check(funcType(ps.get(2)._genericType()), (FunctionType ft) -> matchZeroOne(ft._returnMultiplicity()) && taxoMap.get("cov_Date").contains(ft._returnType()._rawType()._name()) && check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.size() == 1 && isOne(nps.get(0)._multiplicity())))));

        map.put("meta::pure::functions::date::calendar::annualized_Date_1__String_1__Date_1__Number_$0_1$", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(2)._genericType()._rawType()._name()) && matchZeroOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::calendar::cme_Date_1__String_1__Date_1__Number_$0_1$", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(2)._genericType()._rawType()._name()) && matchZeroOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::calendar::cw_Date_1__String_1__Date_1__Number_$0_1$", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(2)._genericType()._rawType()._name()) && matchZeroOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::calendar::cw_fm_Date_1__String_1__Date_1__Number_$0_1$", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(2)._genericType()._rawType()._name()) && matchZeroOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::calendar::CYMinus2_Date_1__String_1__Date_1__Number_$0_1$", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(2)._genericType()._rawType()._name()) && matchZeroOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::calendar::CYMinus3_Date_1__String_1__Date_1__Number_$0_1$", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(2)._genericType()._rawType()._name()) && matchZeroOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::calendar::mtd_Date_1__String_1__Date_1__Number_$0_1$", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(2)._genericType()._rawType()._name()) && matchZeroOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::calendar::p12wa_Date_1__String_1__Date_1__Number_$0_1$", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(2)._genericType()._rawType()._name()) && matchZeroOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::calendar::p12mtd_Date_1__String_1__Date_1__Number_$0_1$", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(2)._genericType()._rawType()._name()) && matchZeroOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::calendar::p12wtd_Date_1__String_1__Date_1__Number_$0_1$", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(2)._genericType()._rawType()._name()) && matchZeroOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::calendar::p4wa_Date_1__String_1__Date_1__Number_$0_1$", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(2)._genericType()._rawType()._name()) && matchZeroOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::calendar::p4wtd_Date_1__String_1__Date_1__Number_$0_1$", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(2)._genericType()._rawType()._name()) && matchZeroOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::calendar::p52wtd_Date_1__String_1__Date_1__Number_$0_1$", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(2)._genericType()._rawType()._name()) && matchZeroOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::calendar::p52wa_Date_1__String_1__Date_1__Number_$0_1$", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(2)._genericType()._rawType()._name()) && matchZeroOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::calendar::pma_Date_1__String_1__Date_1__Number_$0_1$", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(2)._genericType()._rawType()._name()) && matchZeroOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::calendar::pmtd_Date_1__String_1__Date_1__Number_$0_1$", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(2)._genericType()._rawType()._name()) && matchZeroOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::calendar::pqtd_Date_1__String_1__Date_1__Number_$0_1$", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(2)._genericType()._rawType()._name()) && matchZeroOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::calendar::priorDay_Date_1__String_1__Date_1__Number_$0_1$", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(2)._genericType()._rawType()._name()) && matchZeroOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::calendar::priorYear_Date_1__String_1__Date_1__Number_$0_1$", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(2)._genericType()._rawType()._name()) && matchZeroOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::calendar::pw_Date_1__String_1__Date_1__Number_$0_1$", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(2)._genericType()._rawType()._name()) && matchZeroOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::calendar::pw_fm_Date_1__String_1__Date_1__Number_$0_1$", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(2)._genericType()._rawType()._name()) && matchZeroOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::calendar::pwa_Date_1__String_1__Date_1__Number_$0_1$", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(2)._genericType()._rawType()._name()) && matchZeroOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::calendar::pwtd_Date_1__String_1__Date_1__Number_$0_1$", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(2)._genericType()._rawType()._name()) && matchZeroOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::calendar::pymtd_Date_1__String_1__Date_1__Number_$0_1$", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(2)._genericType()._rawType()._name()) && matchZeroOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::calendar::pyqtd_Date_1__String_1__Date_1__Number_$0_1$", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(2)._genericType()._rawType()._name()) && matchZeroOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::calendar::pytd_Date_1__String_1__Date_1__Number_$0_1$", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(2)._genericType()._rawType()._name()) && matchZeroOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::calendar::pywa_Date_1__String_1__Date_1__Number_$0_1$", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(2)._genericType()._rawType()._name()) && matchZeroOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::calendar::pywtd_Date_1__String_1__Date_1__Number_$0_1$", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(2)._genericType()._rawType()._name()) && matchZeroOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::calendar::qtd_Date_1__String_1__Date_1__Number_$0_1$", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(2)._genericType()._rawType()._name()) && matchZeroOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::calendar::reportEndDay_Date_1__String_1__Date_1__Number_$0_1$", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(2)._genericType()._rawType()._name()) && matchZeroOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::calendar::wtd_Date_1__String_1__Date_1__Number_$0_1$", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(2)._genericType()._rawType()._name()) && matchZeroOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::calendar::ytd_Date_1__String_1__Date_1__Number_$0_1$", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(2)._genericType()._rawType()._name()) && matchZeroOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(3)._genericType()._rawType()._name()));
    }

    private void registerAdditionalSubtypes(CompilerExtensions compilerExtensions)
    {
        compilerExtensions.getExtraSubTypesForFunctionMatching().forEach((key, value) ->
        {
            MutableSet<String> sets = taxoMap.get(key);
            if (sets == null)
            {
                throw new RuntimeException("An extension is trying to add subtypes [" + value.makeString(",") + "] for the key '" + key + "' but the key doesn't exist.\n" +
                        "Existing keys: [" + taxoMap.keysView().makeString(",") + "]");
            }
            sets.addAll(value);
        });
    }


// ------------------------------------------------------------------------------------------------
// Please do not update the following code manually! Please check with the team when introducing
// new matchers as this might be complicated by modularization
// THIS CODE IS GENERATED BY A FUNCTION IN PURE - CONTACT THE CORE TEAM FOR MORE DETAILS
//-------------------------------------------------------------------------------------------------

    private static MutableMap<String, MutableSet<String>> buildTaxoMap()
    {
        MutableMap<String, MutableSet<String>> map = Maps.mutable.empty();
        map.put("cov_type_Class", Sets.mutable.with("Class", "ClassProjection", "MappingClass", "Nil"));
        map.put("cov_binding_Binding", Sets.mutable.with("Binding", "Nil"));
        map.put("cov_Byte", Sets.mutable.with("Byte", "Nil"));
        map.put("cov_String", Sets.mutable.with("String", "Varchar", "Nil"));
        map.put("cov_function_LambdaFunction", Sets.mutable.with("LambdaFunction", "Nil"));
        map.put("cov_type_Unit", Sets.mutable.with("Unit", "Nil"));
        map.put("cov_Number", Sets.mutable.with("Number", "Integer", "Float", "Decimal", "TinyInt", "UTinyInt", "SmallInt", "USmallInt", "Int", "UInt", "BigInt", "UBigInt", "Float4", "Double", "Numeric", "Nil"));
        map.put("cov_Boolean", Sets.mutable.with("Boolean", "Nil"));
        map.put("cov_type_Type", Sets.mutable.with("Type", "Class", "FunctionType", "DataType", "RelationType", "ClassProjection", "MappingClass", "Unit", "Enumeration", "PrimitiveType", "Measure", "Mass", "Gram", "Kilogram", "Pound", "Nil"));
        map.put("cov_Integer", Sets.mutable.with("Integer", "TinyInt", "UTinyInt", "SmallInt", "USmallInt", "Int", "UInt", "BigInt", "UBigInt", "Nil"));
        map.put("cov_Date", Sets.mutable.with("Date", "StrictDate", "DateTime", "LatestDate", "Timestamp", "Nil"));
        map.put("cov_function_FunctionDefinition", Sets.mutable.with("FunctionDefinition", "NewPropertyRouteNodeFunctionDefinition", "LambdaFunction", "QualifiedProperty", "ConcreteFunctionDefinition", "Nil"));
        map.put("cov_graphFetch_RootGraphFetchTree", Sets.mutable.with("RootGraphFetchTree", "ExtendedRootGraphFetchTree", "RoutedRootGraphFetchTree", "DataQualityRootGraphFetchTree", "SerializeTopRootGraphFetchTree", "Nil"));
        map.put("cov_StrictDate", Sets.mutable.with("StrictDate", "Nil"));
        map.put("cov_date_Duration", Sets.mutable.with("Duration", "Nil"));
        map.put("cov_date_DurationUnit", Sets.mutable.with("DurationUnit", "Nil"));
        map.put("cov_DateTime", Sets.mutable.with("DateTime", "Timestamp", "Nil"));
        map.put("cov_date_DayOfWeek", Sets.mutable.with("DayOfWeek", "Nil"));
        map.put("cov_hash_HashType", Sets.mutable.with("HashType", "Nil"));
        map.put("cov_function_KeyExpression", Sets.mutable.with("KeyExpression", "Nil"));
        map.put("cov_Float", Sets.mutable.with("Float", "Float4", "Double", "Nil"));
        map.put("cov_Decimal", Sets.mutable.with("Decimal", "Numeric", "Nil"));
        map.put("cov_mathUtility_RowMapper", Sets.mutable.with("RowMapper", "Nil"));
        map.put("cov_wavgUtility_WavgRowMapper", Sets.mutable.with("WavgRowMapper", "RowMapper", "Nil"));
        map.put("cov_relation_Relation", Sets.mutable.with("Relation", "RelationElementAccessor", "TDS", "RelationStoreAccessor", "TDSRelationAccessor", "Nil"));
        map.put("cov_relation_ColSpec", Sets.mutable.with("ColSpec", "Nil"));
        map.put("cov_relation__Window", Sets.mutable.with("_Window", "Nil"));
        map.put("cov_relation_ColSpecArray", Sets.mutable.with("ColSpecArray", "Nil"));
        map.put("cov_relation_AggColSpecArray", Sets.mutable.with("AggColSpecArray", "Nil"));
        map.put("cov_relation_AggColSpec", Sets.mutable.with("AggColSpec", "Nil"));
        map.put("cov_relation_FuncColSpecArray", Sets.mutable.with("FuncColSpecArray", "Nil"));
        map.put("cov_relation_FuncColSpec", Sets.mutable.with("FuncColSpec", "Nil"));
        map.put("cov_relation_JoinKind", Sets.mutable.with("JoinKind", "Nil"));
        map.put("cov_relation_SortInfo", Sets.mutable.with("SortInfo", "Nil"));
        map.put("cov_relation_Frame", Sets.mutable.with("Frame", "_Range", "_RangeInterval", "Rows", "Nil"));
        map.put("cov_collection_Pair", Sets.mutable.with("Pair", "PureFunctionToProcessFunctionPair", "PureFunctionToProcessFunctionPair", "PureFunctionToMongoDBFunctionPair", "PureFunctionToLambdaComparisonOperatorPair", "PureFunctionToServiceStoreFunctionPair", "OldAliasToNewAlias", "PureFunctionToRelationalFunctionPair", "PureFunctionTDSToRelationalFunctionPair", "Nil"));
        map.put("cov_mapping_Mapping", Sets.mutable.with("Mapping", "Nil"));
        map.put("cov_extension_Extension", Sets.mutable.with("Extension", "Nil"));
        map.put("cov_dataQuality_Checked", Sets.mutable.with("Checked", "Nil"));
        map.put("cov_execution_AlloySerializationConfig", Sets.mutable.with("AlloySerializationConfig", "Nil"));
        map.put("cov_runtime_PackageableRuntime", Sets.mutable.with("PackageableRuntime", "Nil"));
        map.put("cov_runtime_Runtime", Sets.mutable.with("Runtime", "EngineRuntime", "Nil"));
        map.put("cov_tds_TabularDataSet", Sets.mutable.with("TabularDataSet", "TabularDataSetImplementation", "IndexTDS", "TableTDS", "Nil"));
        map.put("cov_runtime_ExecutionContext", Sets.mutable.with("ExecutionContext", "MultiExecutionContext", "ExtendedExecutionContext", "Elasticsearch7ExecutionContext", "MongoDBExecutionContext", "RelationalExecutionContext", "FunctionActivatorExecutionContext", "ExecutionOptionContext", "AnalyticsExecutionContext", "MemSqlFunctionExecutionContext", "SnowFlakeAppExecutionContext", "Nil"));
        map.put("cov_tds_BasicColumnSpecification", Sets.mutable.with("BasicColumnSpecification", "Nil"));
        map.put("contra_tds_TDSRow", Sets.mutable.with("TDSRow", "Any"));
        map.put("cov_collection_AggregateValue", Sets.mutable.with("AggregateValue", "Nil"));
        map.put("cov_tds_AggregateValue", Sets.mutable.with("AggregateValue", "Nil"));
        map.put("cov_join_JoinType", Sets.mutable.with("JoinType", "Nil"));
        map.put("cov_tds_OlapOperation", Sets.mutable.with("OlapOperation", "OlapAggregation", "OlapRank", "TdsOlapAggregation", "TdsOlapRank", "Nil"));
        map.put("cov_tds_SortInformation", Sets.mutable.with("SortInformation", "Nil"));
        map.put("cov_tds_ColumnSpecification", Sets.mutable.with("ColumnSpecification", "BasicColumnSpecification", "WindowColumnSpecification", "Nil"));
        map.put("cov_path_Path", Sets.mutable.with("Path", "Nil"));
        map.put("cov_mapping_TableTDS", Sets.mutable.with("TableTDS", "Nil"));
        map.put("cov_tds_SortDirection", Sets.mutable.with("SortDirection", "Nil"));
        map.put("cov_relation_Table", Sets.mutable.with("Table", "ViewSelectSQLQuery", "VarCrossSetPlaceHolder", "Nil"));
        return map;
    }

    private Map<String, Dispatch> buildDispatch()
    {
        CompileContext context = this.pureModel.getContext();
        Map<String, Dispatch> map = Maps.mutable.empty();
        registerUnknown(map);
        map.put("meta::core::runtime::getRuntimeWithModelConnection_Class_1__Any_MANY__Runtime_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_type_Class").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::core::runtime::getRuntimeWithModelQueryConnection_Class_1__Binding_1__Byte_MANY__Runtime_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_type_Class").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_binding_Binding").contains(ps.get(1)._genericType()._rawType()._name()) && taxoMap.get("cov_Byte").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::core::runtime::getRuntimeWithModelQueryConnection_Class_1__Binding_1__String_1__Runtime_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_type_Class").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_binding_Binding").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::core::runtime::getRuntimeWithModelQueryConnection_Class_1__String_1__Byte_MANY__Runtime_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_type_Class").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && taxoMap.get("cov_Byte").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::core::runtime::getRuntimeWithModelQueryConnection_Class_1__String_1__String_1__Runtime_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_type_Class").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::core::runtime::mergeRuntimes_Any_$1_MANY$__Runtime_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && matchOneMany(ps.get(0)._multiplicity()));
        map.put("meta::json::toJSON_Any_MANY__String_1_", (List<ValueSpecification> ps) -> ps.size() == 1);
        map.put("meta::json::toJSON_T_MANY__LambdaFunction_MANY__String_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && taxoMap.get("cov_function_LambdaFunction").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::executionPlan::engine::java::convert_Any_1__Unit_1__Any_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_type_Unit").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::executionPlan::engine::java::unitType_Any_1__String_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()));
        map.put("meta::pure::executionPlan::engine::java::unitValue_Any_1__Number_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()));
        map.put("meta::pure::executionPlan::featureFlag::withFeatureFlags_T_MANY__Enum_MANY__T_MANY_", (List<ValueSpecification> ps) -> ps.size() == 2);
        map.put("meta::pure::functions::asserts::assertContains_Any_MANY__Any_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(1)._multiplicity()));
        map.put("meta::pure::functions::asserts::assertContains_Any_MANY__Any_1__Function_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(1)._multiplicity()) && isOne(ps.get(2)._multiplicity()) && ("Nil".equals(ps.get(2)._genericType()._rawType()._name()) || check(funcType(ps.get(2)._genericType()), (FunctionType ft) -> isOne(ft._returnMultiplicity()) && taxoMap.get("cov_String").contains(ft._returnType()._rawType()._name()) && check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.isEmpty()))));
        map.put("meta::pure::functions::asserts::assertContains_Any_MANY__Any_1__String_1__Any_MANY__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(1)._multiplicity()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::asserts::assertContains_Any_MANY__Any_1__String_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(1)._multiplicity()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::asserts::assertEmpty_Any_MANY__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 1);
        map.put("meta::pure::functions::asserts::assertEmpty_Any_MANY__Function_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(1)._multiplicity()) && ("Nil".equals(ps.get(1)._genericType()._rawType()._name()) || check(funcType(ps.get(1)._genericType()), (FunctionType ft) -> isOne(ft._returnMultiplicity()) && taxoMap.get("cov_String").contains(ft._returnType()._rawType()._name()) && check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.isEmpty()))));
        map.put("meta::pure::functions::asserts::assertEmpty_Any_MANY__String_1__Any_MANY__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::asserts::assertEmpty_Any_MANY__String_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::asserts::assertEqWithinTolerance_Number_1__Number_1__Number_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::asserts::assertEqWithinTolerance_Number_1__Number_1__Number_1__Function_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(2)._genericType()._rawType()._name()) && isOne(ps.get(3)._multiplicity()) && ("Nil".equals(ps.get(3)._genericType()._rawType()._name()) || check(funcType(ps.get(3)._genericType()), (FunctionType ft) -> isOne(ft._returnMultiplicity()) && taxoMap.get("cov_String").contains(ft._returnType()._rawType()._name()) && check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.isEmpty()))));
        map.put("meta::pure::functions::asserts::assertEqWithinTolerance_Number_1__Number_1__Number_1__String_1__Any_MANY__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 5 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(2)._genericType()._rawType()._name()) && isOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::asserts::assertEqWithinTolerance_Number_1__Number_1__Number_1__String_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(2)._genericType()._rawType()._name()) && isOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::asserts::assertEq_Any_1__Any_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && isOne(ps.get(1)._multiplicity()));
        map.put("meta::pure::functions::asserts::assertEq_Any_1__Any_1__Function_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && isOne(ps.get(1)._multiplicity()) && isOne(ps.get(2)._multiplicity()) && ("Nil".equals(ps.get(2)._genericType()._rawType()._name()) || check(funcType(ps.get(2)._genericType()), (FunctionType ft) -> isOne(ft._returnMultiplicity()) && taxoMap.get("cov_String").contains(ft._returnType()._rawType()._name()) && check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.isEmpty()))));
        map.put("meta::pure::functions::asserts::assertEq_Any_1__Any_1__String_1__Any_MANY__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && isOne(ps.get(1)._multiplicity()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::asserts::assertEq_Any_1__Any_1__String_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && isOne(ps.get(1)._multiplicity()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::asserts::assertEquals_Any_MANY__Any_MANY__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2);
        map.put("meta::pure::functions::asserts::assertEquals_Any_MANY__Any_MANY__Function_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(2)._multiplicity()) && ("Nil".equals(ps.get(2)._genericType()._rawType()._name()) || check(funcType(ps.get(2)._genericType()), (FunctionType ft) -> isOne(ft._returnMultiplicity()) && taxoMap.get("cov_String").contains(ft._returnType()._rawType()._name()) && check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.isEmpty()))));
        map.put("meta::pure::functions::asserts::assertEquals_Any_MANY__Any_MANY__String_1__Any_MANY__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::asserts::assertEquals_Any_MANY__Any_MANY__String_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::asserts::assertFalse_Boolean_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::asserts::assertFalse_Boolean_1__Function_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && ("Nil".equals(ps.get(1)._genericType()._rawType()._name()) || check(funcType(ps.get(1)._genericType()), (FunctionType ft) -> isOne(ft._returnMultiplicity()) && taxoMap.get("cov_String").contains(ft._returnType()._rawType()._name()) && check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.isEmpty()))));
        map.put("meta::pure::functions::asserts::assertFalse_Boolean_1__String_1__Any_MANY__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::asserts::assertFalse_Boolean_1__String_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::asserts::assertInstanceOf_Any_1__Type_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_type_Type").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::asserts::assertInstanceOf_Any_1__Type_1__Function_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_type_Type").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && ("Nil".equals(ps.get(2)._genericType()._rawType()._name()) || check(funcType(ps.get(2)._genericType()), (FunctionType ft) -> isOne(ft._returnMultiplicity()) && taxoMap.get("cov_String").contains(ft._returnType()._rawType()._name()) && check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.isEmpty()))));
        map.put("meta::pure::functions::asserts::assertInstanceOf_Any_1__Type_1__String_1__Any_MANY__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_type_Type").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::asserts::assertInstanceOf_Any_1__Type_1__String_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_type_Type").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::asserts::assertIsNot_Any_1__Any_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && isOne(ps.get(1)._multiplicity()));
        map.put("meta::pure::functions::asserts::assertIsNot_Any_1__Any_1__Function_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && isOne(ps.get(1)._multiplicity()) && isOne(ps.get(2)._multiplicity()) && ("Nil".equals(ps.get(2)._genericType()._rawType()._name()) || check(funcType(ps.get(2)._genericType()), (FunctionType ft) -> isOne(ft._returnMultiplicity()) && taxoMap.get("cov_String").contains(ft._returnType()._rawType()._name()) && check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.isEmpty()))));
        map.put("meta::pure::functions::asserts::assertIsNot_Any_1__Any_1__String_1__Any_MANY__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && isOne(ps.get(1)._multiplicity()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::asserts::assertIsNot_Any_1__Any_1__String_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && isOne(ps.get(1)._multiplicity()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::asserts::assertIs_Any_1__Any_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && isOne(ps.get(1)._multiplicity()));
        map.put("meta::pure::functions::asserts::assertIs_Any_1__Any_1__Function_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && isOne(ps.get(1)._multiplicity()) && isOne(ps.get(2)._multiplicity()) && ("Nil".equals(ps.get(2)._genericType()._rawType()._name()) || check(funcType(ps.get(2)._genericType()), (FunctionType ft) -> isOne(ft._returnMultiplicity()) && taxoMap.get("cov_String").contains(ft._returnType()._rawType()._name()) && check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.isEmpty()))));
        map.put("meta::pure::functions::asserts::assertIs_Any_1__Any_1__String_1__Any_MANY__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && isOne(ps.get(1)._multiplicity()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::asserts::assertIs_Any_1__Any_1__String_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && isOne(ps.get(1)._multiplicity()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::asserts::assertJsonStringsEqual_String_1__String_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::asserts::assertNotContains_Any_MANY__Any_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(1)._multiplicity()));
        map.put("meta::pure::functions::asserts::assertNotContains_Any_MANY__Any_1__Function_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(1)._multiplicity()) && isOne(ps.get(2)._multiplicity()) && ("Nil".equals(ps.get(2)._genericType()._rawType()._name()) || check(funcType(ps.get(2)._genericType()), (FunctionType ft) -> isOne(ft._returnMultiplicity()) && taxoMap.get("cov_String").contains(ft._returnType()._rawType()._name()) && check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.isEmpty()))));
        map.put("meta::pure::functions::asserts::assertNotContains_Any_MANY__Any_1__String_1__Any_MANY__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(1)._multiplicity()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::asserts::assertNotContains_Any_MANY__Any_1__String_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(1)._multiplicity()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::asserts::assertNotEmpty_Any_MANY__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 1);
        map.put("meta::pure::functions::asserts::assertNotEmpty_Any_MANY__Function_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(1)._multiplicity()) && ("Nil".equals(ps.get(1)._genericType()._rawType()._name()) || check(funcType(ps.get(1)._genericType()), (FunctionType ft) -> isOne(ft._returnMultiplicity()) && taxoMap.get("cov_String").contains(ft._returnType()._rawType()._name()) && check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.isEmpty()))));
        map.put("meta::pure::functions::asserts::assertNotEmpty_Any_MANY__String_1__Any_MANY__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::asserts::assertNotEmpty_Any_MANY__String_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::asserts::assertNotEq_Any_1__Any_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && isOne(ps.get(1)._multiplicity()));
        map.put("meta::pure::functions::asserts::assertNotEq_Any_1__Any_1__Function_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && isOne(ps.get(1)._multiplicity()) && isOne(ps.get(2)._multiplicity()) && ("Nil".equals(ps.get(2)._genericType()._rawType()._name()) || check(funcType(ps.get(2)._genericType()), (FunctionType ft) -> isOne(ft._returnMultiplicity()) && taxoMap.get("cov_String").contains(ft._returnType()._rawType()._name()) && check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.isEmpty()))));
        map.put("meta::pure::functions::asserts::assertNotEq_Any_1__Any_1__String_1__Any_MANY__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && isOne(ps.get(1)._multiplicity()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::asserts::assertNotEq_Any_1__Any_1__String_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && isOne(ps.get(1)._multiplicity()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::asserts::assertNotEquals_Any_MANY__Any_MANY__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2);
        map.put("meta::pure::functions::asserts::assertNotEquals_Any_MANY__Any_MANY__Function_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(2)._multiplicity()) && ("Nil".equals(ps.get(2)._genericType()._rawType()._name()) || check(funcType(ps.get(2)._genericType()), (FunctionType ft) -> isOne(ft._returnMultiplicity()) && taxoMap.get("cov_String").contains(ft._returnType()._rawType()._name()) && check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.isEmpty()))));
        map.put("meta::pure::functions::asserts::assertNotEquals_Any_MANY__Any_MANY__String_1__Any_MANY__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::asserts::assertNotEquals_Any_MANY__Any_MANY__String_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::asserts::assertNotSize_Any_MANY__Integer_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::asserts::assertNotSize_Any_MANY__Integer_1__Function_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && ("Nil".equals(ps.get(2)._genericType()._rawType()._name()) || check(funcType(ps.get(2)._genericType()), (FunctionType ft) -> isOne(ft._returnMultiplicity()) && taxoMap.get("cov_String").contains(ft._returnType()._rawType()._name()) && check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.isEmpty()))));
        map.put("meta::pure::functions::asserts::assertNotSize_Any_MANY__Integer_1__String_1__Any_MANY__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::asserts::assertNotSize_Any_MANY__Integer_1__String_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::asserts::assertSameElements_Any_MANY__Any_MANY__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2);
        map.put("meta::pure::functions::asserts::assertSameElements_Any_MANY__Any_MANY__Function_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(2)._multiplicity()) && ("Nil".equals(ps.get(2)._genericType()._rawType()._name()) || check(funcType(ps.get(2)._genericType()), (FunctionType ft) -> isOne(ft._returnMultiplicity()) && taxoMap.get("cov_String").contains(ft._returnType()._rawType()._name()) && check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.isEmpty()))));
        map.put("meta::pure::functions::asserts::assertSameElements_Any_MANY__Any_MANY__String_1__Any_MANY__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::asserts::assertSameElements_Any_MANY__Any_MANY__String_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::asserts::assertSize_Any_MANY__Integer_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::asserts::assertSize_Any_MANY__Integer_1__Function_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && ("Nil".equals(ps.get(2)._genericType()._rawType()._name()) || check(funcType(ps.get(2)._genericType()), (FunctionType ft) -> isOne(ft._returnMultiplicity()) && taxoMap.get("cov_String").contains(ft._returnType()._rawType()._name()) && check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.isEmpty()))));
        map.put("meta::pure::functions::asserts::assertSize_Any_MANY__Integer_1__String_1__Any_MANY__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::asserts::assertSize_Any_MANY__Integer_1__String_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::asserts::assert_Boolean_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::asserts::assert_Boolean_1__Function_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && ("Nil".equals(ps.get(1)._genericType()._rawType()._name()) || check(funcType(ps.get(1)._genericType()), (FunctionType ft) -> isOne(ft._returnMultiplicity()) && taxoMap.get("cov_String").contains(ft._returnType()._rawType()._name()) && check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.isEmpty()))));
        map.put("meta::pure::functions::asserts::assert_Boolean_1__String_1__Any_MANY__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::asserts::assert_Boolean_1__String_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::xor_Boolean_1__Boolean_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::asserts::fail_Function_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && ("Nil".equals(ps.get(0)._genericType()._rawType()._name()) || check(funcType(ps.get(0)._genericType()), (FunctionType ft) -> isOne(ft._returnMultiplicity()) && taxoMap.get("cov_String").contains(ft._returnType()._rawType()._name()) && check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.isEmpty()))));
        map.put("meta::pure::functions::asserts::fail_String_1__Any_MANY__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::asserts::fail_String_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::asserts::fail__Boolean_1_", (List<ValueSpecification> ps) -> ps.isEmpty());
        map.put("meta::pure::functions::boolean::and_Boolean_1__Boolean_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::eq_Any_1__Any_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && isOne(ps.get(1)._multiplicity()));
        map.put("meta::pure::functions::boolean::equalJsonStrings_String_1__String_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::equal_Any_MANY__Any_MANY__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2);
        map.put("meta::pure::functions::boolean::greaterThanEqual_Boolean_$0_1$__Boolean_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::greaterThanEqual_Boolean_$0_1$__Boolean_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::greaterThanEqual_Boolean_1__Boolean_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::greaterThanEqual_Boolean_1__Boolean_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::greaterThanEqual_Date_$0_1$__Date_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::greaterThanEqual_Date_$0_1$__Date_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::greaterThanEqual_Date_1__Date_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::greaterThanEqual_Date_1__Date_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::greaterThanEqual_Number_$0_1$__Number_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::greaterThanEqual_Number_$0_1$__Number_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::greaterThanEqual_Number_1__Number_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::greaterThanEqual_Number_1__Number_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::greaterThanEqual_String_$0_1$__String_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::greaterThanEqual_String_$0_1$__String_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::greaterThanEqual_String_1__String_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::greaterThanEqual_String_1__String_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::greaterThan_Boolean_$0_1$__Boolean_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::greaterThan_Boolean_$0_1$__Boolean_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::greaterThan_Boolean_1__Boolean_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::greaterThan_Boolean_1__Boolean_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::greaterThan_Date_$0_1$__Date_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::greaterThan_Date_$0_1$__Date_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::greaterThan_Date_1__Date_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::greaterThan_Date_1__Date_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::greaterThan_Number_$0_1$__Number_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::greaterThan_Number_$0_1$__Number_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::greaterThan_Number_1__Number_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::greaterThan_Number_1__Number_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::greaterThan_String_$0_1$__String_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::greaterThan_String_$0_1$__String_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::greaterThan_String_1__String_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::greaterThan_String_1__String_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::isFalse_Boolean_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::isTrue_Boolean_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::is_Any_1__Any_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && isOne(ps.get(1)._multiplicity()));
        map.put("meta::pure::functions::boolean::lessThanEqual_Boolean_$0_1$__Boolean_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::lessThanEqual_Boolean_$0_1$__Boolean_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::lessThanEqual_Boolean_1__Boolean_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::lessThanEqual_Boolean_1__Boolean_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::lessThanEqual_Date_$0_1$__Date_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::lessThanEqual_Date_$0_1$__Date_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::lessThanEqual_Date_1__Date_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::lessThanEqual_Date_1__Date_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::lessThanEqual_Number_$0_1$__Number_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::lessThanEqual_Number_$0_1$__Number_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::lessThanEqual_Number_1__Number_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::lessThanEqual_Number_1__Number_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::lessThanEqual_String_$0_1$__String_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::lessThanEqual_String_$0_1$__String_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::lessThanEqual_String_1__String_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::lessThanEqual_String_1__String_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::lessThan_Boolean_$0_1$__Boolean_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::lessThan_Boolean_$0_1$__Boolean_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::lessThan_Boolean_1__Boolean_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::lessThan_Boolean_1__Boolean_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::lessThan_Date_$0_1$__Date_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::lessThan_Date_$0_1$__Date_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::lessThan_Date_1__Date_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::lessThan_Date_1__Date_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::lessThan_Number_$0_1$__Number_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::lessThan_Number_$0_1$__Number_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::lessThan_Number_1__Number_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::lessThan_Number_1__Number_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::lessThan_String_$0_1$__String_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::lessThan_String_$0_1$__String_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::lessThan_String_1__String_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::lessThan_String_1__String_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::not_Boolean_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::or_Boolean_1__Boolean_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::between_StrictDate_$0_1$__StrictDate_$0_1$__StrictDate_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_StrictDate").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_StrictDate").contains(ps.get(1)._genericType()._rawType()._name()) && matchZeroOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_StrictDate").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::between_DateTime_$0_1$__DateTime_$0_1$__DateTime_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_DateTime").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_DateTime").contains(ps.get(1)._genericType()._rawType()._name()) && matchZeroOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_DateTime").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::between_Number_$0_1$__Number_$0_1$__Number_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(1)._genericType()._rawType()._name()) && matchZeroOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::boolean::between_String_$0_1$__String_$0_1$__String_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && matchZeroOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::collection::add_T_MANY__T_1__T_$1_MANY$_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(1)._multiplicity()));
        map.put("meta::pure::functions::collection::agg_FunctionDefinition_1__FunctionDefinition_1__AggregateValue_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_function_FunctionDefinition").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_function_FunctionDefinition").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::collection::and_Boolean_MANY__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_Boolean").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::collection::at_T_MANY__Integer_1__T_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::collection::concatenate_T_MANY__T_MANY__T_MANY_", (List<ValueSpecification> ps) -> ps.size() == 2);
        map.put("meta::pure::functions::collection::containsAll_Any_MANY__Any_MANY__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2);
        map.put("meta::pure::functions::collection::containsAny_Any_MANY__Any_MANY__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2);
        map.put("meta::pure::functions::collection::contains_Any_MANY__Any_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(1)._multiplicity()));
        map.put("meta::pure::functions::collection::count_Any_MANY__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 1);
        map.put("meta::pure::functions::collection::defaultIfEmpty_T_MANY__T_$1_MANY$__T_$1_MANY$_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchOneMany(ps.get(1)._multiplicity()));
        map.put("meta::pure::functions::collection::distinct_T_MANY__T_MANY_", (List<ValueSpecification> ps) -> ps.size() == 1);
        map.put("meta::pure::functions::collection::dropAt_T_MANY__Integer_1__Integer_1__T_MANY_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::collection::dropAt_T_MANY__Integer_1__T_MANY_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::collection::drop_T_MANY__Integer_1__T_MANY_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::collection::exists_T_MANY__Function_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(1)._multiplicity()) && ("Nil".equals(ps.get(1)._genericType()._rawType()._name()) || check(funcType(ps.get(1)._genericType()), (FunctionType ft) -> isOne(ft._returnMultiplicity()) && taxoMap.get("cov_Boolean").contains(ft._returnType()._rawType()._name()) && check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.size() == 1 && isOne(nps.get(0)._multiplicity())))));
        map.put("meta::pure::functions::collection::filter_T_MANY__Function_1__T_MANY_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(1)._multiplicity()) && ("Nil".equals(ps.get(1)._genericType()._rawType()._name()) || check(funcType(ps.get(1)._genericType()), (FunctionType ft) -> isOne(ft._returnMultiplicity()) && taxoMap.get("cov_Boolean").contains(ft._returnType()._rawType()._name()) && check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.size() == 1 && isOne(nps.get(0)._multiplicity())))));
        map.put("meta::pure::functions::collection::first_T_MANY__T_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 1);
        map.put("meta::pure::functions::collection::fold_T_MANY__Function_1__V_m__V_m_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(1)._multiplicity()) && ("Nil".equals(ps.get(1)._genericType()._rawType()._name()) || check(funcType(ps.get(1)._genericType()), (FunctionType ft) -> check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.size() == 2 && isOne(nps.get(0)._multiplicity())))));
        map.put("meta::pure::functions::collection::forAll_T_MANY__Function_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(1)._multiplicity()) && ("Nil".equals(ps.get(1)._genericType()._rawType()._name()) || check(funcType(ps.get(1)._genericType()), (FunctionType ft) -> isOne(ft._returnMultiplicity()) && taxoMap.get("cov_Boolean").contains(ft._returnType()._rawType()._name()) && check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.size() == 1 && isOne(nps.get(0)._multiplicity())))));
        map.put("meta::pure::functions::collection::getAllForEachDate_Class_1__Date_MANY__T_MANY_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_type_Class").contains(ps.get(0)._genericType()._rawType()._name()) && taxoMap.get("cov_Date").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::collection::getAllVersionsInRange_Class_1__Date_1__Date_1__T_MANY_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_type_Class").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::collection::getAllVersions_Class_1__T_MANY_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_type_Class").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::collection::getAll_Class_1__Date_1__Date_1__T_MANY_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_type_Class").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::collection::getAll_Class_1__Date_1__T_MANY_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_type_Class").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::collection::getAll_Class_1__T_MANY_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_type_Class").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::collection::greatest_X_$1_MANY$__X_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && matchOneMany(ps.get(0)._multiplicity()));
        map.put("meta::pure::functions::collection::greatest_X_MANY__X_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 1);
        map.put("meta::pure::functions::collection::head_T_MANY__T_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 1);
        map.put("meta::pure::functions::collection::in_Any_$0_1$__Any_MANY__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()));
        map.put("meta::pure::functions::collection::in_Any_1__Any_MANY__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()));
        map.put("meta::pure::functions::collection::indexOf_T_MANY__T_1__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(1)._multiplicity()));
        map.put("meta::pure::functions::collection::isDistinct_T_MANY__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 1);
        map.put("meta::pure::functions::collection::isDistinct_T_MANY__RootGraphFetchTree_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_graphFetch_RootGraphFetchTree").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::collection::isEmpty_Any_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && matchZeroOne(ps.get(0)._multiplicity()));
        map.put("meta::pure::functions::collection::isEmpty_Any_MANY__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 1);
        map.put("meta::pure::functions::collection::isEqual_T_1__T_1__RootGraphFetchTree_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && isOne(ps.get(1)._multiplicity()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_graphFetch_RootGraphFetchTree").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::collection::isNotEmpty_Any_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && matchZeroOne(ps.get(0)._multiplicity()));
        map.put("meta::pure::functions::collection::isNotEmpty_Any_MANY__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 1);
        map.put("meta::pure::functions::collection::last_T_MANY__T_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 1);
        map.put("meta::pure::functions::collection::least_X_$1_MANY$__X_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && matchOneMany(ps.get(0)._multiplicity()));
        map.put("meta::pure::functions::collection::least_X_MANY__X_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 1);
        map.put("meta::pure::functions::collection::limit_T_MANY__Integer_1__T_MANY_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::collection::map_T_$0_1$__Function_1__V_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && isOne(ps.get(1)._multiplicity()) && ("Nil".equals(ps.get(1)._genericType()._rawType()._name()) || check(funcType(ps.get(1)._genericType()), (FunctionType ft) -> matchZeroOne(ft._returnMultiplicity()) && check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.size() == 1 && isOne(nps.get(0)._multiplicity())))));
        map.put("meta::pure::functions::collection::map_T_MANY__Function_1__V_MANY_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(1)._multiplicity()) && ("Nil".equals(ps.get(1)._genericType()._rawType()._name()) || check(funcType(ps.get(1)._genericType()), (FunctionType ft) -> check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.size() == 1 && isOne(nps.get(0)._multiplicity())))));
        map.put("meta::pure::functions::collection::map_T_m__Function_1__V_m_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(1)._multiplicity()) && ("Nil".equals(ps.get(1)._genericType()._rawType()._name()) || check(funcType(ps.get(1)._genericType()), (FunctionType ft) -> isOne(ft._returnMultiplicity()) && check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.size() == 1 && isOne(nps.get(0)._multiplicity())))));
        map.put("meta::pure::functions::collection::objectReferenceIn_Any_1__String_MANY__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::collection::oneOf_Boolean_MANY__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_Boolean").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::collection::or_Boolean_$1_MANY$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && matchOneMany(ps.get(0)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::collection::or_Boolean_MANY__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_Boolean").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::collection::paginated_T_MANY__Integer_1__Integer_1__T_MANY_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::collection::pair_U_1__V_1__Pair_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && isOne(ps.get(1)._multiplicity()));
        map.put("meta::pure::functions::collection::range_Integer_1__Integer_1__Integer_1__Integer_MANY_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::collection::range_Integer_1__Integer_1__Integer_MANY_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::collection::removeDuplicatesBy_T_MANY__Function_1__T_MANY_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(1)._multiplicity()) && ("Nil".equals(ps.get(1)._genericType()._rawType()._name()) || check(funcType(ps.get(1)._genericType()), (FunctionType ft) -> isOne(ft._returnMultiplicity()) && check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.size() == 1 && isOne(nps.get(0)._multiplicity())))));
        map.put("meta::pure::functions::collection::removeDuplicates_T_MANY__Function_$0_1$__Function_$0_1$__T_MANY_", (List<ValueSpecification> ps) -> ps.size() == 3 && matchZeroOne(ps.get(1)._multiplicity()) && ("Nil".equals(ps.get(1)._genericType()._rawType()._name()) || check(funcType(ps.get(1)._genericType()), (FunctionType ft) -> isOne(ft._returnMultiplicity()) && check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.size() == 1 && isOne(nps.get(0)._multiplicity())))) && matchZeroOne(ps.get(2)._multiplicity()) && ("Nil".equals(ps.get(2)._genericType()._rawType()._name()) || check(funcType(ps.get(2)._genericType()), (FunctionType ft) -> isOne(ft._returnMultiplicity()) && taxoMap.get("cov_Boolean").contains(ft._returnType()._rawType()._name()) && check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.size() == 2 && isOne(nps.get(0)._multiplicity()) && isOne(nps.get(1)._multiplicity())))));
        map.put("meta::pure::functions::collection::removeDuplicates_T_MANY__Function_1__T_MANY_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(1)._multiplicity()) && ("Nil".equals(ps.get(1)._genericType()._rawType()._name()) || check(funcType(ps.get(1)._genericType()), (FunctionType ft) -> isOne(ft._returnMultiplicity()) && taxoMap.get("cov_Boolean").contains(ft._returnType()._rawType()._name()) && check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.size() == 2 && isOne(nps.get(0)._multiplicity()) && isOne(nps.get(1)._multiplicity())))));
        map.put("meta::pure::functions::collection::removeDuplicates_T_MANY__T_MANY_", (List<ValueSpecification> ps) -> ps.size() == 1);
        map.put("meta::pure::functions::collection::reverse_T_m__T_m_", (List<ValueSpecification> ps) -> ps.size() == 1);
        map.put("meta::pure::functions::collection::size_Any_MANY__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 1);
        map.put("meta::pure::functions::collection::slice_T_MANY__Integer_1__Integer_1__T_MANY_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::collection::sortBy_T_m__Function_$0_1$__T_m_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(1)._multiplicity()) && ("Nil".equals(ps.get(1)._genericType()._rawType()._name()) || check(funcType(ps.get(1)._genericType()), (FunctionType ft) -> isOne(ft._returnMultiplicity()) && check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.size() == 1 && isOne(nps.get(0)._multiplicity())))));
        map.put("meta::pure::functions::collection::sort_T_m__Function_$0_1$__Function_$0_1$__T_m_", (List<ValueSpecification> ps) -> ps.size() == 3 && matchZeroOne(ps.get(1)._multiplicity()) && ("Nil".equals(ps.get(1)._genericType()._rawType()._name()) || check(funcType(ps.get(1)._genericType()), (FunctionType ft) -> isOne(ft._returnMultiplicity()) && check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.size() == 1 && isOne(nps.get(0)._multiplicity())))) && matchZeroOne(ps.get(2)._multiplicity()) && ("Nil".equals(ps.get(2)._genericType()._rawType()._name()) || check(funcType(ps.get(2)._genericType()), (FunctionType ft) -> isOne(ft._returnMultiplicity()) && taxoMap.get("cov_Integer").contains(ft._returnType()._rawType()._name()) && check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.size() == 2 && isOne(nps.get(0)._multiplicity()) && isOne(nps.get(1)._multiplicity())))));
        map.put("meta::pure::functions::collection::sort_T_m__Function_$0_1$__T_m_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(1)._multiplicity()) && ("Nil".equals(ps.get(1)._genericType()._rawType()._name()) || check(funcType(ps.get(1)._genericType()), (FunctionType ft) -> isOne(ft._returnMultiplicity()) && taxoMap.get("cov_Integer").contains(ft._returnType()._rawType()._name()) && check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.size() == 2 && isOne(nps.get(0)._multiplicity()) && isOne(nps.get(1)._multiplicity())))));
        map.put("meta::pure::functions::collection::sort_T_m__T_m_", (List<ValueSpecification> ps) -> ps.size() == 1);
        map.put("meta::pure::functions::collection::tail_T_MANY__T_MANY_", (List<ValueSpecification> ps) -> ps.size() == 1);
        map.put("meta::pure::functions::collection::take_T_MANY__Integer_1__T_MANY_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::collection::union_T_MANY__T_MANY__T_MANY_", (List<ValueSpecification> ps) -> ps.size() == 2);
        map.put("meta::pure::functions::collection::uniqueValueOnly_T_MANY__T_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 1);
        map.put("meta::pure::functions::collection::uniqueValueOnly_T_MANY__T_$0_1$__T_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(1)._multiplicity()));
        map.put("meta::pure::functions::collection::zip_T_MANY__U_MANY__Pair_MANY_", (List<ValueSpecification> ps) -> ps.size() == 2);
        map.put("meta::pure::functions::constraints::warn_Boolean_1__String_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::add_StrictDate_1__Duration_1__StrictDate_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_StrictDate").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_date_Duration").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::adjust_Date_1__Integer_1__DurationUnit_1__Date_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_date_DurationUnit").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::timeBucket_DateTime_1__Integer_1__DurationUnit_1__DateTime_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && Sets.immutable.with("Nil", "DateTime").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && ("Nil".equals(ps.get(1)._genericType()._rawType()._name()) || "Integer".equals(ps.get(1)._genericType()._rawType()._name())) && isOne(ps.get(2)._multiplicity()) && ("Nil".equals(ps.get(2)._genericType()._rawType()._name()) || "DurationUnit".equals(ps.get(2)._genericType()._rawType()._name())));
        map.put("meta::pure::functions::date::timeBucket_DateTime_$0_1$__Integer_1__DurationUnit_1__DateTime_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && Sets.immutable.with("Nil", "DateTime").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && ("Nil".equals(ps.get(1)._genericType()._rawType()._name()) || "Integer".equals(ps.get(1)._genericType()._rawType()._name())) && isOne(ps.get(2)._multiplicity()) && ("Nil".equals(ps.get(2)._genericType()._rawType()._name()) || "DurationUnit".equals(ps.get(2)._genericType()._rawType()._name())));
        map.put("meta::pure::functions::date::timeBucket_StrictDate_1__Integer_1__DurationUnit_1__StrictDate_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && Sets.immutable.with("Nil", "StrictDate").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && ("Nil".equals(ps.get(1)._genericType()._rawType()._name()) || "Integer".equals(ps.get(1)._genericType()._rawType()._name())) && isOne(ps.get(2)._multiplicity()) && ("Nil".equals(ps.get(2)._genericType()._rawType()._name()) || "DurationUnit".equals(ps.get(2)._genericType()._rawType()._name())));
        map.put("meta::pure::functions::date::timeBucket_StrictDate_$0_1$__Integer_1__DurationUnit_1__StrictDate_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && Sets.immutable.with("Nil", "StrictDate").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && ("Nil".equals(ps.get(1)._genericType()._rawType()._name()) || "Integer".equals(ps.get(1)._genericType()._rawType()._name())) && isOne(ps.get(2)._multiplicity()) && ("Nil".equals(ps.get(2)._genericType()._rawType()._name()) || "DurationUnit".equals(ps.get(2)._genericType()._rawType()._name())));
        map.put("meta::pure::functions::date::convertTimeZone_DateTime_1__String_1__String_1__String_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_DateTime").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::dateDiff_Date_$0_1$__Date_$0_1$__DurationUnit_1__Integer_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 3 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_date_DurationUnit").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::dateDiff_Date_1__Date_1__DurationUnit_1__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_date_DurationUnit").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::datePart_Date_$0_1$__Date_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 1 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::datePart_Date_1__Date_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::date_Integer_1__Date_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::date_Integer_1__Integer_1__Date_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::date_Integer_1__Integer_1__Integer_1__Integer_1__DateTime_1_", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(2)._genericType()._rawType()._name()) && isOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::date_Integer_1__Integer_1__Integer_1__Integer_1__Integer_1__DateTime_1_", (List<ValueSpecification> ps) -> ps.size() == 5 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(2)._genericType()._rawType()._name()) && isOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(3)._genericType()._rawType()._name()) && isOne(ps.get(4)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(4)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::date_Integer_1__Integer_1__Integer_1__Integer_1__Integer_1__Number_1__DateTime_1_", (List<ValueSpecification> ps) -> ps.size() == 6 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(2)._genericType()._rawType()._name()) && isOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(3)._genericType()._rawType()._name()) && isOne(ps.get(4)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(4)._genericType()._rawType()._name()) && isOne(ps.get(5)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(5)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::date_Integer_1__Integer_1__Integer_1__StrictDate_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::dayOfMonth_Date_1__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::dayOfWeekNumber_Date_1__DayOfWeek_1__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_date_DayOfWeek").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::dayOfWeekNumber_Date_1__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::dayOfWeek_Date_1__DayOfWeek_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::dayOfWeek_Integer_1__DayOfWeek_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::dayOfYear_Date_1__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::daysOfMonth_Date_1__Integer_MANY_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::firstDayOfMonth_Date_1__Date_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::firstDayOfQuarter_Date_1__StrictDate_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::firstDayOfThisMonth__Date_1_", (List<ValueSpecification> ps) -> ps.isEmpty());
        map.put("meta::pure::functions::date::firstDayOfThisQuarter__StrictDate_1_", (List<ValueSpecification> ps) -> ps.isEmpty());
        map.put("meta::pure::functions::date::firstDayOfThisWeek__Date_1_", (List<ValueSpecification> ps) -> ps.isEmpty());
        map.put("meta::pure::functions::date::firstDayOfThisYear__Date_1_", (List<ValueSpecification> ps) -> ps.isEmpty());
        map.put("meta::pure::functions::date::firstDayOfWeek_Date_1__Date_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::firstDayOfYear_Date_1__Date_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::firstHourOfDay_Date_1__DateTime_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::firstMillisecondOfSecond_Date_1__DateTime_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::firstMinuteOfHour_Date_1__DateTime_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::firstSecondOfMinute_Date_1__DateTime_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::hasDay_Date_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::hasHour_Date_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::hasMinute_Date_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::hasMonth_Date_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::hasSecond_Date_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::hasSubsecondWithAtLeastPrecision_Date_1__Integer_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::hasSubsecond_Date_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::hasYear_Date_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::hour_Date_1__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::isAfterDay_Date_$0_1$__Date_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::isAfterDay_Date_$0_1$__Date_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::isAfterDay_Date_1__Date_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::isAfterDay_Date_1__Date_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::isBeforeDay_Date_$0_1$__Date_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::isBeforeDay_Date_$0_1$__Date_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::isBeforeDay_Date_1__Date_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::isBeforeDay_Date_1__Date_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::isOnDay_Date_$0_1$__Date_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::isOnDay_Date_$0_1$__Date_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::isOnDay_Date_1__Date_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::isOnDay_Date_1__Date_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::isOnOrAfterDay_Date_$0_1$__Date_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::isOnOrAfterDay_Date_$0_1$__Date_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::isOnOrAfterDay_Date_1__Date_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::isOnOrAfterDay_Date_1__Date_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::isOnOrBeforeDay_Date_$0_1$__Date_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::isOnOrBeforeDay_Date_$0_1$__Date_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::isOnOrBeforeDay_Date_1__Date_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::isOnOrBeforeDay_Date_1__Date_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::max_DateTime_1__DateTime_1__DateTime_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_DateTime").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_DateTime").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::max_DateTime_MANY__DateTime_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_DateTime").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::max_Date_1__Date_1__Date_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::max_Date_MANY__Date_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::max_StrictDate_1__StrictDate_1__StrictDate_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_StrictDate").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_StrictDate").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::max_StrictDate_MANY__StrictDate_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_StrictDate").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::min_DateTime_1__DateTime_1__DateTime_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_DateTime").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_DateTime").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::min_DateTime_MANY__DateTime_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_DateTime").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::min_Date_1__Date_1__Date_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::min_Date_MANY__Date_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::min_StrictDate_1__StrictDate_1__StrictDate_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_StrictDate").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_StrictDate").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::min_StrictDate_MANY__StrictDate_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_StrictDate").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::minute_Date_1__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::monthNumber_Date_$0_1$__Integer_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 1 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::monthNumber_Date_1__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::month_Date_1__Month_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::month_Integer_1__Month_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::mostRecentDayOfWeek_Date_1__DayOfWeek_1__Date_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_date_DayOfWeek").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::mostRecentDayOfWeek_DayOfWeek_1__Date_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_date_DayOfWeek").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::now__DateTime_1_", (List<ValueSpecification> ps) -> ps.isEmpty());
        map.put("meta::pure::functions::date::previousDayOfWeek_Date_1__DayOfWeek_1__Date_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_date_DayOfWeek").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::previousDayOfWeek_DayOfWeek_1__Date_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_date_DayOfWeek").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::quarterNumber_Date_1__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::quarter_Date_1__Quarter_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::quarter_Integer_1__Quarter_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::second_Date_1__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::toEpochValue_Date_1__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::today__StrictDate_1_", (List<ValueSpecification> ps) -> ps.isEmpty());
        map.put("meta::pure::functions::date::weekOfYear_Date_$0_1$__Integer_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 1 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::weekOfYear_Date_1__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::year_Date_$0_1$__Integer_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 1 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::date::year_Date_1__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Date").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::hash::hash_String_1__HashType_1__String_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_hash_HashType").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::hash::hashCode_Any_MANY__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 1);
        map.put("meta::pure::functions::lang::cast_Any_m__T_1__T_m_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(1)._multiplicity()));
        map.put("meta::pure::functions::lang::compare_T_1__T_1__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && isOne(ps.get(1)._multiplicity()));
        map.put("meta::pure::functions::lang::eval_Function_1__T_n__U_p__V_m_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && ("Nil".equals(ps.get(0)._genericType()._rawType()._name()) || check(funcType(ps.get(0)._genericType()), (FunctionType ft) -> check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.size() == 2))));
        map.put("meta::pure::functions::lang::eval_Function_1__T_n__V_m_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && ("Nil".equals(ps.get(0)._genericType()._rawType()._name()) || check(funcType(ps.get(0)._genericType()), (FunctionType ft) -> check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.size() == 1))));
        map.put("meta::pure::functions::lang::eval_Function_1__V_m_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && ("Nil".equals(ps.get(0)._genericType()._rawType()._name()) || check(funcType(ps.get(0)._genericType()), (FunctionType ft) -> check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.isEmpty()))));
        map.put("meta::pure::functions::lang::if_Boolean_1__Function_1__Function_1__T_m_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && ("Nil".equals(ps.get(1)._genericType()._rawType()._name()) || check(funcType(ps.get(1)._genericType()), (FunctionType ft) -> check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.isEmpty()))) && isOne(ps.get(2)._multiplicity()) && ("Nil".equals(ps.get(2)._genericType()._rawType()._name()) || check(funcType(ps.get(2)._genericType()), (FunctionType ft) -> check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.isEmpty()))));
        map.put("meta::pure::functions::lang::letFunction_String_1__T_m__T_m_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::lang::match_Any_MANY__Function_$1_MANY$__T_m_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchOneMany(ps.get(1)._multiplicity()) && ("Nil".equals(ps.get(1)._genericType()._rawType()._name()) || check(funcType(ps.get(1)._genericType()), (FunctionType ft) -> check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.size() == 1))));
        map.put("meta::pure::functions::lang::new_Class_1__String_1__KeyExpression_MANY__T_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_type_Class").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && taxoMap.get("cov_function_KeyExpression").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::lang::subType_Any_m__T_1__T_m_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(1)._multiplicity()));
        map.put("meta::pure::functions::math::abs_Float_1__Float_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Float").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::abs_Integer_1__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::abs_Decimal_1__Decimal_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Decimal").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::abs_Number_1__Number_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::acos_Number_1__Float_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::asin_Number_1__Float_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::atan2_Number_1__Number_1__Float_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::atan_Number_1__Float_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::average_Float_MANY__Float_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_Float").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::average_Integer_MANY__Float_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_Integer").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::average_Number_MANY__Float_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::median_Number_MANY__Float_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_Float").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::median_Integer_MANY__Float_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_Integer").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::median_Float_MANY__Float_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::mode_Float_MANY__Float_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_Float").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::mode_Integer_MANY__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_Integer").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::mode_Number_MANY__Number_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::cbrt_Number_1__Float_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::ceiling_Number_1__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::cos_Number_1__Float_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::cosh_Number_1__Float_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::cot_Number_1__Float_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::distanceHaversineDegrees_Number_1__Number_1__Number_1__Number_1__Number_1_", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(2)._genericType()._rawType()._name()) && isOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::distanceHaversineRadians_Number_1__Number_1__Number_1__Number_1__Number_1_", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(2)._genericType()._rawType()._name()) && isOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::distanceSphericalLawOfCosinesDegrees_Number_1__Number_1__Number_1__Number_1__Number_1_", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(2)._genericType()._rawType()._name()) && isOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::distanceSphericalLawOfCosinesRadians_Number_1__Number_1__Number_1__Number_1__Number_1_", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(2)._genericType()._rawType()._name()) && isOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::divide_Number_1__Number_1__Float_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::earthRadius__Float_1_", (List<ValueSpecification> ps) -> ps.isEmpty());
        map.put("meta::pure::functions::math::exp_Number_1__Float_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::floor_Number_1__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::log10_Number_1__Float_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::log_Number_1__Float_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::max_Float_$1_MANY$__Float_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && matchOneMany(ps.get(0)._multiplicity()) && taxoMap.get("cov_Float").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::max_Float_1__Float_1__Float_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Float").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Float").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::max_Float_MANY__Float_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_Float").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::max_Integer_$1_MANY$__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && matchOneMany(ps.get(0)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::max_Integer_1__Integer_1__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::max_Integer_MANY__Integer_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_Integer").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::max_Number_$1_MANY$__Number_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && matchOneMany(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::max_Number_1__Number_1__Number_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::max_Number_MANY__Number_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::maxBy_T_MANY__Number_MANY__T_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 2 && taxoMap.get("cov_Number").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::maxBy_T_MANY__Number_MANY__Integer_1__T_MANY_", (List<ValueSpecification> ps) -> ps.size() == 3 && taxoMap.get("cov_Number").contains(ps.get(1)._genericType()._rawType()._name()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::maxBy_RowMapper_MANY__T_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_mathUtility_RowMapper").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::maxBy_RowMapper_MANY__Integer_1__T_MANY_", (List<ValueSpecification> ps) -> ps.size() == 2 && taxoMap.get("cov_mathUtility_RowMapper").contains(ps.get(0)._genericType()._rawType()._name()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::minBy_T_MANY__Number_MANY__T_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 2 && taxoMap.get("cov_Number").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::minBy_T_MANY__Number_MANY__Integer_1__T_MANY_", (List<ValueSpecification> ps) -> ps.size() == 3 && taxoMap.get("cov_Number").contains(ps.get(1)._genericType()._rawType()._name()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::minBy_RowMapper_MANY__T_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_mathUtility_RowMapper").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::minBy_RowMapper_MANY__Integer_1__T_MANY_", (List<ValueSpecification> ps) -> ps.size() == 2 && taxoMap.get("cov_mathUtility_RowMapper").contains(ps.get(0)._genericType()._rawType()._name()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::mean_Float_MANY__Float_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_Float").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::mean_Integer_MANY__Float_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_Integer").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::mean_Number_MANY__Float_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::min_Float_$1_MANY$__Float_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && matchOneMany(ps.get(0)._multiplicity()) && taxoMap.get("cov_Float").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::min_Float_1__Float_1__Float_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Float").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Float").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::min_Float_MANY__Float_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_Float").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::min_Integer_$1_MANY$__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && matchOneMany(ps.get(0)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::min_Integer_1__Integer_1__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::min_Integer_MANY__Integer_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_Integer").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::min_Number_$1_MANY$__Number_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && matchOneMany(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::min_Number_1__Number_1__Number_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::min_Number_MANY__Number_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::minus_Decimal_MANY__Decimal_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_Decimal").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::minus_Float_MANY__Float_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_Float").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::minus_Integer_MANY__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_Integer").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::minus_Number_MANY__Number_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::mod_Integer_1__Integer_1__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::olap::averageRank_Any_MANY__Map_1_", (List<ValueSpecification> ps) -> ps.size() == 1);
        map.put("meta::pure::functions::math::olap::denseRank_Any_MANY__Map_1_", (List<ValueSpecification> ps) -> ps.size() == 1);
        map.put("meta::pure::functions::math::olap::rank_Any_MANY__Map_1_", (List<ValueSpecification> ps) -> ps.size() == 1);
        map.put("meta::pure::functions::math::percentile_Number_MANY__Float_1__Boolean_1__Boolean_1__Number_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 4 && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Float").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(2)._genericType()._rawType()._name()) && isOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::percentile_Number_MANY__Float_1__Number_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 2 && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Float").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::pi__Float_1_", (List<ValueSpecification> ps) -> ps.isEmpty());
        map.put("meta::pure::functions::math::plus_Decimal_MANY__Decimal_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_Decimal").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::plus_Float_MANY__Float_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_Float").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::plus_Integer_MANY__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_Integer").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::plus_Number_MANY__Number_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::pow_Number_1__Number_1__Number_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::rem_Number_1__Number_1__Number_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::round_Decimal_1__Integer_1__Decimal_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Decimal").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::round_Float_1__Integer_1__Float_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Float").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::round_Number_1__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::sign_Number_1__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::sin_Number_1__Float_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::sinh_Number_1__Float_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::sqrt_Number_1__Float_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::stdDevPopulation_Number_$1_MANY$__Number_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && matchOneMany(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::stdDevPopulation_Number_MANY__Number_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::stdDevSample_Number_$1_MANY$__Number_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && matchOneMany(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::stdDevSample_Number_MANY__Number_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::sum_Float_MANY__Float_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_Float").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::sum_Integer_MANY__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_Integer").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::sum_Number_MANY__Number_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::tan_Number_1__Float_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::tanh_Number_1__Float_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::times_Decimal_MANY__Decimal_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_Decimal").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::times_Float_MANY__Float_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_Float").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::times_Integer_MANY__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_Integer").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::times_Number_MANY__Number_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::toDecimal_Number_1__Decimal_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::toDegrees_Number_1__Float_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::toFloat_Number_1__Float_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::toRadians_Number_1__Float_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::variancePopulation_Number_MANY__Number_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::varianceSample_Number_MANY__Number_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::variance_Number_MANY__Boolean_1__Number_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::covarPopulation_Number_MANY__Number_MANY__Number_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 2 && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()) && taxoMap.get("cov_Number").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::covarPopulation_RowMapper_MANY__Number_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_mathUtility_RowMapper").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::covarSample_Number_MANY__Number_MANY__Number_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 2 && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()) && taxoMap.get("cov_Number").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::covarSample_RowMapper_MANY__Number_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_mathUtility_RowMapper").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::corr_Number_MANY__Number_MANY__Number_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 2 && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()) && taxoMap.get("cov_Number").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::corr_RowMapper_MANY__Number_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_mathUtility_RowMapper").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::mathUtility::rowMapper_T_$0_1$__U_$0_1$__RowMapper_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && matchZeroOne(ps.get(1)._multiplicity()));
        map.put("meta::pure::functions::math::wavgUtility::wavgRowMapper_Number_$0_1$__Number_$0_1$__WavgRowMapper_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::wavg_Number_MANY__Number_MANY__Float_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && taxoMap.get("cov_Number").contains(ps.get(0)._genericType()._rawType()._name()) && taxoMap.get("cov_Number").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::wavg_RowMapper_MANY__Float_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_wavgUtility_WavgRowMapper").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::bitAnd_Integer_1__Integer_1__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::bitNot_Integer_1__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::bitOr_Integer_1__Integer_1__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::bitXor_Integer_1__Integer_1__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::bitShiftLeft_Integer_1__Integer_1__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::math::bitShiftRight_Integer_1__Integer_1__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::meta::enumName_Enumeration_1__String_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()));
        map.put("meta::pure::functions::meta::enumValues_Enumeration_1__T_MANY_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()));
        map.put("meta::pure::functions::meta::id_Any_1__String_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()));
        map.put("meta::pure::functions::meta::instanceOf_Any_1__Type_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_type_Type").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::meta::newUnit_Unit_1__Number_1__Any_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_type_Unit").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Number").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::meta::typeName_Any_1__String_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()));
        map.put("meta::pure::functions::meta::typePath_Any_1__String_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()));
        map.put("meta::pure::functions::meta::type_Any_MANY__Type_1_", (List<ValueSpecification> ps) -> ps.size() == 1);
        map.put("meta::pure::functions::multiplicity::toOneMany_T_MANY__T_$1_MANY$_", (List<ValueSpecification> ps) -> ps.size() == 1);
        map.put("meta::pure::functions::multiplicity::toOne_T_MANY__T_1_", (List<ValueSpecification> ps) -> ps.size() == 1);
        map.put("meta::pure::functions::relation::asOfJoin_Relation_1__Relation_1__Function_1__Function_1__Relation_1_", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && ("Nil".equals(ps.get(2)._genericType()._rawType()._name()) || check(funcType(ps.get(2)._genericType()), (FunctionType ft) -> isOne(ft._returnMultiplicity()) && taxoMap.get("cov_Boolean").contains(ft._returnType()._rawType()._name()) && check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.size() == 2 && isOne(nps.get(0)._multiplicity()) && isOne(nps.get(1)._multiplicity())))) && isOne(ps.get(3)._multiplicity()) && ("Nil".equals(ps.get(3)._genericType()._rawType()._name()) || check(funcType(ps.get(3)._genericType()), (FunctionType ft) -> isOne(ft._returnMultiplicity()) && taxoMap.get("cov_Boolean").contains(ft._returnType()._rawType()._name()) && check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.size() == 2 && isOne(nps.get(0)._multiplicity()) && isOne(nps.get(1)._multiplicity())))));
        map.put("meta::pure::functions::relation::asOfJoin_Relation_1__Relation_1__Function_1__Relation_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && ("Nil".equals(ps.get(2)._genericType()._rawType()._name()) || check(funcType(ps.get(2)._genericType()), (FunctionType ft) -> isOne(ft._returnMultiplicity()) && taxoMap.get("cov_Boolean").contains(ft._returnType()._rawType()._name()) && check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.size() == 2 && isOne(nps.get(0)._multiplicity()) && isOne(nps.get(1)._multiplicity())))));
        map.put("meta::pure::functions::relation::ascending_ColSpec_1__SortInfo_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_ColSpec").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::concatenate_Relation_1__Relation_1__Relation_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::cumulativeDistribution_Relation_1___Window_1__T_1__Float_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_relation__Window").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()));
        map.put("meta::pure::functions::relation::denseRank_Relation_1___Window_1__T_1__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_relation__Window").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()));
        map.put("meta::pure::functions::relation::descending_ColSpec_1__SortInfo_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_ColSpec").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::distinct_Relation_1__ColSpecArray_1__Relation_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_relation_ColSpecArray").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::distinct_Relation_1__Relation_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::drop_Relation_1__Integer_1__Relation_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::extend_Relation_1__AggColSpecArray_1__Relation_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_relation_AggColSpecArray").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::extend_Relation_1__AggColSpec_1__Relation_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_relation_AggColSpec").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::extend_Relation_1__FuncColSpecArray_1__Relation_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_relation_FuncColSpecArray").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::extend_Relation_1__FuncColSpec_1__Relation_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_relation_FuncColSpec").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::extend_Relation_1___Window_1__AggColSpecArray_1__Relation_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_relation__Window").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_relation_AggColSpecArray").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::extend_Relation_1___Window_1__AggColSpec_1__Relation_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_relation__Window").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_relation_AggColSpec").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::extend_Relation_1___Window_1__FuncColSpecArray_1__Relation_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_relation__Window").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_relation_FuncColSpecArray").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::extend_Relation_1___Window_1__FuncColSpec_1__Relation_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_relation__Window").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_relation_FuncColSpec").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::filter_Relation_1__Function_1__Relation_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && ("Nil".equals(ps.get(1)._genericType()._rawType()._name()) || check(funcType(ps.get(1)._genericType()), (FunctionType ft) -> isOne(ft._returnMultiplicity()) && taxoMap.get("cov_Boolean").contains(ft._returnType()._rawType()._name()) && check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.size() == 1 && isOne(nps.get(0)._multiplicity())))));
        map.put("meta::pure::functions::relation::first_Relation_1___Window_1__T_1__T_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_relation__Window").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()));
        map.put("meta::pure::functions::relation::groupBy_Relation_1__ColSpecArray_1__AggColSpecArray_1__Relation_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_relation_ColSpecArray").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_relation_AggColSpecArray").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::groupBy_Relation_1__ColSpecArray_1__AggColSpec_1__Relation_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_relation_ColSpecArray").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_relation_AggColSpec").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::groupBy_Relation_1__ColSpec_1__AggColSpecArray_1__Relation_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_relation_ColSpec").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_relation_AggColSpecArray").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::groupBy_Relation_1__ColSpec_1__AggColSpec_1__Relation_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_relation_ColSpec").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_relation_AggColSpec").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::aggregate_Relation_1__AggColSpec_1__Relation_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_relation_AggColSpec").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::aggregate_Relation_1__AggColSpecArray_1__Relation_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_relation_AggColSpecArray").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::join_Relation_1__Relation_1__JoinKind_1__Function_1__Relation_1_", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_relation_JoinKind").contains(ps.get(2)._genericType()._rawType()._name()) && isOne(ps.get(3)._multiplicity()) && ("Nil".equals(ps.get(3)._genericType()._rawType()._name()) || check(funcType(ps.get(3)._genericType()), (FunctionType ft) -> isOne(ft._returnMultiplicity()) && taxoMap.get("cov_Boolean").contains(ft._returnType()._rawType()._name()) && check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.size() == 2 && isOne(nps.get(0)._multiplicity()) && isOne(nps.get(1)._multiplicity())))));
        map.put("meta::pure::functions::relation::lag_Relation_1__T_1__Integer_1__T_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::lag_Relation_1__T_1__T_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()));
        map.put("meta::pure::functions::relation::last_Relation_1___Window_1__T_1__T_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_relation__Window").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()));
        map.put("meta::pure::functions::relation::lead_Relation_1__T_1__Integer_1__T_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::lead_Relation_1__T_1__T_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()));
        map.put("meta::pure::functions::relation::limit_Relation_1__Integer_1__Relation_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::nth_Relation_1___Window_1__T_1__Integer_1__T_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_relation__Window").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && isOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::ntile_Relation_1__T_1__Integer_1__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::over_ColSpecArray_1__SortInfo_MANY___Window_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_ColSpecArray").contains(ps.get(0)._genericType()._rawType()._name()) && taxoMap.get("cov_relation_SortInfo").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::over_ColSpecArray_1___Window_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_ColSpecArray").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::over_ColSpecArray_1__Rows_1___Window_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_ColSpecArray").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_relation_Frame").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::over_ColSpec_1__Rows_1___Window_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_ColSpec").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_relation_Frame").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::over_ColSpec_1__SortInfo_MANY___Window_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_ColSpec").contains(ps.get(0)._genericType()._rawType()._name()) && taxoMap.get("cov_relation_SortInfo").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::over_SortInfo_1___Range_1___Window_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && taxoMap.get("cov_relation_SortInfo").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::over_ColSpec_1___Window_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_ColSpec").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::over_SortInfo_MANY___Window_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_relation_SortInfo").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::over_ColSpecArray_1__SortInfo_MANY__Rows_1___Window_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && taxoMap.get("cov_relation_ColSpecArray").contains(ps.get(0)._genericType()._rawType()._name()) && taxoMap.get("cov_relation_SortInfo").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_relation_Frame").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::over_ColSpec_1__SortInfo_MANY__Rows_1___Window_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && taxoMap.get("cov_relation_ColSpec").contains(ps.get(0)._genericType()._rawType()._name()) && taxoMap.get("cov_relation_SortInfo").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_relation_Frame").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::over_ColSpecArray_1__SortInfo_1___Range_1___Window_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && taxoMap.get("cov_relation_ColSpecArray").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_relation_SortInfo").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_relation_Frame").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::over_ColSpec_1__SortInfo_1___Range_1___Window_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && taxoMap.get("cov_relation_ColSpec").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_relation_SortInfo").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_relation_Frame").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::over_String_MANY__SortInfo_MANY__Frame_$0_1$___Window_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && taxoMap.get("cov_relation_SortInfo").contains(ps.get(1)._genericType()._rawType()._name()) && matchZeroOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_relation_Frame").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::percentRank_Relation_1___Window_1__T_1__Float_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_relation__Window").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()));
        map.put("meta::pure::functions::relation::pivot_Relation_1__ColSpecArray_1__AggColSpecArray_1__Relation_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_relation_ColSpecArray").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_relation_AggColSpecArray").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::pivot_Relation_1__ColSpecArray_1__AggColSpec_1__Relation_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_relation_ColSpecArray").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_relation_AggColSpec").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::pivot_Relation_1__ColSpec_1__AggColSpecArray_1__Relation_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_relation_ColSpec").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_relation_AggColSpecArray").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::pivot_Relation_1__ColSpec_1__AggColSpec_1__Relation_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_relation_ColSpec").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_relation_AggColSpec").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::project_C_MANY__FuncColSpecArray_1__Relation_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_relation_FuncColSpecArray").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::project_Relation_1__FuncColSpecArray_1__Relation_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_relation_FuncColSpecArray").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::rank_Relation_1___Window_1__T_1__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_relation__Window").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()));
        map.put("meta::pure::functions::relation::rename_Relation_1__ColSpec_1__ColSpec_1__Relation_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_relation_ColSpec").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_relation_ColSpec").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::rowNumber_Relation_1__T_1__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()));
        map.put("meta::pure::functions::relation::select_Relation_1__ColSpecArray_1__Relation_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_relation_ColSpecArray").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::select_Relation_1__ColSpec_1__Relation_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_relation_ColSpec").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::select_Relation_1__Relation_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::eval_ColSpec_1__T_1__Z_MANY_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_ColSpec").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()));
        map.put("meta::pure::functions::relation::size_Relation_1__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::slice_Relation_1__Integer_1__Integer_1__Relation_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::relation::sort_Relation_1__SortInfo_MANY__Relation_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Relation").contains(ps.get(0)._genericType()._rawType()._name()) && taxoMap.get("cov_relation_SortInfo").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::ascii_String_1__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::char_Integer_1__String_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::chunk_String_1__Integer_1__String_MANY_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::contains_String_$0_1$__String_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::contains_String_1__String_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::decodeBase64_String_1__String_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::decodeUrl_String_1__String_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::decodeUrl_String_1__String_1__String_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::encodeBase64_String_1__String_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::encodeUrl_String_1__String_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::encodeUrl_String_1__String_1__String_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::endsWith_String_$0_1$__String_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::endsWith_String_1__String_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::equalIgnoreCase_String_1__String_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::format_String_1__Any_MANY__String_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::humanize_String_1__String_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::indexOf_String_1__String_1__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::indexOf_String_1__String_1__Integer_1__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::isAlphaNumeric_String_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::isDigit_String_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::isDigit_String_1__Integer_1__Integer_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::isLetter_String_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::isLetter_String_1__Integer_1__Integer_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::isLowerCase_String_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::isNoLongerThan_String_$0_1$__Integer_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::isNoShorterThan_String_$0_1$__Integer_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::isUUID_String_$0_1$__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::isUpperCase_String_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::jaroWinklerSimilarity_String_1__String_1__Float_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::joinStrings_String_MANY__String_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::joinStrings_String_MANY__String_1__String_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::joinStrings_String_MANY__String_1__String_1__String_1__String_1_", (List<ValueSpecification> ps) -> ps.size() == 4 && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(2)._genericType()._rawType()._name()) && isOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::lastIndexOf_String_1__String_1__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::left_String_1__Integer_1__String_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::length_String_1__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::levenshteinDistance_String_1__String_1__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::lpad_String_1__Integer_1__String_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::lpad_String_1__Integer_1__String_1__String_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::ltrim_String_1__String_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::makeCamelCase_String_1__Boolean_1__String_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Boolean").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::makeCamelCase_String_1__String_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::makeString_Any_MANY__String_1_", (List<ValueSpecification> ps) -> ps.size() == 1);
        map.put("meta::pure::functions::string::makeString_Any_MANY__String_1__String_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::makeString_Any_MANY__String_1__String_1__String_1__String_1_", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(2)._genericType()._rawType()._name()) && isOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::makeString_Pair_MANY__String_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_collection_Pair").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::matches_String_1__String_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::parseBoolean_String_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::parseDate_String_1__Date_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::parseDecimal_String_1__Decimal_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::parseFloat_String_1__Float_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::parseInteger_String_1__Integer_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::plus_String_MANY__String_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::repeatString_String_$0_1$__Integer_1__String_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::replace_String_1__String_1__String_1__String_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::reverseString_String_1__String_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::right_String_1__Integer_1__String_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::rpad_String_1__Integer_1__String_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::rpad_String_1__Integer_1__String_1__String_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::rtrim_String_1__String_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::splitOnCamelCase_String_1__String_MANY_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::splitPart_String_$0_1$__String_1__Integer_1__String_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 3 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::split_String_1__String_1__String_MANY_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::startsWith_String_$0_1$__String_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && matchZeroOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::startsWith_String_1__String_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::substringAfter_String_1__String_1__String_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::substringBefore_String_1__String_1__String_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::substring_String_1__Integer_1__Integer_1__String_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::substring_String_1__Integer_1__String_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::toLower_String_1__String_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::toLowerFirstCharacter_String_1__String_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::toString_Any_1__String_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()));
        map.put("meta::pure::functions::string::toUpper_String_1__String_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::toUpperFirstCharacter_String_1__String_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::functions::string::trim_String_1__String_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::graphFetch::calculateSourceTree_RootGraphFetchTree_1__Mapping_1__Extension_MANY__RootGraphFetchTree_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_graphFetch_RootGraphFetchTree").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_mapping_Mapping").contains(ps.get(1)._genericType()._rawType()._name()) && taxoMap.get("cov_extension_Extension").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::graphFetch::execution::graphFetchChecked_T_MANY__RootGraphFetchTree_1__Checked_MANY_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_graphFetch_RootGraphFetchTree").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::graphFetch::execution::graphFetch_T_MANY__RootGraphFetchTree_1__Integer_1__T_MANY_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_graphFetch_RootGraphFetchTree").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::graphFetch::execution::graphFetch_T_MANY__RootGraphFetchTree_1__T_MANY_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_graphFetch_RootGraphFetchTree").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::graphFetch::execution::serialize_Checked_MANY__RootGraphFetchTree_1__AlloySerializationConfig_1__String_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && taxoMap.get("cov_dataQuality_Checked").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_graphFetch_RootGraphFetchTree").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_execution_AlloySerializationConfig").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::graphFetch::execution::serialize_Checked_MANY__RootGraphFetchTree_1__String_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && taxoMap.get("cov_dataQuality_Checked").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_graphFetch_RootGraphFetchTree").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::graphFetch::execution::serialize_T_MANY__RootGraphFetchTree_1__AlloySerializationConfig_1__String_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_graphFetch_RootGraphFetchTree").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_execution_AlloySerializationConfig").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::graphFetch::execution::serialize_T_MANY__RootGraphFetchTree_1__String_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_graphFetch_RootGraphFetchTree").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::mapping::from_T_m__Mapping_1__PackageableRuntime_1__T_m_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_mapping_Mapping").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_runtime_PackageableRuntime").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::mapping::from_T_m__Mapping_1__Runtime_1__T_m_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_mapping_Mapping").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_runtime_Runtime").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::mapping::from_T_m__PackageableRuntime_1__T_m_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_runtime_PackageableRuntime").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::mapping::from_T_m__Runtime_1__T_m_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_runtime_Runtime").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::mapping::from_TabularDataSet_1__Mapping_1__PackageableRuntime_1__ExecutionContext_1__TabularDataSet_1_", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_tds_TabularDataSet").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_mapping_Mapping").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_runtime_PackageableRuntime").contains(ps.get(2)._genericType()._rawType()._name()) && isOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_runtime_ExecutionContext").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::mapping::from_TabularDataSet_1__Mapping_1__PackageableRuntime_1__TabularDataSet_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_tds_TabularDataSet").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_mapping_Mapping").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_runtime_PackageableRuntime").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::mapping::from_TabularDataSet_1__Mapping_1__Runtime_1__ExecutionContext_1__TabularDataSet_1_", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_tds_TabularDataSet").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_mapping_Mapping").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_runtime_Runtime").contains(ps.get(2)._genericType()._rawType()._name()) && isOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_runtime_ExecutionContext").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::mapping::from_TabularDataSet_1__Mapping_1__Runtime_1__TabularDataSet_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_tds_TabularDataSet").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_mapping_Mapping").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_runtime_Runtime").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::mapping::with_T_m__Mapping_1__PackageableRuntime_1__T_m_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_mapping_Mapping").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_runtime_PackageableRuntime").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::mapping::with_T_m__Mapping_1__Runtime_1__T_m_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_mapping_Mapping").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_runtime_Runtime").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::mapping::with_T_m__PackageableRuntime_1__T_m_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_runtime_PackageableRuntime").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::mapping::with_T_m__Runtime_1__T_m_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_runtime_Runtime").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::tds::agg_String_1__FunctionDefinition_1__FunctionDefinition_1__AggregateValue_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_function_FunctionDefinition").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_function_FunctionDefinition").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::tds::asc_String_1__SortInformation_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::tds::col_Function_1__String_1__BasicColumnSpecification_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && ("Nil".equals(ps.get(0)._genericType()._rawType()._name()) || check(funcType(ps.get(0)._genericType()), (FunctionType ft) -> check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.size() == 1 && isOne(nps.get(0)._multiplicity())))) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::tds::col_Function_1__String_1__String_1__BasicColumnSpecification_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && ("Nil".equals(ps.get(0)._genericType()._rawType()._name()) || check(funcType(ps.get(0)._genericType()), (FunctionType ft) -> check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.size() == 1 && isOne(nps.get(0)._multiplicity())))) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::tds::concatenate_TabularDataSet_1__TabularDataSet_1__TabularDataSet_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_tds_TabularDataSet").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_tds_TabularDataSet").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::tds::desc_String_1__SortInformation_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::tds::distinct_TabularDataSet_1__TabularDataSet_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_tds_TabularDataSet").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::tds::drop_TabularDataSet_1__Integer_1__TabularDataSet_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_tds_TabularDataSet").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::tds::extend_TabularDataSet_1__BasicColumnSpecification_MANY__TabularDataSet_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_tds_TabularDataSet").contains(ps.get(0)._genericType()._rawType()._name()) && taxoMap.get("cov_tds_BasicColumnSpecification").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::tds::extensions::firstNotNull_T_MANY__T_$0_1$_", (List<ValueSpecification> ps) -> ps.size() == 1);
        map.put("meta::pure::tds::filter_TabularDataSet_1__Function_1__TabularDataSet_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_tds_TabularDataSet").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && ("Nil".equals(ps.get(1)._genericType()._rawType()._name()) || check(funcType(ps.get(1)._genericType()), (FunctionType ft) -> isOne(ft._returnMultiplicity()) && taxoMap.get("cov_Boolean").contains(ft._returnType()._rawType()._name()) && check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.size() == 1 && isOne(nps.get(0)._multiplicity()) && taxoMap.get("contra_tds_TDSRow").contains(nps.get(0)._genericType()._rawType()._name())))));
        map.put("meta::pure::tds::func_FunctionDefinition_1__TdsOlapRank_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_function_FunctionDefinition").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::tds::func_String_1__FunctionDefinition_1__TdsOlapAggregation_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_function_FunctionDefinition").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::tds::groupByWithWindowSubset_K_MANY__Function_MANY__AggregateValue_MANY__String_MANY__String_MANY__String_MANY__TabularDataSet_1_", (List<ValueSpecification> ps) -> ps.size() == 6 && ("Nil".equals(ps.get(1)._genericType()._rawType()._name()) || check(funcType(ps.get(1)._genericType()), (FunctionType ft) -> check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.size() == 1 && isOne(nps.get(0)._multiplicity())))) && taxoMap.get("cov_collection_AggregateValue").contains(ps.get(2)._genericType()._rawType()._name()) && taxoMap.get("cov_String").contains(ps.get(3)._genericType()._rawType()._name()) && taxoMap.get("cov_String").contains(ps.get(4)._genericType()._rawType()._name()) && taxoMap.get("cov_String").contains(ps.get(5)._genericType()._rawType()._name()));
        map.put("meta::pure::tds::groupBy_K_MANY__Function_MANY__AggregateValue_MANY__String_MANY__TabularDataSet_1_", (List<ValueSpecification> ps) -> ps.size() == 4 && ("Nil".equals(ps.get(1)._genericType()._rawType()._name()) || check(funcType(ps.get(1)._genericType()), (FunctionType ft) -> check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.size() == 1 && isOne(nps.get(0)._multiplicity())))) && taxoMap.get("cov_collection_AggregateValue").contains(ps.get(2)._genericType()._rawType()._name()) && taxoMap.get("cov_String").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::tds::groupBy_TabularDataSet_1__String_MANY__AggregateValue_MANY__TabularDataSet_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_tds_TabularDataSet").contains(ps.get(0)._genericType()._rawType()._name()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && taxoMap.get("cov_tds_AggregateValue").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::tds::join_TabularDataSet_1__TabularDataSet_1__JoinType_1__Function_1__TabularDataSet_1_", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_tds_TabularDataSet").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_tds_TabularDataSet").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_join_JoinType").contains(ps.get(2)._genericType()._rawType()._name()) && isOne(ps.get(3)._multiplicity()) && ("Nil".equals(ps.get(3)._genericType()._rawType()._name()) || check(funcType(ps.get(3)._genericType()), (FunctionType ft) -> isOne(ft._returnMultiplicity()) && taxoMap.get("cov_Boolean").contains(ft._returnType()._rawType()._name()) && check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.size() == 2 && isOne(nps.get(0)._multiplicity()) && taxoMap.get("contra_tds_TDSRow").contains(nps.get(0)._genericType()._rawType()._name()) && isOne(nps.get(1)._multiplicity()) && taxoMap.get("contra_tds_TDSRow").contains(nps.get(1)._genericType()._rawType()._name())))));
        map.put("meta::pure::tds::join_TabularDataSet_1__TabularDataSet_1__JoinType_1__String_$1_MANY$__String_$1_MANY$__TabularDataSet_1_", (List<ValueSpecification> ps) -> ps.size() == 5 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_tds_TabularDataSet").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_tds_TabularDataSet").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_join_JoinType").contains(ps.get(2)._genericType()._rawType()._name()) && matchOneMany(ps.get(3)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(3)._genericType()._rawType()._name()) && matchOneMany(ps.get(4)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(4)._genericType()._rawType()._name()));
        map.put("meta::pure::tds::join_TabularDataSet_1__TabularDataSet_1__JoinType_1__String_$1_MANY$__TabularDataSet_1_", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_tds_TabularDataSet").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_tds_TabularDataSet").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_join_JoinType").contains(ps.get(2)._genericType()._rawType()._name()) && matchOneMany(ps.get(3)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::tds::limit_TabularDataSet_1__Integer_$0_1$__TabularDataSet_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_tds_TabularDataSet").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::tds::limit_TabularDataSet_1__Integer_1__TabularDataSet_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_tds_TabularDataSet").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::tds::olapGroupBy_TabularDataSet_1__FunctionDefinition_1__String_1__TabularDataSet_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_tds_TabularDataSet").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_function_FunctionDefinition").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::tds::olapGroupBy_TabularDataSet_1__OlapOperation_1__String_1__TabularDataSet_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_tds_TabularDataSet").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_tds_OlapOperation").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::tds::olapGroupBy_TabularDataSet_1__SortInformation_$0_1$__FunctionDefinition_1__String_1__TabularDataSet_1_", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_tds_TabularDataSet").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_tds_SortInformation").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_function_FunctionDefinition").contains(ps.get(2)._genericType()._rawType()._name()) && isOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::tds::olapGroupBy_TabularDataSet_1__SortInformation_$0_1$__OlapOperation_1__String_1__TabularDataSet_1_", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_tds_TabularDataSet").contains(ps.get(0)._genericType()._rawType()._name()) && matchZeroOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_tds_SortInformation").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_tds_OlapOperation").contains(ps.get(2)._genericType()._rawType()._name()) && isOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::tds::olapGroupBy_TabularDataSet_1__String_MANY__FunctionDefinition_1__String_1__TabularDataSet_1_", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_tds_TabularDataSet").contains(ps.get(0)._genericType()._rawType()._name()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_function_FunctionDefinition").contains(ps.get(2)._genericType()._rawType()._name()) && isOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::tds::olapGroupBy_TabularDataSet_1__String_MANY__OlapOperation_1__String_1__TabularDataSet_1_", (List<ValueSpecification> ps) -> ps.size() == 4 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_tds_TabularDataSet").contains(ps.get(0)._genericType()._rawType()._name()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_tds_OlapOperation").contains(ps.get(2)._genericType()._rawType()._name()) && isOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::tds::olapGroupBy_TabularDataSet_1__String_MANY__SortInformation_$0_1$__FunctionDefinition_1__String_1__TabularDataSet_1_", (List<ValueSpecification> ps) -> ps.size() == 5 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_tds_TabularDataSet").contains(ps.get(0)._genericType()._rawType()._name()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && matchZeroOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_tds_SortInformation").contains(ps.get(2)._genericType()._rawType()._name()) && isOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_function_FunctionDefinition").contains(ps.get(3)._genericType()._rawType()._name()) && isOne(ps.get(4)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(4)._genericType()._rawType()._name()));
        map.put("meta::pure::tds::olapGroupBy_TabularDataSet_1__String_MANY__SortInformation_$0_1$__OlapOperation_1__String_1__TabularDataSet_1_", (List<ValueSpecification> ps) -> ps.size() == 5 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_tds_TabularDataSet").contains(ps.get(0)._genericType()._rawType()._name()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && matchZeroOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_tds_SortInformation").contains(ps.get(2)._genericType()._rawType()._name()) && isOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_tds_OlapOperation").contains(ps.get(3)._genericType()._rawType()._name()) && isOne(ps.get(4)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(4)._genericType()._rawType()._name()));
        map.put("meta::pure::tds::paginated_TabularDataSet_1__Integer_1__Integer_1__TabularDataSet_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_tds_TabularDataSet").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::tds::projectWithColumnSubset_T_MANY__ColumnSpecification_MANY__String_MANY__TabularDataSet_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && taxoMap.get("cov_tds_ColumnSpecification").contains(ps.get(1)._genericType()._rawType()._name()) && taxoMap.get("cov_String").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::tds::projectWithColumnSubset_T_MANY__Function_MANY__String_MANY__String_MANY__TabularDataSet_1_", (List<ValueSpecification> ps) -> ps.size() == 4 && ("Nil".equals(ps.get(1)._genericType()._rawType()._name()) || check(funcType(ps.get(1)._genericType()), (FunctionType ft) -> check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.size() == 1 && isOne(nps.get(0)._multiplicity())))) && taxoMap.get("cov_String").contains(ps.get(2)._genericType()._rawType()._name()) && taxoMap.get("cov_String").contains(ps.get(3)._genericType()._rawType()._name()));
        map.put("meta::pure::tds::project_K_MANY__Function_MANY__String_MANY__TabularDataSet_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && ("Nil".equals(ps.get(1)._genericType()._rawType()._name()) || check(funcType(ps.get(1)._genericType()), (FunctionType ft) -> check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.size() == 1 && isOne(nps.get(0)._multiplicity())))) && taxoMap.get("cov_String").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::tds::project_T_MANY__ColumnSpecification_MANY__TabularDataSet_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && taxoMap.get("cov_tds_ColumnSpecification").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::tds::project_T_MANY__Path_MANY__TabularDataSet_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && taxoMap.get("cov_path_Path").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::tds::project_TableTDS_1__ColumnSpecification_MANY__TabularDataSet_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_mapping_TableTDS").contains(ps.get(0)._genericType()._rawType()._name()) && taxoMap.get("cov_tds_ColumnSpecification").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::tds::project_TabularDataSet_1__ColumnSpecification_MANY__TabularDataSet_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_tds_TabularDataSet").contains(ps.get(0)._genericType()._rawType()._name()) && taxoMap.get("cov_tds_ColumnSpecification").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::tds::renameColumns_TabularDataSet_1__Pair_MANY__TabularDataSet_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_tds_TabularDataSet").contains(ps.get(0)._genericType()._rawType()._name()) && taxoMap.get("cov_collection_Pair").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::tds::restrictDistinct_TabularDataSet_1__String_MANY__TabularDataSet_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_tds_TabularDataSet").contains(ps.get(0)._genericType()._rawType()._name()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::tds::restrict_TabularDataSet_1__String_MANY__TabularDataSet_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_tds_TabularDataSet").contains(ps.get(0)._genericType()._rawType()._name()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::tds::slice_TabularDataSet_1__Integer_1__Integer_1__TabularDataSet_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_tds_TabularDataSet").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::tds::sort_TabularDataSet_1__SortInformation_MANY__TabularDataSet_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_tds_TabularDataSet").contains(ps.get(0)._genericType()._rawType()._name()) && taxoMap.get("cov_tds_SortInformation").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::tds::sort_TabularDataSet_1__String_1__SortDirection_1__TabularDataSet_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_tds_TabularDataSet").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_tds_SortDirection").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::tds::sort_TabularDataSet_1__String_MANY__TabularDataSet_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_tds_TabularDataSet").contains(ps.get(0)._genericType()._rawType()._name()) && taxoMap.get("cov_String").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::tds::tableToTDS_Table_1__TableTDS_1_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_relation_Table").contains(ps.get(0)._genericType()._rawType()._name()));
        map.put("meta::pure::tds::take_TabularDataSet_1__Integer_1__TabularDataSet_1_", (List<ValueSpecification> ps) -> ps.size() == 2 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_tds_TabularDataSet").contains(ps.get(0)._genericType()._rawType()._name()) && isOne(ps.get(1)._multiplicity()) && taxoMap.get("cov_Integer").contains(ps.get(1)._genericType()._rawType()._name()));
        map.put("meta::pure::tds::tdsContains_T_1__Function_MANY__String_MANY__TabularDataSet_1__Function_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 5 && isOne(ps.get(0)._multiplicity()) && ("Nil".equals(ps.get(1)._genericType()._rawType()._name()) || check(funcType(ps.get(1)._genericType()), (FunctionType ft) -> matchZeroOne(ft._returnMultiplicity()) && check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.size() == 1 && isOne(nps.get(0)._multiplicity())))) && taxoMap.get("cov_String").contains(ps.get(2)._genericType()._rawType()._name()) && isOne(ps.get(3)._multiplicity()) && taxoMap.get("cov_tds_TabularDataSet").contains(ps.get(3)._genericType()._rawType()._name()) && isOne(ps.get(4)._multiplicity()) && ("Nil".equals(ps.get(4)._genericType()._rawType()._name()) || check(funcType(ps.get(4)._genericType()), (FunctionType ft) -> isOne(ft._returnMultiplicity()) && taxoMap.get("cov_Boolean").contains(ft._returnType()._rawType()._name()) && check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.size() == 2 && isOne(nps.get(0)._multiplicity()) && taxoMap.get("contra_tds_TDSRow").contains(nps.get(0)._genericType()._rawType()._name()) && isOne(nps.get(1)._multiplicity()) && taxoMap.get("contra_tds_TDSRow").contains(nps.get(1)._genericType()._rawType()._name())))));
        map.put("meta::pure::tds::tdsContains_T_1__Function_MANY__TabularDataSet_1__Boolean_1_", (List<ValueSpecification> ps) -> ps.size() == 3 && isOne(ps.get(0)._multiplicity()) && ("Nil".equals(ps.get(1)._genericType()._rawType()._name()) || check(funcType(ps.get(1)._genericType()), (FunctionType ft) -> matchZeroOne(ft._returnMultiplicity()) && check(ft._parameters().toList(), (List<? extends VariableExpression> nps) -> nps.size() == 1 && isOne(nps.get(0)._multiplicity())))) && isOne(ps.get(2)._multiplicity()) && taxoMap.get("cov_tds_TabularDataSet").contains(ps.get(2)._genericType()._rawType()._name()));
        map.put("meta::pure::tds::tdsRows_TabularDataSet_1__TDSRow_MANY_", (List<ValueSpecification> ps) -> ps.size() == 1 && isOne(ps.get(0)._multiplicity()) && taxoMap.get("cov_tds_TabularDataSet").contains(ps.get(0)._genericType()._rawType()._name()));
        ListIterate.flatCollect(context.getCompilerExtensions().getExtraFunctionHandlerDispatchBuilderInfoCollectors(), collector -> collector.valueOf(this)).forEach(info -> map.put(info.functionName, info.dispatch));
        return map;
    }

// ------------------------------------------------------------------------------------------------
// Please do not update the following code manually! Please check with the team when introducing
// new matchers as this might be complicated by modularization
// THIS CODE IS GENERATED BY A FUNCTION IN PURE - CONTACT THE CORE TEAM FOR MORE DETAILS
//-------------------------------------------------------------------------------------------------

}
