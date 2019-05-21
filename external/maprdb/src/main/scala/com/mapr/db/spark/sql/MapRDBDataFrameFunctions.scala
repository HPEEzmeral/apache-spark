/* Copyright (c) 2015 & onwards. MapR Tech, Inc., All rights reserved */
package com.mapr.db.spark.sql

import com.mapr.db.spark.utils.{LoggingTrait, MapRSpark}
import org.ojai.DocumentConstants

import org.apache.spark.sql.DataFrame

private[spark] case class MapRDBDataFrameFunctions(@transient df: DataFrame)
    extends LoggingTrait {

  private var bufferWrites = true

  def setBufferWrites(bufferWrites: Boolean): Unit = {
    this.bufferWrites = bufferWrites
  }

  def saveToMapRDB(tableName: String,
                   idFieldPath: String = DocumentConstants.ID_KEY,
                   createTable: Boolean = false,
                   bulkInsert: Boolean = false): Unit =
    MapRSpark.save(df, tableName, idFieldPath, createTable, bulkInsert, bufferWrites)

  def insertToMapRDB(tableName: String,
                     idFieldPath: String = DocumentConstants.ID_KEY,
                     createTable: Boolean = false,
                     bulkInsert: Boolean = false): Unit =
    MapRSpark.insert(df, tableName, idFieldPath, createTable, bulkInsert, bufferWrites)
}
