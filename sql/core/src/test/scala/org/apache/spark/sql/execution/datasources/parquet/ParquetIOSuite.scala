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

package org.apache.spark.sql.execution.datasources.parquet

import java.util.Locale

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.reflect.ClassTag
import scala.reflect.runtime.universe.TypeTag

import com.google.common.primitives.UnsignedLong
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.hadoop.mapreduce.{JobContext, TaskAttemptContext}
import org.apache.parquet.column.{Encoding, ParquetProperties}
import org.apache.parquet.column.ParquetProperties.WriterVersion.PARQUET_1_0
import org.apache.parquet.example.data.Group
import org.apache.parquet.example.data.simple.{SimpleGroup, SimpleGroupFactory}
import org.apache.parquet.hadoop._
import org.apache.parquet.hadoop.example.ExampleParquetWriter
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.apache.parquet.hadoop.metadata.CompressionCodecName.GZIP
import org.apache.parquet.schema.{MessageType, MessageTypeParser}

import org.apache.spark.{SPARK_VERSION_SHORT, SparkException}
import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.{InternalRow, ScalaReflection}
import org.apache.spark.sql.catalyst.expressions.{GenericInternalRow, UnsafeRow}
import org.apache.spark.sql.catalyst.util.DateTimeUtils
import org.apache.spark.sql.execution.datasources.SQLHadoopMapReduceCommitProtocol
import org.apache.spark.sql.functions._
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.types.UTF8String

/**
 * A test suite that tests basic Parquet I/O.
 */
class ParquetIOSuite extends QueryTest with ParquetTest with SharedSparkSession {
  import testImplicits._

  /**
   * Writes `data` to a Parquet file, reads it back and check file contents.
   */
  protected def checkParquetFile[T <: Product : ClassTag: TypeTag](data: Seq[T]): Unit = {
    withParquetDataFrame(data)(r => checkAnswer(r, data.map(Row.fromTuple)))
  }

  test("basic data types (without binary)") {
    val data = (1 to 4).map { i =>
      (i % 2 == 0, i, i.toLong, i.toFloat, i.toDouble)
    }
    checkParquetFile(data)
  }

  test("raw binary") {
    val data = (1 to 4).map(i => Tuple1(Array.fill(3)(i.toByte)))
    withParquetDataFrame(data) { df =>
      assertResult(data.map(_._1.mkString(",")).sorted) {
        df.collect().map(_.getAs[Array[Byte]](0).mkString(",")).sorted
      }
    }
  }

  test("SPARK-11694 Parquet logical types are not being tested properly") {
    val parquetSchema = MessageTypeParser.parseMessageType(
      """message root {
        |  required int32 a(INT_8);
        |  required int32 b(INT_16);
        |  required int32 c(DATE);
        |  required int32 d(DECIMAL(1,0));
        |  required int64 e(DECIMAL(10,0));
        |  required binary f(UTF8);
        |  required binary g(ENUM);
        |  required binary h(DECIMAL(32,0));
        |  required fixed_len_byte_array(32) i(DECIMAL(32,0));
        |  required int64 j(TIMESTAMP_MILLIS);
        |  required int64 k(TIMESTAMP_MICROS);
        |}
      """.stripMargin)

    val expectedSparkTypes = Seq(ByteType, ShortType, DateType, DecimalType(1, 0),
      DecimalType(10, 0), StringType, StringType, DecimalType(32, 0), DecimalType(32, 0),
      TimestampType, TimestampType)

    withTempPath { location =>
      val path = new Path(location.getCanonicalPath)
      val conf = spark.sessionState.newHadoopConf()
      writeMetadata(parquetSchema, path, conf)
      readParquetFile(path.toString)(df => {
        val sparkTypes = df.schema.map(_.dataType)
        assert(sparkTypes === expectedSparkTypes)
      })
    }
  }

  test("string") {
    val data = (1 to 4).map(i => Tuple1(i.toString))
    // Property spark.sql.parquet.binaryAsString shouldn't affect Parquet files written by Spark SQL
    // as we store Spark SQL schema in the extra metadata.
    withSQLConf(SQLConf.PARQUET_BINARY_AS_STRING.key -> "false")(checkParquetFile(data))
    withSQLConf(SQLConf.PARQUET_BINARY_AS_STRING.key -> "true")(checkParquetFile(data))
  }

  testStandardAndLegacyModes("fixed-length decimals") {
    def makeDecimalRDD(decimal: DecimalType): DataFrame = {
      spark
        .range(1000)
        // Parquet doesn't allow column names with spaces, have to add an alias here.
        // Minus 500 here so that negative decimals are also tested.
        .select((('id - 500) / 100.0) cast decimal as 'dec)
        .coalesce(1)
    }

    val combinations = Seq((5, 2), (1, 0), (1, 1), (18, 10), (18, 17), (19, 0), (38, 37))
    for ((precision, scale) <- combinations) {
      withTempPath { dir =>
        val data = makeDecimalRDD(DecimalType(precision, scale))
        data.write.parquet(dir.getCanonicalPath)
        readParquetFile(dir.getCanonicalPath) { df => {
          checkAnswer(df, data.collect().toSeq)
        }}
      }
    }
  }

  test("date type") {
    def makeDateRDD(): DataFrame =
      sparkContext
        .parallelize(0 to 1000)
        .map(i => Tuple1(DateTimeUtils.toJavaDate(i)))
        .toDF()
        .select($"_1")

    withTempPath { dir =>
      val data = makeDateRDD()
      data.write.parquet(dir.getCanonicalPath)
      readParquetFile(dir.getCanonicalPath) { df =>
        checkAnswer(df, data.collect().toSeq)
      }
    }
  }

  testStandardAndLegacyModes("map") {
    val data = (1 to 4).map(i => Tuple1(Map(i -> s"val_$i")))
    checkParquetFile(data)
  }

  testStandardAndLegacyModes("array") {
    val data = (1 to 4).map(i => Tuple1(Seq(i, i + 1)))
    checkParquetFile(data)
  }

  testStandardAndLegacyModes("array and double") {
    val data = (1 to 4).map(i => (i.toDouble, Seq(i.toDouble, (i + 1).toDouble)))
    checkParquetFile(data)
  }

  testStandardAndLegacyModes("struct") {
    val data = (1 to 4).map(i => Tuple1((i, s"val_$i")))
    withParquetDataFrame(data) { df =>
      // Structs are converted to `Row`s
      checkAnswer(df, data.map { case Tuple1(struct) =>
        Row(Row(struct.productIterator.toSeq: _*))
      })
    }
  }

  testStandardAndLegacyModes("array of struct") {
    val data = (1 to 4).map { i =>
      Tuple1(
        Seq(
          Tuple1(s"1st_val_$i"),
          Tuple1(s"2nd_val_$i")
        )
      )
    }
    withParquetDataFrame(data) { df =>
      // Structs are converted to `Row`s
      checkAnswer(df, data.map { case Tuple1(array) =>
        Row(array.map(struct => Row(struct.productIterator.toSeq: _*)))
      })
    }
  }

  testStandardAndLegacyModes("array of nested struct") {
    val data = (1 to 4).map { i =>
      Tuple1(
        Seq(
          Tuple1(
            Tuple1(s"1st_val_$i")),
          Tuple1(
            Tuple1(s"2nd_val_$i"))
        )
      )
    }
    withParquetDataFrame(data) { df =>
      // Structs are converted to `Row`s
      checkAnswer(df, data.map { case Tuple1(array) =>
        Row(array.map { case Tuple1(Tuple1(str)) => Row(Row(str))})
      })
    }
  }

  testStandardAndLegacyModes("nested struct with array of array as field") {
    val data = (1 to 4).map(i => Tuple1((i, Seq(Seq(s"val_$i")))))
    withParquetDataFrame(data) { df =>
      // Structs are converted to `Row`s
      checkAnswer(df, data.map { case Tuple1(struct) =>
        Row(Row(struct.productIterator.toSeq: _*))
      })
    }
  }

  testStandardAndLegacyModes("nested map with struct as key type") {
    val data = (1 to 4).map { i =>
      Tuple1(
        Map(
          (i, s"kA_$i") -> s"vA_$i",
          (i, s"kB_$i") -> s"vB_$i"
        )
      )
    }
    withParquetDataFrame(data) { df =>
      // Structs are converted to `Row`s
      checkAnswer(df, data.map { case Tuple1(m) =>
        Row(m.map { case (k, v) => Row(k.productIterator.toSeq: _*) -> v })
      })
    }
  }

  testStandardAndLegacyModes("nested map with struct as value type") {
    val data = (1 to 4).map { i =>
      Tuple1(
        Map(
          s"kA_$i" -> ((i, s"vA_$i")),
          s"kB_$i" -> ((i, s"vB_$i"))
        )
      )
    }
    withParquetDataFrame(data) { df =>
      // Structs are converted to `Row`s
      checkAnswer(df, data.map { case Tuple1(m) =>
        Row(m.mapValues(struct => Row(struct.productIterator.toSeq: _*)))
      })
    }
  }

  test("nulls") {
    val allNulls = (
      null.asInstanceOf[java.lang.Boolean],
      null.asInstanceOf[Integer],
      null.asInstanceOf[java.lang.Long],
      null.asInstanceOf[java.lang.Float],
      null.asInstanceOf[java.lang.Double])

    withParquetDataFrame(allNulls :: Nil) { df =>
      val rows = df.collect()
      assert(rows.length === 1)
      assert(rows.head === Row(Seq.fill(5)(null): _*))
    }
  }

  test("nones") {
    val allNones = (
      None.asInstanceOf[Option[Int]],
      None.asInstanceOf[Option[Long]],
      None.asInstanceOf[Option[String]])

    withParquetDataFrame(allNones :: Nil) { df =>
      val rows = df.collect()
      assert(rows.length === 1)
      assert(rows.head === Row(Seq.fill(3)(null): _*))
    }
  }

  test("SPARK-34817: Support for unsigned Parquet logical types") {
    val parquetSchema = MessageTypeParser.parseMessageType(
      """message root {
        |  required INT32 a(UINT_8);
        |  required INT32 b(UINT_16);
        |  required INT32 c(UINT_32);
        |  required INT64 d(UINT_64);
        |}
      """.stripMargin)

    val expectedSparkTypes = Seq(ShortType, IntegerType, LongType, DecimalType.LongDecimal)

    withTempPath { location =>
      val path = new Path(location.getCanonicalPath)
      val conf = spark.sessionState.newHadoopConf()
      writeMetadata(parquetSchema, path, conf)
      val sparkTypes = spark.read.parquet(path.toString).schema.map(_.dataType)
      assert(sparkTypes === expectedSparkTypes)
    }
  }

  test("SPARK-11692 Support for Parquet logical types, JSON and BSON (embedded types)") {
    val parquetSchema = MessageTypeParser.parseMessageType(
      """message root {
        |  required binary a(JSON);
        |  required binary b(BSON);
        |}
      """.stripMargin)

    val expectedSparkTypes = Seq(StringType, BinaryType)

    withTempPath { location =>
      val path = new Path(location.getCanonicalPath)
      val conf = spark.sessionState.newHadoopConf()
      writeMetadata(parquetSchema, path, conf)
      val sparkTypes = spark.read.parquet(path.toString).schema.map(_.dataType)
      assert(sparkTypes === expectedSparkTypes)
    }
  }

  test("compression codec") {
    val hadoopConf = spark.sessionState.newHadoopConf()
    def compressionCodecFor(path: String, codecName: String): String = {
      val codecs = for {
        footer <- readAllFootersWithoutSummaryFiles(new Path(path), hadoopConf)
        block <- footer.getParquetMetadata.getBlocks.asScala
        column <- block.getColumns.asScala
      } yield column.getCodec.name()

      assert(codecs.distinct === Seq(codecName))
      codecs.head
    }

    val data = (0 until 10).map(i => (i, i.toString))

    def checkCompressionCodec(codec: CompressionCodecName): Unit = {
      withSQLConf(SQLConf.PARQUET_COMPRESSION.key -> codec.name()) {
        withParquetFile(data) { path =>
          assertResult(spark.conf.get(SQLConf.PARQUET_COMPRESSION).toUpperCase(Locale.ROOT)) {
            compressionCodecFor(path, codec.name())
          }
        }
      }
    }

    // Checks default compression codec
    checkCompressionCodec(
      CompressionCodecName.fromConf(spark.conf.get(SQLConf.PARQUET_COMPRESSION)))

    checkCompressionCodec(CompressionCodecName.UNCOMPRESSED)
    checkCompressionCodec(CompressionCodecName.GZIP)
    checkCompressionCodec(CompressionCodecName.SNAPPY)
    checkCompressionCodec(CompressionCodecName.ZSTD)
  }

  private def createParquetWriter(
      schema: MessageType,
      path: Path,
      dictionaryEnabled: Boolean = false,
      pageSize: Int = 1024,
      dictionaryPageSize: Int = 1024): ParquetWriter[Group] = {
    val hadoopConf = spark.sessionState.newHadoopConf()

    ExampleParquetWriter
      .builder(path)
      .withDictionaryEncoding(dictionaryEnabled)
      .withType(schema)
      .withWriterVersion(PARQUET_1_0)
      .withCompressionCodec(GZIP)
      .withRowGroupSize(1024 * 1024)
      .withPageSize(pageSize)
      .withDictionaryPageSize(dictionaryPageSize)
      .withConf(hadoopConf)
      .build()
  }

  test("SPARK-34859: test multiple pages with different sizes and nulls") {
    def makeRawParquetFile(
        path: Path,
        dictionaryEnabled: Boolean,
        n: Int,
        pageSize: Int): Seq[Option[Int]] = {
      val schemaStr =
        """
          |message root {
          |  optional boolean _1;
          |  optional int32   _2;
          |  optional int64   _3;
          |  optional float   _4;
          |  optional double  _5;
          |}
        """.stripMargin

      val schema = MessageTypeParser.parseMessageType(schemaStr)
      val writer = createParquetWriter(schema, path,
        dictionaryEnabled = dictionaryEnabled, pageSize = pageSize, dictionaryPageSize = pageSize)

      val rand = scala.util.Random
      val expected = (0 until n).map { i =>
        if (rand.nextBoolean()) {
          None
        } else {
          Some(i)
        }
      }
      expected.foreach { opt =>
        val record = new SimpleGroup(schema)
        opt match {
          case Some(i) =>
            record.add(0, i % 2 == 0)
            record.add(1, i)
            record.add(2, i.toLong)
            record.add(3, i.toFloat)
            record.add(4, i.toDouble)
          case _ =>
        }
        writer.write(record)
      }

      writer.close()
      expected
    }

    Seq(true, false).foreach { dictionaryEnabled =>
      Seq(64, 128, 89).foreach { pageSize =>
        withTempDir { dir =>
          val path = new Path(dir.toURI.toString, "part-r-0.parquet")
          val expected = makeRawParquetFile(path, dictionaryEnabled, 1000, pageSize)
          readParquetFile(path.toString) { df =>
            checkAnswer(df, expected.map {
              case None =>
                Row(null, null, null, null, null)
              case Some(i) =>
                Row(i % 2 == 0, i, i.toLong, i.toFloat, i.toDouble)
            })
          }
        }
      }
    }
  }

  test("read raw Parquet file") {
    def makeRawParquetFile(path: Path): Unit = {
      val schemaStr =
        """
          |message root {
          |  required boolean _1;
          |  required int32   _2;
          |  required int64   _3;
          |  required float   _4;
          |  required double  _5;
          |}
        """.stripMargin
      val schema = MessageTypeParser.parseMessageType(schemaStr)


      val writer = createParquetWriter(schema, path)

      (0 until 10).foreach { i =>
        val record = new SimpleGroup(schema)
        record.add(0, i % 2 == 0)
        record.add(1, i)
        record.add(2, i.toLong)
        record.add(3, i.toFloat)
        record.add(4, i.toDouble)
        writer.write(record)
      }

      writer.close()
    }

    withTempDir { dir =>
      val path = new Path(dir.toURI.toString, "part-r-0.parquet")
      makeRawParquetFile(path)
      readParquetFile(path.toString) { df =>
        checkAnswer(df, (0 until 10).map { i =>
          Row(i % 2 == 0, i, i.toLong, i.toFloat, i.toDouble) })
      }
    }
  }

  test("SPARK-34817: Read UINT_8/UINT_16/UINT_32 from parquet") {
    Seq(true, false).foreach { dictionaryEnabled =>
      def makeRawParquetFile(path: Path): Unit = {
        val schemaStr =
          """message root {
            |  required INT32 a(UINT_8);
            |  required INT32 b(UINT_16);
            |  required INT32 c(UINT_32);
            |}
        """.stripMargin
        val schema = MessageTypeParser.parseMessageType(schemaStr)

        val writer = createParquetWriter(schema, path, dictionaryEnabled)

        val factory = new SimpleGroupFactory(schema)
        (0 until 1000).foreach { i =>
          val group = factory.newGroup()
            .append("a", i % 100 + Byte.MaxValue)
            .append("b", i % 100 + Short.MaxValue)
            .append("c", i % 100 + Int.MaxValue)
          writer.write(group)
        }
        writer.close()
      }

      withTempDir { dir =>
        val path = new Path(dir.toURI.toString, "part-r-0.parquet")
        makeRawParquetFile(path)
        readParquetFile(path.toString) { df =>
          checkAnswer(df, (0 until 1000).map { i =>
            Row(i % 100 + Byte.MaxValue,
              i % 100 + Short.MaxValue,
              i % 100 + Int.MaxValue.toLong)
          })
        }
      }
    }
  }

  test("SPARK-34817: Read UINT_64 as Decimal from parquet") {
    Seq(true, false).foreach { dictionaryEnabled =>
      def makeRawParquetFile(path: Path): Unit = {
        val schemaStr =
          """message root {
            |  required INT64 a(UINT_64);
            |}
        """.stripMargin
        val schema = MessageTypeParser.parseMessageType(schemaStr)

        val writer = createParquetWriter(schema, path, dictionaryEnabled)

        val factory = new SimpleGroupFactory(schema)
        (-500 until 500).foreach { i =>
          val group = factory.newGroup()
            .append("a", i % 100L)
          writer.write(group)
        }
        writer.close()
      }

      withTempDir { dir =>
        val path = new Path(dir.toURI.toString, "part-r-0.parquet")
        makeRawParquetFile(path)
        readParquetFile(path.toString) { df =>
          checkAnswer(df, (-500 until 500).map { i =>
            val bi = UnsignedLong.fromLongBits(i % 100L).bigIntegerValue()
            Row(new java.math.BigDecimal(bi))
          })
        }
      }
    }
  }

  test("SPARK-35640: read binary as timestamp should throw schema incompatible error") {
    val data = (1 to 4).map(i => Tuple1(i.toString))
    val readSchema = StructType(Seq(StructField("_1", DataTypes.TimestampType)))

    withParquetFile(data) { path =>
      val errMsg = intercept[Exception](spark.read.schema(readSchema).parquet(path).collect())
          .getMessage
      assert(errMsg.contains("Parquet column cannot be converted in file"))
    }
  }

  test("SPARK-35640: int as long should throw schema incompatible error") {
    val data = (1 to 4).map(i => Tuple1(i))
    val readSchema = StructType(Seq(StructField("_1", DataTypes.LongType)))

    withParquetFile(data) { path =>
      val errMsg = intercept[Exception](spark.read.schema(readSchema).parquet(path).collect())
          .getMessage
      assert(errMsg.contains("Parquet column cannot be converted in file"))
    }
  }

  test("write metadata") {
    val hadoopConf = spark.sessionState.newHadoopConf()
    withTempPath { file =>
      val path = new Path(file.toURI.toString)
      val fs = FileSystem.getLocal(hadoopConf)
      val schema = StructType.fromAttributes(ScalaReflection.attributesFor[(Int, String)])
      writeMetadata(schema, path, hadoopConf)

      assert(fs.exists(new Path(path, ParquetFileWriter.PARQUET_COMMON_METADATA_FILE)))
      assert(fs.exists(new Path(path, ParquetFileWriter.PARQUET_METADATA_FILE)))

      val expectedSchema = new SparkToParquetSchemaConverter().convert(schema)
      val actualSchema = readFooter(path, hadoopConf).getFileMetaData.getSchema

      actualSchema.checkContains(expectedSchema)
      expectedSchema.checkContains(actualSchema)
    }
  }

  test("save - overwrite") {
    withParquetFile((1 to 10).map(i => (i, i.toString))) { file =>
      val newData = (11 to 20).map(i => (i, i.toString))
      newData.toDF().write.format("parquet").mode(SaveMode.Overwrite).save(file)
      readParquetFile(file) { df =>
        checkAnswer(df, newData.map(Row.fromTuple))
      }
    }
  }

  test("save - ignore") {
    val data = (1 to 10).map(i => (i, i.toString))
    withParquetFile(data) { file =>
      val newData = (11 to 20).map(i => (i, i.toString))
      newData.toDF().write.format("parquet").mode(SaveMode.Ignore).save(file)
      readParquetFile(file) { df =>
        checkAnswer(df, data.map(Row.fromTuple))
      }
    }
  }

  test("save - throw") {
    val data = (1 to 10).map(i => (i, i.toString))
    withParquetFile(data) { file =>
      val newData = (11 to 20).map(i => (i, i.toString))
      val errorMessage = intercept[Throwable] {
        newData.toDF().write.format("parquet").mode(SaveMode.ErrorIfExists).save(file)
      }.getMessage
      assert(errorMessage.contains("already exists"))
    }
  }

  test("save - append") {
    val data = (1 to 10).map(i => (i, i.toString))
    withParquetFile(data) { file =>
      val newData = (11 to 20).map(i => (i, i.toString))
      newData.toDF().write.format("parquet").mode(SaveMode.Append).save(file)
      readParquetFile(file) { df =>
        checkAnswer(df, (data ++ newData).map(Row.fromTuple))
      }
    }
  }

  test("SPARK-6315 regression test") {
    // Spark 1.1 and prior versions write Spark schema as case class string into Parquet metadata.
    // This has been deprecated by JSON format since 1.2.  Notice that, 1.3 further refactored data
    // types API, and made StructType.fields an array.  This makes the result of StructType.toString
    // different from prior versions: there's no "Seq" wrapping the fields part in the string now.
    val sparkSchema =
      "StructType(Seq(StructField(a,BooleanType,false),StructField(b,IntegerType,false)))"

    // The Parquet schema is intentionally made different from the Spark schema.  Because the new
    // Parquet data source simply falls back to the Parquet schema once it fails to parse the Spark
    // schema.  By making these two different, we are able to assert the old style case class string
    // is parsed successfully.
    val parquetSchema = MessageTypeParser.parseMessageType(
      """message root {
        |  required int32 c;
        |}
      """.stripMargin)

    withTempPath { location =>
      val extraMetadata = Map(ParquetReadSupport.SPARK_METADATA_KEY -> sparkSchema.toString)
      val path = new Path(location.getCanonicalPath)
      val conf = spark.sessionState.newHadoopConf()
      writeMetadata(parquetSchema, path, conf, extraMetadata)

      readParquetFile(path.toString) { df =>
        assertResult(df.schema) {
          StructType(
            StructField("a", BooleanType, nullable = true) ::
              StructField("b", IntegerType, nullable = true) ::
              Nil)
        }
      }
    }
  }

  test("SPARK-8121: spark.sql.parquet.output.committer.class shouldn't be overridden") {
    withSQLConf(SQLConf.FILE_COMMIT_PROTOCOL_CLASS.key ->
        classOf[SQLHadoopMapReduceCommitProtocol].getCanonicalName) {
      val extraOptions = Map(
        SQLConf.OUTPUT_COMMITTER_CLASS.key -> classOf[ParquetOutputCommitter].getCanonicalName,
        SQLConf.PARQUET_OUTPUT_COMMITTER_CLASS.key ->
          classOf[JobCommitFailureParquetOutputCommitter].getCanonicalName
      )
      withTempPath { dir =>
        val message = intercept[SparkException] {
          spark.range(0, 1).write.options(extraOptions).parquet(dir.getCanonicalPath)
        }.getCause.getMessage
        assert(message === "Intentional exception for testing purposes")
      }
    }
  }

  test("SPARK-6330 regression test") {
    // In 1.3.0, save to fs other than file: without configuring core-site.xml would get:
    // IllegalArgumentException: Wrong FS: hdfs://..., expected: file:///
    intercept[Throwable] {
      spark.read.parquet("file:///nonexistent")
    }
    val errorMessage = intercept[Throwable] {
      spark.read.parquet("maprfs:///nonexistent/fake/path")
    }.toString
    assert(errorMessage.contains("Could not create FileClient"))
  }

  test("SPARK-7837 Do not close output writer twice when commitTask() fails") {
    withSQLConf(SQLConf.FILE_COMMIT_PROTOCOL_CLASS.key ->
        classOf[SQLHadoopMapReduceCommitProtocol].getCanonicalName) {
      // Using a output committer that always fail when committing a task, so that both
      // `commitTask()` and `abortTask()` are invoked.
      val extraOptions = Map[String, String](
        SQLConf.PARQUET_OUTPUT_COMMITTER_CLASS.key ->
          classOf[TaskCommitFailureParquetOutputCommitter].getCanonicalName
      )

      // Before fixing SPARK-7837, the following code results in an NPE because both
      // `commitTask()` and `abortTask()` try to close output writers.

      withTempPath { dir =>
        val m1 = intercept[SparkException] {
          spark.range(1).coalesce(1).write.options(extraOptions).parquet(dir.getCanonicalPath)
        }.getCause.getMessage
        assert(m1.contains("Intentional exception for testing purposes"))
      }

      withTempPath { dir =>
        val m2 = intercept[SparkException] {
          val df = spark.range(1).select('id as 'a, 'id as 'b).coalesce(1)
          df.write.partitionBy("a").options(extraOptions).parquet(dir.getCanonicalPath)
        }.getCause.getMessage
        assert(m2.contains("Intentional exception for testing purposes"))
      }
    }
  }

  test("SPARK-11044 Parquet writer version fixed as version1 ") {
    withSQLConf(SQLConf.FILE_COMMIT_PROTOCOL_CLASS.key ->
        classOf[SQLHadoopMapReduceCommitProtocol].getCanonicalName) {
      // For dictionary encoding, Parquet changes the encoding types according to its writer
      // version. So, this test checks one of the encoding types in order to ensure that
      // the file is written with writer version2.
      val extraOptions = Map[String, String](
        // Write a Parquet file with writer version2.
        ParquetOutputFormat.WRITER_VERSION -> ParquetProperties.WriterVersion.PARQUET_2_0.toString,
        // By default, dictionary encoding is enabled from Parquet 1.2.0 but
        // it is enabled just in case.
        ParquetOutputFormat.ENABLE_DICTIONARY -> "true"
      )

      val hadoopConf = spark.sessionState.newHadoopConfWithOptions(extraOptions)

      withSQLConf(ParquetOutputFormat.JOB_SUMMARY_LEVEL -> "ALL") {
        withTempPath { dir =>
          val path = s"${dir.getCanonicalPath}/part-r-0.parquet"
          spark.range(1 << 16).selectExpr("(id % 4) AS i")
            .coalesce(1).write.options(extraOptions).mode("overwrite").parquet(path)

          val blockMetadata = readFooter(new Path(path), hadoopConf).getBlocks.asScala.head
          val columnChunkMetadata = blockMetadata.getColumns.asScala.head

          // If the file is written with version2, this should include
          // Encoding.RLE_DICTIONARY type. For version1, it is Encoding.PLAIN_DICTIONARY
          assert(columnChunkMetadata.getEncodings.contains(Encoding.RLE_DICTIONARY))
        }
      }
    }
  }

  test("null and non-null strings") {
    // Create a dataset where the first values are NULL and then some non-null values. The
    // number of non-nulls needs to be bigger than the ParquetReader batch size.
    val data: Dataset[String] = spark.range(200).map (i =>
      if (i < 150) null
      else "a"
    )
    val df = data.toDF("col")
    assert(df.agg("col" -> "count").collect().head.getLong(0) == 50)

    withTempPath { dir =>
      val path = s"${dir.getCanonicalPath}/data"
      df.write.parquet(path)

      readParquetFile(path) { df2 =>
        assert(df2.agg("col" -> "count").collect().head.getLong(0) == 50)
      }
    }
  }

  test("read dictionary encoded decimals written as INT32") {
    withAllParquetReaders {
      checkAnswer(
        // Decimal column in this file is encoded using plain dictionary
        readResourceParquetFile("test-data/dec-in-i32.parquet"),
        spark.range(1 << 4).select('id % 10 cast DecimalType(5, 2) as 'i32_dec))
    }
  }

  test("read dictionary encoded decimals written as INT64") {
    withAllParquetReaders {
      checkAnswer(
        // Decimal column in this file is encoded using plain dictionary
        readResourceParquetFile("test-data/dec-in-i64.parquet"),
        spark.range(1 << 4).select('id % 10 cast DecimalType(10, 2) as 'i64_dec))
    }
  }

  test("read dictionary encoded decimals written as FIXED_LEN_BYTE_ARRAY") {
    withAllParquetReaders {
      checkAnswer(
        // Decimal column in this file is encoded using plain dictionary
        readResourceParquetFile("test-data/dec-in-fixed-len.parquet"),
        spark.range(1 << 4).select('id % 10 cast DecimalType(10, 2) as 'fixed_len_dec))
    }
  }

  test("read dictionary and plain encoded timestamp_millis written as INT64") {
    withAllParquetReaders {
      checkAnswer(
        // timestamp column in this file is encoded using combination of plain
        // and dictionary encodings.
        readResourceParquetFile("test-data/timemillis-in-i64.parquet"),
        (1 to 3).map(i => Row(new java.sql.Timestamp(10))))
    }
  }

  test("SPARK-12589 copy() on rows returned from reader works for strings") {
    withTempPath { dir =>
      val data = (1, "abc") ::(2, "helloabcde") :: Nil
      data.toDF().write.parquet(dir.getCanonicalPath)
      var hash1: Int = 0
      var hash2: Int = 0
      (false :: true :: Nil).foreach { v =>
        withSQLConf(SQLConf.PARQUET_VECTORIZED_READER_ENABLED.key -> v.toString) {
          val df = spark.read.parquet(dir.getCanonicalPath)
          val rows = df.queryExecution.toRdd.map(_.copy()).collect()
          val unsafeRows = rows.map(_.asInstanceOf[UnsafeRow])
          if (!v) {
            hash1 = unsafeRows(0).hashCode()
            hash2 = unsafeRows(1).hashCode()
          } else {
            assert(hash1 == unsafeRows(0).hashCode())
            assert(hash2 == unsafeRows(1).hashCode())
          }
        }
      }
    }
  }

  test("SPARK-36726: test incorrect Parquet row group file offset") {
    readParquetFile(testFile("test-data/malformed-file-offset.parquet")) { df =>
      assert(df.count() == 3650)
    }
  }

  test("VectorizedParquetRecordReader - direct path read") {
    val data = (0 to 10).map(i => (i, (i + 'a').toChar.toString))
    withTempPath { dir =>
      spark.createDataFrame(data).repartition(1).write.parquet(dir.getCanonicalPath)
      val file = SpecificParquetRecordReaderBase.listDirectory(dir).get(0);
      {
        val conf = sqlContext.conf
        val reader = new VectorizedParquetRecordReader(
          conf.offHeapColumnVectorEnabled, conf.parquetVectorizedReaderBatchSize)
        try {
          reader.initialize(file, null)
          val result = mutable.ArrayBuffer.empty[(Int, String)]
          while (reader.nextKeyValue()) {
            val row = reader.getCurrentValue.asInstanceOf[InternalRow]
            val v = (row.getInt(0), row.getString(1))
            result += v
          }
          assert(data.toSet == result.toSet)
        } finally {
          reader.close()
        }
      }

      // Project just one column
      {
        val conf = sqlContext.conf
        val reader = new VectorizedParquetRecordReader(
          conf.offHeapColumnVectorEnabled, conf.parquetVectorizedReaderBatchSize)
        try {
          reader.initialize(file, ("_2" :: Nil).asJava)
          val result = mutable.ArrayBuffer.empty[(String)]
          while (reader.nextKeyValue()) {
            val row = reader.getCurrentValue.asInstanceOf[InternalRow]
            result += row.getString(0)
          }
          assert(data.map(_._2).toSet == result.toSet)
        } finally {
          reader.close()
        }
      }

      // Project columns in opposite order
      {
        val conf = sqlContext.conf
        val reader = new VectorizedParquetRecordReader(
          conf.offHeapColumnVectorEnabled, conf.parquetVectorizedReaderBatchSize)
        try {
          reader.initialize(file, ("_2" :: "_1" :: Nil).asJava)
          val result = mutable.ArrayBuffer.empty[(String, Int)]
          while (reader.nextKeyValue()) {
            val row = reader.getCurrentValue.asInstanceOf[InternalRow]
            val v = (row.getString(0), row.getInt(1))
            result += v
          }
          assert(data.map { x => (x._2, x._1) }.toSet == result.toSet)
        } finally {
          reader.close()
        }
      }

      // Empty projection
      {
        val conf = sqlContext.conf
        val reader = new VectorizedParquetRecordReader(
          conf.offHeapColumnVectorEnabled, conf.parquetVectorizedReaderBatchSize)
        try {
          reader.initialize(file, List[String]().asJava)
          var result = 0
          while (reader.nextKeyValue()) {
            result += 1
          }
          assert(result == data.length)
        } finally {
          reader.close()
        }
      }
    }
  }

  test("VectorizedParquetRecordReader - partition column types") {
    withTempPath { dir =>
      Seq(1).toDF().repartition(1).write.parquet(dir.getCanonicalPath)

      val dataTypes =
        Seq(StringType, BooleanType, ByteType, BinaryType, ShortType, IntegerType, LongType,
          FloatType, DoubleType, DecimalType(25, 5), DateType, TimestampType)

      val constantValues =
        Seq(
          UTF8String.fromString("a string"),
          true,
          1.toByte,
          "Spark SQL".getBytes,
          2.toShort,
          3,
          Long.MaxValue,
          0.25.toFloat,
          0.75D,
          Decimal("1234.23456"),
          DateTimeUtils.fromJavaDate(java.sql.Date.valueOf("2015-01-01")),
          DateTimeUtils.fromJavaTimestamp(java.sql.Timestamp.valueOf("2015-01-01 23:50:59.123")))

      dataTypes.zip(constantValues).foreach { case (dt, v) =>
        val schema = StructType(StructField("pcol", dt) :: Nil)
        val conf = sqlContext.conf
        val vectorizedReader = new VectorizedParquetRecordReader(
          conf.offHeapColumnVectorEnabled, conf.parquetVectorizedReaderBatchSize)
        val partitionValues = new GenericInternalRow(Array(v))
        val file = SpecificParquetRecordReaderBase.listDirectory(dir).get(0)

        try {
          vectorizedReader.initialize(file, null)
          vectorizedReader.initBatch(schema, partitionValues)
          vectorizedReader.nextKeyValue()
          val row = vectorizedReader.getCurrentValue.asInstanceOf[InternalRow]

          // Use `GenericMutableRow` by explicitly copying rather than `ColumnarBatch`
          // in order to use get(...) method which is not implemented in `ColumnarBatch`.
          val actual = row.copy().get(1, dt)
          val expected = v
          if (dt.isInstanceOf[BinaryType]) {
            assert(actual.asInstanceOf[Array[Byte]] sameElements expected.asInstanceOf[Array[Byte]])
          } else {
            assert(actual == expected)
          }
        } finally {
          vectorizedReader.close()
        }
      }
    }
  }

  test("SPARK-18433: Improve DataSource option keys to be more case-insensitive") {
    withSQLConf(SQLConf.PARQUET_COMPRESSION.key -> "snappy") {
      val option = new ParquetOptions(Map("Compression" -> "uncompressed"), spark.sessionState.conf)
      assert(option.compressionCodecClassName == "UNCOMPRESSED")
    }
  }

  test("SPARK-23173 Writing a file with data converted from JSON with and incorrect user schema") {
    withTempPath { file =>
      val jsonData =
        """{
        |  "a": 1,
        |  "c": "foo"
        |}
        |""".stripMargin
      val jsonSchema = new StructType()
        .add("a", LongType, nullable = false)
        .add("b", StringType, nullable = false)
        .add("c", StringType, nullable = false)
      spark.range(1).select(from_json(lit(jsonData), jsonSchema) as "input")
        .write.parquet(file.getAbsolutePath)
      checkAnswer(spark.read.parquet(file.getAbsolutePath), Seq(Row(Row(1, null, "foo"))))
    }
  }

  test("Write Spark version into Parquet metadata") {
    withTempPath { dir =>
      spark.range(1).repartition(1).write.parquet(dir.getAbsolutePath)
      assert(getMetaData(dir)(SPARK_VERSION_METADATA_KEY) === SPARK_VERSION_SHORT)
    }
  }

  Seq(true, false).foreach { vec =>
    test(s"SPARK-34167: read LongDecimals with precision < 10, VectorizedReader $vec") {
      // decimal32-written-as-64-bit.snappy.parquet was generated using a 3rd-party library. It has
      // 10 rows of Decimal(9, 1) written as LongDecimal instead of an IntDecimal
      readParquetFile(testFile("test-data/decimal32-written-as-64-bit.snappy.parquet"), vec) {
        df =>
          assert(10 == df.collect().length)
          val first10Df = df.head(10)
          assert(
            Seq(792059492, 986842987, 540247998, null, 357991078,
              494131059, 92536396, 426847157, -999999999, 204486094)
              .zip(first10Df).forall(d =>
              d._2.isNullAt(0) && d._1 == null ||
                d._1 == d._2.getDecimal(0).unscaledValue().intValue()
            ))
      }
      // decimal32-written-as-64-bit-dict.snappy.parquet was generated using a 3rd-party library. It
      // has 2048 rows of Decimal(3, 1) written as LongDecimal instead of an IntDecimal
      readParquetFile(
        testFile("test-data/decimal32-written-as-64-bit-dict.snappy.parquet"), vec) {
        df =>
          assert(2048 == df.collect().length)
          val first10Df = df.head(10)
          assert(Seq(751, 937, 511, null, 337, 467, 84, 403, -999, 190)
            .zip(first10Df).forall(d =>
            d._2.isNullAt(0) && d._1 == null ||
              d._1 == d._2.getDecimal(0).unscaledValue().intValue()))

          val last10Df = df.tail(10)
          assert(Seq(866, 20, 492, 76, 824, 604, 343, 820, 864, 243)
            .zip(last10Df).forall(d =>
            d._1 == d._2.getDecimal(0).unscaledValue().intValue()))
      }
    }
  }
}

class JobCommitFailureParquetOutputCommitter(outputPath: Path, context: TaskAttemptContext)
  extends ParquetOutputCommitter(outputPath, context) {

  override def commitJob(jobContext: JobContext): Unit = {
    sys.error("Intentional exception for testing purposes")
  }
}

class TaskCommitFailureParquetOutputCommitter(outputPath: Path, context: TaskAttemptContext)
  extends ParquetOutputCommitter(outputPath, context) {

  override def commitTask(context: TaskAttemptContext): Unit = {
    sys.error("Intentional exception for testing purposes")
  }
}
