/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.ql.optimizer.stats.annotation;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.conf.HiveConf.ConfVars;
import org.apache.hadoop.hive.ql.ErrorMsg;
import org.apache.hadoop.hive.ql.exec.ColumnInfo;
import org.apache.hadoop.hive.ql.exec.CommonJoinOperator;
import org.apache.hadoop.hive.ql.exec.DemuxOperator;
import org.apache.hadoop.hive.ql.exec.FilterOperator;
import org.apache.hadoop.hive.ql.exec.GroupByOperator;
import org.apache.hadoop.hive.ql.exec.LimitOperator;
import org.apache.hadoop.hive.ql.exec.Operator;
import org.apache.hadoop.hive.ql.exec.ReduceSinkOperator;
import org.apache.hadoop.hive.ql.exec.RowSchema;
import org.apache.hadoop.hive.ql.exec.SelectOperator;
import org.apache.hadoop.hive.ql.exec.TableScanOperator;
import org.apache.hadoop.hive.ql.lib.Node;
import org.apache.hadoop.hive.ql.lib.NodeProcessor;
import org.apache.hadoop.hive.ql.lib.NodeProcessorCtx;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.metadata.Table;
import org.apache.hadoop.hive.ql.parse.PrunedPartitionList;
import org.apache.hadoop.hive.ql.parse.SemanticException;
import org.apache.hadoop.hive.ql.plan.AggregationDesc;
import org.apache.hadoop.hive.ql.plan.ColStatistics;
import org.apache.hadoop.hive.ql.plan.ExprNodeColumnDesc;
import org.apache.hadoop.hive.ql.plan.ExprNodeConstantDesc;
import org.apache.hadoop.hive.ql.plan.ExprNodeDesc;
import org.apache.hadoop.hive.ql.plan.ExprNodeGenericFuncDesc;
import org.apache.hadoop.hive.ql.plan.JoinDesc;
import org.apache.hadoop.hive.ql.plan.OperatorDesc;
import org.apache.hadoop.hive.ql.plan.Statistics;
import org.apache.hadoop.hive.ql.stats.StatsUtils;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDF;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDFOPAnd;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDFOPEqual;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDFOPEqualNS;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDFOPEqualOrGreaterThan;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDFOPEqualOrLessThan;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDFOPGreaterThan;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDFOPLessThan;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDFOPNot;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDFOPNotEqual;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDFOPNotNull;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDFOPNull;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDFOPOr;
import org.apache.hadoop.hive.serde.serdeConstants;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

public class StatsRulesProcFactory {

  /**
   * Collect basic statistics like number of rows, data size and column level
   * statistics from the table. Also sets the state of the available statistics.
   * Basic and column statistics can have one of the following states
   * COMPLETE, PARTIAL, NONE. In case of partitioned table, the basic and column
   * stats are aggregated together to table level statistics.
   *
   */
  public static class TableScanStatsRule extends DefaultStatsRule implements NodeProcessor {

    @Override
    public Object process(Node nd, Stack<Node> stack, NodeProcessorCtx procCtx,
        Object... nodeOutputs) throws SemanticException {
      TableScanOperator tsop = (TableScanOperator) nd;
      AnnotateStatsProcCtx aspCtx = (AnnotateStatsProcCtx) procCtx;
      PrunedPartitionList partList = null;
      try {
        partList = aspCtx.getParseContext().getPrunedPartitions(tsop.getName(), tsop);
      } catch (HiveException e1) {
        throw new SemanticException(e1);
      }
      Table table = aspCtx.getParseContext().getTopToTable().get(tsop);

      // gather statistics for the first time and the attach it to table scan operator
      Statistics stats = StatsUtils.collectStatistics(aspCtx.getConf(), partList, table, tsop);
      try {
        tsop.setStatistics(stats.clone());
      } catch (CloneNotSupportedException e) {
        throw new SemanticException(ErrorMsg.STATISTICS_CLONING_FAILED.getMsg());
      }
      return null;
    }
  }

  /**
   * SELECT operator doesn't change the number of rows emitted from the parent
   * operator. It changes the size of each tuple emitted. In a typical case,
   * where only subset of columns are selected the average row size will
   * reduce as some of the columns will be pruned. In order to accurately
   * compute the average row size, column level statistics is required.
   * Column level statistics stores average size of values in column which
   * can be used to more reliably estimate the reduction in size of each
   * tuple. In the absence of column level statistics, size of columns will be
   * based on data type. For primitive data types size from
   * {@link org.apache.hadoop.hive.ql.util.JavaDataModel} will be
   * used and for variable length data types worst case will be assumed.
   *
   * <p>
   * <i>For more information, refer 'Estimating The Cost Of Operations' chapter in
   * "Database Systems: The Complete Book" by Garcia-Molina et. al.</i>
   * </p>
   *
   */
  public static class SelectStatsRule extends DefaultStatsRule implements NodeProcessor {

    @Override
    public Object process(Node nd, Stack<Node> stack, NodeProcessorCtx procCtx,
        Object... nodeOutputs) throws SemanticException {

      SelectOperator sop = (SelectOperator) nd;
      Operator<? extends OperatorDesc> parent = sop.getParentOperators().get(0);
      Statistics parentStats = parent.getStatistics();
      AnnotateStatsProcCtx aspCtx = (AnnotateStatsProcCtx) procCtx;
      HiveConf conf = aspCtx.getConf();

      // SELECT (*) does not change the statistics. Just pass on the parent statistics
      if (sop.getConf().isSelectStar()) {
        try {
          if (parentStats != null) {
            sop.setStatistics(parentStats.clone());
          }
        } catch (CloneNotSupportedException e) {
          throw new SemanticException(ErrorMsg.STATISTICS_CLONING_FAILED.getMsg());
        }
        return null;
      }

      try {
        if (satisfyPrecondition(parentStats)) {
          Statistics stats = parentStats.clone();
          List<ColStatistics> colStats = StatsUtils.getColStatisticsFromExprMap(conf, parentStats,
              sop.getColumnExprMap(), sop.getSchema());
          long dataSize = StatsUtils.getDataSizeFromColumnStats(stats.getNumRows(), colStats);
          stats.setColumnStats(colStats);
          stats.setDataSize(dataSize);
          sop.setStatistics(stats);
        } else {
          if (parentStats != null) {
            sop.setStatistics(parentStats.clone());
          }
        }
      } catch (CloneNotSupportedException e) {
        throw new SemanticException(ErrorMsg.STATISTICS_CLONING_FAILED.getMsg());
      }
      return null;
    }

  }

  /**
   * FILTER operator does not change the average row size but it does change
   * the number of rows emitted. The reduction in the number of rows emitted
   * is dependent on the filter expression.
   *
   * <ul>
   * <i>Notations:</i>
   * <li>T(S) - Number of tuples in relations S</li>
   * <li>V(S,A) - Number of distinct values of attribute A in relation S</li>
   * </ul>
   *
   * <ul>
   * <i>Rules:</i> <b>
   * <li>Column equals a constant</li></b> T(S) = T(R) / V(R,A)
   * <p>
   * <b>
   * <li>Inequality conditions</li></b> T(S) = T(R) / 3
   * <p>
   * <b>
   * <li>Not equals comparison</li></b> - Simple formula T(S) = T(R)
   * <p>
   * - Alternate formula T(S) = T(R) (V(R,A) - 1) / V(R,A)
   * <p>
   * <b>
   * <li>NOT condition</li></b> T(S) = 1 - T(S'), where T(S') is the satisfying condition
   * <p>
   * <b>
   * <li>Multiple AND conditions</li></b> Cascadingly apply the rules 1 to 3 (order doesn't matter)
   * <p>
   * <b>
   * <li>Multiple OR conditions</li></b> - Simple formula is to evaluate conditions independently
   * and sum the results T(S) = m1 + m2
   * <p>
   *
   * - Alternate formula T(S) = T(R) * ( 1 - ( 1 - m1/T(R) ) * ( 1 - m2/T(R) ))
   * <p>
   * where, m1 is the number of tuples that satisfy condition1 and m2 is the number of tuples that
   * satisfy condition2
   * </ul>
   * <p>
   * <i>For more information, refer 'Estimating The Cost Of Operations' chapter in
   * "Database Systems: The Complete Book" by Garcia-Molina et. al.</i>
   * </p>
   *
   */
  public static class FilterStatsRule extends DefaultStatsRule implements NodeProcessor {

    @Override
    public Object process(Node nd, Stack<Node> stack, NodeProcessorCtx procCtx,
        Object... nodeOutputs) throws SemanticException {
      AnnotateStatsProcCtx aspCtx = (AnnotateStatsProcCtx) procCtx;
      FilterOperator fop = (FilterOperator) nd;
      Operator<? extends OperatorDesc> parent = fop.getParentOperators().get(0);
      Statistics parentStats = parent.getStatistics();

      try {
        if (satisfyPrecondition(parentStats)) {
          ExprNodeDesc pred = fop.getConf().getPredicate();

          // evaluate filter expression and update statistics
          long newNumRows = evaluateExpression(parentStats, pred, aspCtx);
          Statistics st = parentStats.clone();
          updateStats(st, newNumRows);
          fop.setStatistics(st);
        } else {
          if (parentStats != null) {
            fop.setStatistics(parentStats.clone());
          }
        }

        aspCtx.setAndExprStats(null);
      } catch (CloneNotSupportedException e) {
        throw new SemanticException(ErrorMsg.STATISTICS_CLONING_FAILED.getMsg());
      }
      return null;
    }

    private long evaluateExpression(Statistics stats, ExprNodeDesc pred,
        AnnotateStatsProcCtx aspCtx) throws CloneNotSupportedException {
      long newNumRows = 0;
      Statistics andStats = null;
      if (pred instanceof ExprNodeGenericFuncDesc) {
        ExprNodeGenericFuncDesc genFunc = (ExprNodeGenericFuncDesc) pred;
        GenericUDF udf = genFunc.getGenericUDF();

        // for AND condition cascadingly update stats
        if (udf instanceof GenericUDFOPAnd) {
          andStats = stats.clone();
          aspCtx.setAndExprStats(andStats);

          // evaluate children
          for (ExprNodeDesc child : genFunc.getChildren()) {
            newNumRows = evaluateChildExpr(aspCtx.getAndExprStats(), child, aspCtx);
            updateStats(aspCtx.getAndExprStats(), newNumRows);
          }
        } else {

          // for OR condition independently compute and update stats
          if (udf instanceof GenericUDFOPOr) {
            for (ExprNodeDesc child : genFunc.getChildren()) {
              newNumRows += evaluateChildExpr(stats, child, aspCtx);
            }
          } else if (udf instanceof GenericUDFOPNot) {
            newNumRows = evaluateNotExpr(stats, pred, aspCtx);
          } else if (udf instanceof GenericUDFOPNotNull) {
            newNumRows = evaluateColEqualsNullExpr(stats, pred, aspCtx);
            newNumRows = stats.getNumRows() - newNumRows;
          } else if (udf instanceof GenericUDFOPNull) {
            newNumRows = evaluateColEqualsNullExpr(stats, pred, aspCtx);
          } else {

            // single predicate condition
            newNumRows = evaluateChildExpr(stats, pred, aspCtx);
          }
        }
      } else if (pred instanceof ExprNodeColumnDesc) {

        // can be boolean column in which case return true count
        ExprNodeColumnDesc encd = (ExprNodeColumnDesc) pred;
        String colName = encd.getColumn();
        String tabAlias = encd.getTabAlias();
        String colType = encd.getTypeString();
        if (colType.equalsIgnoreCase(serdeConstants.BOOLEAN_TYPE_NAME)) {
          ColStatistics cs = stats.getColumnStatisticsForColumn(tabAlias, colName);
          return cs.getNumTrues();
        } else {

          // if not boolean column return half the number of rows
          return stats.getNumRows() / 2;
        }
      }

      return newNumRows;
    }

    private long evaluateNotExpr(Statistics stats, ExprNodeDesc pred, AnnotateStatsProcCtx aspCtx)
        throws CloneNotSupportedException {

      long numRows = stats.getNumRows();

      // if the evaluate yields true then pass all rows else pass 0 rows
      if (pred instanceof ExprNodeGenericFuncDesc) {
        ExprNodeGenericFuncDesc genFunc = (ExprNodeGenericFuncDesc) pred;
        for (ExprNodeDesc leaf : genFunc.getChildren()) {
          if (leaf instanceof ExprNodeGenericFuncDesc) {

            // GenericUDF
            long newNumRows = 0;
            for (ExprNodeDesc child : ((ExprNodeGenericFuncDesc) pred).getChildren()) {
              newNumRows = evaluateChildExpr(stats, child, aspCtx);
            }
            return numRows - newNumRows;
          } else if (leaf instanceof ExprNodeConstantDesc) {
            ExprNodeConstantDesc encd = (ExprNodeConstantDesc) leaf;
            if (encd.getValue().equals(true)) {
              return 0;
            } else {
              return numRows;
            }
          } else if (leaf instanceof ExprNodeColumnDesc) {

            // NOT on boolean columns is possible. in which case return false count.
            ExprNodeColumnDesc encd = (ExprNodeColumnDesc) leaf;
            String colName = encd.getColumn();
            String tabAlias = encd.getTabAlias();
            String colType = encd.getTypeString();
            if (colType.equalsIgnoreCase(serdeConstants.BOOLEAN_TYPE_NAME)) {
              ColStatistics cs = stats.getColumnStatisticsForColumn(tabAlias, colName);
              return cs.getNumFalses();
            } else {

              // if not boolean column return half the number of rows
              return numRows / 2;
            }
          }
        }
      }

      // worst case
      return numRows;
    }

    private long evaluateColEqualsNullExpr(Statistics stats, ExprNodeDesc pred,
        AnnotateStatsProcCtx aspCtx) {

      long numRows = stats.getNumRows();

      // evaluate similar to "col = constant" expr
      if (pred instanceof ExprNodeGenericFuncDesc) {

        ExprNodeGenericFuncDesc genFunc = (ExprNodeGenericFuncDesc) pred;
        for (ExprNodeDesc leaf : genFunc.getChildren()) {

          if (leaf instanceof ExprNodeColumnDesc) {
            ExprNodeColumnDesc colDesc = (ExprNodeColumnDesc) leaf;
            String colName = colDesc.getColumn();
            String tabAlias = colDesc.getTabAlias();
            ColStatistics cs = stats.getColumnStatisticsForColumn(tabAlias, colName);
            if (cs != null) {
              long dvs = cs.getCountDistint();
              // if NULLs exists, add 1 to distinct count
              if (cs.getNumNulls() > 0) {
                dvs += 1;
              }
              if (dvs != 0) {
                return numRows / dvs;
              } else {
                return numRows;
              }
            }
          }
        }
      }

      // worst case
      return numRows;
    }

    private long evaluateChildExpr(Statistics stats, ExprNodeDesc child, AnnotateStatsProcCtx aspCtx)
        throws CloneNotSupportedException {

      long numRows = stats.getNumRows();

      if (child instanceof ExprNodeGenericFuncDesc) {

        ExprNodeGenericFuncDesc genFunc = (ExprNodeGenericFuncDesc) child;
        GenericUDF udf = genFunc.getGenericUDF();

        if (udf instanceof GenericUDFOPEqual || udf instanceof GenericUDFOPEqualNS) {
          String colName = null;
          String tabAlias = null;
          boolean isConst = false;

          for (ExprNodeDesc leaf : genFunc.getChildren()) {
            if (leaf instanceof ExprNodeConstantDesc) {

              // if the first argument is const then just set the flag and continue
              if (colName == null) {
                isConst = true;
                continue;
              }
              ColStatistics cs = stats.getColumnStatisticsForColumn(tabAlias, colName);
              if (cs != null) {
                long dvs = cs.getCountDistint();
                // if NULLs exists, add 1 to distinct count
                if (cs.getNumNulls() > 0) {
                  dvs += 1;
                }

                if (dvs != 0) {
                  return numRows / dvs;
                } else {
                  return numRows;
                }
              }
            } else if (leaf instanceof ExprNodeColumnDesc) {
              ExprNodeColumnDesc colDesc = (ExprNodeColumnDesc) leaf;
              colName = colDesc.getColumn();
              tabAlias = colDesc.getTabAlias();

              // if const is first argument then evaluate the result
              if (isConst) {
                ColStatistics cs = stats.getColumnStatisticsForColumn(tabAlias, colName);
                if (cs != null) {
                  long dvs = cs.getCountDistint();
                  // if NULLs exists, add 1 to distinct count
                  if (cs.getNumNulls() > 0) {
                    dvs += 1;
                  }

                  if (dvs != 0) {
                    return numRows / dvs;
                  } else {
                    return numRows;
                  }
                }
              }
            }
          }
        } else if (udf instanceof GenericUDFOPNotEqual) {
          return numRows;
        } else if (udf instanceof GenericUDFOPEqualOrGreaterThan ||
            udf instanceof GenericUDFOPEqualOrLessThan ||
            udf instanceof GenericUDFOPGreaterThan ||
            udf instanceof GenericUDFOPLessThan) {
          return numRows / 3;
        } else {
          return evaluateExpression(stats, genFunc, aspCtx);
        }
      }

      // worst case
      return numRows;
    }

  }

  /**
   * GROUPBY operator changes the number of rows. The number of rows emitted
   * by GBY operator will be atleast 1 or utmost T(R) (number of rows in relation T)
   * based on the aggregation. A better estimate can be found if we have column statistics
   * on the columns that we are grouping on.
   * <p>
   * Suppose if we are grouping by attributes A,B,C and if statistics for columns A,B,C are
   * available then a better estimate can be found by taking the smaller of product of V(R,[A,B,C])
   * (product of distinct cardinalities of A,B,C) and T(R)/2.
   * <p>
   * T(R) = min (T(R)/2 , V(R,[A,B,C]) ---> [1]
   *
   * <p>
   * In the presence of grouping sets, map-side GBY will emit more rows depending on the size of
   * grouping set (input rows * size of grouping set). These rows will get reduced because of
   * map-side hash aggregation. Hash aggregation is an optimization in hive to reduce the number of
   * rows shuffled between map and reduce stage. This optimization will be disabled if the memory
   * used for hash aggregation exceeds 90% of max available memory for hash aggregation. The number
   * of rows emitted from map-side will vary if hash aggregation is enabled throughout execution or
   * disabled. In the presence of grouping sets, following rules will be applied
   * <p>
   * If <b>hash-aggregation is enabled</b>, for query SELECT * FROM table GROUP BY (A,B) WITH CUBE
   * <p>
   * T(R) = min(T(R)/2, T(R, GBY(A,B)) + T(R, GBY(A)) + T(R, GBY(B)) + 1))
   * <p>
   * where, GBY(A,B), GBY(B), GBY(B) are the GBY rules mentioned above [1]
   *
   * <p>
   * If <b>hash-aggregation is disabled</b>, apply the GBY rule [1] and then multiply the result by
   * number of elements in grouping set T(R) = T(R) * length_of_grouping_set. Since we do not know
   * if hash-aggregation is enabled or disabled during compile time, we will assume worst-case i.e,
   * hash-aggregation is disabled
   *
   * <p>
   * NOTE: The number of rows from map-side GBY operator is dependent on map-side parallelism i.e,
   * number of mappers. The map-side parallelism is expected from hive config
   * "hive.stats.map.parallelism". If the config is not set then default parallelism of 1 will be
   * assumed.
   *
   * <p>
   * <i>For more information, refer 'Estimating The Cost Of Operations' chapter in
   * "Database Systems: The Complete Book" by Garcia-Molina et. al.</i>
   * </p>
   *
   */
  public static class GroupByStatsRule extends DefaultStatsRule implements NodeProcessor {

    @Override
    public Object process(Node nd, Stack<Node> stack, NodeProcessorCtx procCtx,
        Object... nodeOutputs) throws SemanticException {
      GroupByOperator gop = (GroupByOperator) nd;
      Operator<? extends OperatorDesc> parent = gop.getParentOperators().get(0);
      Statistics parentStats = parent.getStatistics();
      AnnotateStatsProcCtx aspCtx = (AnnotateStatsProcCtx) procCtx;
      HiveConf conf = aspCtx.getConf();
      int mapSideParallelism = HiveConf.getIntVar(conf,
          HiveConf.ConfVars.HIVE_STATS_MAP_SIDE_PARALLELISM);

      try {
        if (satisfyPrecondition(parentStats)) {
          Statistics stats = parentStats.clone();
          RowSchema rs = gop.getSchema();
          List<AggregationDesc> aggDesc = gop.getConf().getAggregators();
          Map<String, ExprNodeDesc> colExprMap = gop.getColumnExprMap();
          List<ColStatistics> colStats = StatsUtils.getColStatisticsFromExprMap(conf, parentStats,
              colExprMap, rs);
          stats.setColumnStats(colStats);
          long dvProd = 1;
          long newNumRows = 0;

          // compute product of distinct values of grouping columns
          for (ColStatistics cs : colStats) {
            if (cs != null) {
              long dv = cs.getCountDistint();
              if (cs.getNumNulls() > 0) {
                dv += 1;
              }
              dvProd *= dv;
            }
          }

          // map side
          if (gop.getChildOperators().get(0) instanceof ReduceSinkOperator) {

            // since we do not know if hash-aggregation will be enabled or disabled
            // at runtime we will assume that map-side group by does not do any reduction.
            // hence no group by rule will be applied

            // map-side grouping set present. if grouping set is present then
            // multiply the number of rows by number of elements in grouping set
            if (gop.getConf().isGroupingSetsPresent()) {
              int multiplier = gop.getConf().getListGroupingSets().size();

              // take into account the map-side parallelism as well, default is 1
              multiplier *= mapSideParallelism;
              newNumRows = multiplier * stats.getNumRows();
              long dataSize = multiplier * stats.getDataSize();
              stats.setNumRows(newNumRows);
              stats.setDataSize(dataSize);
              for (ColStatistics cs : colStats) {
                if (cs != null) {
                  long oldNumNulls = cs.getNumNulls();
                  long newNumNulls = multiplier * oldNumNulls;
                  cs.setNumNulls(newNumNulls);
                }
              }
            } else {

              // map side no grouping set
              newNumRows = stats.getNumRows() * mapSideParallelism;
              updateStats(stats, newNumRows);
            }
          } else {

            // reduce side
            newNumRows = applyGBYRule(stats.getNumRows(), dvProd);
            updateStats(stats, newNumRows);
          }

          // if UDAFs are present, new columns needs to be added
          if (!aggDesc.isEmpty()) {
            List<ColStatistics> aggColStats = Lists.newArrayList();
            for (ColumnInfo ci : rs.getSignature()) {

              // if the columns in row schema is not contained in column
              // expression map, then those are the aggregate columns that
              // are added GBY operator. we will estimate the column statistics
              // for those newly added columns
              if (!colExprMap.containsKey(ci.getInternalName())) {
                String colName = ci.getInternalName();
                colName = StatsUtils.stripPrefixFromColumnName(colName);
                String tabAlias = ci.getTabAlias();
                String colType = ci.getTypeName();
                ColStatistics cs = new ColStatistics(tabAlias, colName, colType);
                cs.setCountDistint(stats.getNumRows());
                cs.setNumNulls(0);
                cs.setAvgColLen(StatsUtils.getAvgColLenOfFixedLengthTypes(colType));
                aggColStats.add(cs);
              }
            }
            stats.addToColumnStats(aggColStats);

            // if UDAF present and if column expression map is empty then it must
            // be full aggregation query like count(*) in which case number of rows will be 1
            if (colExprMap.isEmpty()) {
              stats.setNumRows(1);
              updateStats(stats, 1);
            }
          }

          gop.setStatistics(stats);
        } else {
          if (parentStats != null) {
            gop.setStatistics(parentStats.clone());
          }
        }
      } catch (CloneNotSupportedException e) {
        throw new SemanticException(ErrorMsg.STATISTICS_CLONING_FAILED.getMsg());
      }
      return null;
    }

    private long applyGBYRule(long numRows, long dvProd) {
      long newNumRows = numRows;

      // to avoid divide by 2 to become 0
      if (numRows > 1) {
        if (dvProd != 0) {
          newNumRows = Math.min(numRows / 2, dvProd);
        } else {
          newNumRows = numRows / 2;
        }
      }
      return newNumRows;
    }
  }

  /**
   * JOIN operator can yield any of the following three cases <li>The values of join keys are
   * disjoint in both relations in which case T(RXS) = 0 (we need histograms for this)</li> <li>Join
   * key is primary key on relation R and foreign key on relation S in which case every tuple in S
   * will have a tuple in R T(RXS) = T(S) (we need histograms for this)</li> <li>Both R & S relation
   * have same value for join-key. Ex: bool column with all true values T(RXS) = T(R) * T(S) (we
   * need histograms for this. counDistinct = 1 and same value)</li>
   *
   * <p>
   * In the absence of histograms, we can use the following general case
   * <p>
   * <b>Single attribute</b>
   * <p>
   * T(RXS) = (T(R)*T(S))/max(V(R,Y), V(S,Y)) where Y is the join attribute
   * <p>
   * <b>Multiple attributes</b>
   * <p>
   * T(RXS) = T(R)*T(S)/max(V(R,y1), V(S,y1)) * max(V(R,y2), V(S,y2)), where y1 and y2 are the join
   * attributes
   *
   * <p>
   * <i>For more information, refer 'Estimating The Cost Of Operations' chapter in
   * "Database Systems: The Complete Book" by Garcia-Molina et. al.</i>
   * </p>
   */
  public static class JoinStatsRule extends DefaultStatsRule implements NodeProcessor {

    @Override
    public Object process(Node nd, Stack<Node> stack, NodeProcessorCtx procCtx,
        Object... nodeOutputs) throws SemanticException {
      CommonJoinOperator<? extends JoinDesc> jop = (CommonJoinOperator<? extends JoinDesc>) nd;
      List<Operator<? extends OperatorDesc>> parents = jop.getParentOperators();
      AnnotateStatsProcCtx aspCtx = (AnnotateStatsProcCtx) procCtx;
      HiveConf conf = aspCtx.getConf();
      boolean allStatsAvail = true;
      boolean allSatisfyPreCondition = true;

      for (Operator<? extends OperatorDesc> op : parents) {
        if (op.getStatistics() == null) {
          allStatsAvail = false;
        }
      }

      if (allStatsAvail) {

        for (Operator<? extends OperatorDesc> op : parents) {
          if (!satisfyPrecondition(op.getStatistics())) {
            allSatisfyPreCondition = false;
          }
        }

        if (allSatisfyPreCondition) {
          // statistics object that is combination of statistics from all relations involved in JOIN
          Statistics stats = new Statistics();
          long prodRows = 1;
          List<Long> distinctVals = Lists.newArrayList();
          boolean multiAttr = false;


          Map<String, ColStatistics> joinedColStats = Maps.newHashMap();
          Map<Integer, List<String>> joinKeys = Maps.newHashMap();

          // get the join keys from parent ReduceSink operators
          for (int pos = 0; pos < parents.size(); pos++) {
            ReduceSinkOperator parent = (ReduceSinkOperator) jop.getParentOperators().get(pos);

            Statistics parentStats = parent.getStatistics();
            prodRows *= parentStats.getNumRows();
            List<ExprNodeDesc> keyExprs = parent.getConf().getKeyCols();

            // multi-attribute join key
            if (keyExprs.size() > 1) {
              multiAttr = true;
            }

            // compute fully qualified join key column names. this name will be used to
            // quickly look-up for column statistics of join key.
            // TODO: expressions in join condition will be ignored. assign internal name
            // for expressions and estimate column statistics for expression.
            List<String> fqCols = StatsUtils.getFullQualifedColNameFromExprs(keyExprs,
                parent.getColumnExprMap());
            joinKeys.put(pos, fqCols);

            Map<String, ExprNodeDesc> colExprMap = parent.getColumnExprMap();
            RowSchema rs = parent.getSchema();

            // get column statistics for all output columns
            List<ColStatistics> cs = StatsUtils.getColStatisticsFromExprMap(conf, parentStats,
                colExprMap, rs);
            for (ColStatistics c : cs) {
              if (c != null) {
                joinedColStats.put(c.getFullyQualifiedColName(), c);
              }
            }

            // since new statistics is derived from all relations involved in JOIN,
            // we need to update the state information accordingly
            stats.updateBasicStatsState(parentStats.getBasicStatsState());
            stats.updateColumnStatsState(parentStats.getColumnStatsState());
          }

          // compute denominator i.e, max(V(R,Y), V(S,Y)) in case of single attribute join.
          // else max(V(R,y1), V(S,y1)) * max(V(R,y2), V(S,y2)) in case of multi-attribute join
          long denom = 1;
          if (multiAttr) {
            List<Long> perAttrDVs = Lists.newArrayList();
            int numAttr = joinKeys.get(0).size();
            for (int idx = 0; idx < numAttr; idx++) {
              for (Integer i : joinKeys.keySet()) {
                String col = joinKeys.get(i).get(idx);
                ColStatistics cs = joinedColStats.get(col);
                if (cs != null) {
                  perAttrDVs.add(cs.getCountDistint());
                }
              }
              distinctVals.add(getDenominator(perAttrDVs));
              perAttrDVs.clear();
            }

            for (Long l : distinctVals) {
              denom *= l;
            }
          } else {
            for (List<String> jkeys : joinKeys.values()) {
              for (String jk : jkeys) {
                ColStatistics cs = joinedColStats.get(jk);
                if (cs != null) {
                  distinctVals.add(cs.getCountDistint());
                }
              }
            }
            denom = getDenominator(distinctVals);
          }

          // column statistics from different sources are put together and rename
          // fully qualified column names based on output schema of join operator
          Map<String, ExprNodeDesc> colExprMap = jop.getColumnExprMap();
          RowSchema rs = jop.getSchema();
          List<ColStatistics> outColStats = Lists.newArrayList();
          for (ColumnInfo ci : rs.getSignature()) {
            String key = ci.getInternalName();
            ExprNodeDesc end = colExprMap.get(key);
            if (end instanceof ExprNodeColumnDesc) {
              String colName = ((ExprNodeColumnDesc) end).getColumn();
              colName = StatsUtils.stripPrefixFromColumnName(colName);
              String tabAlias = ((ExprNodeColumnDesc) end).getTabAlias();
              String fqColName = StatsUtils.getFullyQualifiedColumnName(tabAlias, colName);
              ColStatistics cs = joinedColStats.get(fqColName);
              String outColName = key;
              String outTabAlias = ci.getTabAlias();
              outColName = StatsUtils.stripPrefixFromColumnName(outColName);
              if (cs != null) {
                cs.setColumnName(outColName);
                cs.setTableAlias(outTabAlias);
              }
              outColStats.add(cs);
            }
          }

          // update join statistics
          stats.setColumnStats(outColStats);
          long newRowCount = prodRows / denom;
          stats.setNumRows(newRowCount);
          stats.setDataSize(StatsUtils.getDataSizeFromColumnStats(newRowCount, outColStats));
          jop.setStatistics(stats);
        }
      }
      return null;
    }

    private long getDenominator(List<Long> distinctVals) {

      // simple join from 2 relations
      // denom = max(v1, v2)
      if (distinctVals.size() <= 2) {
        return Collections.max(distinctVals);
      } else {

        // join from multiple relations
        // denom = max(v1, v2) * max(v2, v3) * max(v3, v4)
        long denom = 1;
        for (int i = 0; i < distinctVals.size() - 1; i++) {
          long v1 = distinctVals.get(i);
          long v2 = distinctVals.get(i + 1);
          if (v1 >= v2) {
            denom *= v1;
          } else {
            denom *= v2;
          }
        }
        return denom;
      }
    }

  }

  /**
   * LIMIT operator changes the number of rows and thereby the data size.
   *
   */
  public static class LimitStatsRule extends DefaultStatsRule implements NodeProcessor {

    @Override
    public Object process(Node nd, Stack<Node> stack, NodeProcessorCtx procCtx,
        Object... nodeOutputs) throws SemanticException {
      LimitOperator lop = (LimitOperator) nd;
      Operator<? extends OperatorDesc> parent = lop.getParentOperators().get(0);
      Statistics parentStats = parent.getStatistics();

      try {
        if (satisfyPrecondition(parentStats)) {
          Statistics stats = parentStats.clone();
          long limit = -1;
          limit = lop.getConf().getLimit();
          if (limit == -1) {
            limit = lop.getConf().getLeastRows();
          }

          // if limit is greate than available rows then do not update statistics
          if (limit <= parentStats.getNumRows()) {
            updateStats(stats, limit);
          }
          lop.setStatistics(stats);
        } else {
          if (parentStats != null) {
            lop.setStatistics(parentStats.clone());
          }
        }
      } catch (CloneNotSupportedException e) {
        throw new SemanticException(ErrorMsg.STATISTICS_CLONING_FAILED.getMsg());
      }
      return null;
    }

  }

  /**
   * Default rule is to aggregate the statistics from all its parent operators.
   *
   */
  public static class DefaultStatsRule implements NodeProcessor {

    @Override
    public Object process(Node nd, Stack<Node> stack, NodeProcessorCtx procCtx,
        Object... nodeOutputs) throws SemanticException {
      Operator<? extends OperatorDesc> op = (Operator<? extends OperatorDesc>) nd;
      OperatorDesc conf = op.getConf();

      if (conf != null) {
        Statistics stats = conf.getStatistics();
        if (stats == null) {
          if (op.getParentOperators() != null) {

            // if parent statistics is null then that branch of the tree is not walked yet.
            // don't update the stats until all branches are walked
            if (isAllParentsContainStatistics(op)) {
              stats = new Statistics();
              for (Operator<? extends OperatorDesc> parent : op.getParentOperators()) {
                if (parent.getStatistics() != null) {
                  Statistics parentStats = parent.getStatistics();
                  stats.addToNumRows(parentStats.getNumRows());
                  stats.addToDataSize(parentStats.getDataSize());
                  stats.updateBasicStatsState(parentStats.getBasicStatsState());
                  stats.updateColumnStatsState(parentStats.getColumnStatsState());
                  stats.addToColumnStats(parentStats.getColumnStats());
                  op.getConf().setStatistics(stats);
                }
              }
            }
          }
        }
      }
      return null;
    }

    // check if all parent statistics are available
    private boolean isAllParentsContainStatistics(Operator<? extends OperatorDesc> op) {
      for (Operator<? extends OperatorDesc> parent : op.getParentOperators()) {
        if (parent.getStatistics() == null) {
          return false;
        }
      }
      return true;
    }

  }

  public static NodeProcessor getTableScanRule() {
    return new TableScanStatsRule();
  }

  public static NodeProcessor getSelectRule() {
    return new SelectStatsRule();
  }

  public static NodeProcessor getFilterRule() {
    return new FilterStatsRule();
  }

  public static NodeProcessor getGroupByRule() {
    return new GroupByStatsRule();
  }

  public static NodeProcessor getJoinRule() {
    return new JoinStatsRule();
  }

  public static NodeProcessor getLimitRule() {
    return new LimitStatsRule();
  }

  public static NodeProcessor getDefaultRule() {
    return new DefaultStatsRule();
  }

  /**
   * Update the basic statistics of the statistics object based on the row number
   *
   * @param stats
   *          - statistics to be updated
   * @param newNumRows
   *          - new number of rows
   */
  static void updateStats(Statistics stats, long newNumRows) {
    long oldRowCount = stats.getNumRows();
    double ratio = (double) newNumRows / (double) oldRowCount;
    stats.setNumRows(newNumRows);

    List<ColStatistics> colStats = stats.getColumnStats();
    for (ColStatistics cs : colStats) {
      long oldNumNulls = cs.getNumNulls();
      long oldDV = cs.getCountDistint();
      long newNumNulls = Math.round(ratio * oldNumNulls);
      long newDV = oldDV;

      // if ratio is greater than 1, then number of rows increases. This can happen
      // when some operators like GROUPBY duplicates the input rows in which case
      // number of distincts should not change. Update the distinct count only when
      // the output number of rows is less than input number of rows.
      if (ratio <= 1.0) {
        newDV = Math.round(ratio * oldDV);
      }
      cs.setNumNulls(newNumNulls);
      cs.setCountDistint(newDV);
    }
    stats.setColumnStats(colStats);
    long newDataSize = StatsUtils.getDataSizeFromColumnStats(newNumRows, colStats);
    stats.setDataSize(newDataSize);
  }

  static boolean satisfyPrecondition(Statistics stats) {
    return stats != null && stats.getBasicStatsState().equals(Statistics.State.COMPLETE)
        && !stats.getColumnStatsState().equals(Statistics.State.NONE);
  }
}
