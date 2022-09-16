/*
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
package org.apache.flink.table.planner.plan.utils

import org.apache.flink.table.planner.hint.FlinkHints
import org.apache.flink.table.planner.plan.metadata.FlinkRelMetadataQuery
import org.apache.flink.table.planner.plan.nodes.physical.stream.StreamPhysicalRel

import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.core.{Correlate, Join, TableScan}
import org.apache.calcite.rel.externalize.RelWriterImpl
import org.apache.calcite.rel.hint.Hintable
import org.apache.calcite.sql.SqlExplainLevel
import org.apache.calcite.util.Pair

import java.io.PrintWriter
import java.util

import scala.collection.JavaConversions._

/** Explain a relational expression as tree style. */
class RelTreeWriterImpl(
    pw: PrintWriter,
    explainLevel: SqlExplainLevel = SqlExplainLevel.EXPPLAN_ATTRIBUTES,
    withIdPrefix: Boolean = false,
    withChangelogTraits: Boolean = false,
    withRowType: Boolean = false,
    withTreeStyle: Boolean = true,
    withUpsertKey: Boolean = false,
    withJoinHint: Boolean = true,
    withQueryBlockAlias: Boolean = false)
  extends RelWriterImpl(pw, explainLevel, withIdPrefix) {

  var lastChildren: Seq[Boolean] = Nil

  var depth = 0

  override def explain_(rel: RelNode, values: util.List[Pair[String, AnyRef]]): Unit = {
    val inputs = rel.getInputs
    val mq = rel.getCluster.getMetadataQuery
    if (!mq.isVisibleInExplain(rel, explainLevel)) {
      // render children in place of this, at same level
      inputs.toSeq.foreach(_.explain(this))
      return
    }

    val s = new StringBuilder
    if (withTreeStyle) {
      if (depth > 0) {
        lastChildren.init.foreach(isLast => s.append(if (isLast) "   " else ":  "))
        s.append(if (lastChildren.last) "+- " else ":- ")
      }
    }

    if (withIdPrefix) {
      s.append(rel.getId).append(":")
    }

    rel.getRelTypeName match {
      case name if name.startsWith("BatchExec") => s.append(name.substring(9))
      case name if name.startsWith("BatchPhysical") => s.append(name.substring(13))
      case name if name.startsWith("StreamExec") => s.append(name.substring(10))
      case name if name.startsWith("StreamPhysical") => s.append(name.substring(14))
      case name => s.append(name)
    }

    val printValues = new util.ArrayList[Pair[String, AnyRef]]()
    if (explainLevel != SqlExplainLevel.NO_ATTRIBUTES) {
      printValues.addAll(values)
    }

    if (withChangelogTraits) rel match {
      case streamRel: StreamPhysicalRel =>
        val changelogMode = ChangelogPlanUtils.getChangelogMode(streamRel)
        printValues.add(
          Pair.of("changelogMode", ChangelogPlanUtils.stringifyChangelogMode(changelogMode)))
      case _ => // ignore
    }

    if (withUpsertKey) rel match {
      case streamRel: StreamPhysicalRel =>
        val fmq = FlinkRelMetadataQuery.reuseOrCreate(rel.getCluster.getMetadataQuery)
        val upsertKeys = fmq.getUpsertKeys(streamRel)
        if (null != upsertKeys && !upsertKeys.isEmpty) {
          val fieldNames = streamRel.getRowType.getFieldNames
          printValues.add(
            Pair.of(
              "upsertKeys",
              upsertKeys
                .map(bitset => bitset.toArray.map(fieldNames).mkString("[", ", ", "]"))
                .mkString(", ")))
        }
      case _ => // ignore
    }

    if (withJoinHint) {
      rel match {
        case _: Join | _: Correlate =>
          val joinHints = FlinkHints.getAllJoinHints(rel.asInstanceOf[Hintable].getHints)
          if (joinHints.nonEmpty) {
            printValues.add(Pair.of("joinHints", RelExplainUtil.hintsToString(joinHints)))
          }
        case _ => // ignore
      }
    }

    if (withQueryBlockAlias) {
      rel match {
        case node: Hintable =>
          node match {
            case _: TableScan =>
            // We don't need to pint hints about TableScan because TableScan will always
            // print hints if exist. See more in such as LogicalTableScan#explainTerms
            case _ =>
              val queryBlockAliasHints = FlinkHints.getQueryBlockAliasHints(node.getHints)
              if (queryBlockAliasHints.nonEmpty) {
                printValues.add(
                  Pair.of("hints", RelExplainUtil.hintsToString(queryBlockAliasHints)))
              }
          }
        case _ => // ignore
      }
    }

    if (!printValues.isEmpty) {
      var j = 0
      printValues.toSeq.foreach {
        case value if value.right.isInstanceOf[RelNode] => // do nothing
        case value =>
          if (j == 0) s.append("(") else s.append(", ")
          j = j + 1
          s.append(value.left).append("=[").append(value.right).append("]")
      }
      if (j > 0) s.append(")")
    }

    if (withRowType) {
      s.append(", rowType=[").append(rel.getRowType.toString).append("]")
    }

    if (explainLevel == SqlExplainLevel.ALL_ATTRIBUTES) {
      s.append(": rowcount = ")
        .append(mq.getRowCount(rel))
        .append(", cumulative cost = ")
        .append(mq.getCumulativeCost(rel))
    }
    pw.println(s)

    if (inputs.length > 1) inputs.toSeq.init.foreach {
      rel =>
        if (withTreeStyle) {
          depth = depth + 1
          lastChildren = lastChildren :+ false
        }

        rel.explain(this)

        if (withTreeStyle) {
          depth = depth - 1
          lastChildren = lastChildren.init
        }
    }
    if (!inputs.isEmpty) {
      if (withTreeStyle) {
        depth = depth + 1
        lastChildren = lastChildren :+ true
      }

      inputs.toSeq.last.explain(this)

      if (withTreeStyle) {
        depth = depth - 1
        lastChildren = lastChildren.init
      }
    }
  }
}
