/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.plans.logical

import org.apache.spark.SparkException
import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.catalyst.expressions.{Expression, Literal, UnaryExpression, Unevaluable}
import org.apache.spark.sql.catalyst.parser.ParserInterface
import org.apache.spark.sql.catalyst.util.{GeneratedColumn, IdentityColumnUtil}
import org.apache.spark.sql.catalyst.util.IdentityColumnSpec
import org.apache.spark.sql.catalyst.util.ResolveDefaultColumns.validateDefaultValueExpr
import org.apache.spark.sql.catalyst.util.ResolveDefaultColumnsUtils.{CURRENT_DEFAULT_COLUMN_METADATA_KEY, EXISTS_DEFAULT_COLUMN_METADATA_KEY}
import org.apache.spark.sql.connector.catalog.{Column => V2Column, ColumnDefaultValue}
import org.apache.spark.sql.connector.expressions.LiteralValue
import org.apache.spark.sql.errors.QueryCompilationErrors
import org.apache.spark.sql.internal.connector.ColumnImpl
import org.apache.spark.sql.types.{DataType, Metadata, MetadataBuilder, StructField}

/**
 * Column definition for tables. This is an expression so that analyzer can resolve the default
 * value expression in DDL commands automatically.
 */
case class ColumnDefinition(
    name: String,
    dataType: DataType,
    nullable: Boolean = true,
    comment: Option[String] = None,
    defaultValue: Option[DefaultValueExpression] = None,
    generationExpression: Option[String] = None,
    identitySpec: Option[IdentityColumnSpec] = None,
    metadata: Metadata = Metadata.empty) extends Expression with Unevaluable {
  assert(generationExpression.isEmpty || identitySpec.isEmpty)

  override def children: Seq[Expression] = defaultValue.toSeq

  override protected def withNewChildrenInternal(
      newChildren: IndexedSeq[Expression]): Expression = {
    copy(defaultValue = newChildren.headOption.map(_.asInstanceOf[DefaultValueExpression]))
  }

  def toV2Column(statement: String): V2Column = {
    val finalMetadata = if (identitySpec.isDefined) {
      val metadataBuilder = new MetadataBuilder().withMetadata(metadata)
      encodeIdentitySpec(metadataBuilder)
      metadataBuilder.build()
    } else {
      metadata
    }
    ColumnImpl(
      name,
      dataType,
      nullable,
      comment.orNull,
      defaultValue.map(_.toV2(statement, name)).orNull,
      generationExpression.orNull,
      identitySpec.orNull,
      if (finalMetadata == Metadata.empty) null else finalMetadata.json)
  }

  def toV1Column: StructField = {
    val metadataBuilder = new MetadataBuilder().withMetadata(metadata)
    comment.foreach { c =>
      metadataBuilder.putString("comment", c)
    }
    defaultValue.foreach { default =>
      // For v1 CREATE TABLE command, we will resolve and execute the default value expression later
      // in the rule `DataSourceAnalysis`. We just need to put the default value SQL string here.
      metadataBuilder.putString(CURRENT_DEFAULT_COLUMN_METADATA_KEY, default.originalSQL)
      metadataBuilder.putString(EXISTS_DEFAULT_COLUMN_METADATA_KEY, default.originalSQL)
    }
    generationExpression.foreach { generationExpr =>
      metadataBuilder.putString(GeneratedColumn.GENERATION_EXPRESSION_METADATA_KEY, generationExpr)
    }
    encodeIdentitySpec(metadataBuilder)
    StructField(name, dataType, nullable, metadataBuilder.build())
  }

  private def encodeIdentitySpec(metadataBuilder: MetadataBuilder): Unit = {
    identitySpec.foreach{ spec: IdentityColumnSpec =>
      // At table creation time, the high water mark field is not added.
      metadataBuilder.putLong(IdentityColumnUtil.DELTA_IDENTITY_INFO_START, spec.start)
      metadataBuilder.putLong(IdentityColumnUtil.DELTA_IDENTITY_INFO_STEP, spec.step)
      metadataBuilder.putBoolean(
        IdentityColumnUtil.DELTA_IDENTITY_INFO_ALLOW_EXPLICIT_INSERT,
        spec.allowExplicitInsert)
    }
  }
}

object ColumnDefinition {

  def fromV1Column(col: StructField, parser: ParserInterface): ColumnDefinition = {
    val metadataBuilder = new MetadataBuilder().withMetadata(col.metadata)
    metadataBuilder.remove("comment")
    metadataBuilder.remove(CURRENT_DEFAULT_COLUMN_METADATA_KEY)
    metadataBuilder.remove(EXISTS_DEFAULT_COLUMN_METADATA_KEY)
    metadataBuilder.remove(GeneratedColumn.GENERATION_EXPRESSION_METADATA_KEY)
    metadataBuilder.remove(IdentityColumnUtil.DELTA_IDENTITY_INFO_START)
    metadataBuilder.remove(IdentityColumnUtil.DELTA_IDENTITY_INFO_STEP)
    metadataBuilder.remove(IdentityColumnUtil.DELTA_IDENTITY_INFO_ALLOW_EXPLICIT_INSERT)

    val hasDefaultValue = col.getCurrentDefaultValue().isDefined &&
      col.getExistenceDefaultValue().isDefined
    val defaultValue = if (hasDefaultValue) {
      val defaultValueSQL = col.getCurrentDefaultValue().get
      Some(DefaultValueExpression(parser.parseExpression(defaultValueSQL), defaultValueSQL))
    } else {
      None
    }
    val generationExpr = GeneratedColumn.getGenerationExpression(col)
    val identitySpec = if (col.metadata.contains(
      IdentityColumnUtil.DELTA_IDENTITY_INFO_START)) {
      Some(IdentityColumnSpec(
        col.metadata.getLong(IdentityColumnUtil.DELTA_IDENTITY_INFO_START),
        col.metadata.getLong(IdentityColumnUtil.DELTA_IDENTITY_INFO_STEP),
        col.metadata.getBoolean(IdentityColumnUtil.DELTA_IDENTITY_INFO_ALLOW_EXPLICIT_INSERT)
      ))
    } else {
      None
    }
    ColumnDefinition(
      col.name,
      col.dataType,
      col.nullable,
      col.getComment(),
      defaultValue,
      generationExpr,
      identitySpec,
      metadataBuilder.build()
    )
  }

  // Called by `CheckAnalysis` to check column definitions in DDL commands.
  def checkColumnDefinitions(plan: LogicalPlan): Unit = {
    plan match {
      // Do not check anything if the children are not resolved yet.
      case _ if !plan.childrenResolved =>

      case cmd: V2CreateTablePlan if cmd.columns.exists(_.defaultValue.isDefined) =>
        val statement = cmd match {
          case _: CreateTable => "CREATE TABLE"
          case _: ReplaceTable => "REPLACE TABLE"
          case other =>
            val cmd = other.getClass.getSimpleName
            throw SparkException.internalError(
              s"Command $cmd should not have column default value expression.")
        }
        cmd.columns.foreach { col =>
          if (col.defaultValue.isDefined && col.generationExpression.isDefined) {
            throw new AnalysisException(
              errorClass = "GENERATED_COLUMN_WITH_DEFAULT_VALUE",
              messageParameters = Map(
                "colName" -> col.name,
                "defaultValue" -> col.defaultValue.get.originalSQL,
                "genExpr" -> col.generationExpression.get
              )
            )
          }

          col.defaultValue.foreach { default =>
            validateDefaultValueExpr(default, statement, col.name, col.dataType)
          }
        }

      case _ =>
    }
  }
}

/**
 * A fake expression to hold the column/variable default value expression and its original SQL text.
 */
case class DefaultValueExpression(child: Expression, originalSQL: String)
  extends UnaryExpression with Unevaluable {
  override def dataType: DataType = child.dataType
  override protected def withNewChildInternal(newChild: Expression): Expression =
    copy(child = newChild)

  // Convert the default expression to ColumnDefaultValue, which is required by DS v2 APIs.
  def toV2(statement: String, colName: String): ColumnDefaultValue = child match {
    case Literal(value, dataType) =>
      new ColumnDefaultValue(originalSQL, LiteralValue(value, dataType))
    case _ =>
      throw QueryCompilationErrors.defaultValueNotConstantError(statement, colName, originalSQL)
  }
}
