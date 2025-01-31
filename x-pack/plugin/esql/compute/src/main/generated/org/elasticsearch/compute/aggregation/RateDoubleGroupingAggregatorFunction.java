// Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
// or more contributor license agreements. Licensed under the Elastic License
// 2.0; you may not use this file except in compliance with the Elastic License
// 2.0.
package org.elasticsearch.compute.aggregation;

import java.lang.Integer;
import java.lang.Override;
import java.lang.String;
import java.lang.StringBuilder;
import java.util.List;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.DoubleBlock;
import org.elasticsearch.compute.data.DoubleVector;
import org.elasticsearch.compute.data.ElementType;
import org.elasticsearch.compute.data.IntBlock;
import org.elasticsearch.compute.data.IntVector;
import org.elasticsearch.compute.data.LongBlock;
import org.elasticsearch.compute.data.LongVector;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.operator.DriverContext;

/**
 * {@link GroupingAggregatorFunction} implementation for {@link RateDoubleAggregator}.
 * This class is generated. Edit {@code GroupingAggregatorImplementer} instead.
 */
public final class RateDoubleGroupingAggregatorFunction implements GroupingAggregatorFunction {
  private static final List<IntermediateStateDesc> INTERMEDIATE_STATE_DESC = List.of(
      new IntermediateStateDesc("timestamps", ElementType.LONG),
      new IntermediateStateDesc("values", ElementType.DOUBLE),
      new IntermediateStateDesc("resets", ElementType.DOUBLE)  );

  private final RateDoubleAggregator.DoubleRateGroupingState state;

  private final List<Integer> channels;

  private final DriverContext driverContext;

  private final long unitInMillis;

  public RateDoubleGroupingAggregatorFunction(List<Integer> channels,
      RateDoubleAggregator.DoubleRateGroupingState state, DriverContext driverContext,
      long unitInMillis) {
    this.channels = channels;
    this.state = state;
    this.driverContext = driverContext;
    this.unitInMillis = unitInMillis;
  }

  public static RateDoubleGroupingAggregatorFunction create(List<Integer> channels,
      DriverContext driverContext, long unitInMillis) {
    return new RateDoubleGroupingAggregatorFunction(channels, RateDoubleAggregator.initGrouping(driverContext, unitInMillis), driverContext, unitInMillis);
  }

  public static List<IntermediateStateDesc> intermediateStateDesc() {
    return INTERMEDIATE_STATE_DESC;
  }

  @Override
  public int intermediateBlockCount() {
    return INTERMEDIATE_STATE_DESC.size();
  }

  @Override
  public GroupingAggregatorFunction.AddInput prepareProcessPage(SeenGroupIds seenGroupIds,
      Page page) {
    DoubleBlock valuesBlock = page.getBlock(channels.get(0));
    DoubleVector valuesVector = valuesBlock.asVector();
    LongBlock timestampsBlock = page.getBlock(channels.get(1));
    LongVector timestampsVector = timestampsBlock.asVector();
    if (timestampsVector == null)  {
      throw new IllegalStateException("expected @timestamp vector; but got a block");
    }
    if (valuesVector == null) {
      if (valuesBlock.mayHaveNulls()) {
        state.enableGroupIdTracking(seenGroupIds);
      }
      return new GroupingAggregatorFunction.AddInput() {
        @Override
        public void add(int positionOffset, IntBlock groupIds) {
          addRawInput(positionOffset, groupIds, valuesBlock, timestampsVector);
        }

        @Override
        public void add(int positionOffset, IntVector groupIds) {
          addRawInput(positionOffset, groupIds, valuesBlock, timestampsVector);
        }

        @Override
        public void close() {
        }
      };
    }
    return new GroupingAggregatorFunction.AddInput() {
      @Override
      public void add(int positionOffset, IntBlock groupIds) {
        addRawInput(positionOffset, groupIds, valuesVector, timestampsVector);
      }

      @Override
      public void add(int positionOffset, IntVector groupIds) {
        addRawInput(positionOffset, groupIds, valuesVector, timestampsVector);
      }

      @Override
      public void close() {
      }
    };
  }

  private void addRawInput(int positionOffset, IntVector groups, DoubleBlock values,
      LongVector timestamps) {
    for (int groupPosition = 0; groupPosition < groups.getPositionCount(); groupPosition++) {
      int groupId = groups.getInt(groupPosition);
      if (values.isNull(groupPosition + positionOffset)) {
        continue;
      }
      int valuesStart = values.getFirstValueIndex(groupPosition + positionOffset);
      int valuesEnd = valuesStart + values.getValueCount(groupPosition + positionOffset);
      for (int v = valuesStart; v < valuesEnd; v++) {
        RateDoubleAggregator.combine(state, groupId, timestamps.getLong(v), values.getDouble(v));
      }
    }
  }

  private void addRawInput(int positionOffset, IntVector groups, DoubleVector values,
      LongVector timestamps) {
    for (int groupPosition = 0; groupPosition < groups.getPositionCount(); groupPosition++) {
      int groupId = groups.getInt(groupPosition);
      var valuePosition = groupPosition + positionOffset;
      RateDoubleAggregator.combine(state, groupId, timestamps.getLong(valuePosition), values.getDouble(valuePosition));
    }
  }

  private void addRawInput(int positionOffset, IntBlock groups, DoubleBlock values,
      LongVector timestamps) {
    for (int groupPosition = 0; groupPosition < groups.getPositionCount(); groupPosition++) {
      if (groups.isNull(groupPosition)) {
        continue;
      }
      int groupStart = groups.getFirstValueIndex(groupPosition);
      int groupEnd = groupStart + groups.getValueCount(groupPosition);
      for (int g = groupStart; g < groupEnd; g++) {
        int groupId = groups.getInt(g);
        if (values.isNull(groupPosition + positionOffset)) {
          continue;
        }
        int valuesStart = values.getFirstValueIndex(groupPosition + positionOffset);
        int valuesEnd = valuesStart + values.getValueCount(groupPosition + positionOffset);
        for (int v = valuesStart; v < valuesEnd; v++) {
          RateDoubleAggregator.combine(state, groupId, timestamps.getLong(v), values.getDouble(v));
        }
      }
    }
  }

  private void addRawInput(int positionOffset, IntBlock groups, DoubleVector values,
      LongVector timestamps) {
    for (int groupPosition = 0; groupPosition < groups.getPositionCount(); groupPosition++) {
      if (groups.isNull(groupPosition)) {
        continue;
      }
      int groupStart = groups.getFirstValueIndex(groupPosition);
      int groupEnd = groupStart + groups.getValueCount(groupPosition);
      for (int g = groupStart; g < groupEnd; g++) {
        int groupId = groups.getInt(g);
        var valuePosition = groupPosition + positionOffset;
        RateDoubleAggregator.combine(state, groupId, timestamps.getLong(valuePosition), values.getDouble(valuePosition));
      }
    }
  }

  @Override
  public void selectedMayContainUnseenGroups(SeenGroupIds seenGroupIds) {
    state.enableGroupIdTracking(seenGroupIds);
  }

  @Override
  public void addIntermediateInput(int positionOffset, IntVector groups, Page page) {
    state.enableGroupIdTracking(new SeenGroupIds.Empty());
    assert channels.size() == intermediateBlockCount();
    Block timestampsUncast = page.getBlock(channels.get(0));
    if (timestampsUncast.areAllValuesNull()) {
      return;
    }
    LongBlock timestamps = (LongBlock) timestampsUncast;
    Block valuesUncast = page.getBlock(channels.get(1));
    if (valuesUncast.areAllValuesNull()) {
      return;
    }
    DoubleBlock values = (DoubleBlock) valuesUncast;
    Block resetsUncast = page.getBlock(channels.get(2));
    if (resetsUncast.areAllValuesNull()) {
      return;
    }
    DoubleVector resets = ((DoubleBlock) resetsUncast).asVector();
    assert timestamps.getPositionCount() == values.getPositionCount() && timestamps.getPositionCount() == resets.getPositionCount();
    for (int groupPosition = 0; groupPosition < groups.getPositionCount(); groupPosition++) {
      int groupId = groups.getInt(groupPosition);
      RateDoubleAggregator.combineIntermediate(state, groupId, timestamps, values, resets.getDouble(groupPosition + positionOffset), groupPosition + positionOffset);
    }
  }

  @Override
  public void addIntermediateRowInput(int groupId, GroupingAggregatorFunction input, int position) {
    if (input.getClass() != getClass()) {
      throw new IllegalArgumentException("expected " + getClass() + "; got " + input.getClass());
    }
    RateDoubleAggregator.DoubleRateGroupingState inState = ((RateDoubleGroupingAggregatorFunction) input).state;
    state.enableGroupIdTracking(new SeenGroupIds.Empty());
    RateDoubleAggregator.combineStates(state, groupId, inState, position);
  }

  @Override
  public void evaluateIntermediate(Block[] blocks, int offset, IntVector selected) {
    state.toIntermediate(blocks, offset, selected, driverContext);
  }

  @Override
  public void evaluateFinal(Block[] blocks, int offset, IntVector selected,
      DriverContext driverContext) {
    blocks[offset] = RateDoubleAggregator.evaluateFinal(state, selected, driverContext);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append(getClass().getSimpleName()).append("[");
    sb.append("channels=").append(channels);
    sb.append("]");
    return sb.toString();
  }

  @Override
  public void close() {
    state.close();
  }
}
