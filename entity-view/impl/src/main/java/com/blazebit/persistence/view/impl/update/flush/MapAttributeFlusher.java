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

package com.blazebit.persistence.view.impl.update.flush;

import com.blazebit.persistence.DeleteCriteriaBuilder;
import com.blazebit.persistence.InsertCriteriaBuilder;
import com.blazebit.persistence.SubqueryBuilder;
import com.blazebit.persistence.UpdateCriteriaBuilder;
import com.blazebit.persistence.view.FlushStrategy;
import com.blazebit.persistence.view.impl.EntityViewManagerImpl;
import com.blazebit.persistence.view.impl.accessor.AttributeAccessor;
import com.blazebit.persistence.view.impl.accessor.InitialValueAttributeAccessor;
import com.blazebit.persistence.view.impl.change.DirtyChecker;
import com.blazebit.persistence.view.impl.change.MapDirtyChecker;
import com.blazebit.persistence.view.impl.collection.CollectionRemoveListener;
import com.blazebit.persistence.view.impl.collection.MapAction;
import com.blazebit.persistence.view.impl.collection.MapClearAction;
import com.blazebit.persistence.view.impl.collection.MapInstantiatorImplementor;
import com.blazebit.persistence.view.impl.collection.MapPutAction;
import com.blazebit.persistence.view.impl.collection.MapPutAllAction;
import com.blazebit.persistence.view.impl.collection.MapRemoveAction;
import com.blazebit.persistence.view.impl.collection.RecordingCollection;
import com.blazebit.persistence.view.impl.collection.RecordingMap;
import com.blazebit.persistence.view.impl.entity.MapViewToEntityMapper;
import com.blazebit.persistence.view.impl.entity.ViewToEntityMapper;
import com.blazebit.persistence.view.spi.type.BasicDirtyTracker;
import com.blazebit.persistence.view.spi.type.DirtyStateTrackable;
import com.blazebit.persistence.view.spi.type.MutableStateTrackable;
import com.blazebit.persistence.view.impl.update.EntityViewUpdater;
import com.blazebit.persistence.view.impl.update.UpdateContext;
import com.blazebit.persistence.view.impl.update.UpdateQueryFactory;
import com.blazebit.persistence.view.spi.type.BasicUserType;
import com.blazebit.persistence.view.spi.type.EntityViewProxy;

import javax.persistence.EntityManager;
import javax.persistence.Query;
import javax.persistence.Tuple;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 *
 * @author Christian Beikov
 * @since 1.2.0
 */
public class MapAttributeFlusher<E, V extends Map<?, ?>> extends AbstractPluralAttributeFlusher<MapAttributeFlusher<E, V>, MapAction<?>, RecordingMap<?, ?, ?>, E, V> implements DirtyAttributeFlusher<MapAttributeFlusher<E, V>, E, V>, MapDirtyChecker<V, Object, E> {

    private static final Map.Entry<Object, Object> REMOVED_MARKER = new AbstractMap.SimpleEntry<>(null, null);

    private final MapInstantiatorImplementor<?, ?> mapInstantiator;
    private final MapViewToEntityMapper mapper;
    private final MapViewToEntityMapper loadOnlyMapper;
    private final CollectionRemoveListener keyCascadeDeleteListener;
    private final CollectionRemoveListener keyRemoveListener;
    private final TypeDescriptor keyDescriptor;
    private final BasicDirtyChecker<Object> keyDirtyChecker;
    private final int embeddableParentCount;
    private final boolean upsert;

    @SuppressWarnings("unchecked")
    public MapAttributeFlusher(String attributeName, String mapping, Class<?> ownerEntityClass, String ownerIdAttributeName, String ownerMapping, DirtyAttributeFlusher<?, ?, ?> ownerIdFlusher, DirtyAttributeFlusher<?, ?, ?> elementFlusher, boolean supportsCollectionDml, FlushStrategy flushStrategy, AttributeAccessor attributeMapper, InitialValueAttributeAccessor viewAttributeAccessor,
                               boolean optimisticLockProtected, boolean collectionUpdatable, CollectionRemoveListener keyCascadeDeleteListener, CollectionRemoveListener elementCascadeDeleteListener, CollectionRemoveListener keyRemoveListener, CollectionRemoveListener elementRemoveListener,
                               boolean viewOnlyDeleteCascaded, boolean jpaProviderDeletesCollection, TypeDescriptor keyDescriptor, TypeDescriptor elementDescriptor, MapViewToEntityMapper mapper, MapViewToEntityMapper loadOnlyMapper, MapInstantiatorImplementor<?, ?> mapInstantiator) {
        super(attributeName, mapping, collectionUpdatable || elementDescriptor.isMutable(), ownerEntityClass, ownerIdAttributeName, ownerMapping, ownerIdFlusher, elementFlusher, supportsCollectionDml, flushStrategy, attributeMapper, viewAttributeAccessor, optimisticLockProtected, collectionUpdatable, viewOnlyDeleteCascaded, jpaProviderDeletesCollection, elementCascadeDeleteListener,
                elementRemoveListener, elementDescriptor);
        this.mapInstantiator = mapInstantiator;
        this.keyDescriptor = keyDescriptor;
        if (keyDescriptor.isSubview() || keyDescriptor.isJpaEntity()) {
            this.keyDirtyChecker = null;
        } else {
            this.keyDirtyChecker = new BasicDirtyChecker<>(keyDescriptor);
        }
        this.keyRemoveListener = keyRemoveListener;
        this.keyCascadeDeleteListener = keyCascadeDeleteListener;
        this.mapper = mapper;
        this.loadOnlyMapper = loadOnlyMapper;
        this.embeddableParentCount = countEmbeddableParents(ownerMapping);
        this.upsert = false;
    }

    protected MapAttributeFlusher(MapAttributeFlusher original, boolean fetch) {
        this(original, fetch, false, null, null, null);
    }

    protected MapAttributeFlusher(MapAttributeFlusher original, boolean fetch, boolean upsert, PluralFlushOperation flushOperation, List<? extends MapAction<?>> collectionActions, List<CollectionElementAttributeFlusher<E, V>> elementFlushers) {
        super(original, fetch, flushOperation, collectionActions, elementFlushers);
        this.mapInstantiator = original.mapInstantiator;
        this.keyDescriptor = original.keyDescriptor;
        this.keyDirtyChecker = original.keyDirtyChecker;
        this.keyRemoveListener = original.keyRemoveListener;
        this.keyCascadeDeleteListener = original.keyCascadeDeleteListener;
        this.mapper = original.mapper;
        this.loadOnlyMapper = original.loadOnlyMapper;
        this.embeddableParentCount = original.embeddableParentCount;
        this.upsert = upsert;
    }

    private static int countEmbeddableParents(String ownerMapping) {
        if (ownerMapping == null) {
            return 0;
        }
        int count = 1;
        for (int i = 0; i < ownerMapping.length(); i++) {
            if (ownerMapping.charAt(i) == '.') {
                count++;
            }
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    protected V createMap(int size) {
        return (V) mapInstantiator.createMap(size);
    }

    @SuppressWarnings("unchecked")
    protected V createJpaMap(int size) {
        return (V) mapInstantiator.createJpaMap(size);
    }

    @Override
    protected V createJpaCollection() {
        return (V) mapInstantiator.createJpaMap(0);
    }

    @SuppressWarnings("unchecked")
    protected RecordingMap<?, ?, ?> createRecordingMap(int size) {
        return mapInstantiator.createRecordingMap(size);
    }

    @Override
    public V cloneDeep(Object view, V oldValue, V newValue) {
        if (newValue == null || newValue.isEmpty()) {
            return newValue;
        }
        BasicUserType<Object> keyBasicUserType = keyDescriptor.getBasicUserType();
        BasicUserType<Object> elementBasicUserType = elementDescriptor.getBasicUserType();
        // We only do a collection copy if the key or element type supports deep cloning
        boolean cloneKey = keyBasicUserType != null && keyDescriptor.shouldFlushMutations()  && !keyDescriptor.isSubview()
                && !keyBasicUserType.supportsDirtyChecking() && keyBasicUserType.supportsDeepCloning();
        boolean cloneValue = elementBasicUserType != null && elementDescriptor.shouldFlushMutations() && !elementDescriptor.isSubview()
                && !elementBasicUserType.supportsDirtyChecking() && elementBasicUserType.supportsDeepCloning();
        if (cloneKey || cloneValue) {
            if (cloneKey && cloneValue) {
                V newCollection = createMap(newValue.size());
                Map<Object, Object> collection = (Map<Object, Object>) newCollection;
                for (Map.Entry<?, ?> entry : newValue.entrySet()) {
                    collection.put(keyBasicUserType.deepClone(entry.getKey()), elementBasicUserType.deepClone(entry.getValue()));
                }
                return newCollection;
            } else if (cloneKey) {
                V newCollection = createMap(newValue.size());
                Map<Object, Object> collection = (Map<Object, Object>) newCollection;
                for (Map.Entry<?, ?> entry : newValue.entrySet()) {
                    collection.put(keyBasicUserType.deepClone(entry.getKey()), entry.getValue());
                }
                return newCollection;
            } else {
                V newCollection = createMap(newValue.size());
                Map<Object, Object> collection = (Map<Object, Object>) newCollection;
                for (Map.Entry<?, ?> entry : newValue.entrySet()) {
                    collection.put(entry.getKey(), elementBasicUserType.deepClone(entry.getValue()));
                }
                return newCollection;
            }
        }
        return newValue;
    }

    @Override
    public Object getNewInitialValue(UpdateContext context, V clonedValue, V currentValue) {
        BasicUserType<Object> keyBasicUserType = keyDescriptor.getBasicUserType();
        BasicUserType<Object> elementBasicUserType = elementDescriptor.getBasicUserType();
        // We only do a collection copy if the key or element type supports deep cloning
        boolean cloneKey = keyBasicUserType != null && keyDescriptor.shouldFlushMutations()  && !keyDescriptor.isSubview()
                && !keyBasicUserType.supportsDirtyChecking() && keyBasicUserType.supportsDeepCloning();
        boolean cloneValue = elementBasicUserType != null && elementDescriptor.shouldFlushMutations() && !elementDescriptor.isSubview()
                && !elementBasicUserType.supportsDirtyChecking() && elementBasicUserType.supportsDeepCloning();
        if (cloneKey || cloneValue) {
            return clonedValue;
        } else {
            return currentValue;
        }
    }

    @Override
    public boolean isPassThrough() {
        return !collectionUpdatable && !keyDescriptor.shouldFlushMutations() && !elementDescriptor.shouldFlushMutations();
    }

    @Override
    protected boolean isIndexed() {
        return true;
    }

    @Override
    protected void addFlatViewElementFlushActions(UpdateContext context, TypeDescriptor typeDescriptor, List<MapAction<?>> actions, V current) {
        final ViewToEntityMapper mapper = typeDescriptor.getViewToEntityMapper();
        for (Map.Entry<?, ?> entry : current.entrySet()) {
            Object o = entry.getValue();
            if (o instanceof MutableStateTrackable) {
                MutableStateTrackable element = (MutableStateTrackable) o;
                @SuppressWarnings("unchecked")
                DirtyAttributeFlusher<?, E, V> flusher = (DirtyAttributeFlusher<?, E, V>) (DirtyAttributeFlusher) mapper.getNestedDirtyFlusher(context, element, (DirtyAttributeFlusher) null);
                if (flusher != null) {
                    // At this point, we have to check the collection actions to determine if the element was added through actions somehow
                    // We will register a special MapPutAction if the element was not added through actions to issue an UPDATE statement
                    // By default, since the element is dirty, we start with the state UPDATED and go through state transitions
                    // based on the containment of the element in the added/removed objects collections of the actions
                    EntryState state = EntryState.UPDATED;
                    Object replacedObject = element;
                    for (MapAction<?> action : actions) {
                        Collection<Object> removedElements = action.getRemovedElements();
                        Collection<Object> addedElements = action.getAddedElements();
                        if (removedElements.isEmpty()) {
                            if (identityContains(addedElements, element)) {
                                state = state.onAdd();
                            }
                        } else if (addedElements.isEmpty()) {
                            if (identityContains(removedElements, element)) {
                                state = state.onRemove();
                            }
                        } else {
                            // Here we have a MapPutAction or MapPutAllAction where added/removed have the same cardinality
                            Iterator<Object> addedIter = addedElements.iterator();
                            Iterator<Object> removedIter = removedElements.iterator();
                            while (addedIter.hasNext()) {
                                Object added = addedIter.next();
                                Object removed = removedIter.next();
                                if (removed == element) {
                                    if (added == element) {
                                        // This is a MapPutAction or MapPutAllAction where the old and new object are the same instances
                                        replacedObject = element;
                                        state = EntryState.UPDATED;
                                    } else {
                                        state = state.onRemove();
                                    }
                                    break;
                                } else if (added == element) {
                                    replacedObject = removed;
                                    state = EntryState.UPDATED;
                                    break;
                                }
                            }
                        }
                    }

                    // If the element was UPDATED and the replacedObject is the element itself,
                    // this means that this really was just a mutation of the view
                    // and there is no action that would flush the object changes already
                    if (state == EntryState.UPDATED && replacedObject == element) {
                        actions.add(new MapPutAction<>(entry.getKey(), element, element));
                    }
                }
            }
        }
    }

    @Override
    protected void invokeCollectionAction(UpdateContext context, Object ownerView, Object view, V targetCollection, Object value, List<? extends MapAction<?>> collectionActions) {
        if (mapping == null) {
            // When the target collection is null this means that there is no collection role in the entity
            // This happens for correlated attributes and we will just provide an empty collection for applying actions
            targetCollection = createMap(0);
            for (MapAction<V> action : (List<MapAction<V>>) (List<?>) collectionActions) {
                action.doAction(targetCollection, context, loadOnlyMapper, keyRemoveListener, removeListener);
            }
        } else {
            if (flushStrategy == FlushStrategy.QUERY && !context.isForceEntity()) {
                FusedMapActions fusedCollectionActions = null;
                // We can't selectively delete/add if duplicates are allowed. Bags always need to be recreated
                if (canFlushSeparateCollectionOperations()) {
                    if (collectionActions.isEmpty()) {
                        return;
                    } else if (!(collectionActions.get(0) instanceof MapClearAction<?, ?, ?>)) {
                        // The replace action is handled specially
                        fusedCollectionActions = getFusedOperations(collectionActions);
                    }
                }
                flushCollectionOperations(context, ownerView, view, null, (V) value, null, fusedCollectionActions, true);
            } else {
                for (MapAction<V> action : (List<MapAction<V>>) (List<?>) collectionActions) {
                    action.doAction(targetCollection, context, loadOnlyMapper, keyRemoveListener, removeListener);
                }
            }
        }
    }

    @Override
    protected V replaceWithRecordingCollection(UpdateContext context, Object view, V value, List<? extends MapAction<?>> actions) {
        Map<?, ?> initialState = (Map<?, ?>) viewAttributeAccessor.getInitialValue(view);
        initialState = initialState != null ? initialState : Collections.emptyMap();
        RecordingMap<Map<?, ?>, ?, ?> map;
        if (value instanceof RecordingMap<?, ?, ?>) {
            map = (RecordingMap<Map<?, ?>, ?, ?>) value;
        } else {
            if (value != null) {
                map = (RecordingMap<Map<?, ?>, ?, ?>) createRecordingMap(value.size());
                ((Map<Object, Object>) map.getDelegate()).putAll(value);
            } else {
                map = (RecordingMap<Map<?, ?>, ?, ?>) createRecordingMap(0);
            }
            viewAttributeAccessor.setValue(view, map);
        }
        if (actions != null && !actions.isEmpty()) {
            map.initiateActionsAgainstState((List<MapAction<Map<?, ?>>>) actions, initialState);
            map.resetActions(context);
        }
        V initialValue = cloneDeep(view, null, (V) map);
        if (initialValue != value) {
            viewAttributeAccessor.setInitialValue(view, initialValue);
        }
        return (V) map;
    }

    @Override
    public Query flushQuery(UpdateContext context, String parameterPrefix, UpdateQueryFactory queryFactory, Query query, Object ownerView, Object view, V current, UnmappedOwnerAwareDeleter ownerAwareDeleter, DirtyAttributeFlusher<?, ?, ?> ownerFlusher) {
        if (!supportsQueryFlush()) {
            throw new UnsupportedOperationException("Query flush not supported for configuration!");
        }

        if (flushOperation != null) {
            if (current instanceof RecordingMap<?, ?, ?>) {
                ((RecordingMap<?, ?, ?>) current).resetActions(context);
            } else {
                List<MapAction<Map<?, ?>>> actions = new ArrayList<>();
                actions.add(new MapClearAction());
                if (current != null && !current.isEmpty()) {
                    actions.add(new MapPutAllAction(current, Collections.emptyMap()));
                }
                current = replaceWithRecordingCollection(context, view, current, actions);
            }
            invokeFlushOperation(context, ownerView, view, null, current);
        } else {
            boolean isRecording = current instanceof RecordingMap<?, ?, ?>;
            if (isRecording) {
                RecordingMap<Map<?, ?>, ?, ?> recordingMap = (RecordingMap<Map<?, ?>, ?, ?>) current;
                if (entityAttributeAccessor == null) {
                    // We have a correlation mapping here
                    recordingMap.resetActions(context);
                }

                Map<Object, Object> embeddables = null;
                if (elementDescriptor.shouldFlushMutations()) {
                    if (elementDescriptor.shouldJpaPersistOrMerge()) {
                        mergeAndRequeue(context, recordingMap, (Map<Object, Object>) recordingMap.getDelegate());
                    } else if (elementDescriptor.isSubview() && (elementDescriptor.isIdentifiable() || isIndexed())) {
                        embeddables = flushCollectionViewElements(context, current);
                    }
                }

                if (entityAttributeAccessor != null && collectionUpdatable) {
                    V initial = (V) viewAttributeAccessor.getInitialValue(view);
                    if (initial instanceof RecordingMap<?, ?, ?>) {
                        initial = (V) ((RecordingMap) initial).getInitialVersion();
                    }
                    recordingMap.resetActions(context);
                    // If the initial object was null like it happens during full flushing, we can only replace the collection
                    flushCollectionOperations(context, ownerView, view, initial, current, embeddables, (FusedMapActions) null, initial != null);
                }
            } else {
                EqualityChecker equalityChecker;
                if (elementDescriptor.isSubview()) {
                    equalityChecker = EqualsEqualityChecker.INSTANCE;
                } else {
                    equalityChecker = new IdentityEqualityChecker(elementDescriptor.getBasicUserType());
                }
                V initial = (V) viewAttributeAccessor.getInitialValue(view);
                if (initial instanceof RecordingMap<?, ?, ?>) {
                    initial = (V) ((RecordingMap) initial).getInitialVersion();
                }
                List<MapAction<Map<Object, Object>>> actions;
                if (initial == null && replaceWithReferenceContents || !elementDescriptor.supportsDeepEqualityCheck() || elementDescriptor.getBasicUserType() != null && !elementDescriptor.getBasicUserType().supportsDeepCloning()) {
                    actions = replaceActions(current);
                } else {
                    actions = determineCollectionActions(context, initial, current, equalityChecker);
                }
                current = replaceWithRecordingCollection(context, view, current, actions);

                Map<Object, Object> embeddables = null;
                if (elementDescriptor.shouldFlushMutations()) {
                    if (elementDescriptor.shouldJpaPersistOrMerge()) {
                        mergeAndRequeue(context, null, (Map<Object, Object>) current);
                    } else if (elementDescriptor.isSubview() && (elementDescriptor.isIdentifiable() || isIndexed())) {
                        embeddables = flushCollectionViewElements(context, current);
                    }
                }

                if (entityAttributeAccessor != null && collectionUpdatable) {
                    // If the initial object was null like it happens during full flushing, we can only replace the collection
                    flushCollectionOperations(context, ownerView, view, initial, current, embeddables, (FusedMapActions) null, initial != null);
                }
            }
        }
        return query;
    }

    protected final DeleteCriteriaBuilder<?> createCollectionDeleter(UpdateContext context) {
        DeleteCriteriaBuilder<?> deleteCb = context.getEntityViewManager().getCriteriaBuilderFactory().deleteCollection(context.getEntityManager(), ownerEntityClass, "e", getMapping());
        deleteCb.setWhereExpression(ownerIdWhereFragment);
        return deleteCb;
    }

    protected Map<Object, Object> appendRemoveSpecific(UpdateContext context, DeleteCriteriaBuilder<?> deleteCb, FusedMapActions fusedCollectionActions) {
        deleteCb.where("KEY(e." + getMapping() + ")").in(fusedCollectionActions.getRemovedKeys(context));
        return fusedCollectionActions.getRemoved();
    }

    protected boolean deleteElements(UpdateContext context, Object ownerView, Object view, V value, boolean removeSpecific, FusedMapActions fusedCollectionActions, boolean deleteAll) {
        DeleteCriteriaBuilder<?> deleteCb = null;
        boolean removedAll = true;
        Map<Object, Object> removedObjects = Collections.emptyMap();
        if (!deleteAll && fusedCollectionActions != null) {
            if (fusedCollectionActions.getRemoveCount() > 0) {
                deleteCb = createCollectionDeleter(context);
                if (removeSpecific) {
                    removedObjects = appendRemoveSpecific(context, deleteCb, fusedCollectionActions);
                    removedAll = false;
                    if (removedObjects.isEmpty()) {
                        deleteCb = null;
                    }
                }
            } else {
                removedAll = false;
            }
        } else {
            deleteCb = createCollectionDeleter(context);
        }

        if (deleteCb != null) {
            Query deleteQuery = deleteCb.getQuery();
            ownerIdFlusher.flushQuery(context, null, null, deleteQuery, ownerView, view, ownerIdFlusher.getViewAttributeAccessor().getValue(ownerView), null, null);
            deleteQuery.executeUpdate();
            if (removedAll) {
                return true;
            }
            processRemovedObjects(context, removedObjects);
        }

        // TODO: Think about allowing to use batching when we implement #657

        return false;
    }

    protected void addElements(UpdateContext context, Object ownerView, Object view, Map<Object, Object> removedAllObjects, boolean flushAtOnce, V value, Map<Object, Object> embeddablesToUpdate, FusedMapActions fusedCollectionActions, boolean initialKnown) {
        Map<Object, Object> appends;
        String mapping = getMapping();
        if (fusedCollectionActions == null || !removedAllObjects.isEmpty()) {
            appends = (Map<Object, Object>) value;
            Iterator<Map.Entry<Object, Object>> iterator = removedAllObjects.entrySet().iterator();
            List<Object> removeWrappers = null;
            while (iterator.hasNext()) {
                Map.Entry<Object, Object> entry = iterator.next();
                Object currentValue = value.get(entry.getKey());
                if (currentValue != null) {
                    if (!currentValue.equals(entry.getValue())) {
                        if (removeWrappers == null) {
                            removeWrappers = new ArrayList<>();
                        }
                        removeWrappers.add(new FusedMapActions.RemoveWrapper(entry.getKey()));
                    }
                    iterator.remove();
                }
            }
            if (removeWrappers != null) {
                for (Object removeWrapper : removeWrappers) {
                    removedAllObjects.put(removeWrapper, null);
                }
            }
        } else {
            Map<Object, Object> replaces = fusedCollectionActions.getReplaces();
            if (replaces.size() != 0 || embeddablesToUpdate != null && !embeddablesToUpdate.isEmpty()) {
                UpdateCriteriaBuilder<?> updateCb = context.getEntityViewManager().getCriteriaBuilderFactory().updateCollection(context.getEntityManager(), ownerEntityClass, "e", mapping);
                updateCb.setExpression(mapping, ":element");
                updateCb.setWhereExpression(ownerIdWhereFragment);
                updateCb.where("KEY(" + mapping + ")").eqExpression(":key");
                Query query = updateCb.getQuery();

                if (replaces.size() != 0) {
                    ownerIdFlusher.flushQuery(context, null, null, query, ownerView, view, ownerIdFlusher.getViewAttributeAccessor().getValue(ownerView), null, null);
                    boolean checkTransient = elementDescriptor.isJpaEntity() && !elementDescriptor.shouldJpaPersist();
                    ViewToEntityMapper keyViewToEntityMapper = keyDescriptor.getLoadOnlyViewToEntityMapper();
                    ViewToEntityMapper valueViewToEntityMapper = elementDescriptor.getLoadOnlyViewToEntityMapper();
                    for (Map.Entry<Object, Object> replace : replaces.entrySet()) {
                        Object k = replace.getKey();
                        Object v = replace.getValue();
                        if (keyViewToEntityMapper == null) {
                            if (checkTransient && keyDescriptor.getBasicUserType().shouldPersist(k)) {
                                throw new IllegalStateException("Collection " + attributeName + " references an unsaved transient instance - save the transient instance before flushing: " + k);
                            }
                        } else {
                            k = keyViewToEntityMapper.applyToEntity(context, null, k);
                        }
                        if (valueViewToEntityMapper == null) {
                            if (checkTransient && elementDescriptor.getBasicUserType().shouldPersist(v)) {
                                throw new IllegalStateException("Collection " + attributeName + " references an unsaved transient instance - save the transient instance before flushing: " + v);
                            }
                        } else {
                            v = valueViewToEntityMapper.applyToEntity(context, null, v);
                        }

                        query.setParameter("key", k);
                        query.setParameter("element", v);
                        query.executeUpdate();
                    }
                }
                if (embeddablesToUpdate != null && !embeddablesToUpdate.isEmpty()) {
                    for (Map.Entry<Object, Object> entry : embeddablesToUpdate.entrySet()) {
                        query.setParameter("key", entry.getKey());
                        query.setParameter("element", entry.getValue());
                        query.executeUpdate();
                    }
                }
            }

            appends = fusedCollectionActions.getAdded();
        }

        if (appends.size() > 0) {
            InsertCriteriaBuilder<?> insertCb = context.getEntityViewManager().getCriteriaBuilderFactory().insertCollection(context.getEntityManager(), ownerEntityClass, mapping);

            String keyEntityIdAttributeName = keyDescriptor.getEntityIdAttributeName();
            String keyAttributeIdAttributeName = keyDescriptor.getAttributeIdAttributeName();
            if (keyEntityIdAttributeName == null) {
                insertCb.fromValues(ownerEntityClass, "KEY(" + mapping + ")", "key", 1);
            } else if (keyEntityIdAttributeName.equals(keyAttributeIdAttributeName)) {
                insertCb.fromIdentifiableValues((Class<Object>) keyDescriptor.getJpaType(), "key", 1);
            } else {
                insertCb.fromIdentifiableValues((Class<Object>) keyDescriptor.getJpaType(), keyAttributeIdAttributeName, "key", 1);
            }

            String entityIdAttributeName = elementDescriptor.getEntityIdAttributeName();
            String attributeIdAttributeName = elementDescriptor.getAttributeIdAttributeName();
            if (entityIdAttributeName == null) {
                insertCb.fromValues(ownerEntityClass, mapping, "val", 1);
            } else if (entityIdAttributeName.equals(attributeIdAttributeName)) {
                insertCb.fromIdentifiableValues((Class<Object>) elementDescriptor.getJpaType(), "val", 1);
            } else {
                insertCb.fromIdentifiableValues((Class<Object>) elementDescriptor.getJpaType(), attributeIdAttributeName, "val", 1);
            }
            insertCb.bind("KEY(" + mapping + ")").select("key");
            for (int i = 0; i < ownerIdBindFragments.length; i += 2) {
                insertCb.bind(ownerIdBindFragments[i]).select(ownerIdBindFragments[i + 1]);
            }
            insertCb.bind(mapping).select("val");
            if (upsert) {
                SubqueryBuilder<? extends InsertCriteriaBuilder<?>> subqueryBuilder = insertCb.whereNotExists()
                    .from(ownerEntityClass, "subOwner")
                    .innerJoin("subOwner." + mapping, "sub");
                subqueryBuilder.where("KEY(sub)").eqExpression("key");
                for (int i = 0; i < ownerIdBindFragments.length; i += 2) {
                    subqueryBuilder.where("subOwner." + ownerIdBindFragments[i]).eqExpression(ownerIdBindFragments[i + 1]);
                }
                subqueryBuilder.end();
            }
            Query query = insertCb.getQuery();
            ownerIdFlusher.flushQuery(context, null, null, query, ownerView, view, ownerIdFlusher.getViewAttributeAccessor().getValue(ownerView), null, null);

            // TODO: Use batching when we implement #657
            Object[] singletonKeyArray = new Object[1];
            Object[] singletonValueArray = new Object[1];
            List<Object> singletonKeyList = Arrays.asList(singletonKeyArray);
            List<Object> singletonValueList = Arrays.asList(singletonValueArray);
            ViewToEntityMapper keyViewToEntityMapper = keyDescriptor.getLoadOnlyViewToEntityMapper();
            ViewToEntityMapper valueViewToEntityMapper = elementDescriptor.getLoadOnlyViewToEntityMapper();
            boolean checkTransient = elementDescriptor.isJpaEntity() && !elementDescriptor.shouldJpaPersist();
            Query updateQuery = null;
            for (Map.Entry<Object, Object> entry : appends.entrySet()) {
                Object k = entry.getKey();
                Object v = entry.getValue();
                if (k != null && v != null) {
                    if (keyViewToEntityMapper == null) {
                        if (checkTransient && keyDescriptor.getBasicUserType().shouldPersist(k)) {
                            throw new IllegalStateException("Collection " + attributeName + " references an unsaved transient instance - save the transient instance before flushing: " + k);
                        }
                        singletonKeyArray[0] = k;
                    } else {
                        singletonKeyArray[0] = keyViewToEntityMapper.applyToEntity(context, null, k);
                    }
                    if (valueViewToEntityMapper == null) {
                        if (checkTransient && elementDescriptor.getBasicUserType().shouldPersist(v)) {
                            throw new IllegalStateException("Collection " + attributeName + " references an unsaved transient instance - save the transient instance before flushing: " + v);
                        }
                        singletonValueArray[0] = v;
                    } else {
                        singletonValueArray[0] = valueViewToEntityMapper.applyToEntity(context, null, v);
                    }
                    query.setParameter("key", singletonKeyList);
                    query.setParameter("val", singletonValueList);
                    if (query.executeUpdate() == 0 && upsert) {
                        if (updateQuery == null) {
                            UpdateCriteriaBuilder<?> updateCb = context.getEntityViewManager().getCriteriaBuilderFactory().updateCollection(context.getEntityManager(), ownerEntityClass, "sub", mapping);

                            if (keyEntityIdAttributeName == null) {
                                updateCb.fromValues(ownerEntityClass, "KEY(" + mapping + ")", "key", 1);
                            } else if (keyEntityIdAttributeName.equals(keyAttributeIdAttributeName)) {
                                updateCb.fromIdentifiableValues((Class<Object>) keyDescriptor.getJpaType(), "key", 1);
                            } else {
                                updateCb.fromIdentifiableValues((Class<Object>) keyDescriptor.getJpaType(), keyAttributeIdAttributeName, "key", 1);
                            }

                            if (entityIdAttributeName == null) {
                                updateCb.fromValues(ownerEntityClass, mapping, "val", 1);
                            } else if (entityIdAttributeName.equals(attributeIdAttributeName)) {
                                updateCb.fromIdentifiableValues((Class<Object>) elementDescriptor.getJpaType(), "val", 1);
                            } else {
                                updateCb.fromIdentifiableValues((Class<Object>) elementDescriptor.getJpaType(), attributeIdAttributeName, "val", 1);
                            }
                            updateCb.where("KEY(sub." + mapping + ")").eqExpression("key");
                            for (int i = 0; i < ownerIdBindFragments.length; i += 2) {
                                updateCb.where("sub." + ownerIdBindFragments[i]).eqExpression(ownerIdBindFragments[i + 1]);
                            }
                            updateCb.setExpression(mapping, "val");
                            updateQuery = updateCb.getQuery();
                            ownerIdFlusher.flushQuery(context, null, null, updateQuery, ownerView, view, ownerIdFlusher.getViewAttributeAccessor().getValue(ownerView), null, null);
                        }
                        updateQuery.setParameter("key", singletonKeyList);
                        updateQuery.setParameter("val", singletonValueList);
                        updateQuery.executeUpdate();
                    }
                }
            }
        }
    }

    protected void flushCollectionOperations(UpdateContext context, Object ownerView, Object view, V initial, V value, Map<Object, Object> embeddablesToUpdate, FusedMapActions fusedCollectionActions, boolean initialKnown) {
        boolean removeSpecific = fusedCollectionActions != null && fusedCollectionActions.operationCount() < value.size() + 1;
        Map<Object, Object> removedAllObjects;
        if (deleteElements(context, ownerView, view, value, removeSpecific, fusedCollectionActions, !initialKnown && replaceWithReferenceContents)) {
            if (fusedCollectionActions == null) {
                if (initial == null) {
                    removedAllObjects = Collections.emptyMap();
                } else {
                    removedAllObjects = new IdentityHashMap<>(initial);
                }
            } else {
                removedAllObjects = new IdentityHashMap<>(fusedCollectionActions.getRemoved());
            }
        } else {
            removedAllObjects = Collections.emptyMap();
        }
        addElements(context, ownerView, view, removedAllObjects, true, value, embeddablesToUpdate, fusedCollectionActions, initialKnown);
        processRemovedObjects(context, removedAllObjects);
    }

    private void processRemovedObjects(UpdateContext context, Map<Object, Object> removedObjects) {
        if (keyRemoveListener != null || removeListener != null) {
            for (Map.Entry<Object, Object> entry : removedObjects.entrySet()) {
                if (keyRemoveListener != null && !(entry.getKey() instanceof FusedMapActions.RemoveWrapper)) {
                    keyRemoveListener.onCollectionRemove(context, entry.getKey());
                }
                if (removeListener != null && entry.getValue() != null) {
                    removeListener.onCollectionRemove(context, entry.getValue());
                }
            }
        }
    }

    private Map<Object, Object> flushCollectionViewElements(UpdateContext context, V value) {
        ViewToEntityMapper keyMapper = mapper.getKeyMapper();
        ViewToEntityMapper valueMapper = mapper.getValueMapper();
        final Iterator<Map.Entry<Object, Object>> iter = getRecordingIterator(value);
        Map<Object, Object> embeddables = null;
        if ((!keyDescriptor.isIdentifiable() || !elementDescriptor.isIdentifiable()) && mapping != null) {
            embeddables = new LinkedHashMap<>();
        }
        try {
            while (iter.hasNext()) {
                Map.Entry<Object, Object> entry = iter.next();
                Object k = entry.getKey();
                Object v = entry.getValue();

                Object newKey = k;
                if (keyMapper != null) {
                    newKey = keyMapper.applyToEntity(context, null, k);
                }
                Object newValue = v;
                if (valueMapper != null) {
                    newValue = valueMapper.applyToEntity(context, null, v);
                }
                if (embeddables != null) {
                    // Only query flushing of an element collection can bring us here
                    embeddables.put(newKey, newValue);
                }
            }
        } finally {
            resetRecordingIterator(value);
        }
        return embeddables;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean flushEntity(UpdateContext context, E entity, Object ownerView, Object view, V value, Runnable postReplaceListener) {
        if (flushOperation != null) {
            if (value instanceof RecordingMap<?, ?, ?>) {
                ((RecordingMap<?, ?, ?>) value).resetActions(context);
            } else {
                value = replaceWithRecordingCollection(context, view, value, collectionActions);
            }
            invokeFlushOperation(context, ownerView, view, entity, value);
            return true;
        }
        if (collectionUpdatable) {
            boolean replace = false;
            boolean wasDirty = false;
            boolean isRecording = value instanceof RecordingMap<?, ?, ?>;
            List<MapAction<Map<Object, Object>>> actions = null;
            Object initial = viewAttributeAccessor.getInitialValue(view);
            if (isRecording && (initial != null || !replaceWithReferenceContents)) {
                RecordingMap<Map<?, ?>, ?, ?> recordingMap = (RecordingMap<Map<?, ?>, ?, ?>) value;

                if (keyDescriptor.shouldFlushMutations() || elementDescriptor.shouldFlushMutations()) {
                    if (keyDescriptor.shouldJpaPersistOrMerge() || elementDescriptor.shouldJpaPersistOrMerge()) {
                        wasDirty |= mergeAndRequeue(context, recordingMap, (Map<Object, Object>) recordingMap.getDelegate());
                    } else if (keyDescriptor.isSubview() && keyDescriptor.shouldFlushMutations() && keyDescriptor.isIdentifiable()
                            && (!elementDescriptor.isSubview() || !elementDescriptor.shouldFlushMutations() || elementDescriptor.isIdentifiable())
                            || elementDescriptor.isSubview() && elementDescriptor.shouldFlushMutations() && elementDescriptor.isIdentifiable()
                            && (!keyDescriptor.isSubview() || !keyDescriptor.shouldFlushMutations() || keyDescriptor.isIdentifiable())) {
                        // Prevent entering this fast path when any of the two types would require a flush but isn't identifiable
                        flushCollectionViewElements(context, value);
                        wasDirty = true;
                    } else {
                        if (fetch && elementDescriptor.supportsDeepEqualityCheck() && entityAttributeAccessor != null) {
                            Map<Object, Object> jpaCollection = (Map<Object, Object>) entityAttributeAccessor.getValue(entity);

                            if (jpaCollection == null || jpaCollection.isEmpty()) {
                                replace = true;
                            } else {
                                EqualityChecker equalityChecker;
                                if (elementDescriptor.isSubview()) {
                                    if (elementDescriptor.isIdentifiable()) {
                                        equalityChecker = new EntityIdWithViewIdEqualityChecker(elementDescriptor.getViewToEntityMapper());
                                    } else {
                                        equalityChecker = new EntityWithViewEqualityChecker(elementDescriptor.getViewToEntityMapper());
                                    }
                                } else {
                                    equalityChecker = new DeepEqualityChecker(elementDescriptor.getBasicUserType());
                                }

                                if (jpaCollection == null || jpaCollection.isEmpty()) {
                                    replace = true;
                                } else {
                                    actions = determineJpaCollectionActions(context, (V) jpaCollection, value, equalityChecker);

                                    if (actions.size() > value.size()) {
                                        // More collection actions means more statements are issued
                                        // We'd rather replace in such a case
                                        replace = true;
                                    } else {
                                        for (MapAction<Map<Object, Object>> action : actions) {
                                            action.doAction(jpaCollection, context, loadOnlyMapper, keyRemoveListener, removeListener);
                                        }
                                        return !actions.isEmpty();
                                    }
                                }
                            }
                        } else {
                            // Non-identifiable mutable elements can't be updated, but have to be replaced
                            replace = true;
                        }
                    }
                }
                if (!replace) {
                    if (entityAttributeAccessor == null) {
                        // When having a correlated attribute, we consider is being dirty when it changed
                        wasDirty |= !recordingMap.resetActions(context).isEmpty();
                    } else {
                        Map<?, ?> map = (Map<?, ?>) entityAttributeAccessor.getValue(entity);
                        if (map == null) {
                            replace = true;
                        } else {
                            wasDirty |= recordingMap.hasActions();
                            recordingMap.replay(map, context, loadOnlyMapper, keyRemoveListener, removeListener);
                        }
                    }
                }
            } else {
                actions = replaceActions(value);
                value = replaceWithRecordingCollection(context, view, value, actions);

                if (fetch) {
                    if (value == null || value.isEmpty()) {
                        replace = true;
                    } else if (keyDescriptor.shouldFlushMutations() && !keyDescriptor.isIdentifiable()) {
                        // Non-identifiable mutable keys can't be updated, but have to be replaced
                        replace = true;
                        if (elementDescriptor.shouldFlushMutations() && elementDescriptor.isIdentifiable()) {
                            mergeAndRequeue(context, null, (Map<Object, Object>) value);
                        }
                    } else if (elementDescriptor.shouldFlushMutations()) {
                        if (keyDescriptor.shouldFlushMutations()) {
                            if (keyDescriptor.shouldJpaPersistOrMerge() || elementDescriptor.shouldJpaPersistOrMerge()) {
                                wasDirty |= mergeAndRequeue(context, null, (Map<Object, Object>) value);
                            } else if (keyDescriptor.isSubview() || elementDescriptor.isSubview()) {
                                if (elementDescriptor.isIdentifiable()) {
                                    flushCollectionViewElements(context, value);
                                    wasDirty = true;
                                } else {
                                    replace = true;
                                    mergeAndRequeue(context, null, (Map<Object, Object>) value);
                                }
                            } else {
                                replace = true;
                            }
                        } else {
                            if (elementDescriptor.shouldJpaPersistOrMerge()) {
                                wasDirty |= mergeAndRequeue(context, null, (Map<Object, Object>) value);
                            } else if (elementDescriptor.isSubview()) {
                                if (elementDescriptor.isIdentifiable()) {
                                    flushCollectionViewElements(context, value);
                                }
                            } else if (!elementDescriptor.supportsDeepEqualityCheck()) {
                                replace = true;
                            }
                        }
                    } else if (elementDescriptor.supportsEqualityCheck()) {
                        if (keyDescriptor.shouldFlushMutations() && keyDescriptor.isIdentifiable()) {
                            wasDirty |= mergeAndRequeue(context, null, (Map<Object, Object>) value);
                        }
                    }

                    if (!replace) {
                        if (entityAttributeAccessor == null) {
                            // When having a correlated attribute, we consider is being dirty when it changed
                            wasDirty = true;
                        } else {
                            // When we know the collection was fetched, we can try to "merge" the changes into the JPA collection
                            // If either of the collections is empty, we simply do the replace logic
                            Map<Object, Object> jpaCollection = (Map<Object, Object>) entityAttributeAccessor.getValue(entity);
                            if (jpaCollection == null || jpaCollection.isEmpty()) {
                                replace = true;
                            } else {
                                actions = determineJpaCollectionActions(context, (V) jpaCollection, value, elementEqualityChecker);

                                if (actions.size() > value.size()) {
                                    // More collection actions means more statements are issued
                                    // We'd rather replace in such a case
                                    replace = true;
                                } else {
                                    for (MapAction<Map<Object, Object>> action : actions) {
                                        action.doAction(jpaCollection, context, loadOnlyMapper, keyRemoveListener, removeListener);
                                    }
                                    wasDirty |= !actions.isEmpty();
                                }
                            }
                        }
                    }
                } else {
                    replace = true;
                }
            }

            if (replace) {
                if (isRecording) {
                    ((RecordingMap<Map<?, ?>, ?, ?>) value).resetActions(context);
                }
                replaceCollection(context, ownerView, view, entity, value, FlushStrategy.ENTITY);
                return true;
            }
            return wasDirty;
        } else if (keyDescriptor.shouldFlushMutations() || elementDescriptor.shouldFlushMutations()) {
            return mergeCollectionElements(context, ownerView, view, entity, value);
        } else {
            // Only pass through is possible here
            replaceCollection(context, ownerView, view, entity, value, FlushStrategy.ENTITY);
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    private Iterator<Map.Entry<Object, Object>> getRecordingIterator(Map<?, ?> value) {
        // If the key or the value are views, we need a recording iterator
        // TODO: only create a recording iterator when the mappers can have creatable types
        if (value instanceof RecordingMap<?, ?, ?> && (mapper.getKeyMapper() != null || mapper.getValueMapper() != null)) {
            return (Iterator<Map.Entry<Object, Object>>) (Iterator) ((RecordingMap<?, ?, ?>) value).recordingIterator();
        }

        return (Iterator<Map.Entry<Object, Object>>) (Iterator) value.entrySet().iterator();
    }

    @SuppressWarnings("unchecked")
    private void resetRecordingIterator(Map<?, ?> value) {
        if (value instanceof RecordingMap<?, ?, ?> && (mapper.getKeyMapper() != null || mapper.getValueMapper() != null)) {
            ((RecordingMap<?, ?, ?>) value).resetRecordingIterator();
        }
    }

    protected List<MapAction<Map<Object, Object>>> replaceActions(V value) {
        List<MapAction<Map<Object, Object>>> actions = new ArrayList<>();
        actions.add(new MapClearAction());
        if (value != null && !value.isEmpty()) {
            actions.add(new MapPutAllAction(value, Collections.emptyMap()));
        }
        return actions;
    }

    @Override
    public List<PostFlushDeleter> remove(UpdateContext context, E entity, Object view, V value) {
        V map;
        if (view instanceof DirtyStateTrackable) {
            map = (V) viewAttributeAccessor.getInitialValue(view);
        } else {
            map = value;
        }

        if (map != null && !map.isEmpty()) {
            // Entity flushing will do the delete anyway, so we can skip this
            if (flushStrategy == FlushStrategy.QUERY && !context.isForceEntity() && !jpaProviderDeletesCollection) {
                removeByOwnerId(context, ((EntityViewProxy) view).$$_getId(), false);
            }

            if (cascadeDeleteListener != null || keyCascadeDeleteListener != null) {
                List<Object> keys;
                List<Object> values;
                if (map instanceof RecordingMap) {
                    RecordingMap<?, ?, ?> recordingMap = (RecordingMap<?, ?, ?>) map;
                    Set<?> removedKeys = recordingMap.getRemovedKeys();
                    Set<?> removedElements = recordingMap.getRemovedElements();
                    Set<?> addedKeys = recordingMap.getAddedKeys();
                    Set<?> addedElements = recordingMap.getAddedElements();
                    keys = new ArrayList<>(recordingMap.size() + removedKeys.size());
                    values = new ArrayList<>(recordingMap.size() + removedElements.size());

                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        // Only report removes for objects that previously existed
                        if (keyCascadeDeleteListener != null && !addedKeys.contains(entry.getKey())) {
                            keys.add(entry.getKey());
                        }
                        if (cascadeDeleteListener != null && !addedElements.contains(entry.getValue())) {
                            values.add(entry.getValue());
                        }
                    }

                    // Report removed object that would have previously existed as removed
                    if (keyCascadeDeleteListener != null) {
                        keys.addAll(removedKeys);
                    }
                    if (cascadeDeleteListener != null) {
                        values.addAll(removedElements);
                    }
                } else {
                    keys = new ArrayList<>(map.size());
                    values = new ArrayList<>(map.size());
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        if (keyCascadeDeleteListener != null) {
                            keys.add(entry.getKey());
                        }
                        if (cascadeDeleteListener != null) {
                            values.add(entry.getValue());
                        }
                    }
                }
                if (keys.size() > 0 || values.size() > 0) {
                    List<PostFlushDeleter> list = new ArrayList<>(2);
                    if (keys.size() > 0) {
                        list.add(new PostFlushCollectionElementDeleter(keyCascadeDeleteListener, keys));
                    }
                    if (values.size() > 0) {
                        list.add(new PostFlushCollectionElementDeleter(cascadeDeleteListener, values));
                    }
                    return list;
                }
            }
        }

        return Collections.emptyList();
    }

    @Override
    public List<PostFlushDeleter> removeByOwnerId(UpdateContext context, Object id) {
        return removeByOwnerId(context, id, true);
    }

    private List<PostFlushDeleter> removeByOwnerId(UpdateContext context, Object ownerId, boolean cascade) {
        EntityViewManagerImpl evm = context.getEntityViewManager();
        if (cascade) {
            List<Object> elementIds;
            // If there is no inverseFlusher/mapped by attribute, the collection has a join table
            if (evm.getDbmsDialect().supportsReturningColumns()) {
                List<Tuple> tuples = evm.getCriteriaBuilderFactory().deleteCollection(context.getEntityManager(), ownerEntityClass, "e", mapping)
                        .where(ownerIdAttributeName).eq(ownerId)
                        .executeWithReturning(mapping + "." + elementDescriptor.getAttributeIdAttributeName())
                        .getResultList();

                elementIds = new ArrayList<>(tuples.size());
                for (Tuple tuple : tuples) {
                    elementIds.add(tuple.get(0));
                }
            } else {
                elementIds = (List<Object>) evm.getCriteriaBuilderFactory().create(context.getEntityManager(), ownerEntityClass, "e")
                        .where(ownerIdAttributeName).eq(ownerId)
                        .select("e." + mapping + "." + elementDescriptor.getAttributeIdAttributeName())
                        .getResultList();
                if (!elementIds.isEmpty() && !jpaProviderDeletesCollection) {
                    // We must always delete this, otherwise we might get a constraint violation because of the cascading delete
                    DeleteCriteriaBuilder<?> cb = evm.getCriteriaBuilderFactory().deleteCollection(context.getEntityManager(), ownerEntityClass, "e", mapping);
                    cb.where(ownerIdAttributeName).eq(ownerId);
                    cb.executeUpdate();
                }
            }

            return Collections.<PostFlushDeleter>singletonList(new PostFlushCollectionElementByIdDeleter(elementDescriptor.getElementToEntityMapper(), elementIds));
        } else if (!jpaProviderDeletesCollection) {
            // delete from Entity(collectionRole) e where e.id = :id
            DeleteCriteriaBuilder<?> cb = evm.getCriteriaBuilderFactory().deleteCollection(context.getEntityManager(), ownerEntityClass, "e", mapping);
            cb.where("e." + ownerIdAttributeName).eq(ownerId);
            cb.executeUpdate();
        }

        return Collections.emptyList();
    }

    @Override
    public void remove(UpdateContext context, Object id) {
        throw new UnsupportedOperationException("Unsupported!");
    }

    @Override
    public void removeFromEntity(UpdateContext context, E entity) {
        V value = (V) entityAttributeAccessor.getValue(entity);

        if (value != null) {
            // In any case we clear the collection
            if (cascadeDeleteListener != null || keyCascadeDeleteListener != null) {
                if (!value.isEmpty()) {
                    for (Map.Entry<?, ?> entry : value.entrySet()) {
                        if (keyCascadeDeleteListener != null) {
                            keyCascadeDeleteListener.onEntityCollectionRemove(context, entry.getKey());
                        }
                        if (cascadeDeleteListener != null) {
                            cascadeDeleteListener.onEntityCollectionRemove(context, entry.getValue());
                        }
                    }
                    entityAttributeAccessor.setValue(entity, null);
                }
            } else {
                value.clear();
            }
        }
    }

    @Override
    protected boolean canFlushSeparateCollectionOperations() {
        return true;
    }

    @Override
    public boolean requiresDeleteCascadeAfterRemove() {
        return false;
    }

    @Override
    public boolean isViewOnlyDeleteCascaded() {
        return viewOnlyDeleteCascaded;
    }

    protected final <X> X persistOrMergeKey(UpdateContext context, EntityManager em, X object) {
        return persistOrMerge(em, object, keyDescriptor);
    }

    private boolean mergeAndRequeue(UpdateContext context, RecordingMap recordingCollection, Map<Object, Object> newCollection) {
        EntityManager em = context.getEntityManager();
        Map<Object, Object> queuedElements = null;
        final ViewToEntityMapper keyMapper = mapper.getKeyMapper();
        final ViewToEntityMapper valueMapper = mapper.getValueMapper();
        final boolean flushKey = keyDescriptor.shouldJpaPersistOrMerge();
        final boolean flushValue = elementDescriptor.shouldJpaPersistOrMerge();
        final Iterator<Map.Entry<Object, Object>> iter = getRecordingIterator(newCollection);
        try {
            while (iter.hasNext()) {
                Map.Entry<Object, Object> entry = iter.next();
                Object key = entry.getKey();
                Object value = entry.getValue();

                if (flushKey) {
                    key = persistOrMergeKey(context, em, key);
                } else if (keyMapper != null) {
                    keyMapper.applyToEntity(context, null, key);
                }

                if (flushValue) {
                    value = persistOrMerge(em, value);
                } else if (valueMapper != null) {
                    valueMapper.applyToEntity(context, null, value);
                }

                if (key != entry.getKey()) {
                    if (queuedElements == null) {
                        queuedElements = new HashMap<>(newCollection.size());
                    }
                    iter.remove();
                    queuedElements.put(key, value);
                    if (recordingCollection != null) {
                        recordingCollection.replaceActionElement(entry.getKey(), entry.getValue(), key, value);
                    }
                } else if (value != entry.getValue()) {
                    entry.setValue(value);
                }
            }
        } finally {
            resetRecordingIterator(newCollection);
        }

        if (queuedElements != null) {
            newCollection.putAll(queuedElements);
        }
        return true;
    }

    @Override
    protected boolean mergeCollectionElements(UpdateContext context, Object ownerView, Object view, E entity, V value) {
        if (elementFlushers != null) {
            if (flushStrategy == FlushStrategy.ENTITY || context.isForceEntity()) {
                for (CollectionElementAttributeFlusher<E, V> elementFlusher : elementFlushers) {
                    elementFlusher.flushEntity(context, entity, ownerView, view, value, null);
                }
            } else {
                for (CollectionElementAttributeFlusher<E, V> elementFlusher : elementFlushers) {
                    elementFlusher.flushQuery(context, null, null, null, ownerView, view, value, null, null);
                }
            }
            return !elementFlushers.isEmpty();
        } else {
            // Invocations of JPA merge can change the identity that leads to requeuing into the collection being required
            final boolean needsRequeuing = keyDescriptor.shouldJpaMerge()
                    || value instanceof RecordingMap<?, ?, ?> && elementDescriptor.shouldJpaMerge();

            if (needsRequeuing) {
                if (value instanceof RecordingMap<?, ?, ?>) {
                    return mergeAndRequeue(context, (RecordingMap) value, ((RecordingMap) value).getDelegate());
                } else {
                    return mergeAndRequeue(context, null, (Map<Object, Object>) (Map<?, ?>) value);
                }
            } else {
                @SuppressWarnings("unchecked")
                final EntityManager em = context.getEntityManager();
                final ViewToEntityMapper keyMapper = mapper.getKeyMapper();
                final ViewToEntityMapper valueMapper = mapper.getValueMapper();
                final boolean flushKey = keyDescriptor.shouldJpaPersistOrMerge();
                final boolean flushValue = elementDescriptor.shouldJpaPersistOrMerge();

                final Iterator<Map.Entry<Object, Object>> iter = getRecordingIterator(value);
                try {
                    while (iter.hasNext()) {
                        Map.Entry<Object, Object> entry = iter.next();
                        Object k = entry.getKey();
                        Object v = entry.getValue();

                        if (flushKey) {
                            persistOrMergeKey(context, em, k);
                        } else if (keyMapper != null) {
                            keyMapper.applyToEntity(context, null, k);
                        }

                        if (v != null) {
                            if (flushValue) {
                                v = persistOrMerge(em, v);
                            } else if (valueMapper != null) {
                                valueMapper.applyToEntity(context, null, v);
                            }
                        }

                        if (v != entry.getValue()) {
                            entry.setValue(v);
                        }
                    }
                } finally {
                    resetRecordingIterator(value);
                }
                return true;
            }
        }
    }

    @Override
    protected void replaceCollection(UpdateContext context, Object ownerView, Object view, E entity, V value, FlushStrategy flushStrategy) {
        if (flushStrategy == FlushStrategy.QUERY) {
            Map<Object, Object> removedAllObjects;
            if (deleteElements(context, ownerView, view, value, false, null, true)) {
                // TODO: We should load the initial value
                removedAllObjects = Collections.emptyMap();
            } else {
                removedAllObjects = Collections.emptyMap();
            }
            addElements(context, ownerView, view, removedAllObjects, true, value, null, null, true);
            processRemovedObjects(context, removedAllObjects);
        } else {
            if (entityAttributeAccessor != null) {
                if (keyDescriptor.isSubview() || elementDescriptor.isSubview()) {
                    Map<Object, Object> newMap;
                    if (value == null) {
                        newMap = (Map<Object, Object>) createJpaMap(0);
                    } else {
                        newMap = (Map<Object, Object>) createJpaMap(value.size());
                        ViewToEntityMapper keyMapper = mapper.getKeyMapper();
                        ViewToEntityMapper valueMapper = mapper.getValueMapper();
                        final Iterator<Map.Entry<Object, Object>> iter = getRecordingIterator(value);
                        try {
                            while (iter.hasNext()) {
                                Map.Entry<Object, Object> entry = iter.next();
                                Object k = entry.getKey();
                                Object v = entry.getValue();

                                if (keyMapper != null) {
                                    k = keyMapper.applyToEntity(context, null, k);
                                }
                                if (valueMapper != null) {
                                    v = valueMapper.applyToEntity(context, null, v);
                                }

                                newMap.put(k, v);
                            }
                        } finally {
                            resetRecordingIterator(value);
                        }
                    }
                    entityAttributeAccessor.setValue(entity, newMap);
                } else {
                    entityAttributeAccessor.setValue(entity, value);
                }
            }
        }
    }

    @Override
    public <X> DirtyChecker<X>[] getNestedCheckers(V current) {
        throw new UnsupportedOperationException();
    }

    @Override
    public DirtyKind getDirtyKind(V initial, V current) {
        if (current == null) {
            if (initial == null) {
                return DirtyKind.NONE;
            }
            return DirtyKind.UPDATED;
        }
        if (initial == null) {
            return DirtyKind.UPDATED;
        }

        if (initial == current) {
            if (current instanceof RecordingMap<?, ?, ?>) {
                RecordingMap<?, ?, ?> recordingCollection = (RecordingMap<?, ?, ?>) current;
                if (recordingCollection.hasActions()) {
                    return DirtyKind.MUTATED;
                }

                if (keyDescriptor.shouldFlushMutations() || elementDescriptor.shouldFlushMutations()) {
                    if (keyDescriptor.shouldFlushMutations() && !keyDescriptor.supportsDirtyCheck()
                            || elementDescriptor.shouldFlushMutations() && !elementDescriptor.supportsDirtyCheck()) {
                        // If we don't support dirty checking we always have to assume dirtyness
                        return DirtyKind.MUTATED;
                    }

                    // The dirty checking fast-path can only work when the key and the element are either immutable or report changes via the DirtyTracker API
                    if (!recordingCollection.$$_isDirty()
                            && (!keyDescriptor.shouldFlushMutations() || keyDescriptor.shouldFlushMutations() && keyDescriptor.isSubview())
                            && (!elementDescriptor.shouldFlushMutations() || elementDescriptor.shouldFlushMutations() && elementDescriptor.isSubview())) {
                        return DirtyKind.NONE;
                    }

                    ViewToEntityMapper keyMapper = keyDescriptor.getViewToEntityMapper();
                    ViewToEntityMapper valueMapper = elementDescriptor.getViewToEntityMapper();
                    BasicUserType<Object> keyUserType = keyDescriptor.getBasicUserType();
                    BasicUserType<Object> valueUserType = elementDescriptor.getBasicUserType();
                    for (Map.Entry<?, ?> entry : recordingCollection.entrySet()) {
                        Object key = entry.getKey();
                        Object value = entry.getValue();
                        if (keyDescriptor.supportsDirtyCheck()) {
                            if (keyDescriptor.isSubview()) {
                                if (key instanceof DirtyStateTrackable) {
                                    DirtyStateTrackable element = (DirtyStateTrackable) key;
                                    if (keyMapper.getUpdater(value).getDirtyChecker().getDirtyKind(element, element) == DirtyKind.MUTATED) {
                                        return DirtyKind.MUTATED;
                                    }
                                }
                            } else {
                                String[] dirtyProperties = keyUserType.getDirtyProperties(value);
                                if (dirtyProperties != null) {
                                    return DirtyKind.MUTATED;
                                }
                            }
                        }
                        if (elementDescriptor.supportsDirtyCheck()) {
                            if (elementDescriptor.isSubview()) {
                                if (value instanceof DirtyStateTrackable) {
                                    DirtyStateTrackable element = (DirtyStateTrackable) value;
                                    if (valueMapper.getUpdater(value).getDirtyChecker().getDirtyKind(element, element) == DirtyKind.MUTATED) {
                                        return DirtyKind.MUTATED;
                                    }
                                }
                            } else {
                                String[] dirtyProperties = valueUserType.getDirtyProperties(value);
                                if (dirtyProperties != null) {
                                    return DirtyKind.MUTATED;
                                }
                            }
                        }
                    }
                } else {
                    // Since initial == current, nothing changed
                    return DirtyKind.NONE;
                }
            }
        } else {
            if (initial.size() != current.size()) {
                return DirtyKind.MUTATED;
            }
            if (keyDescriptor.shouldFlushMutations() || elementDescriptor.shouldFlushMutations()) {
                if (keyDescriptor.shouldFlushMutations() && !keyDescriptor.supportsDirtyCheck()
                        || elementDescriptor.shouldFlushMutations() && !elementDescriptor.supportsDirtyCheck()) {

                    if ((!keyDescriptor.shouldFlushMutations() || keyDescriptor.isSubview() || keyDescriptor.shouldFlushMutations() && keyDescriptor.getBasicUserType().supportsDeepCloning())
                            && (!elementDescriptor.shouldFlushMutations() || elementDescriptor.isSubview() || elementDescriptor.shouldFlushMutations() && elementDescriptor.getBasicUserType().supportsDeepCloning())) {
                        return collectionEquals(initial, current) ? DirtyKind.NONE : DirtyKind.MUTATED;
                    } else {
                        // If we don't support dirty checking we always have to assume dirtyness
                        return DirtyKind.MUTATED;
                    }
                }

                ViewToEntityMapper keyMapper = keyDescriptor.getViewToEntityMapper();
                ViewToEntityMapper valueMapper = elementDescriptor.getViewToEntityMapper();
                BasicUserType<Object> keyUserType = keyDescriptor.getBasicUserType();
                BasicUserType<Object> valueUserType = elementDescriptor.getBasicUserType();

                for (Map.Entry<?, ?> entry : current.entrySet()) {
                    Object key = entry.getKey();
                    Object value = entry.getValue();
                    if (!Objects.equals(initial.get(key), value)) {
                        return DirtyKind.MUTATED;
                    }

                    if (keyDescriptor.supportsDirtyCheck()) {
                        if (keyDescriptor.isSubview()) {
                            if (key instanceof DirtyStateTrackable) {
                                DirtyStateTrackable element = (DirtyStateTrackable) key;
                                if (keyMapper.getUpdater(value).getDirtyChecker().getDirtyKind(element, element) == DirtyKind.MUTATED) {
                                    return DirtyKind.MUTATED;
                                }
                            }
                        } else {
                            String[] dirtyProperties = keyUserType.getDirtyProperties(value);
                            if (dirtyProperties != null) {
                                return DirtyKind.MUTATED;
                            }
                        }
                    }
                    if (elementDescriptor.supportsDirtyCheck()) {
                        if (elementDescriptor.isSubview()) {
                            if (value instanceof DirtyStateTrackable) {
                                DirtyStateTrackable element = (DirtyStateTrackable) value;
                                if (valueMapper.getUpdater(value).getDirtyChecker().getDirtyKind(element, element) == DirtyKind.MUTATED) {
                                    return DirtyKind.MUTATED;
                                }
                            }
                        } else {
                            String[] dirtyProperties = valueUserType.getDirtyProperties(value);
                            if (dirtyProperties != null) {
                                return DirtyKind.MUTATED;
                            }
                        }
                    }
                }

            } else {
                return collectionEquals(initial, current) ? DirtyKind.NONE : DirtyKind.MUTATED;
            }
        }

        return DirtyKind.NONE;
    }

    @Override
    public DirtyChecker<E> getElementDirtyChecker(E element) {
        if (!elementDescriptor.shouldFlushMutations()) {
            return null;
        }
        if (elementDescriptor.isSubview()) {
            EntityViewUpdater updater = elementDescriptor.getViewToEntityMapper().getUpdater(element);
            if (updater == null) {
                throw new IllegalArgumentException("Found unexpected element in plural attribute '" + attributeName + "'. The object does not seem to be flushable: " + element);
            }
            return (DirtyChecker<E>) updater.getDirtyChecker();
        } else if (elementDescriptor.isJpaEntity()) {
            return (DirtyChecker<E>) elementDescriptor.getEntityToEntityMapper().getDirtyChecker();
        } else {
            return (DirtyChecker<E>) elementDirtyChecker;
        }
    }

    @Override
    public DirtyChecker<Object> getKeyDirtyChecker(Object element) {
        if (!keyDescriptor.shouldFlushMutations()) {
            return null;
        }
        if (keyDescriptor.isSubview()) {
            EntityViewUpdater updater = keyDescriptor.getViewToEntityMapper().getUpdater(element);
            if (updater == null) {
                throw new IllegalArgumentException("Found unexpected key in map attribute '" + attributeName + "'. The object does not seem to be flushable: " + element);
            }
            return (DirtyChecker<Object>) (DirtyChecker<?>) updater.getDirtyChecker();
        } else if (keyDescriptor.isJpaEntity()) {
            return (DirtyChecker<Object>) keyDescriptor.getEntityToEntityMapper().getDirtyChecker();
        } else {
            return (DirtyChecker<Object>) keyDirtyChecker;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public DirtyAttributeFlusher<MapAttributeFlusher<E, V>, E, V> getDirtyFlusher(UpdateContext context, Object view, Object initial, Object current) {
        if (collectionUpdatable) {
            if (initial != current) {
                // If the new map is empty, we don't need to load the old one
                if (current == null || ((Map<?, ?>) current).isEmpty()) {
                    if (initial != null && ((Map<?, ?>) initial).isEmpty()) {
                        return null;
                    }
                    return partialFlusher(false, PluralFlushOperation.COLLECTION_REPLACE_ONLY, Collections.EMPTY_LIST, Collections.<CollectionElementAttributeFlusher<E, V>>emptyList());
                }
                // If the initial map is empty, we also don't need to load the old one
                if (initial == null && replaceWithReferenceContents || initial != null && ((Map<?, ?>) initial).isEmpty()) {
                    return partialFlusher(false, PluralFlushOperation.COLLECTION_REPLACE_ONLY, Collections.EMPTY_LIST, Collections.<CollectionElementAttributeFlusher<E, V>>emptyList());
                }
                if (initial instanceof RecordingCollection<?, ?>) {
                    initial = ((RecordingCollection<?, ?>) initial).getInitialVersion();
                }

                if (keyDescriptor.shouldFlushMutations()) {
                    if (keyDescriptor.supportsDirtyCheck()) {
                        if (elementDescriptor.shouldFlushMutations()) {
                            // If the elements are mutable, replacing the map and merging elements might lead to N+1 queries
                            // Since maps rarely change drastically, loading the old map it is probably a good way to avoid many queries
                            if (elementDescriptor.supportsDirtyCheck()) {
                                // Check elements for dirtyness
                                return determineDirtyFlusherForNewCollection(context, (V) initial, (V) current);
                            } else if (elementDescriptor.supportsDeepEqualityCheck()) {
                                if (canFlushSeparateCollectionOperations() && elementDescriptor.getBasicUserType() != null && elementDescriptor.getBasicUserType().supportsDeepCloning()) {
                                    // If we can determine equality, we fetch and merge or replace the elements
                                    EqualityChecker equalityChecker;
                                    if (elementDescriptor.isSubview()) {
                                        equalityChecker = EqualsEqualityChecker.INSTANCE;
                                    } else {
                                        equalityChecker = new IdentityEqualityChecker(elementDescriptor.getBasicUserType());
                                    }
                                    List<MapAction<Map<Object, Object>>> actions;
                                    if (initial == null && replaceWithReferenceContents) {
                                        actions = replaceActions((V) current);
                                    } else {
                                        actions = determineCollectionActions(context, (V) initial, (V) current, equalityChecker);
                                    }
                                    if (actions.isEmpty()) {
                                        return null;
                                    }
                                    return partialFlusher(true, PluralFlushOperation.COLLECTION_REPLAY_ONLY, actions, Collections.<CollectionElementAttributeFlusher<E, V>>emptyList());
                                } else {
                                    return this;
                                }
                            } else if (elementDescriptor.isJpaEntity()) {
                                // When we have a JPA entity, we fetch everything
                                EqualityChecker equalityChecker;
                                if (elementDescriptor.isSubview()) {
                                    equalityChecker = EqualsEqualityChecker.INSTANCE;
                                } else {
                                    equalityChecker = new IdentityEqualityChecker(elementDescriptor.getBasicUserType());
                                }
                                List<MapAction<Map<Object, Object>>> actions;
                                if (initial == null && replaceWithReferenceContents) {
                                    actions = replaceActions((V) current);
                                } else {
                                    actions = determineCollectionActions(context, (V) initial, (V) current, equalityChecker);
                                }
                                return partialFlusher(true, PluralFlushOperation.COLLECTION_REPLAY_AND_ELEMENT, actions, getElementFlushers(context, (V) current, actions));
                            } else {
                                // Other types are mutable basic types that aren't known to us like e.g. java.util.Date would be if we hadn't registered it
                                return partialFlusher(false, PluralFlushOperation.COLLECTION_REPLACE_ONLY, Collections.EMPTY_LIST, Collections.<CollectionElementAttributeFlusher<E, V>>emptyList());
                            }
                        } else {
                            return determineDirtyFlusherForNewCollection(context, (V) initial, (V) current);
                        }
                    } else if (keyDescriptor.supportsDeepEqualityCheck() || keyDescriptor.isJpaEntity()) {
                        // If we can determine equality, we fetch and merge the elements
                        // We also fetch if we have entities since we assume map rarely change drastically
                        return this;
                    } else {
                        // Other types are mutable basic types that aren't known to us like e.g. java.util.Date would be if we hadn't registered it
                        return partialFlusher(false, PluralFlushOperation.COLLECTION_REPLACE_ONLY, Collections.EMPTY_LIST, Collections.<CollectionElementAttributeFlusher<E, V>>emptyList());
                    }
                } else if (elementDescriptor.shouldFlushMutations()) {
                    // If the elements are mutable, replacing the map and merging elements might lead to N+1 queries
                    // Since maps rarely change drastically, loading the old map it is probably a good way to avoid many queries
                    if (elementDescriptor.supportsDirtyCheck()) {
                        // Check elements for dirtyness
                        return determineDirtyFlusherForNewCollection(context, (V) initial, (V) current);
                    } else if (elementDescriptor.supportsDeepEqualityCheck()) {
                        if (canFlushSeparateCollectionOperations() && elementDescriptor.getBasicUserType() != null && elementDescriptor.getBasicUserType().supportsDeepCloning()) {
                            // If we can determine equality, we fetch and merge or replace the elements
                            EqualityChecker equalityChecker;
                            if (elementDescriptor.isSubview()) {
                                equalityChecker = EqualsEqualityChecker.INSTANCE;
                            } else {
                                equalityChecker = new IdentityEqualityChecker(elementDescriptor.getBasicUserType());
                            }
                            List<MapAction<Map<Object, Object>>> actions;
                            if (initial == null && replaceWithReferenceContents) {
                                actions = replaceActions((V) current);
                            } else {
                                actions = determineCollectionActions(context, (V) initial, (V) current, equalityChecker);
                            }
                            if (actions.isEmpty()) {
                                return null;
                            }
                            return partialFlusher(true, PluralFlushOperation.COLLECTION_REPLAY_ONLY, actions, Collections.<CollectionElementAttributeFlusher<E, V>>emptyList());
                        } else {
                            return this;
                        }
                    } else if (elementDescriptor.isJpaEntity()) {
                        // When we have a JPA entity, we fetch everything
                        EqualityChecker equalityChecker;
                        if (elementDescriptor.isSubview()) {
                            equalityChecker = EqualsEqualityChecker.INSTANCE;
                        } else {
                            equalityChecker = new IdentityEqualityChecker(elementDescriptor.getBasicUserType());
                        }
                        List<MapAction<Map<Object, Object>>> actions;
                        if (initial == null && replaceWithReferenceContents) {
                            actions = replaceActions((V) current);
                        } else {
                            actions = determineCollectionActions(context, (V) initial, (V) current, equalityChecker);
                        }
                        return partialFlusher(true, PluralFlushOperation.COLLECTION_REPLAY_AND_ELEMENT, actions, getElementFlushers(context, (V) current, actions));
                    } else {
                        // Other types are mutable basic types that aren't known to us like e.g. java.util.Date would be if we hadn't registered it
                        return partialFlusher(false, PluralFlushOperation.COLLECTION_REPLACE_ONLY, Collections.EMPTY_LIST, Collections.<CollectionElementAttributeFlusher<E, V>>emptyList());
                    }
                } else {
                    return determineDirtyFlusherForNewCollection(context, (V) initial, (V) current);
                }
            } else {
                // If the initial and current reference are null or empty, no need to do anything further
                if (initial != null && !(initial instanceof RecordingMap<?, ?, ?>) && ((Map<?, ?>) initial).isEmpty()) {
                    return null;
                }
                if (current instanceof RecordingMap<?, ?, ?> && !((RecordingMap<?, ?, ?>) current).hasActions() && ((RecordingMap<?, ?, ?>) current).isEmpty()) {
                    // But skip doing anything if the collections kept being empty
                    return null;
                }

                if (keyDescriptor.shouldFlushMutations()) {
                    if (keyDescriptor.supportsDirtyCheck()) {
                        if (elementDescriptor.shouldFlushMutations()) {
                            if (elementDescriptor.supportsDirtyCheck()) {
                                if (current instanceof RecordingMap<?, ?, ?>) {
                                    return getDirtyFlusherForRecordingCollection(context, (RecordingMap<?, ?, ?>) current);
                                } else {
                                    // Since we don't know what changed in the map, we do a full fetch and merge
                                    return this;
                                }
                            } else if (elementDescriptor.supportsDeepEqualityCheck() || elementDescriptor.isJpaEntity()) {
                                // If we can determine equality, we fetch and merge the elements
                                // We also fetch if we have entities since we assume map rarely change drastically else {
                                if (current instanceof RecordingMap<?, ?, ?>) {
                                    if (elementDescriptor.isIdentifiable()) {
                                        List<? extends MapAction<?>> actions = ((RecordingMap<?, ?, ?>) current).getActions();
                                        if (actions == null) {
                                            actions = Collections.emptyList();
                                        }
                                        return partialFlusher(true, isUpsert((RecordingMap<?, ?, ?>) current), PluralFlushOperation.COLLECTION_REPLAY_AND_ELEMENT, actions, getElementFlushers(context, (V) current, actions));
                                    }
                                }
                                return this;
                            } else {
                                // Other types are mutable basic types that aren't known to us like e.g. java.util.Date would be if we hadn't registered it
                                return partialFlusher(false, isUpsert((RecordingMap<?, ?, ?>) current), PluralFlushOperation.COLLECTION_REPLACE_ONLY, Collections.EMPTY_LIST, Collections.<CollectionElementAttributeFlusher<E, V>>emptyList());
                            }
                        } else {
                            // Immutable elements in an updatable map
                            if (current instanceof RecordingMap<?, ?, ?>) {
                                return getDirtyFlusherForRecordingCollection(context, (RecordingMap<?, ?, ?>) current);
                            } else {
                                // Since we don't know what changed in the map, we do a full fetch and merge
                                return this;
                            }
                        }
                    } else {
                        // Since we don't know what changed in the map, we do a full fetch and merge
                        return this;
                    }
                } else {
                    if (elementDescriptor.shouldFlushMutations()) {
                        if (elementDescriptor.supportsDirtyCheck()) {
                            if (current instanceof RecordingMap<?, ?, ?>) {
                                return getDirtyFlusherForRecordingCollection(context, (RecordingMap<?, ?, ?>) current);
                            } else {
                                // Since we don't know what changed in the map, we do a full fetch and merge
                                return this;
                            }
                        } else if (elementDescriptor.supportsDeepEqualityCheck() || elementDescriptor.isJpaEntity()) {
                            // If we can determine equality, we fetch and merge the elements
                            // We also fetch if we have entities since we assume map rarely change drastically
                            if (current instanceof RecordingMap<?, ?, ?>) {
                                List<? extends MapAction<?>> actions = ((RecordingMap<?, ?, ?>) current).getActions();
                                if (actions == null) {
                                    actions = Collections.emptyList();
                                }
                                if (elementDescriptor.isIdentifiable()) {
                                    return partialFlusher(true, isUpsert((RecordingMap<?, ?, ?>) current), PluralFlushOperation.COLLECTION_REPLAY_AND_ELEMENT, actions, getElementFlushers(context, (V) current, actions));
                                }
                            }
                            return this;
                        } else {
                            // Other types are mutable basic types that aren't known to us like e.g. java.util.Date would be if we hadn't registered it
                            return partialFlusher(false, isUpsert((RecordingMap<?, ?, ?>) current), PluralFlushOperation.COLLECTION_REPLACE_ONLY, Collections.EMPTY_LIST, Collections.<CollectionElementAttributeFlusher<E, V>>emptyList());
                        }
                    } else {
                        // Immutable elements in an updatable map
                        if (current instanceof RecordingMap<?, ?, ?>) {
                            return getDirtyFlusherForRecordingCollection(context, (RecordingMap<?, ?, ?>) current);
                        } else {
                            // Since we don't know what changed in the map, we do a full fetch and merge
                            return this;
                        }
                    }
                }
            }
        } else {
            // Not updatable
            if (keyDescriptor.shouldFlushMutations() || elementDescriptor.shouldFlushMutations()) {
                if (initial != current) {
                    // If the reference changed, this is probably because of defensive copies
                    return null;
                } else {
                    // Flushes for non-identifiable types can't be done separately, so we need to fetch and merge accordingly
                    if (!keyDescriptor.isIdentifiable() || !elementDescriptor.isIdentifiable()) {
                        return this;
                    }
                    return getElementOnlyFlusher(context, (V) current);
                }
            } else {
                // Not updatable and no cascading, this is for pass through flushers only
                return null;
            }
        }
    }

    private boolean isUpsert(RecordingMap<?, ?, ?> map) {
        // Can only be an upsert if initial == current, this map was previously empty and the parent is a "reference"
        if (map.size() == map.getAddedKeys().size()) {
            BasicDirtyTracker parent = map.$$_getParent();
            if (parent instanceof EntityViewProxy) {
                EntityViewProxy parentProxy = (EntityViewProxy) parent;
                // Since a map can be part of an embeddable and updatable flat views for that type can be "new",
                // even though they should be "reference", we have to also check the parent of the parent
                for (int i = 0; i <= embeddableParentCount; i++) {
                    if (parentProxy.$$_isReference()) {
                        return true;
                    } else if (!parentProxy.$$_isNew() || !(parentProxy instanceof MutableStateTrackable)) {
                        return false;
                    }
                    parentProxy = (EntityViewProxy) ((MutableStateTrackable) parentProxy).$$_getParent();
                }
            }
        }
        return false;
    }

    protected DirtyAttributeFlusher<MapAttributeFlusher<E, V>, E, V> determineDirtyFlusherForNewCollection(UpdateContext context, V initial, V current) {
        EqualityChecker equalityChecker;
        if (elementDescriptor.isSubview()) {
            equalityChecker = EqualsEqualityChecker.INSTANCE;
        } else {
            equalityChecker = new IdentityEqualityChecker(elementDescriptor.getBasicUserType());
        }
        List<MapAction<Map<Object, Object>>> collectionActions = determineCollectionActions(context, initial, current, equalityChecker);

        // If nothing changed in the collection and no changes should be flushed, we are done
        if (collectionActions.size() == 0) {
            List<CollectionElementAttributeFlusher<E, V>> elementFlushers = getElementFlushers(context, current, collectionActions);
            // A "null" element flusher list is given when a fetch and compare is more appropriate
            if (elementFlushers == null) {
                return this;
            }
            return getReplayAndElementFlusher(context, initial, current, collectionActions, elementFlushers);
        }

        if (initial == null && replaceWithReferenceContents || initial != null && collectionActions.size() > current.size()) {
            // More collection actions means more statements are issued
            // We'd rather replace in such a case
            if (elementDescriptor.shouldFlushMutations()) {
                return getReplaceOrMergeAndElementFlusher(context, initial, current);
            } else {
                return getReplaceOrMergeOnlyFlusher(context, initial, current);
            }
        } else {
            // Reset the actions since we determined new actions
            if (initial != null && current instanceof RecordingMap<?, ?, ?>) {
                RecordingMap<Map<?, ?>, ?, ?> recordingCollection = (RecordingMap<Map<?, ?>, ?, ?>) current;
                recordingCollection.initiateActionsAgainstState((List<MapAction<Map<?, ?>>>) (List<?>) collectionActions, initial);
            }
            // If we determine possible collection actions, we try to apply them, but if not
            if (elementDescriptor.shouldFlushMutations()) {
                List<CollectionElementAttributeFlusher<E, V>> elementFlushers = getElementFlushers(context, current, collectionActions);
                // A "null" element flusher list is given when a fetch and compare is more appropriate
                if (elementFlushers == null) {
                    return this;
                }
                return getReplayAndElementFlusher(context, initial, current, collectionActions, elementFlushers);
            } else {
                return getReplayOnlyFlusher(context, initial, current, collectionActions);
            }
        }
    }

    protected List<MapAction<Map<Object, Object>>> determineJpaCollectionActions(UpdateContext context, V initial, V current, EqualityChecker equalityChecker) {
        List<MapAction<Map<Object, Object>>> actions = new ArrayList<>();
        Map.Entry<Object, Object>[] objectsToAdd = (Map.Entry<Object, Object>[]) current.entrySet().toArray(new Map.Entry[current.size()]);

        if (keyDescriptor.isSubview() && keyDescriptor.isIdentifiable()) {
            final AttributeAccessor entityIdAccessor = keyDescriptor.getViewToEntityMapper().getEntityIdAccessor();
            final AttributeAccessor subviewIdAccessor = keyDescriptor.getViewToEntityMapper().getViewIdAccessor();

            OUTER: for (Map.Entry<?, ?> entry : initial.entrySet()) {
                Object initialObject = entry.getKey();
                Object initialViewId = entityIdAccessor.getValue(initialObject);
                Object initialElement = getViewElement(context, elementDescriptor, entry.getValue());
                for (int i = 0; i < objectsToAdd.length; i++) {
                    Map.Entry<Object, Object> entryToAdd = objectsToAdd[i];
                    Object currentObject = entryToAdd.getKey();
                    if (currentObject != REMOVED_MARKER) {
                        Object currentViewId = subviewIdAccessor.getValue(currentObject);
                        if (initialViewId.equals(currentViewId)) {
                            objectsToAdd[i] = REMOVED_MARKER;
                            if (!equalityChecker.isEqual(context, entry.getValue(), entryToAdd.getValue())) {
                                if (keyDescriptor.shouldFlushMutations()) {
                                    actions.add((MapAction<Map<Object, Object>>) (MapAction<?>) new MapRemoveAction<>(initialObject, initialElement));
                                }
                                actions.add((MapAction<Map<Object, Object>>) (MapAction<?>) new MapPutAction<>(entryToAdd.getKey(), entryToAdd.getValue(), initialElement));
                            }
                            continue OUTER;
                        }
                    }
                }
                actions.add((MapAction<Map<Object, Object>>) (MapAction<?>) new MapRemoveAction<>(initialObject, initialElement));
            }
        } else {
            final BasicUserType<Object> basicUserType = keyDescriptor.getBasicUserType();

            OUTER: for (Map.Entry<?, ?> entry : initial.entrySet()) {
                Object initialObject = entry.getKey();
                Object initialElement = getViewElement(context, elementDescriptor, entry.getValue());
                for (int i = 0; i < objectsToAdd.length; i++) {
                    Map.Entry<Object, Object> entryToAdd = objectsToAdd[i];
                    Object currentObject = entryToAdd.getKey();
                    if (currentObject != REMOVED_MARKER) {
                        if (basicUserType.isEqual(initialObject, currentObject)) {
                            objectsToAdd[i] = REMOVED_MARKER;
                            if (!equalityChecker.isEqual(context, entry.getValue(), entryToAdd.getValue())) {
                                if (keyDescriptor.shouldFlushMutations()) {
                                    actions.add((MapAction<Map<Object, Object>>) (MapAction<?>) new MapRemoveAction<>(initialObject, initialElement));
                                }
                                actions.add((MapAction<Map<Object, Object>>) (MapAction<?>) new MapPutAction<>(entryToAdd.getKey(), entryToAdd.getValue(), initialElement));
                            }
                            continue OUTER;
                        }
                    }
                }
                actions.add((MapAction<Map<Object, Object>>) (MapAction<?>) new MapRemoveAction<>(initialObject, initialElement));
            }
        }


        for (int i = 0; i < objectsToAdd.length; i++) {
            Map.Entry<Object, Object> currentObject = objectsToAdd[i];
            if (currentObject != REMOVED_MARKER) {
                Object initialElement = getViewElement(context, elementDescriptor, initial.get(currentObject.getKey()));
                actions.add((MapAction<Map<Object, Object>>) (MapAction<?>) new MapPutAction<>(currentObject.getKey(), currentObject.getValue(), initialElement));
            }
        }

        return actions;
    }

    protected List<MapAction<Map<Object, Object>>> determineCollectionActions(UpdateContext context, V initial, V current, EqualityChecker equalityChecker) {
        List<MapAction<Map<Object, Object>>> actions = new ArrayList<>();
        Map.Entry<Object, Object>[] objectsToAdd = (Map.Entry<Object, Object>[]) current.entrySet().toArray(new Map.Entry[current.size()]);

        if (initial != null && !initial.isEmpty()) {
            if (keyDescriptor.isSubview() && keyDescriptor.isIdentifiable()) {
                final AttributeAccessor subviewIdAccessor = keyDescriptor.getViewToEntityMapper().getViewIdAccessor();

                OUTER:
                for (Map.Entry<?, ?> entry : initial.entrySet()) {
                    Object initialObject = entry.getKey();
                    Object initialViewId = subviewIdAccessor.getValue(initialObject);
                    for (int i = 0; i < objectsToAdd.length; i++) {
                        Map.Entry<Object, Object> entryToAdd = objectsToAdd[i];
                        Object currentObject = entryToAdd.getKey();
                        if (currentObject != REMOVED_MARKER) {
                            Object currentViewId = subviewIdAccessor.getValue(currentObject);
                            if (initialViewId.equals(currentViewId)) {
                                objectsToAdd[i] = REMOVED_MARKER;
                                if (!equalityChecker.isEqual(context, entry.getValue(), entryToAdd.getValue())) {
                                    if (keyDescriptor.shouldFlushMutations()) {
                                        actions.add((MapAction<Map<Object, Object>>) (MapAction<?>) new MapRemoveAction<>(initialObject, (Map<Object, Object>) initial));
                                    }
                                    actions.add((MapAction<Map<Object, Object>>) (MapAction<?>) new MapPutAction<>(entryToAdd.getKey(), entryToAdd.getValue(), (Map<Object, Object>) initial));
                                }
                                continue OUTER;
                            }
                        }
                    }
                    actions.add((MapAction<Map<Object, Object>>) (MapAction<?>) new MapRemoveAction<>(initialObject, (Map<Object, Object>) initial));
                }
            } else {
                final BasicUserType<Object> basicUserType = keyDescriptor.getBasicUserType();

                OUTER:
                for (Map.Entry<?, ?> entry : initial.entrySet()) {
                    Object initialObject = entry.getKey();
                    for (int i = 0; i < objectsToAdd.length; i++) {
                        Map.Entry<Object, Object> entryToAdd = objectsToAdd[i];
                        Object currentObject = entryToAdd.getKey();
                        if (currentObject != REMOVED_MARKER) {
                            if (basicUserType.isEqual(initialObject, currentObject)) {
                                objectsToAdd[i] = REMOVED_MARKER;
                                if (!equalityChecker.isEqual(context, entry.getValue(), entryToAdd.getValue())) {
                                    if (keyDescriptor.shouldFlushMutations()) {
                                        actions.add((MapAction<Map<Object, Object>>) (MapAction<?>) new MapRemoveAction<>(initialObject, (Map<Object, Object>) initial));
                                    }
                                    actions.add((MapAction<Map<Object, Object>>) (MapAction<?>) new MapPutAction<>(entryToAdd.getKey(), entryToAdd.getValue(), (Map<Object, Object>) initial));
                                }
                                continue OUTER;
                            }
                        }
                    }
                    actions.add((MapAction<Map<Object, Object>>) (MapAction<?>) new MapRemoveAction<>(initialObject, (Map<Object, Object>) initial));
                }
            }
        }

        for (int i = 0; i < objectsToAdd.length; i++) {
            Map.Entry<Object, Object> currentObject = objectsToAdd[i];
            if (currentObject != REMOVED_MARKER) {
                actions.add((MapAction<Map<Object, Object>>) (MapAction<?>) new MapPutAction<>(currentObject.getKey(), currentObject.getValue(), (Map<Object, Object>) initial));
            }
        }

        return actions;
    }

    @Override
    protected CollectionElementAttributeFlusher<E, V> createPersistFlusher(TypeDescriptor typeDescriptor, Object element) {
        return new PersistCollectionElementAttributeFlusher<E, V>(element, optimisticLockProtected);
    }

    @Override
    protected CollectionElementAttributeFlusher<E, V> createMergeFlusher(TypeDescriptor typeDescriptor, Object element) {
        if (typeDescriptor == keyDescriptor) {
            return new MergeMapKeyAttributeFlusher<>(element, optimisticLockProtected);
        } else {
            return new MergeMapValueAttributeFlusher<E, V>(element, optimisticLockProtected);
        }
    }

    @Override
    protected List<CollectionElementAttributeFlusher<E, V>> getElementFlushers(UpdateContext context, V current, List<? extends MapAction<?>> actions) {
        List<CollectionElementAttributeFlusher<E, V>> elementFlushers = new ArrayList<>();
        if (determineElementFlushers(context, keyDescriptor, elementFlushers, current.keySet(), actions, current)) {
            return null;
        }
        if (determineElementFlushers(context, elementDescriptor, elementFlushers, current.values(), actions, current)) {
            return null;
        }

        return elementFlushers;
    }

    @Override
    protected MapAttributeFlusher<E, V> partialFlusher(boolean fetch, PluralFlushOperation operation, List<? extends MapAction<?>> collectionActions, List<CollectionElementAttributeFlusher<E, V>> elementFlushers) {
        return new MapAttributeFlusher<E, V>(this, fetch, false, operation, collectionActions, elementFlushers);
    }

    protected MapAttributeFlusher<E, V> partialFlusher(boolean fetch, boolean upsert, PluralFlushOperation operation, List<? extends MapAction<?>> collectionActions, List<CollectionElementAttributeFlusher<E, V>> elementFlushers) {
        return new MapAttributeFlusher<E, V>(this, fetch, false, operation, collectionActions, elementFlushers);
    }

    @Override
    protected boolean collectionEquals(V initial, V current) {
        if (initial == null || initial.size() != current.size()) {
            return false;
        }

        return initial.equals(current);
    }

    @Override
    protected DirtyAttributeFlusher<MapAttributeFlusher<E, V>, E, V> getDirtyFlusherForRecordingCollection(UpdateContext context, RecordingMap<?, ?, ?> collection) {
        if (collection.hasActions()) {
            List<? extends MapAction<?>> actions = collection.getActions();
            if (keyDescriptor.shouldFlushMutations() && !keyDescriptor.isIdentifiable()) {
                // When having non-identifiable mutable keys we can only replace
                return this;
            } else if (elementDescriptor.shouldFlushMutations()) {
                // When no mapper is given, we have basic types so we need to fetch and merge accordingly
                if (elementDescriptor.isBasic()) {
                    return this;
                }
                // We have to copy the actions as they will be mutated in getElementFlushers
                if (elementDescriptor.isSubview() && elementDescriptor.supportsDirtyCheck() && !elementDescriptor.isIdentifiable() && isIndexed()) {
                    actions = new ArrayList<>(actions);
                }
                List<CollectionElementAttributeFlusher<E, V>> elementFlushers = getElementFlushers(context, (V) collection, actions);
                // A "null" element flusher list is given when a fetch and compare is more appropriate
                if (elementFlushers == null) {
                    return this;
                } else if (!elementFlushers.isEmpty()) {
                    // We fetch here, because there is a high probability that elements we update were previously contained in the collection
                    // and also because we need a fetched collection to actually compute the diff for a proper merge
                    return partialFlusher(true, isUpsert(collection), PluralFlushOperation.COLLECTION_REPLAY_AND_ELEMENT, actions, elementFlushers);
                }
            }
            // Merging always requires figuring out the diff between collections
            // Maybe at some point we could issue a SQL MERGE statement to implement this, but for now we need to fetch
            return partialFlusher(true, isUpsert(collection), PluralFlushOperation.COLLECTION_REPLAY_ONLY, actions, Collections.<CollectionElementAttributeFlusher<E, V>>emptyList());
        }

        // If the elements are mutable, we always have to check the collection, so we load and compute diffs
        if (keyDescriptor.shouldFlushMutations() || elementDescriptor.shouldFlushMutations()) {
            // When no mapper is given, we have basic types so we need to fetch and merge accordingly
            if (keyDescriptor.shouldFlushMutations() && keyDescriptor.isBasic()
                    || elementDescriptor.shouldFlushMutations() && elementDescriptor.isBasic()) {
                return this;
            }
            return getElementOnlyFlusher(context, (V) collection);
        }

        // No outstanding actions and elements are not mutable, so we are done here
        return null;
    }

    @SuppressWarnings("unchecked")
    protected FusedMapActions getFusedOperations(List<? extends MapAction<?>> collectionActions) {
        return new FusedMapActions(keyDescriptor.getLoadOnlyViewToEntityMapper(), collectionActions);
    }

}
