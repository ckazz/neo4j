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
package org.neo4j.cypher.internal.runtime.ast

import java.lang.reflect.Method

import org.neo4j.cypher.internal.v4_0.ast.semantics.{SemanticCheck, SemanticCheckResult, SemanticCheckableExpression}
import org.neo4j.cypher.internal.v4_0.expressions.{Expression, LogicalProperty, PropertyKeyName}
import org.neo4j.cypher.internal.v4_0.util.{InputPosition, Rewritable}

abstract class RuntimeProperty(val prop: LogicalProperty) extends LogicalProperty with SemanticCheckableExpression{
  override def semanticCheck(ctx: Expression.SemanticContext): SemanticCheck = SemanticCheckResult.success

  override def position: InputPosition = InputPosition.NONE

  override def map: Expression = prop.map

  override def propertyKey: PropertyKeyName = prop.propertyKey

  override def dup(children: Seq[AnyRef]): this.type = {
    val constructor = Rewritable.copyConstructor(this)
    val args = children.toVector

    require(allRewritten(children, constructor, args), s"Unexpected rewrite children $children")


    val ctorArgs = args :+ prop // Add the original Property expression
    val duped = constructor.invoke(this, ctorArgs: _*)
    duped.asInstanceOf[this.type]
  }

  private def allRewritten(children: scala.Seq[AnyRef], constructor: Method, args: Vector[AnyRef]): Boolean = {
    val params = constructor.getParameterTypes
    params.length == args.length + 1 && classOf[LogicalProperty].isAssignableFrom(params.last)
  }
}
