/*
 * Copyright 2019 Databricks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.delta.storage

import org.apache.spark.sql.delta.DeltaLog
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileStatus, Path}

import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.internal.Logging
import org.apache.spark.sql.SparkSession
import org.apache.spark.util.Utils

/**
 * General interface for all critical file system operations required to read and write the
 * [[DeltaLog]]. The correctness of the [[DeltaLog]] is predicated on the atomicity and
 * durability guarantees of the implementation of this interface. Specifically,
 *
 * 1. Atomic visibility of files: Any file written through this store must
 *    be made visible atomically. In other words, this should not generate partial files.
 *
 * 2. Consistent listing: Once a file has been written in a directory, all future listings for
 *    that directory must return that file.
 */
trait LogStore {

  /** Read the given `path` */
  final def read(path: String): Seq[String] = read(new Path(path))

  /** Read the given `path` */
  def read(path: Path): Seq[String]

  /**
   * Write the given `actions` to the given `path` without overwriting any existing file.
   * Implementation must throw [[java.nio.file.FileAlreadyExistsException]] exception if the file
   * already exists. Furthermore, implementation must ensure that the entire file is made
   * visible atomically, that is, it should not generate partial files.
   */
  final def write(path: String, actions: Iterator[String]): Unit = write(new Path(path), actions)

  /**
   * Write the given `actions` to the given `path` with or without overwrite as indicated.
   * Implementation must throw [[java.nio.file.FileAlreadyExistsException]] exception if the file
   * already exists and overwrite = false. Furthermore, implementation must ensure that the
   * entire file is made visible atomically, that is, it should not generate partial files.
   */
  def write(path: Path, actions: Iterator[String], overwrite: Boolean = false): Unit

  /**
   * List the paths in the same directory that are lexicographically greater or equal to
   * (UTF-8 sorting) the given `path`. The result should also be sorted by the file name.
   */
  final def listFrom(path: String): Iterator[FileStatus] = listFrom(new Path(path))

  /**
   * List the paths in the same directory that are lexicographically greater or equal to
   * (UTF-8 sorting) the given `path`. The result should also be sorted by the file name.
   */
  def listFrom(path: Path): Iterator[FileStatus]

  /** Invalidate any caching that the implementation may be using */
  def invalidateCache(): Unit

  /** Resolve the fully qualified path for the given `path`. */
  def resolvePathOnPhysicalStorage(path: Path): Path = {
    throw new UnsupportedOperationException()
  }
}

object LogStore extends Logging {

  val DEFAULT_LOGSTORE_CLASS = {
      classOf[HDFSLogStoreImpl].getName
  }

  def apply(sc: SparkContext): LogStore = {
    apply(sc.getConf, sc.hadoopConfiguration)
  }

  def apply(sparkConf: SparkConf, hadoopConf: Configuration): LogStore = {
    val logStoreClass = Utils.classForName(sparkConf.get(
      "spark.databricks.tahoe.logStore.class",
      DEFAULT_LOGSTORE_CLASS))
    logInfo("LogStore class: " + logStoreClass)
    logStoreClass.getConstructor(classOf[SparkConf], classOf[Configuration])
      .newInstance(sparkConf, hadoopConf).asInstanceOf[LogStore]
  }
}

trait LogStoreProvider {
  def createLogStore(spark: SparkSession): LogStore = {
    LogStore(spark.sparkContext)
  }
}

