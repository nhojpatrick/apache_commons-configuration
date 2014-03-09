/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.configuration.tree;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * <p>
 * An internally used helper class for an atomic update of an
 * {@link InMemoryNodeModel}.
 * </p>
 * <p>
 * This class performs updates on the node structure of a node model consisting
 * of {@link ImmutableNode} objects. Because the nodes themselves cannot be
 * changed updates are achieved by replacing parts of the structure with new
 * nodes; the new nodes are copies of original nodes with the corresponding
 * manipulations applied. Therefore, each update of a node in the structure
 * results in a new structure in which the affected node is replaced by a new
 * one, and this change bubbles up to the root node (because all parent nodes
 * have to be replaced by instances with an updated child reference).
 * </p>
 * <p>
 * A single update of a model may consist of multiple changes on nodes. For
 * instance, a remove property operation can include many nodes. There are some
 * reasons why such updates should be handled in a single "transaction" rather
 * than executing them on altered node structures one by one:
 * <ul>
 * <li>An operation is typically executed on a set of source nodes from the
 * original node hierarchy. While manipulating nodes, nodes of this set may be
 * replaced by new ones. The handling of these replacements complicates things a
 * lot.</li>
 * <li>Performing all updates one after the other may cause more updates of
 * nodes than necessary. Nodes near to the root node always have to be replaced
 * when a child of them gets manipulated. If all these updates are deferred and
 * handled in a single transaction, the resulting operation is more efficient.</li>
 * </ul>
 * </p>
 *
 * @version $Id$
 */
class ModelTransaction
{
    /**
     * Constant for the maximum number of entries in the replacement mapping. If
     * this number is exceeded, the parent mapping is reconstructed. The number
     * is a bit arbitrary. If it is too low, updates - especially on large node
     * structures - are expensive because the parent mapping is often rebuild.
     * If it is too big, read access to the model is slowed down because looking
     * up the parent of a node is more complicated.
     */
    private static final int MAX_REPLACEMENTS = 200;

    /** Constant for an unknown level. */
    private static final int LEVEL_UNKNOWN = -1;

    /** Stores the current tree data of the calling node model. */
    private final TreeData currentData;

    /** The root node for query operations. */
    private final ImmutableNode queryRoot;

    /** The {@code NodeKeyResolver} to be used for this transaction. */
    private final NodeKeyResolver<ImmutableNode> resolver;

    /** A new replacement mapping. */
    private final Map<ImmutableNode, ImmutableNode> replacedNodes;

    /** A new parent mapping. */
    private final Map<ImmutableNode, ImmutableNode> parentMapping;

    /** A collection with nodes which have been added. */
    private final Collection<ImmutableNode> addedNodes;

    /** A collection with nodes which have been removed. */
    private final Collection<ImmutableNode> removedNodes;

    /**
     * Stores the operations to be executed during this transaction. The map is
     * sorted by the levels of the nodes to be manipulated: Operations on nodes
     * down in the hierarchy are executed first because they affect the nodes
     * closer to the root.
     */
    private final SortedMap<Integer, Map<ImmutableNode, Operations>> operations;

    /** The new root node. */
    private ImmutableNode newRoot;

    /**
     * Creates a new instance of {@code ModelTransaction} for the current tree
     * data.
     *
     * @param treeData the current {@code TreeData} structure to operate on
     * @param selector an optional {@code NodeSelector} defining the target root
     *        node for this transaction; this can be used to perform operations
     *        on tracked nodes
     * @param resolver the {@code NodeKeyResolver}
     */
    public ModelTransaction(TreeData treeData, NodeSelector selector,
            NodeKeyResolver<ImmutableNode> resolver)
    {
        currentData = treeData;
        this.resolver = resolver;
        replacedNodes = getCurrentData().copyReplacementMapping();
        parentMapping = getCurrentData().copyParentMapping();
        operations = new TreeMap<Integer, Map<ImmutableNode, Operations>>();
        addedNodes = new LinkedList<ImmutableNode>();
        removedNodes = new LinkedList<ImmutableNode>();
        queryRoot = initQueryRoot(treeData, selector);
    }

    /**
     * Returns the {@code NodeKeyResolver} used by this transaction.
     *
     * @return the {@code NodeKeyResolver}
     */
    public NodeKeyResolver<ImmutableNode> getResolver()
    {
        return resolver;
    }

    /**
     * Returns the root node to be used within queries. This is not necessarily
     * the current root node of the model. If the operation is executed on a
     * tracked node, this node has to be passed as root nodes to the expression
     * engine.
     *
     * @return the root node for queries and calls to the expression engine
     */
    public ImmutableNode getQueryRoot()
    {
        return queryRoot;
    }

    /**
     * Adds an operation for adding a number of new children to a given parent
     * node.
     *
     * @param parent the parent node
     * @param newNodes the collection of new child nodes
     */
    public void addAddNodesOperation(ImmutableNode parent,
            Collection<? extends ImmutableNode> newNodes)
    {
        ChildrenUpdateOperation op = new ChildrenUpdateOperation();
        op.addNewNodes(newNodes);
        fetchOperations(parent, LEVEL_UNKNOWN).addChildrenOperation(op);
    }

    /**
     * Adds an operation for adding a new child to a given parent node.
     *
     * @param parent the parent node
     * @param newChild the new child to be added
     */
    public void addAddNodeOperation(ImmutableNode parent, ImmutableNode newChild)
    {
        ChildrenUpdateOperation op = new ChildrenUpdateOperation();
        op.addNewNode(newChild);
        fetchOperations(parent, LEVEL_UNKNOWN).addChildrenOperation(op);
    }

    /**
     * Adds an operation for adding an attribute to a target node.
     *
     * @param target the target node
     * @param name the name of the attribute
     * @param value the value of the attribute
     */
    public void addAttributeOperation(ImmutableNode target, String name,
            Object value)
    {
        fetchOperations(target, LEVEL_UNKNOWN).addOperation(
                new AddAttributeOperation(name, value));
    }

    /**
     * Adds an operation for removing a child node of a given node.
     *
     * @param parent the parent node
     * @param node the child node to be removed
     */
    public void addRemoveNodeOperation(ImmutableNode parent, ImmutableNode node)
    {
        ChildrenUpdateOperation op = new ChildrenUpdateOperation();
        op.addNodeToRemove(node);
        fetchOperations(parent, LEVEL_UNKNOWN).addChildrenOperation(op);
    }

    /**
     * Adds an operation for removing an attribute from a target node.
     *
     * @param target the target node
     * @param name the name of the attribute
     */
    public void addRemoveAttributeOperation(ImmutableNode target, String name)
    {
        fetchOperations(target, LEVEL_UNKNOWN).addOperation(
                new RemoveAttributeOperation(name));
    }

    /**
     * Adds an operation for clearing the value of a target node.
     *
     * @param target the target node
     */
    public void addClearNodeValueOperation(ImmutableNode target)
    {
        addChangeNodeValueOperation(target, null);
    }

    /**
     * Adds an operation for changing the value of a target node.
     *
     * @param target the target node
     * @param newValue the new value for this node
     */
    public void addChangeNodeValueOperation(ImmutableNode target,
            Object newValue)
    {
        fetchOperations(target, LEVEL_UNKNOWN).addOperation(
                new ChangeNodeValueOperation(newValue));
    }

    /**
     * Executes this transaction resulting in a new {@code TreeData} object. The
     * object returned by this method serves as the definition of a new node
     * structure for the calling model.
     *
     * @return the updated {@code TreeData}
     */
    public TreeData execute()
    {
        executeOperations();
        updateParentMapping();
        return new TreeData(newRoot, parentMapping,
                replacedNodes, currentData.getNodeTracker().update(newRoot,
                        getResolver(), getCurrentData()));
    }

    /**
     * Returns the current {@code TreeData} object this transaction operates on.
     * @return the associated {@code TreeData} object
     */
    public TreeData getCurrentData() {
        return currentData;
    }

    /**
     * Returns the parent node of the given node.
     *
     * @param node the node in question
     * @return the parent of this node
     */
    ImmutableNode getParent(ImmutableNode node)
    {
        return getCurrentData().getParent(node);
    }

    /**
     * Obtains the {@code Operations} object for manipulating the specified
     * node. If no such object exists yet, it is created. The level can be
     * undefined, then it is determined based on the target node.
     *
     * @param target the target node
     * @param level the level of the target node (may be undefined)
     * @return the {@code Operations} object for this node
     */
    Operations fetchOperations(ImmutableNode target, int level)
    {
        Integer nodeLevel =
                Integer.valueOf((level == LEVEL_UNKNOWN) ? level(target)
                        : level);
        Map<ImmutableNode, Operations> levelOperations =
                operations.get(nodeLevel);
        if (levelOperations == null)
        {
            levelOperations = new HashMap<ImmutableNode, Operations>();
            operations.put(nodeLevel, levelOperations);
        }
        Operations ops = levelOperations.get(target);
        if (ops == null)
        {
            ops = new Operations();
            levelOperations.put(target, ops);
        }
        return ops;
    }

    /**
     * Initializes the root node to be used within queries. If a tracked node
     * selector is provided, this node becomes the root node. Otherwise, the
     * actual root node is used.
     *
     * @param treeData the current data of the model
     * @param selector an optional {@code NodeSelector} defining the target root
     * @return the query root node for this transaction
     */
    private ImmutableNode initQueryRoot(TreeData treeData, NodeSelector selector)
    {
        return (selector == null) ? treeData.getRootNode() : treeData
                .getNodeTracker().getTrackedNode(selector);
    }

    /**
     * Determines the level of the specified node in the current hierarchy. The
     * level of the root node is 0, the children of the root have level 1 and so
     * on.
     *
     * @param node the node in question
     * @return the level of this node
     */
    private int level(ImmutableNode node)
    {
        ImmutableNode current = getCurrentData().getParent(node);
        int level = 0;
        while (current != null)
        {
            level++;
            current = getCurrentData().getParent(current);
        }
        return level;
    }

    /**
     * Executes all operations in this transaction.
     */
    private void executeOperations()
    {
        while (!operations.isEmpty())
        {
            Integer level = operations.lastKey(); // start down in hierarchy
            Map<ImmutableNode, Operations> levelOps = operations.remove(level);
            for (Map.Entry<ImmutableNode, Operations> e : levelOps.entrySet())
            {
                e.getValue().apply(e.getKey(), level);
            }
        }
    }

    /**
     * Updates the parent mapping for the resulting {@code TreeData} instance.
     * This method is called after all update operations have been executed. It
     * ensures that the parent mapping is updated for the changes on the nodes
     * structure.
     */
    private void updateParentMapping()
    {
        if (replacedNodes.size() > MAX_REPLACEMENTS)
        {
            rebuildParentMapping();
        }
        else
        {
            updateParentMappingForAddedNodes();
            updateParentMappingForRemovedNodes();
        }
    }

    /**
     * Rebuilds the parent mapping from scratch. This method is called if the
     * replacement mapping exceeds its maximum size. In this case, it is
     * cleared, and a new parent mapping is constructed for the new root node.
     */
    private void rebuildParentMapping()
    {
        replacedNodes.clear();
        parentMapping.clear();
        InMemoryNodeModel.updateParentMapping(parentMapping, newRoot);
    }

    /**
     * Adds newly added nodes and their children to the parent mapping.
     */
    private void updateParentMappingForAddedNodes()
    {
        for (ImmutableNode node : addedNodes)
        {
            InMemoryNodeModel.updateParentMapping(parentMapping, node);
        }
    }

    /**
     * Removes nodes that have been removed during this transaction from the
     * parent and replacement mappings.
     */
    private void updateParentMappingForRemovedNodes()
    {
        for (ImmutableNode node : removedNodes)
        {
            removeNodesFromParentAndReplacementMapping(node);
        }
    }

    /**
     * Removes a node and its children (recursively) from the parent and the
     * replacement mappings.
     *
     * @param root the root of the subtree to be removed
     */
    private void removeNodesFromParentAndReplacementMapping(ImmutableNode root)
    {
        NodeTreeWalker.INSTANCE.walkBFS(root,
                new ConfigurationNodeVisitorAdapter<ImmutableNode>()
                {
                    @Override
                    public void visitBeforeChildren(ImmutableNode node,
                            NodeHandler<ImmutableNode> handler)
                    {
                        parentMapping.remove(node);
                        removeNodeFromReplacementMapping(node);
                    }
                }, getCurrentData());
    }

    /**
     * Removes the specified node completely from the replacement mapping. This
     * also includes the nodes that replace the given one.
     *
     * @param node the node to be removed
     */
    private void removeNodeFromReplacementMapping(ImmutableNode node)
    {
        ImmutableNode replacement = node;
        do
        {
            replacement = replacedNodes.remove(replacement);
        } while (replacement != null);
    }

    /**
     * Constructs the concatenation of two collections. Both can be null.
     *
     * @param col1 the first collection
     * @param col2 the second collection
     * @param <E> the type of the elements involved
     * @return the resulting collection
     */
    private static <E> Collection<E> concatenate(Collection<E> col1,
            Collection<? extends E> col2)
    {
        if (col2 == null)
        {
            return col1;
        }

        Collection<E> result =
                (col1 != null) ? col1 : new ArrayList<E>(col2.size());
        result.addAll(col2);
        return result;
    }

    /**
     * Constructs the concatenation of two sets. Both can be null.
     *
     * @param set1 the first set
     * @param set2 the second set
     * @param <E> the type of the elements involved
     * @return the resulting set
     */
    private static <E> Set<E> concatenate(Set<E> set1, Set<? extends E> set2)
    {
        if (set2 == null)
        {
            return set1;
        }

        Set<E> result = (set1 != null) ? set1 : new HashSet<E>();
        result.addAll(set2);
        return result;
    }

    /**
     * Constructs the concatenation of two maps. Both can be null.
     *
     * @param map1 the first map
     * @param map2 the second map
     * @param <K> the type of the keys
     * @param <V> the type of the values
     * @return the resulting map
     */
    private static <K, V> Map<K, V> concatenate(Map<K, V> map1,
            Map<? extends K, ? extends V> map2)
    {
        if (map2 == null)
        {
            return map1;
        }

        Map<K, V> result = (map1 != null) ? map1 : new HashMap<K, V>();
        result.putAll(map2);
        return result;
    }

    /**
     * Appends a single element to a collection. The collection may be null,
     * then it is created.
     *
     * @param col the collection
     * @param node the element to be added
     * @param <E> the type of elements involved
     * @return the resulting collection
     */
    private static <E> Collection<E> append(Collection<E> col, E node)
    {
        Collection<E> result = (col != null) ? col : new LinkedList<E>();
        result.add(node);
        return result;
    }

    /**
     * Appends a single element to a set. The set may be null then it is
     * created.
     *
     * @param col the set
     * @param elem the element to be added
     * @param <E> the type of the elements involved
     * @return the resulting set
     */
    private static <E> Set<E> append(Set<E> col, E elem)
    {
        Set<E> result = (col != null) ? col : new HashSet<E>();
        result.add(elem);
        return result;
    }

    /**
     * Adds a single key-value pair to a map. The map may be null, then it is
     * created.
     *
     * @param map the map
     * @param key the key
     * @param value the value
     * @param <K> the type of the key
     * @param <V> the type of the value
     * @return the resulting map
     */
    private static <K, V> Map<K, V> append(Map<K, V> map, K key, V value)
    {
        Map<K, V> result = (map != null) ? map : new HashMap<K, V>();
        result.put(key, value);
        return result;
    }

    /**
     * An abstract base class representing an operation to be performed on a
     * node. Concrete subclasses implement specific update operations.
     */
    private abstract class Operation
    {
        /**
         * Executes this operation on the provided target node returning the
         * result.
         *
         * @param target the target node for this operation
         * @param operations the current {@code Operations} instance
         * @return the manipulated node
         */
        protected abstract ImmutableNode apply(ImmutableNode target,
                Operations operations);
    }

    /**
     * A specialized {@code Operation} implementation for replacing the children
     * of a target node. All other properties are not touched. With this
     * operation single children of a node can be altered or removed; new
     * children can be added. This operation is frequently used because each
     * update of a node causes updates of the children of all parent nodes.
     * Therefore, it is treated in a special way and allows adding further sub
     * operations dynamically.
     */
    private class ChildrenUpdateOperation extends Operation
    {
        /** A collection with new nodes to be added. */
        private Collection<ImmutableNode> newNodes;

        /** A collection with nodes to be removed. */
        private Set<ImmutableNode> nodesToRemove;

        /**
         * A map with nodes to be replaced by others. The keys are the nodes to
         * be replaced, the values the replacements.
         */
        private Map<ImmutableNode, ImmutableNode> nodesToReplace;

        /**
         * Adds all operations defined by the specified object to this instance.
         *
         * @param op the operation to be combined
         */
        public void combine(ChildrenUpdateOperation op)
        {
            newNodes = concatenate(newNodes, op.newNodes);
            nodesToReplace = concatenate(nodesToReplace, op.nodesToReplace);
            nodesToRemove = concatenate(nodesToRemove, op.nodesToRemove);
        }

        /**
         * Adds a node to be added to the target of the operation.
         *
         * @param node the new node to be added
         */
        public void addNewNode(ImmutableNode node)
        {
            newNodes = append(newNodes, node);
        }

        /**
         * Adds a collection of nodes to be added to the target of the
         * operation.
         *
         * @param nodes the collection with new nodes
         */
        public void addNewNodes(Collection<? extends ImmutableNode> nodes)
        {
            newNodes = concatenate(newNodes, nodes);
        }

        /**
         * Adds a node for a replacement operation. The original node is going
         * to be replaced by its replacement.
         *
         * @param org the original node
         * @param replacement the replacement node
         */
        public void addNodeToReplace(ImmutableNode org,
                ImmutableNode replacement)
        {
            nodesToReplace = append(nodesToReplace, org, replacement);
        }

        /**
         * Adds a node for a remove operation. This child node is going to be
         * removed from its parent.
         *
         * @param node the child node to be removed
         */
        public void addNodeToRemove(ImmutableNode node)
        {
            nodesToRemove = append(nodesToRemove, node);
        }

        /**
         * {@inheritDoc} This implementation applies changes on the children of
         * the passed in target node according to its configuration: new nodes
         * are added, replacements are performed, and nodes no longer needed are
         * removed.
         */
        @Override
        protected ImmutableNode apply(ImmutableNode target,
                Operations operations)
        {
            Map<ImmutableNode, ImmutableNode> replacements =
                    fetchReplacementMap();
            Set<ImmutableNode> removals = fetchRemovalSet();
            List<ImmutableNode> resultNodes = new LinkedList<ImmutableNode>();

            for (ImmutableNode nd : target.getChildren())
            {
                ImmutableNode repl = replacements.get(nd);
                if (repl != null)
                {
                    resultNodes.add(repl);
                    replacedNodes.put(nd, repl);
                }
                else
                {
                    if (removals.contains(nd))
                    {
                        removedNodes.add(nd);
                    }
                    else
                    {
                        resultNodes.add(nd);
                    }
                }
            }

            concatenate(resultNodes, newNodes);
            operations.newNodesAdded(newNodes);
            return target.replaceChildren(resultNodes);
        }

        /**
         * Obtains the map with replacement nodes. If no replacements are
         * defined, an empty map is returned.
         *
         * @return the map with replacement nodes
         */
        private Map<ImmutableNode, ImmutableNode> fetchReplacementMap()
        {
            return (nodesToReplace != null) ? nodesToReplace : Collections
                    .<ImmutableNode, ImmutableNode> emptyMap();
        }

        /**
         * Returns a set with nodes to be removed. If no remove operations are
         * pending, an empty set is returned.
         *
         * @return the set with nodes to be removed
         */
        private Set<ImmutableNode> fetchRemovalSet()
        {
            return (nodesToRemove != null) ? nodesToRemove : Collections
                    .<ImmutableNode> emptySet();
        }
    }

    /**
     * A specialized operation class for adding an attribute to a target node.
     */
    private class AddAttributeOperation extends Operation
    {
        /** The attribute name. */
        private final String attributeName;

        /** The attribute value. */
        private final Object attributeValue;

        /**
         * Creates a new instance of {@code AddAttributeOperation}.
         *
         * @param name the name of the attribute
         * @param value the value of the attribute
         */
        public AddAttributeOperation(String name, Object value)
        {
            attributeName = name;
            attributeValue = value;
        }

        @Override
        protected ImmutableNode apply(ImmutableNode target,
                Operations operations)
        {
            return target.setAttribute(attributeName, attributeValue);
        }
    }

    /**
     * A specialized operation class for removing an attribute from a target
     * node.
     */
    private class RemoveAttributeOperation extends Operation
    {
        /** The attribute name. */
        private final String attributeName;

        /**
         * Creates a new instance of {@code RemoveAttributeOperation}.
         *
         * @param name the name of the attribute
         */
        public RemoveAttributeOperation(String name)
        {
            attributeName = name;
        }

        @Override
        protected ImmutableNode apply(ImmutableNode target,
                Operations operations)
        {
            return target.removeAttribute(attributeName);
        }
    }

    /**
     * A specialized operation class which changes the value of a node.
     */
    private class ChangeNodeValueOperation extends Operation
    {
        /** The new value for the affected node. */
        private final Object newValue;

        /**
         * Creates a new instance of {@code ChangeNodeValueOperation} and
         * initializes it with the new value to set for the node.
         *
         * @param value the new node value
         */
        public ChangeNodeValueOperation(Object value)
        {
            newValue = value;
        }

        @Override
        protected ImmutableNode apply(ImmutableNode target,
                Operations operations)
        {
            return target.setValue(newValue);
        }
    }

    /**
     * A helper class which collects multiple update operations to be executed
     * on a single node.
     */
    private class Operations
    {
        /** An operation for manipulating child nodes. */
        private ChildrenUpdateOperation childrenOperation;

        /**
         * A collection for the other operations to be performed on the target
         * node.
         */
        private Collection<Operation> operations;

        /** A collection with nodes added by an operation. */
        private Collection<ImmutableNode> addedNodesInOperation;

        /**
         * Adds an operation which manipulates children.
         *
         * @param co the operation
         */
        public void addChildrenOperation(ChildrenUpdateOperation co)
        {
            if (childrenOperation == null)
            {
                childrenOperation = co;
            }
            else
            {
                childrenOperation.combine(co);
            }
        }

        /**
         * Adds an operation.
         *
         * @param op the operation
         */
        public void addOperation(Operation op)
        {
            operations = append(operations, op);
        }

        /**
         * Notifies this object that new nodes have been added by a sub
         * operation. It has to be ensured that these nodes are added to the
         * parent mapping.
         *
         * @param newNodes the collection of newly added nodes
         */
        public void newNodesAdded(Collection<ImmutableNode> newNodes)
        {
            addedNodesInOperation =
                    concatenate(addedNodesInOperation, newNodes);
        }

        /**
         * Executes all operations stored in this object on the given target
         * node. The resulting node then has to be integrated in the current
         * node hierarchy. Unless the root node is already reached, this causes
         * another updated operation to be created which replaces the
         * manipulated child in the parent node.
         *
         * @param target the target node for this operation
         * @param level the level of the target node
         */
        public void apply(ImmutableNode target, int level)
        {
            ImmutableNode node = target;
            if (childrenOperation != null)
            {
                node = childrenOperation.apply(node, this);
            }

            if (operations != null)
            {
                for (Operation op : operations)
                {
                    node = op.apply(node, this);
                }
            }

            handleAddedNodes(node);
            if (level == 0)
            {
                // reached the root node
                newRoot = node;
                replacedNodes.put(target, node);
            }
            else
            {
                // propagate change
                propagateChange(target, node, level);
            }
        }

        /**
         * Propagates the changes on the target node to the next level above of
         * the hierarchy. If the updated node is no longer defined, it can even
         * be removed from its parent. Otherwise, it is just replaced.
         *
         * @param target the target node for this operation
         * @param node the resulting node after applying all operations
         * @param level the level of the target node
         */
        private void propagateChange(ImmutableNode target, ImmutableNode node,
                int level)
        {
            ImmutableNode parent = getParent(target);
            ChildrenUpdateOperation co = new ChildrenUpdateOperation();
            if (InMemoryNodeModel.checkIfNodeDefined(node))
            {
                co.addNodeToReplace(target, node);
            }
            else
            {
                co.addNodeToRemove(target);
            }
            fetchOperations(parent, level - 1).addChildrenOperation(co);
        }

        /**
         * Checks whether new nodes have been added during operation execution.
         * If so, the parent mapping has to be updated.
         *
         * @param node the resulting node after applying all operations
         */
        private void handleAddedNodes(ImmutableNode node)
        {
            if (addedNodesInOperation != null)
            {
                for (ImmutableNode child : addedNodesInOperation)
                {
                    parentMapping.put(child, node);
                    addedNodes.add(child);
                }
            }
        }
    }
}
