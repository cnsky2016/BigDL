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

package com.intel.analytics.sparkdl.dataset

import java.nio.file.{Path, Paths}

import com.intel.analytics.sparkdl.models.imagenet.{AlexNet, GoogleNet_v1}
import com.intel.analytics.sparkdl.nn.{ClassNLLCriterion, Criterion, Module}
import com.intel.analytics.sparkdl.optim.SGD.LearningRateSchedule
import com.intel.analytics.sparkdl.optim._
import com.intel.analytics.sparkdl.tensor.Tensor
import com.intel.analytics.sparkdl.utils.T
import scopt.OptionParser

object ImageNetLocal {
  case class ImageNetLocalParam(
    folder: String = "./",
    net: String = "alexnet",
    cache: String = "./",
    buffer: Int = 256,
    parallel: Int = 1
  )
  case class Config(
    model : Module[Tensor[Float], Tensor[Float], Float],
    criterion : Criterion[Float],
    optimMethod : OptimMethod[Float],
    imageSize : Int,
    batchSize : Int,
    momentum : Double,
    weightDecay : Double,
    testTrigger : Trigger,
    cacheTrigger : Trigger,
    endWhen : Trigger,
    learningRate : Double,
    learningRateSchedule : LearningRateSchedule
  )

  private val configs = Map(
    "alexnet" -> Config(
      AlexNet[Float](classNum = 1000),
      new ClassNLLCriterion[Float](),
      new SGD[Float](),
      imageSize = 227,
      batchSize = 256,
      momentum = 0.9,
      weightDecay = 0.0005,
      testTrigger = Trigger.severalIteration(1000),
      cacheTrigger = Trigger.severalIteration(10000),
      endWhen = Trigger.maxIteration(450000),
      learningRate = 0.01,
      learningRateSchedule = SGD.Step(100000, 0.1)),
    "googlenetv1" -> Config(
      GoogleNet_v1[Float](classNum = 1000),
      new ClassNLLCriterion[Float](),
      new SGD[Float](),
      imageSize = 224,
      batchSize = 32,
      momentum = 0.9,
      weightDecay = 0.0002,
      testTrigger = Trigger.severalIteration(4000),
      cacheTrigger = Trigger.severalIteration(40000),
      endWhen = Trigger.maxIteration(2400000),
      learningRate = 0.01,
      learningRateSchedule = SGD.Poly(0.5, 2400000))
  )

  private val parser = new OptionParser[ImageNetLocalParam]("Spark-DL ImageNet Local Example") {
    head("Spark-DL ImageNet Local Example")
    opt[String]('f', "folder")
      .text("where you put the ImageNet data")
      .action((x, c) => c.copy(folder = x))
    opt[String]('c', "cache")
      .text("where you put the model and state snapshot")
      .action((x, c) => c.copy(cache = x))
    opt[Int]('p', "parallel")
      .text("parallel num")
      .action((x, c) => c.copy(parallel = x))
    opt[Int]('b', "buffer")
      .text("buffer size")
      .action((x, c) => c.copy(buffer = x))
    opt[String]('n', "net")
      .text("net type : alexnet | googlenetv1")
      .action((x, c) => c.copy(net = x.toLowerCase))
      .validate(v =>
        if (Set("alexnet", "googlenetv1").contains(v.toLowerCase())) {
          success
        } else {
          failure("Net type can only be alexnet | googlenetv1 in this example")
        }
      )
  }

  def main(args: Array[String]) {
    parser.parse(args, new ImageNetLocalParam()).map(param => {
      val config = configs(param.net)
      val trainDataSource = ImageNetDataSource(Paths.get(param.folder + "/train"),
        looped = true)
      val validationDataSource = ImageNetDataSource(Paths.get(param.folder + "/val"),
        looped = false)
      val pathToImage = PathToRGBImage(256)
      val cropper = RGBImageCropper(cropWidth = config.imageSize, cropHeight = config.imageSize)
      val normalizer = RGBImageNormalizer(0.485, 0.456, 0.406, 0.229, 0.224, 0.225)
      val multiThreadToTensor = MultiThreadRGBImageToSingleTensor[(Float, Path)](
        width = configs(param.net).imageSize,
        height = configs(param.net).imageSize,
        threadNum = param.parallel,
        batchSize = config.batchSize,
        transformer = pathToImage + cropper + normalizer
      )

      val optimizer = new LocalOptimizer[Float](
        data = trainDataSource -> multiThreadToTensor,
        validationData = validationDataSource -> multiThreadToTensor,
        model = config.model,
        criterion = config.criterion,
        optimMethod = config.optimMethod,
        state = T(
          "learningRate" -> config.learningRate,
          "weightDecay" -> config.weightDecay,
          "dampening" -> 0.0,
          "learningRateSchedule" -> config.learningRateSchedule
        ),
        endWhen = config.endWhen
      )
      optimizer.setCache(param.cache, config.cacheTrigger)
      optimizer.setValidationTrigger(config.testTrigger)
      optimizer.addValidation(new Top1Accuracy[Float])
      optimizer.addValidation(new Top5Accuracy[Float])
      optimizer.optimize()
    })
  }

}
