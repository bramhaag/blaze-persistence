/*
 * Copyright 2014 - 2023 Blazebit.
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

package com.blazebit.persistence.parser.expression.modifier;

import com.blazebit.persistence.parser.expression.Expression;

/**
 *
 * @author Moritz Becker
 * @author Christian Beikov
 * @since 1.2.0
 */
public abstract class AbstractExpressionModifier<SELF extends AbstractExpressionModifier<SELF, T>, T extends Expression> implements ExpressionModifier {

    protected final T target;

    public AbstractExpressionModifier(T target) {
        this.target = target;
    }

    public T getTarget() {
        return target;
    }

}
