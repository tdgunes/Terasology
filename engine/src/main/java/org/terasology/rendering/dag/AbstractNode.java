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
import com.google.common.collect.Range;
import com.google.common.collect.Sets;

import java.util.List;
import java.util.Set;
import org.terasology.rendering.opengl.FBOManager;
import org.terasology.rendering.opengl.FrameBuffersManager;

/**
 * TODO: Add javadocs
 */
public abstract class AbstractNode implements Node {
    private Set<StateChange> desiredStateChanges;
    private NodeTask task;
    private RenderTaskListGenerator taskListGenerator;
    private List<Range<Integer>> rangeList;

    protected AbstractNode() {
        desiredStateChanges = Sets.newLinkedHashSet();
        rangeList = Lists.newArrayList();
    }

    // TODO: remove FrameBuffersManager from here
    protected void requireFBO(FrameBuffersManager frameBuffersManager, String identifier, FBOManager fboManager) {
        if (!frameBuffersManager.isFBOAvailable(identifier)) {
            frameBuffersManager.addDynamicFBO(identifier, fboManager);
        }
    }

    protected boolean addDesiredStateChange(StateChange stateChange) {
        return desiredStateChanges.add(stateChange);
    }

    protected boolean removeDesiredStateChange(StateChange stateChange) {
        return desiredStateChanges.remove(stateChange);
    }

    protected void refreshTaskList() {
        taskListGenerator.refresh();
    }

    public Set<StateChange> getDesiredStateChanges() {
        return desiredStateChanges;
    }

    public RenderPipelineTask generateTask() {
        if (task == null) {
            task = new NodeTask(this);
        }
        return task;
    }

    @Override
    public String toString() {
        return this.getClass().getSimpleName();
    }

    public void setTaskListGenerator(RenderTaskListGenerator taskListGenerator) {
        this.taskListGenerator = taskListGenerator;
    }
}
