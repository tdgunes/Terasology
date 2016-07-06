/*
 * Copyright 2016 MovingBlocks
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
package org.terasology.rendering.dag;

import com.google.common.collect.Lists;
import java.util.List;

/**
 *
 */
public class RenderGraph { // TODO: add extends DirectedAcyclicGraph<Node>
    private List<Node> nodes;

    public RenderGraph() {
        nodes = Lists.newArrayList();
    }

    public String add(Node node, String suggestedId) {
        nodes.add(node);
        return null; // TODO: for instance if "blur" present make id "blur1" and return it
    }

    public String add(Node node) {
        nodes.add(node);
        return null; // TODO: returns a unique id
    }

    // TODO: add remove, get, addEdge, removeEdge methods here

    public List<Node> getNodesInTopologicalOrder() {
        return nodes;
    }
}
