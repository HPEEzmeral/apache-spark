/* Copyright (c) 2015 & onwards. MapR Tech, Inc., All rights reserved */
package com.mapr.db.spark.sql

import scala.reflect.runtime.universe._

import com.mapr.db.spark.utils.MapRSpark

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.types.StructType

case class SparkSessionFunctions(@transient sparkSession: SparkSession,
                                 bufferWrites: Boolean = true)
    extends Serializable {

  def setBufferWrites(bufferWrites: Boolean): SparkSessionFunctions =
    SparkSessionFunctions(sparkSession, bufferWrites)

  def loadFromMapRDB[T <: Product: TypeTag](
      tableName: String,
      schema: StructType = null,
      sampleSize: Double = GenerateSchema.SAMPLE_SIZE): DataFrame = {

    MapRSpark
      .builder()
      .sparkSession(sparkSession)
      .configuration()
      .setTable(tableName)
      .setBufferWrites(bufferWrites)
      .build()
      .toDF[T](schema, sampleSize, bufferWrites)
  }

  def lookupFromMapRDB[T <: Product: TypeTag](
      tableName: String,
      schema: StructType = null,
      sampleSize: Double = GenerateSchema.SAMPLE_SIZE): DataFrame = {

    MapRSpark
      .builder()
      .sparkSession(sparkSession)
      .configuration()
      .setTable(tableName)
      .setBufferWrites(bufferWrites)
      .setHintUsingIndex(hintUsingIndex)
      .setQueryOptions(queryOptions + (SingleFragmentOption -> "true"))
      .build()
      .toDF[T](schema, sampleSize, bufferWrites)
  }
}
