/*
 * Copyright 2014 Blazebit.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.blazebit.persistence.view.impl.metamodel.attribute;

import java.lang.annotation.Annotation;
import java.util.Set;

import com.blazebit.persistence.view.impl.metamodel.AbstractParameterMappingPluralAttribute;
import com.blazebit.persistence.view.impl.metamodel.MetamodelUtils;
import com.blazebit.persistence.view.metamodel.MappingConstructor;
import com.blazebit.persistence.view.metamodel.SetAttribute;

/**
 *
 * @author Christian Beikov
 * @since 1.2.0
 */
public class DefaultParameterMappingSetAttribute<X, Y> extends AbstractParameterMappingPluralAttribute<X, Set<Y>, Y> implements SetAttribute<X, Y> {

    public DefaultParameterMappingSetAttribute(MappingConstructor<X> mappingConstructor, int index, Annotation mapping, Set<Class<?>> entityViews) {
        super(mappingConstructor, index, mapping, entityViews, MetamodelUtils.isSorted(mappingConstructor, index));
    }

    @Override
    public CollectionType getCollectionType() {
        return CollectionType.SET;
    }

    @Override
    public boolean isIndexed() {
        return false;
    }

    @Override
    public boolean isCorrelated() {
        return false;
    }
}
