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
package org.apache.spark.scheduler.cluster.k8s

import io.fabric8.kubernetes.client.KubernetesClient

import org.apache.spark.SecurityManager
import org.apache.spark.deploy.k8s._
import org.apache.spark.deploy.k8s.features._
import org.apache.spark.resource.ResourceProfile
import org.apache.spark.util.Utils

private[spark] class KubernetesExecutorBuilder {

  def buildFromFeatures(
      conf: KubernetesExecutorConf,
      secMgr: SecurityManager,
      client: KubernetesClient,
      resourceProfile: ResourceProfile): KubernetesExecutorSpec = {
    val initialPod = conf.get(Config.KUBERNETES_EXECUTOR_PODTEMPLATE_FILE)
      .map { file =>
        KubernetesUtils.loadPodFromTemplate(
          client,
          file,
          conf.get(Config.KUBERNETES_EXECUTOR_PODTEMPLATE_CONTAINER_NAME),
          conf.sparkConf)
      }
      .getOrElse(SparkPod.initialPod())

    val userFeatures = conf.get(Config.KUBERNETES_EXECUTOR_POD_FEATURE_STEPS)
      .map { className =>
        Utils.classForName(className).newInstance().asInstanceOf[KubernetesFeatureConfigStep]
      }

    val features = Seq(
      new BasicExecutorFeatureStep(conf, secMgr, resourceProfile),
      new ExecutorKubernetesCredentialsFeatureStep(conf),
      new MountSecretsFeatureStep(conf),
      new EnvSecretsFeatureStep(conf),
      new MountVolumesFeatureStep(conf),
      new LocalDirsFeatureStep(conf),
      new MaprConfigFeatureStep(conf)) ++ userFeatures

    val spec = KubernetesExecutorSpec(
      initialPod,
      executorKubernetesResources = Seq.empty)

    // If using a template this will always get the resources from that and combine
    // them with any Spark conf or ResourceProfile resources.
    features.foldLeft(spec) { case (spec, feature) =>
      val configuredPod = feature.configurePod(spec.pod)
      val addedResources = feature.getAdditionalKubernetesResources()
      KubernetesExecutorSpec(
        configuredPod,
        spec.executorKubernetesResources ++ addedResources)
    }
  }

}
