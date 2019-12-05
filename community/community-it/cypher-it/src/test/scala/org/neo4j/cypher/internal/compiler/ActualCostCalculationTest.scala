/*
 * Copyright (c) 2002-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

import org.apache.commons.math3.stat.regression.{OLSMultipleLinearRegression, SimpleRegression}
import org.neo4j.configuration.GraphDatabaseSettings.DEFAULT_DATABASE_NAME
import org.neo4j.cypher.internal.javacompat.GraphDatabaseCypherService
import org.neo4j.cypher.internal.logical.plans.{DoNotGetValue, IndexOrderNone, IndexedProperty, SingleQueryExpression}
import org.neo4j.cypher.internal.runtime.interpreted.TransactionBoundQueryContext.IndexSearchMonitor
import org.neo4j.cypher.internal.runtime.interpreted.commands.expressions.{Literal, Property, Variable}
import org.neo4j.cypher.internal.runtime.interpreted.commands.predicates.Equals
import org.neo4j.cypher.internal.runtime.interpreted.commands.values.TokenType.PropertyKey
import org.neo4j.cypher.internal.runtime.interpreted.pipes._
import org.neo4j.cypher.internal.runtime.interpreted.{QueryStateHelper, TransactionBoundQueryContext, TransactionalContextWrapper}
import org.neo4j.cypher.internal.spi.TransactionBoundPlanContext
import org.neo4j.cypher.internal.v4_0.expressions.{LabelToken, PropertyKeyToken, SemanticDirection}
import org.neo4j.cypher.internal.v4_0.frontend.phases.devNullLogger
import org.neo4j.cypher.internal.util.test_helpers.CypherFunSuite
import org.neo4j.cypher.internal.util.{LabelId, PropertyKeyId}
import org.neo4j.graphdb._
import org.neo4j.internal.kernel.api.security.LoginContext
import org.neo4j.kernel.GraphDatabaseQueryService
import org.neo4j.kernel.api.KernelTransaction.Type
import org.neo4j.kernel.impl.coreapi.InternalTransaction
import org.neo4j.kernel.impl.factory.GraphDatabaseFacade
import org.neo4j.kernel.impl.query.Neo4jTransactionalContextFactory
import org.neo4j.test.TestDatabaseManagementServiceBuilder
import org.neo4j.values.virtual.VirtualValues.EMPTY_MAP

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

/**
 * Estimates values used by CardinalityCostModel, note that this takes at least on the order
 * of a couple of minutes to finish.
 */
class ActualCostCalculationTest extends CypherFunSuite {

  private val N = 1000000
  private val STEPS = 100
  private val LABEL = Label.label("L")
  private val PROPERTY = "prop"
  private val RELATIONSHIP = "REL"

  ignore("do the test") {
    val path = Files.createTempDirectory("apa").toFile.getAbsolutePath
    val managementService = new TestDatabaseManagementServiceBuilder(new File(path)).build()
    val graph: GraphDatabaseQueryService = new GraphDatabaseCypherService(managementService.database(DEFAULT_DATABASE_NAME))
    try {
      graph.createIndex(LABEL, PROPERTY)
      val results = ResultTable.empty
      val chunk = N / STEPS
      for (count <- 1 to STEPS) {
        println(STEPS - count)
        setUpDb(graph, chunk)

        val varName = "x"
        results.addAll("AllNodeScan", runSimulation(graph, allNodes))
        val labelScanPipe = labelScan(varName, LABEL.name())
        results.addAll("NodeByLabelScan", runSimulation(graph, labelScanPipe))
        results.addAll("NodeByID", runSimulation(graph, nodeById(42L)))
        results.addAll("RelByID", runSimulation(graph, relById(42L)))
        results.addAll("NodeIndexSeek", runSimulation(graph, indexSeek(graph)))
        results.addAll("NodeIndexScan", runSimulation(graph, indexScan(graph)))
        results.addAll("Expand", expandResult(graph, labelScanPipe))
        val labelScanAndThenPropFilter = runSimulation(graph, propertyFilter(labelScanPipe, varName))
        results.addAll("LabelScan followed by filter on property", labelScanAndThenPropFilter)
      }

      results.normalizedResult.foreach {
        case (name, slope) => println(s"$name: COST = $slope * NROWS")
      }
    }
    finally {
      managementService.shutdown()
    }
  }

  ignore("cost for eagerness") {
    val path = Files.createTempDirectory("apa").toFile.getAbsolutePath
    val managementService = new TestDatabaseManagementServiceBuilder(new File(path)).build()
    val graph: GraphDatabaseQueryService = new GraphDatabaseCypherService(managementService.database(DEFAULT_DATABASE_NAME))
    try {
      graph.createIndex(LABEL, PROPERTY)
      val results = ResultTable.empty
      val chunk = N / STEPS
      for (_ <- 1 to STEPS) {
        setUpDb(graph, chunk)
        results.addAll("Eager", runSimulation(graph, eager(allNodes)))
      }

      results.foreach {
        case (_, dps) =>
          val res = dps.toList.sortBy(_.numberOfRows)
          println(res.map(_.elapsed).mkString(","))
      }
      results.result.foreach {
        case (name, slope) => println(s"$name: COST = $slope * NROWS")
      }
    }
    finally {
      managementService.shutdown()
    }
  }

  ignore("hash joins") {
    val path = Files.createTempDirectory("apa").toFile.getAbsolutePath
    val managementService = new TestDatabaseManagementServiceBuilder(new File(path)).build()
    val graph: GraphDatabaseQueryService = new GraphDatabaseCypherService(managementService.database(DEFAULT_DATABASE_NAME))
    val labels = Seq("A", "B", "C", "D", "E", "F", "G", "H", "I", "J")
    val x = ListBuffer.empty[Array[Double]]
    val y = ListBuffer.empty[Double]

    try {
      setupDbForJoins(graph, labels)

      //permutate lhs, and rhs of the hashjoin, for each permutation
      //calculate cost of lhs, rhs and the cost for the hash join
      for {label1 <- labels
           label2 <- labels if label1 != label2} {

        val lhsPipe = labelScan("x", label1)
        val rhsPipe = labelScan("x", label2)
        val lhsCost = medianPerRowCount(runSimulation(graph, lhsPipe)).head
        val rhsCost = medianPerRowCount(runSimulation(graph, rhsPipe)).head
        val hashJoinCost = medianPerRowCount(runSimulation(graph, hashJoin(lhsPipe, rhsPipe))).head
        x.append(Array(lhsCost.elapsed, rhsCost.elapsed))
        y.append(hashJoinCost.elapsed)
      }

      //From the collected data, estimate A and B
      val regression = new OLSMultipleLinearRegression()
      regression.setNoIntercept(true)
      regression.newSampleData(y.toArray, x.toArray)
      val params = regression.estimateRegressionParameters()

      println(s"COST = LHS * ${params(0)} + RHS * ${params(1)}")

    } finally {
      managementService.shutdown()
    }
  }

  class ResultTable {
    private val table = mutable.HashMap.empty[String, ListBuffer[DataPoint]]

    def foreach(f: ((String, Seq[DataPoint])) => Unit): Unit = table.foreach(f)
    def add(name: String, dataPoint: DataPoint): Unit =
      table.getOrElseUpdate(name, ListBuffer.empty).append(dataPoint)

    def addAll(name: String, dataPoints: Seq[DataPoint]): Unit =
      table.getOrElseUpdate(name, ListBuffer.empty).appendAll(dataPoints)

    def normalizedResult: collection.Map[String, Double] = {
      val result = table.mapValues(calculateSimpleResult)
      val minValue = result.values.min
      result.mapValues(_/minValue)
    }

    def result: collection.Map[String, Double] = table.mapValues(calculateSimpleResult)

    override def toString: String = table.map{
      case (name, dataPoints) => s"$name: $dataPoints"
    }.mkString("\n")
  }

  object ResultTable {
    def empty = new ResultTable
    def apply() = new ResultTable
  }

  case class DataPoint(elapsed: Double, numberOfRows: Long) {
    def subtractTime(subtract: Double): DataPoint = copy(elapsed = elapsed - subtract)

    override def toString: String = s"$numberOfRows, $elapsed"
  }

  private def expandResult(graph: GraphDatabaseQueryService, scan: Pipe) = {
    val scanCost = medianPerRowCount(runSimulation(graph, scan)).head
    val simulation = runSimulation(graph, expand(scan, RELATIONSHIP)).map(_.subtractTime(scanCost.elapsed))

    simulation
  }

  //From the provided data points, estimate slope and intercept in `cost = slope*NROWS + intercept`
  private def calculateSimpleResult(dataPoints: Seq[DataPoint]): Double= {
    if (dataPoints.isEmpty) throw new IllegalArgumentException("Cannot compute result without any data points")
    else if (dataPoints.size == 1) {
      val dp = dataPoints.head
      dp.elapsed / dp.numberOfRows.toDouble
    } else {
      val regression = new SimpleRegression(false)
      dataPoints.foreach(dp => regression.addData(dp.numberOfRows, dp.elapsed))
      regression.getSlope
    }
  }

  //For each rowcount find the median value
  private def medianPerRowCount(dataPoints: Seq[DataPoint]) =
    dataPoints.groupBy(_.numberOfRows).map {
      case (rowCount, dps) => DataPoint(median(dps.map(_.elapsed)), rowCount)
    }

  private def median(values: Seq[Double]) =
    if (values.length % 2 == 0) {
      val sorted = values.sorted
      (sorted(values.size / 2 - 1) + sorted(values.length / 2)) / 2.0
    } else {
      val sorted = values.sorted
      sorted(values.size / 2)
    }

  private def runSimulation(graph: GraphDatabaseQueryService, pipe: Pipe): Seq[DataPoint] =
    runSimulation(graph, Seq(pipe))

  private def transactionContext(graph: GraphDatabaseQueryService, tx: InternalTransaction) = {
    val contextFactory = Neo4jTransactionalContextFactory.create(graph)
    contextFactory.newContext( tx, "X", EMPTY_MAP)
  }

  //executes the provided pipes and returns execution times
  private def runSimulation(graph: GraphDatabaseQueryService, pipes: Seq[Pipe]) = {
    val results = new ListBuffer[DataPoint]

    graph.withTx { tx =>
      val tc = transactionContext(graph, tx)
      val tcWrapper = TransactionalContextWrapper(tc)
      val queryContext = new TransactionBoundQueryContext(tcWrapper)(mock[IndexSearchMonitor])
      val state = QueryStateHelper.emptyWith(query = queryContext)
      for (x <- 0 to 25) {
        for (pipe <- pipes) {
          val start = System.nanoTime()
          val numberOfRows = pipe.createResults(state).size
          val elapsed = System.nanoTime() - start

          //warmup
          if (x > 4) results.append(DataPoint(elapsed, numberOfRows))
        }
      }
    }
    //remove fastest and slowest
    results.sortBy(_.elapsed).slice(5, results.size - 5)
  }

  private def setUpDb(graph: GraphDatabaseQueryService, chunkSize: Int) {
    graph.withTx { tx =>
      for (_ <- 1 to chunkSize) {
        val node = tx.createNode(LABEL)
        node.createRelationshipTo(tx.createNode(),
          RelationshipType.withName(RELATIONSHIP))
        node.setProperty(PROPERTY, 42)
      }
    }
  }

  //create a database where each subsequent label is more frequent
  private def setupDbForJoins(graph: GraphDatabaseQueryService, labels: Seq[String]): Unit = {
    val nLabels = labels.size
    //divide so that each subsequent label is more frequent,
    //e.g. [100, 200, 300,...] with 100 + 200 + 300 ~ N
    val factor = 2 * N / (nLabels * (nLabels + 1))
    val sizes =  for (i <- 1 to nLabels) yield i * factor
    graph.withTx { tx =>
      for (i <- labels.indices) {
        val label = labels(i)
        val size = sizes(i)
        for (_ <- 1 to size) {
          tx.createNode(Label.label(label))
        }
      }
    }
  }

  private def labelScan(variable: String, label: String) = NodeByLabelScanPipe(variable, LazyLabel(label))()

  private def hashJoin(l: Pipe, r: Pipe) = NodeHashJoinPipe(Set("x"), l, r)()

  private def expand(l: Pipe, t: String) = ExpandAllPipe(l, "x", "r", "n", SemanticDirection.OUTGOING, RelationshipTypes(Array(t)))()

  private def allNodes = AllNodesScanPipe("x")()

  private def nodeById(id: Long) = NodeByIdSeekPipe("x", SingleSeekArg(Literal(id)))()

  private def relById(id: Long) = UndirectedRelationshipByIdSeekPipe("r", SingleSeekArg(Literal(id)), "to", "from")()

  private def eager(pipe: Pipe) = EagerPipe(pipe)()

  private def indexSeek(graph: GraphDatabaseQueryService) = {
    graph.withTx { tx =>
      val transactionalContext = TransactionalContextWrapper(transactionContext(graph, tx))
      val ctx = TransactionBoundPlanContext(transactionalContext, devNullLogger, null)
      val literal = Literal(42)

      val labelId = ctx.getOptLabelId(LABEL.name()).get
      val propKeyId = ctx.getOptPropertyKeyId(PROPERTY).get
      val labelToken = LabelToken(LABEL.name(), LabelId(labelId))
      val propertyKeyToken = Seq(PropertyKeyToken(PROPERTY, PropertyKeyId(propKeyId)))
      // We are calculating the cost excluding deserialization of values from the index
      val properties = propertyKeyToken.map(IndexedProperty(_, DoNotGetValue)).toArray

      NodeIndexSeekPipe(LABEL.name(), labelToken, properties, 0, SingleQueryExpression(literal), IndexSeek, IndexOrderNone)()
    }
  }

  private def indexScan(graph: GraphDatabaseQueryService): NodeIndexScanPipe = {
    graph.withTx { tx =>
      val transactionalContext = TransactionalContextWrapper(transactionContext(graph, tx))
      val ctx = TransactionBoundPlanContext(transactionalContext, devNullLogger, null)

      val labelId = ctx.getOptLabelId(LABEL.name()).get
      val propKeyId = ctx.getOptPropertyKeyId(PROPERTY).get
      val labelToken = LabelToken(LABEL.name(), LabelId(labelId))
      val propertyKeyToken = PropertyKeyToken(PROPERTY, PropertyKeyId(propKeyId))
      // We are calculating the cost excluding deserialization of values from the index

      NodeIndexScanPipe(LABEL.name(), labelToken, Seq(IndexedProperty(propertyKeyToken, DoNotGetValue)), 0, IndexOrderNone)()
    }
  }

  private def propertyFilter(input: Pipe, variable: String) = {
    val literal = Literal(42)

    val propertyKey = PropertyKey(PROPERTY)
    val predicate = Equals(literal, Property(Variable(variable), propertyKey))

    FilterPipe(input, predicate)()
  }

  implicit class RichGraph(graph: GraphDatabaseQueryService) {
    val gds: GraphDatabaseFacade = graph.asInstanceOf[GraphDatabaseCypherService].getGraphDatabaseService

    def withTx[T](f: InternalTransaction => T): T = {
      val tx = graph.beginTransaction(Type.EXPLICIT, LoginContext.AUTH_DISABLED)
      try {
        val result = f(tx)
        tx.commit()
        result
      } finally {
        tx.close()
      }
    }

    def createIndex(label: Label, propertyName: String): Unit = {
      graph.withTx { tx =>
        tx.schema().indexFor(label).on(propertyName).create()
      }

      graph.withTx { tx =>
        tx.schema().awaitIndexesOnline(10, TimeUnit.SECONDS)
      }
    }
  }
}

