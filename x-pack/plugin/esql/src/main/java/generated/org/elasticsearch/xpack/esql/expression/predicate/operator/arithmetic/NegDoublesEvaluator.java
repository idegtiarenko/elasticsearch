// Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
// or more contributor license agreements. Licensed under the Elastic License
// 2.0; you may not use this file except in compliance with the Elastic License
// 2.0.
package org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic;

import java.lang.Override;
import java.lang.String;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.DoubleBlock;
import org.elasticsearch.compute.data.DoubleVector;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.operator.DriverContext;
import org.elasticsearch.compute.operator.EvalOperator;
import org.elasticsearch.core.Releasables;

/**
 * {@link EvalOperator.ExpressionEvaluator} implementation for {@link Neg}.
 * This class is generated. Do not edit it.
 */
public final class NegDoublesEvaluator implements EvalOperator.ExpressionEvaluator {
  private final EvalOperator.ExpressionEvaluator v;

  private final DriverContext driverContext;

  public NegDoublesEvaluator(EvalOperator.ExpressionEvaluator v, DriverContext driverContext) {
    this.v = v;
    this.driverContext = driverContext;
  }

  @Override
  public Block.Ref eval(Page page) {
    try (Block.Ref vRef = v.eval(page)) {
      if (vRef.block().areAllValuesNull()) {
        return Block.Ref.floating(Block.constantNullBlock(page.getPositionCount(), driverContext.blockFactory()));
      }
      DoubleBlock vBlock = (DoubleBlock) vRef.block();
      DoubleVector vVector = vBlock.asVector();
      if (vVector == null) {
        return Block.Ref.floating(eval(page.getPositionCount(), vBlock));
      }
      return Block.Ref.floating(eval(page.getPositionCount(), vVector).asBlock());
    }
  }

  public DoubleBlock eval(int positionCount, DoubleBlock vBlock) {
    try(DoubleBlock.Builder result = DoubleBlock.newBlockBuilder(positionCount, driverContext.blockFactory())) {
      position: for (int p = 0; p < positionCount; p++) {
        if (vBlock.isNull(p) || vBlock.getValueCount(p) != 1) {
          result.appendNull();
          continue position;
        }
        result.appendDouble(Neg.processDoubles(vBlock.getDouble(vBlock.getFirstValueIndex(p))));
      }
      return result.build();
    }
  }

  public DoubleVector eval(int positionCount, DoubleVector vVector) {
    try(DoubleVector.Builder result = DoubleVector.newVectorBuilder(positionCount, driverContext.blockFactory())) {
      position: for (int p = 0; p < positionCount; p++) {
        result.appendDouble(Neg.processDoubles(vVector.getDouble(p)));
      }
      return result.build();
    }
  }

  @Override
  public String toString() {
    return "NegDoublesEvaluator[" + "v=" + v + "]";
  }

  @Override
  public void close() {
    Releasables.closeExpectNoException(v);
  }
}
