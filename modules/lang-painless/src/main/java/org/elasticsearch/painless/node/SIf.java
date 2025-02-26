/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.painless.node;

import org.elasticsearch.painless.Location;
import org.elasticsearch.painless.phase.UserTreeVisitor;

import java.util.Objects;

/**
 * Represents an if block.
 */
public class SIf extends AStatement {

    private final AExpression conditionNode;
    private final SBlock ifBlockNode;

    public SIf(int identifier, Location location, AExpression conditionNode, SBlock ifBlockNode) {
        super(identifier, location);

        this.conditionNode = Objects.requireNonNull(conditionNode);
        this.ifBlockNode = ifBlockNode;
    }

    public AExpression getConditionNode() {
        return conditionNode;
    }

    public SBlock getIfBlockNode() {
        return ifBlockNode;
    }

    @Override
    public <Scope> void visit(UserTreeVisitor<Scope> userTreeVisitor, Scope scope) {
        userTreeVisitor.visitIf(this, scope);
    }

    @Override
    public <Scope> void visitChildren(UserTreeVisitor<Scope> userTreeVisitor, Scope scope) {
        conditionNode.visit(userTreeVisitor, scope);

        if (ifBlockNode != null) {
            ifBlockNode.visit(userTreeVisitor, scope);
        }
    }
}
