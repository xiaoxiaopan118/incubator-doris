// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.nereids.rules.exploration.join;

import org.apache.doris.nereids.trees.expressions.ExprId;
import org.apache.doris.nereids.trees.expressions.Expression;
import org.apache.doris.nereids.trees.expressions.NamedExpression;
import org.apache.doris.nereids.trees.expressions.Slot;
import org.apache.doris.nereids.trees.plans.GroupPlan;
import org.apache.doris.nereids.trees.plans.Plan;
import org.apache.doris.nereids.trees.plans.logical.LogicalJoin;
import org.apache.doris.nereids.trees.plans.logical.LogicalProject;
import org.apache.doris.nereids.util.Utils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Common
 */
class JoinReorderUtils {
    static boolean isAllSlotProject(LogicalProject<LogicalJoin<GroupPlan, GroupPlan>> project) {
        return project.getProjects().stream().allMatch(expr -> expr instanceof Slot);
    }

    /**
     * Split project according to whether namedExpr contains by splitChildExprIds.
     * Notice: projects must all be Slot.
     */
    static Map<Boolean, List<NamedExpression>> splitProject(List<NamedExpression> projects,
            Set<ExprId> splitChildExprIds) {
        return projects.stream()
                .collect(Collectors.partitioningBy(expr -> {
                    Slot slot = (Slot) expr;
                    return splitChildExprIds.contains(slot.getExprId());
                }));
    }

    /**
     * If projects is empty or project output equal plan output, return the original plan.
     */
    public static Plan projectOrSelf(List<NamedExpression> projects, Plan plan) {
        Set<Slot> outputSet = plan.getOutputSet();
        if (projects.isEmpty() || (outputSet.size() == projects.size() && outputSet.containsAll(projects))) {
            return plan;
        }
        return new LogicalProject<>(projects, plan);
    }

    public static Plan projectOrSelfInOrder(List<NamedExpression> projects, Plan plan) {
        if (projects.isEmpty() || projects.equals(plan.getOutput())) {
            return plan;
        }
        return new LogicalProject<>(projects, plan);
    }

    /**
     * When project not empty, we add all slots used by hashOnCondition into projects.
     */
    public static void addSlotsUsedByOn(Set<Slot> usedSlots, List<NamedExpression> projects) {
        if (projects.isEmpty()) {
            return;
        }
        Set<ExprId> projectExprIdSet = projects.stream()
                .map(NamedExpression::getExprId)
                .collect(Collectors.toSet());
        usedSlots.forEach(slot -> {
            if (!projectExprIdSet.contains(slot.getExprId())) {
                projects.add(slot);
            }
        });
    }

    public static Set<Slot> joinChildConditionSlots(LogicalJoin<? extends Plan, ? extends Plan> join, boolean left) {
        Set<Slot> childSlots = left ? join.left().getOutputSet() : join.right().getOutputSet();
        return join.getConditionSlot().stream()
                .filter(childSlots::contains)
                .collect(Collectors.toSet());
    }

    public static Plan newProject(Set<ExprId> requiredExprIds, Plan plan) {
        List<NamedExpression> projects = plan.getOutput().stream()
                .filter(namedExpr -> requiredExprIds.contains(namedExpr.getExprId()))
                .collect(Collectors.toList());
        return new LogicalProject<>(projects, plan);
    }

    public static Map<Boolean, List<Expression>> splitConjuncts(List<Expression> topConjuncts,
            List<Expression> bottomConjuncts, Set<ExprId> bExprIdSet) {
        // top: (A B)(error) (A C) (B C) (A B C)
        // Split topJoin Condition to two part according to include B.
        Map<Boolean, List<Expression>> splitOn = topConjuncts.stream()
                .collect(Collectors.partitioningBy(topHashOn -> {
                    Set<ExprId> usedExprIds = topHashOn.getInputSlotExprIds();
                    return Utils.isIntersecting(usedExprIds, bExprIdSet);
                }));
        // * don't include B, just include (A C)
        // we add it into newBottomJoin HashConjuncts.
        // * include B, include (A B C) or (A B)
        // we add it into newTopJoin HashConjuncts.
        List<Expression> newTopHashConjuncts = splitOn.get(true);
        newTopHashConjuncts.addAll(bottomConjuncts);

        return splitOn;
    }
}
