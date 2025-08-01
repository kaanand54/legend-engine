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

package org.finos.legend.engine.protocol.pure.v1.model.packageableElement.mapping.relationFunction;

import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.externalFormat.BindingTransformer;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.mapping.PropertyMapping;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.mapping.PropertyMappingVisitor;

public class RelationFunctionPropertyMapping extends PropertyMapping
{
    public String column;
    public BindingTransformer bindingTransformer;
    
    @Override
    public <T> T accept(PropertyMappingVisitor<T> visitor)
    {
        return visitor.visit(this);
    }
}
