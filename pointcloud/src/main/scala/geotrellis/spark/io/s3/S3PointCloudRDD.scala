/*
 * Copyright 2016 Azavea
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package geotrellis.spark.io.s3

import geotrellis.spark.pointcloud.ProjectedExtent3D
import geotrellis.spark.pointcloud.json._
import io.pdal._
import org.apache.hadoop.conf.Configuration
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import spray.json._

/**
  * Allows for reading point data files using PDAL as RDD[(ProjectedPackedPointsBounds, PointCloud)]s through S3 API.
  */
object S3PointCloudRDD {
  /**
    * This case class contains the various parameters one can set when reading RDDs from Hadoop using Spark.
    * @param filesExtensions Supported files extensions
    * @param numPartitions   How many partitions Spark should create when it repartitions the data.
    * @param partitionBytes  Desired partition size in bytes, at least one item per partition will be assigned
    * @param getS3Client     A function to instantiate an S3Client. Must be serializable.
    */
  case class Options(
    filesExtensions: Seq[String] = Seq(".las", "laz"),
    numPartitions: Option[Int] = None,
    partitionBytes: Option[Long] = None,
    getS3Client: () => S3Client = () => S3Client.DEFAULT
  )

  object Options {
    def DEFAULT = Options()
  }

  /**
    * Create Configuration for [[S3PointCloudInputFormat]] based on parameters and options.
    *
    * @param bucket   Name of the bucket on S3 where the files are kept.
    * @param prefix   Prefix of all of the keys on S3 that are to be read in.
    * @param options  An instance of [[Options]] that contains any user defined or default settings.
    */
  private def configuration(bucket: String, prefix: String, options: S3PointCloudRDD.Options)(implicit sc: SparkContext): Configuration = {
    val conf = sc.hadoopConfiguration
    S3InputFormat.setBucket(conf, bucket)
    S3InputFormat.setPrefix(conf, prefix)
    S3InputFormat.setExtensions(conf, options.filesExtensions)
    S3InputFormat.setCreateS3Client(conf, options.getS3Client)
    options.numPartitions.foreach(S3InputFormat.setPartitionCount(conf, _))
    options.partitionBytes.foreach(S3InputFormat.setPartitionBytes(conf, _))
    conf
  }

  /**
    * Creates a RDD[(ProjectedPackedPointsBounds, PointCloud)] whose K depends on the type of the point data file that is going to be read in.
    *
    * @param bucket   Name of the bucket on S3 where the files are kept.
    * @param prefix   Prefix of all of the keys on S3 that are to be read in.
    * @param options  An instance of [[Options]] that contains any user defined or default settings.
    */
  def apply(bucket: String, prefix: String, options: Options = Options.DEFAULT)(implicit sc: SparkContext): RDD[(ProjectedExtent3D, PointCloud)] =
    sc.newAPIHadoopRDD(
      configuration(bucket, prefix, options),
      classOf[S3PointCloudInputFormat],
      classOf[S3PointCloudHeader],
      classOf[Iterator[PointCloud]]
    ).mapPartitions(
      _.flatMap { case (header, pointCloudIter) => pointCloudIter.map { pointCloud =>
        header.metadata.parseJson.convertTo[ProjectedExtent3D] -> pointCloud
      } },
      preservesPartitioning = true
    )
}